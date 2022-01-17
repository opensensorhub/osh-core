/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.sweapi.task;

import java.io.IOException;
import java.util.Map;
import org.sensorhub.api.command.ICommandStreamInfo;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.command.CommandStreamFilter;
import org.sensorhub.api.datastore.command.CommandStreamKey;
import org.sensorhub.api.datastore.command.ICommandStreamStore;
import org.sensorhub.api.event.IEventBus;
import org.sensorhub.impl.service.sweapi.IdEncoder;
import org.sensorhub.impl.service.sweapi.InvalidRequestException;
import org.sensorhub.impl.service.sweapi.ObsSystemDbWrapper;
import org.sensorhub.impl.service.sweapi.ServiceErrors;
import org.sensorhub.impl.service.sweapi.SWEApiSecurity.ResourcePermissions;
import org.sensorhub.impl.service.sweapi.resource.RequestContext;
import org.sensorhub.impl.service.sweapi.resource.ResourceFormat;
import org.sensorhub.impl.service.sweapi.resource.ResourceBinding;
import org.sensorhub.impl.service.sweapi.resource.ResourceHandler;
import org.sensorhub.impl.service.sweapi.resource.RequestContext.ResourceRef;
import org.sensorhub.impl.service.sweapi.system.SystemHandler;
import org.sensorhub.impl.system.SystemDatabaseTransactionHandler;


public class CommandStreamHandler extends ResourceHandler<CommandStreamKey, ICommandStreamInfo, CommandStreamFilter, CommandStreamFilter.Builder, ICommandStreamStore>
{
    public static final int EXTERNAL_ID_SEED = 918742953;
    public static final String[] NAMES = { "controls" };
    
    SystemDatabaseTransactionHandler transactionHandler;
    
    
    public CommandStreamHandler(IEventBus eventBus, ObsSystemDbWrapper db, ResourcePermissions permissions)
    {
        super(db.getCommandStreamStore(), new IdEncoder(EXTERNAL_ID_SEED), permissions);
        this.transactionHandler = new SystemDatabaseTransactionHandler(eventBus, db);
    }
    
    
    @Override
    protected ResourceBinding<CommandStreamKey, ICommandStreamInfo> getBinding(RequestContext ctx, boolean forReading) throws IOException
    {
        var format = ctx.getFormat();
        
        if (format.equals(ResourceFormat.JSON))
            return new CommandStreamBindingJson(ctx, idEncoder, forReading);
        else
            throw ServiceErrors.unsupportedFormat(format);
    }
    
    
    @Override
    protected boolean isValidID(long internalID)
    {
        return dataStore.containsKey(new CommandStreamKey(internalID));
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
    protected void buildFilter(final ResourceRef parent, final Map<String, String[]> queryParams, final CommandStreamFilter.Builder builder) throws InvalidRequestException
    {
        super.buildFilter(parent, queryParams, builder);
        
        // only fetch datastreams valid at current time by default
        builder.withCurrentVersion();
        
        // filter on parent if needed
        if (parent.internalID > 0)
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
        var procHandler = transactionHandler.getSystemHandler(sysID);
        
        var csHandler = procHandler.addOrUpdateCommandStream(res.getControlInputName(), res.getRecordStructure(), res.getRecordEncoding());
        var csKey = csHandler.getCommandStreamKey();
        
        return new CommandStreamKey(csKey.getInternalID());
    }
    
    
    @Override
    protected boolean updateEntry(final RequestContext ctx, final CommandStreamKey key, final ICommandStreamInfo res) throws DataStoreException
    {        
        var csID = key.getInternalID();
        var csHandler = transactionHandler.getCommandStreamHandler(csID);
        
        if (csHandler == null)
            return false;
        else
            return csHandler.update(res.getRecordStructure(), res.getRecordEncoding());
    }
    
    
    protected boolean deleteEntry(final RequestContext ctx, final CommandStreamKey key) throws DataStoreException
    {        
        var dsID = key.getInternalID();
        
        var csHandler = transactionHandler.getCommandStreamHandler(dsID);
        if (csHandler == null)
            return false;
        else
            return csHandler.delete();
    }


    @Override
    protected CommandStreamKey getKey(long publicID)
    {
        return new CommandStreamKey(publicID);
    }
    
    
    @Override
    public String[] getNames()
    {
        return NAMES;
    }
}