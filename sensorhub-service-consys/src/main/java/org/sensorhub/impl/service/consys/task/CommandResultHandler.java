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
import java.util.concurrent.ScheduledExecutorService;
import org.sensorhub.api.command.ICommandStatus;
import org.sensorhub.api.command.ICommandStreamInfo;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.command.CommandStatusFilter;
import org.sensorhub.api.datastore.command.CommandStreamKey;
import org.sensorhub.api.datastore.command.ICommandStatusStore;
import org.sensorhub.api.event.IEventBus;
import org.sensorhub.impl.service.consys.InvalidRequestException;
import org.sensorhub.impl.service.consys.ObsSystemDbWrapper;
import org.sensorhub.impl.service.consys.ServiceErrors;
import org.sensorhub.impl.service.consys.RestApiServlet.ResourcePermissions;
import org.sensorhub.impl.service.consys.resource.BaseResourceHandler;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceBinding;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.sensorhub.impl.service.consys.resource.RequestContext.ResourceRef;
import org.sensorhub.impl.service.consys.task.CommandStatusHandler.CommandStatusHandlerContextData;
import org.sensorhub.impl.system.SystemDatabaseTransactionHandler;
import org.vast.util.Asserts;


public class CommandResultHandler extends BaseResourceHandler<BigId, ICommandStatus, CommandStatusFilter, ICommandStatusStore>
{
    public static final int EXTERNAL_ID_SEED = 71145893;
    public static final String[] NAMES = { "result" };
    
    final IEventBus eventBus;
    final IObsSystemDatabase db;
    final SystemDatabaseTransactionHandler transactionHandler;
    final ScheduledExecutorService threadPool;
    
    
    public CommandResultHandler(IEventBus eventBus, ObsSystemDbWrapper db, ScheduledExecutorService threadPool, ResourcePermissions permissions)
    {
        super(db.getReadDb().getCommandStatusStore(), db.getCommandIdEncoder(), db, permissions);
        
        this.eventBus = eventBus;
        this.db = db.getReadDb();
        this.transactionHandler = new SystemDatabaseTransactionHandler(eventBus, db.getWriteDb());
        this.threadPool = threadPool;
    }
    
    
    @Override
    protected ResourceBinding<BigId, ICommandStatus> getBinding(RequestContext ctx, boolean forReading) throws IOException
    {
        var format = ctx.getFormat();
        
        var contextData = new CommandStatusHandlerContextData();
        ctx.setData(contextData);
        
        // try to fetch command stream since it's needed to configure result binding
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
        
        if (!contextData.csInfo.hasInlineResult())
            throw ServiceErrors.notFound("This type of command has no result");
        
        if (forReading)
            throw ServiceErrors.notWritable();
        
        // select binding depending on format
        if (format.equals(ResourceFormat.AUTO))
            ctx.setResponseFormat(ResourceFormat.JSON);
        //if (format.isOneOf(ResourceFormat.AUTO, ResourceFormat.JSON))
        //    return new CommandResultBindingOmJson(ctx, idEncoders, forReading, db);
        //else
            return new CommandResultBindingSweCommon(ctx, idEncoders, forReading, db);
    }
    
    
    @Override
    public void doPost(RequestContext ctx) throws IOException
    {
        if (ctx.isEndOfPath() &&
            !(ctx.getParentRef().type instanceof CommandStreamHandler))
            throw ServiceErrors.unsupportedOperation("Command results can only be created within a command resource");
        
        super.doPost(ctx);
    }
    
    
    protected void subscribe(final RequestContext ctx) throws InvalidRequestException, IOException
    {
        throw new UnsupportedOperationException();
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
        if (parent.type instanceof CommandHandler)
            builder.withCommands(parent.internalID);
        
        // keep only status objects with result
        builder.withValuePredicate(s -> s.getResult() != null);
        
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
        var id = dsHandler.sendStatus(ctx.getCorrelationID(), status);
        return id;
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
