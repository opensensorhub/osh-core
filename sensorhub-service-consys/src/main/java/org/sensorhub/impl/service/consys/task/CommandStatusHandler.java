/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.task;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.ScheduledExecutorService;
import org.sensorhub.api.command.CommandStatusEvent;
import org.sensorhub.api.command.ICommandStatus;
import org.sensorhub.api.command.ICommandStatus.CommandStatusCode;
import org.sensorhub.api.command.ICommandStreamInfo;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.TemporalFilter;
import org.sensorhub.api.datastore.command.CommandStatusFilter;
import org.sensorhub.api.datastore.command.CommandStreamKey;
import org.sensorhub.api.datastore.command.ICommandStatusStore;
import org.sensorhub.api.event.EventUtils;
import org.sensorhub.api.event.IEventBus;
import org.sensorhub.impl.service.consys.InvalidRequestException;
import org.sensorhub.impl.service.consys.HandlerContext;
import org.sensorhub.impl.service.consys.ServiceErrors;
import org.sensorhub.impl.service.consys.RestApiServlet.ResourcePermissions;
import org.sensorhub.impl.service.consys.resource.BaseResourceHandler;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceBinding;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.sensorhub.impl.service.consys.resource.RequestContext.ResourceRef;
import org.sensorhub.impl.service.consys.stream.StreamHandler;
import org.sensorhub.impl.system.CommandStreamTransactionHandler;
import org.sensorhub.impl.system.SystemDatabaseTransactionHandler;
import org.sensorhub.utils.CallbackException;
import org.vast.util.Asserts;


public class CommandStatusHandler extends BaseResourceHandler<BigId, ICommandStatus, CommandStatusFilter, ICommandStatusStore>
{
    public static final int EXTERNAL_ID_SEED = 71145893;
    public static final String[] NAMES = { "status" };
    
    final IEventBus eventBus;
    final IObsSystemDatabase db;
    final SystemDatabaseTransactionHandler transactionHandler;
    final ScheduledExecutorService threadPool;
    
    
    public static class CommandStatusHandlerContextData
    {
        public BigId streamID;
        public ICommandStreamInfo csInfo;
        public BigId foiId;
        public CommandStreamTransactionHandler csHandler;
    }
    
    
    public CommandStatusHandler(HandlerContext ctx, ScheduledExecutorService threadPool, ResourcePermissions permissions)
    {
        super(ctx.getReadDb().getCommandStatusStore(), ctx.getCommandIdEncoder(), ctx, permissions);
        
        this.eventBus = ctx.getEventBus();
        this.db = ctx.getReadDb();
        this.transactionHandler = new SystemDatabaseTransactionHandler(eventBus, ctx.getWriteDb());
        this.threadPool = threadPool;
    }
    
    
    @Override
    protected ResourceBinding<BigId, ICommandStatus> getBinding(RequestContext ctx, boolean forReading) throws IOException
    {
        var format = ctx.getFormat();
        
        var contextData = new CommandStatusHandlerContextData();
        ctx.setData(contextData);
        
        // try to fetch command stream since it's needed to configure binding
        Asserts.checkState(ctx.getParentID() != null);
        BigId csID = null;
        if (ctx.getParentRef().type instanceof CommandHandler)
        {
            var cmd = db.getCommandStore().get(ctx.getParentID());
            csID = cmd.getCommandStreamID();
        }
        else if (ctx.getParentRef().type instanceof CommandStreamHandler)
            csID = ctx.getParentID();
        
        Asserts.checkNotNull(csID, BigId.class);
        contextData.csInfo = db.getCommandStreamStore().get(new CommandStreamKey(csID));
        Asserts.checkNotNull(contextData.csInfo, ICommandStreamInfo.class);
        
        if (forReading)
        {
            // when ingesting status, command stream needs to be known at this stage
            Asserts.checkNotNull(contextData.csInfo, ICommandStreamInfo.class);
            
            // create transaction handler here so it can be reused multiple times
            contextData.streamID = csID;
            contextData.csHandler = transactionHandler.getCommandStreamHandler(contextData.streamID);
            if (contextData.csHandler == null)
                throw ServiceErrors.notWritable();
        }
        
        // select binding depending on format
        if (format.isOneOf(ResourceFormat.AUTO, ResourceFormat.JSON))
            return new CommandStatusBindingJson(ctx, idEncoders, forReading, dataStore);
        else
            throw ServiceErrors.unsupportedFormat(format);
    }
    
    
    @Override
    public void doPost(RequestContext ctx) throws IOException
    {
        if (ctx.isEndOfPath() &&
            !(ctx.getParentRef().type instanceof CommandStreamHandler))
            throw ServiceErrors.unsupportedOperation("Observations can only be created within a Datastream");
        
        super.doPost(ctx);
    }
    
    
    protected void subscribe(final RequestContext ctx) throws InvalidRequestException, IOException
    {
        ctx.getSecurityHandler().checkPermission(permissions.stream);
        Asserts.checkNotNull(ctx.getStreamHandler(), StreamHandler.class);
        
        var dsID = ctx.getParentID();
        if (dsID == null)
            throw ServiceErrors.badRequest("Streaming is only supported on a specific command stream");
        
        var queryParams = ctx.getParameterMap();
        var filter = getFilter(ctx.getParentRef(), queryParams, 0, Long.MAX_VALUE);
        var responseFormat = parseFormat(queryParams);
        ctx.setFormatOptions(responseFormat, parseSelectArg(queryParams));
        
        // continue when streaming actually starts
        ctx.getStreamHandler().setStartCallback(() -> {
            
            try
            {
                // init binding and get datastream info
                var binding = getBinding(ctx, false);
                startRealTimeStream(ctx, dsID, filter, binding);
            }
            catch (IOException e)
            {
                throw new IllegalStateException("Error initializing binding", e);
            }
        });
    }
    
    
    protected void startRealTimeStream(final RequestContext ctx, final BigId dsID, final CommandStatusFilter filter, final ResourceBinding<BigId, ICommandStatus> binding)
    {
        // init event to obs converter
        var csInfo = ((CommandStatusHandlerContextData)ctx.getData()).csInfo;
        var streamHandler = ctx.getStreamHandler();
        
        // create subscriber
        var subscriber = new Subscriber<CommandStatusEvent>() {
            Subscription subscription;
            
            @Override
            public void onSubscribe(Subscription subscription)
            {
                this.subscription = subscription;
                subscription.request(Long.MAX_VALUE);
                ctx.getLogger().debug("Starting real-time command status subscription #{}", System.identityHashCode(subscription));
                
                // cancel subscription if streaming is stopped by client
                ctx.getStreamHandler().setCloseCallback(() -> {
                    subscription.cancel();
                    ctx.getLogger().debug("Cancelling real-time command status subscription #{}", System.identityHashCode(subscription));
                });
            }

            @Override
            public void onNext(CommandStatusEvent event)
            {
                try
                {
                    binding.serialize(null, event.getStatus(), false);
                    streamHandler.sendPacket();
                }
                catch (IOException e)
                {
                    subscription.cancel();
                    throw new CallbackException(e);
                }
            }

            @Override
            public void onError(Throwable e)
            {
                ctx.getLogger().error("Error while publishing real-time command status", e);
            }

            @Override
            public void onComplete()
            {
                ctx.getLogger().debug("Ending real-time command status subscription #{}", System.identityHashCode(subscription));
                streamHandler.close();
            }
        };
        
        var topic = EventUtils.getCommandStatusTopicID(csInfo);
        eventBus.newSubscription(CommandStatusEvent.class)
            .withTopicID(topic)
            .withEventType(CommandStatusEvent.class)
            .subscribe(subscriber);
    }


