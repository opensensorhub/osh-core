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
import org.sensorhub.api.command.ICommandStreamInfo;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.command.CommandStreamFilter;
import org.sensorhub.api.datastore.command.CommandStreamKey;
import org.sensorhub.api.datastore.command.ICommandStreamStore;
import org.sensorhub.impl.security.ItemWithIdPermission;
import org.sensorhub.impl.security.ItemWithParentPermission;
import org.sensorhub.impl.service.consys.InvalidRequestException;
import org.sensorhub.impl.service.consys.HandlerContext;
import org.sensorhub.impl.service.consys.ConSysApiSecurity;
import org.sensorhub.impl.service.consys.ServiceErrors;
import org.sensorhub.impl.service.consys.RestApiServlet.ResourcePermissions;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceBinding;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.sensorhub.impl.service.consys.resource.ResourceHandler;
import org.sensorhub.impl.service.consys.resource.RequestContext.ResourceRef;
import org.sensorhub.impl.service.consys.system.SystemHandler;
import org.sensorhub.impl.system.SystemDatabaseTransactionHandler;


public class CommandStreamHandler extends ResourceHandler<CommandStreamKey, ICommandStreamInfo, CommandStreamFilter, CommandStreamFilter.Builder, ICommandStreamStore>
{
    public static final String[] NAMES = { "controlstreams" };
    
    final IObsSystemDatabase db;
    final SystemDatabaseTransactionHandler transactionHandler;
    final CommandStreamEventsHandler eventsHandler;
    
    
    public CommandStreamHandler(HandlerContext ctx, ResourcePermissions permissions)
    {
        super(ctx.getReadDb().getCommandStreamStore(), ctx.getCommandStreamIdEncoder(), ctx, permissions);
        this.db = ctx.getReadDb();
        this.transactionHandler = new SystemDatabaseTransactionHandler(ctx.getEventBus(), ctx.getWriteDb());
        
        this.eventsHandler = new CommandStreamEventsHandler(ctx, permissions);
        addSubResource(eventsHandler);
    }
    
    
    @Override
    protected ResourceBinding<CommandStreamKey, ICommandStreamInfo> getBinding(RequestContext ctx, boolean forReading) throws IOException
    {
        var format = ctx.getFormat();
        
        if (format.equals(ResourceFormat.HTML) || (format.equals(ResourceFormat.AUTO) && ctx.isBrowserHtmlRequest()))
        {
            var title = ctx.getParentID() != null ? "Control channels of {}" : "All Controls";
            return new CommandStreamBindingHtml(ctx, idEncoders, db, true, title);
        }
        else if (format.isOneOf(ResourceFormat.AUTO, ResourceFormat.JSON))
            return new CommandStreamBindingJson(ctx, idEncoders, db, forReading);
        else
            throw ServiceErrors.unsupportedFormat(format);
    }
    
    
    @Override
    public void doPost(RequestContext ctx) throws IOException
    {
        if (ctx.isEndOfPath() &&
            !(ctx.getParentRef().type instanceof SystemHandler))
            throw ServiceErrors.unsupportedOperation("Command streams can only be created within a System resource");
        
        super.doPost(ctx);
    }
    
    
    @Override
    protected void subscribeToEvents(final RequestContext ctx) throws InvalidRequestException, IOException
    {
        eventsHandler.doGet(ctx);
    }
    
    
    @Override
    protected boolean isValidID(BigId internalID)
    {
        return dataStore.containsKey(new CommandStreamKey(internalID));
    }


    @Override
    protected void buildFilter(final ResourceRef parent, final Map<String, String[]> queryParams, final CommandStreamFilter.Builder builder) throws InvalidRequestException
    {
        super.buildFilter(parent, queryParams, builder);
        
        // only fetch datastreams valid at current time by default
        builder.withCurrentVersion();
        
        // filter on parent if needed
        if (parent.internalID != null)
        {
            builder.withSystems()
                .withInternalIDs(parent.internalID)
                .includeMembers(true)
                .done();
        }
        
        /*// foi param
        var foiIDs = parseResourceIds("foi", queryParams);
        if (foiIDs != null && !foiIDs.isEmpty())
        {
            builder.withFois()
                .withInternalIDs(foiIDs)
                .done();
        }*/

        // system param
        var sysIDs = parseResourceIdsOrUids("system", queryParams, idEncoders.getSystemIdEncoder());
        if (sysIDs != null && !sysIDs.isEmpty())
        {
            if (sysIDs.isUids())
                builder.withSystems().withUniqueIDs(sysIDs.getUids()).includeMembers(true).done();
            else
                builder.withSystems().withInternalIDs(sysIDs.getBigIds()).includeMembers(true).done();
        }
    }


    @Override
    protected void validate(ICommandStreamInfo resource)
    {
        // TODO Auto-generated method stub
        
    }
    
    
    @Override
    protected CommandStreamKey addEntry(final RequestContext ctx, final ICommandStreamInfo res) throws DataStoreException
    {
        var sysID = ctx.getParentID();
        
        var sysHandler = transactionHandler.getSystemHandler(sysID);
        var csHandler = sysHandler.addOrUpdateCommandStream(res);
        
        return csHandler.getCommandStreamKey();
    }
    
    
    @Override
    protected boolean updateEntry(final RequestContext ctx, final CommandStreamKey key, final ICommandStreamInfo res) throws DataStoreException
    {
        var csID = key.getInternalID();
        
        var csHandler = transactionHandler.getCommandStreamHandler(csID);
        if (csHandler == null)
            return false;
        
        return csHandler.update(res);
    }
    
    
    protected boolean deleteEntry(final RequestContext ctx, final CommandStreamKey key) throws DataStoreException
    {
        var dsID = key.getInternalID();
        
        var csHandler = transactionHandler.getCommandStreamHandler(dsID);
        if (csHandler == null)
            return false;
        
        return csHandler.delete();
    }


    @Override
    protected CommandStreamKey getKey(BigId internalID)
    {
        return new CommandStreamKey(internalID);
    }
    
    
    @Override
    protected void addOwnerPermissions(RequestContext ctx, String id)
    {
        var sec = (ConSysApiSecurity)ctx.getSecurityHandler();
        
        addPermissionsToCurrentUser(ctx,
            new ItemWithIdPermission(permissions.allOps, id),
            new ItemWithParentPermission(sec.command_permissions.allOps, id)
        );
    }
    
    
    @Override
    public String[] getNames()
    {
        return NAMES;
    }
}