    @Override
    protected BigId getKey(RequestContext ctx, String id) throws InvalidRequestException
    {
        return decodeID(id);
    }
    
    
    @Override
    protected String encodeKey(final RequestContext ctx, BigId key)
    {
        return idEncoder.encodeID(key);
    }


    @Override
    protected CommandStatusFilter getFilter(ResourceRef parent, Map<String, String[]> queryParams, long offset, long limit) throws InvalidRequestException
    {
        var builder = new CommandStatusFilter.Builder();
        
        // filter on parent command if needed
        if (parent.internalID != null)
        {
            if (parent.type instanceof CommandHandler)
                builder.withCommands(parent.internalID);
        
            // or filter on parent command stream
            else if (parent.type instanceof CommandStreamHandler)
            {
                builder.withCommands()
                    .withCommandStreams(parent.internalID)
                    .done();
            }
        }
        
        // command IDs
        var cmdIDs = parseResourceIds("commands", queryParams, idEncoders.getCommandIdEncoder());
        if (parent.internalID == null && cmdIDs != null && !cmdIDs.isEmpty())
            builder.withCommands(cmdIDs);

        var reportTimeFilterBuilder = new TemporalFilter.Builder();

        // reportTime param
        var issueTime = parseTimeStampArgToBuilder("reportTime", queryParams);
        if (issueTime != null)
            reportTimeFilterBuilder = issueTime;

        // chronological order. attach to reportTime filter
        var descendingOrder = getSingleParam("order", queryParams);
        if (descendingOrder != null && !descendingOrder.isBlank()
                && ("desc".equals(descendingOrder) || "descending".equals(descendingOrder)))
        {
            reportTimeFilterBuilder.descendingOrder(true);
        }

        if (issueTime != null || descendingOrder != null)
            builder.withReportTime(reportTimeFilterBuilder.build());
        
        // executionTime param
        var execTime = parseTimeStampArg("executionTime", queryParams);
        if (execTime != null)
            builder.withExecutionTime(execTime);
        
        // statusCode param
        var statusCodes = parseMultiValuesArg("statusCode", queryParams);
        if (statusCodes != null && !statusCodes.isEmpty())
        {
            try
            {
                Set<CommandStatusCode> enumCodes = statusCodes.stream().map(s -> CommandStatusCode.valueOf(s)).collect(Collectors.toSet());
                builder.withStatus(enumCodes);
            }
            catch (Exception e)
            {
                throw ServiceErrors.badRequest("Invalid status code: " + statusCodes);
            }
        }
        
        // limit
        // need to limit to offset+limit+1 since we rescan from the beginning for now
        if (limit != Long.MAX_VALUE)
            builder.withLimit(offset+limit+1);
        
        return builder.build();
    }


    @Override
    protected BigId addEntry(RequestContext ctx, ICommandStatus status) throws DataStoreException
    {
        var dsHandler = ((CommandStatusHandlerContextData)ctx.getData()).csHandler;
        return dsHandler.sendStatus(ctx.getCorrelationID(), status);
    }
    
    
    @Override
    protected boolean isValidID(BigId internalID)
    {
        return false;
    }


    @Override
    protected void validate(ICommandStatus resource)
    {
        // TODO Auto-generated method stub
        
    }
    
    
    @Override
    public String[] getNames()
    {
        return NAMES;
    }
}
