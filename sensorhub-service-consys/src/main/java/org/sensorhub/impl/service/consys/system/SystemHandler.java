/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.system;

import java.io.IOException;
import java.util.Map;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.feature.FeatureKey;
import org.sensorhub.api.datastore.system.ISystemDescStore;
import org.sensorhub.api.datastore.system.SystemFilter;
import org.sensorhub.api.event.IEventBus;
import org.sensorhub.api.system.ISystemWithDesc;
import org.sensorhub.impl.security.ItemWithIdPermission;
import org.sensorhub.impl.security.ItemWithParentPermission;
import org.sensorhub.impl.service.consys.InvalidRequestException;
import org.sensorhub.impl.service.consys.ObsSystemDbWrapper;
import org.sensorhub.impl.service.consys.ResourceParseException;
import org.sensorhub.impl.service.consys.ConSysApiSecurity;
import org.sensorhub.impl.service.consys.ServiceErrors;
import org.sensorhub.impl.service.consys.RestApiServlet.ResourcePermissions;
import org.sensorhub.impl.service.consys.feature.AbstractFeatureHandler;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceBinding;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.sensorhub.impl.service.consys.resource.RequestContext.ResourceRef;
import org.sensorhub.impl.system.SystemDatabaseTransactionHandler;
import org.sensorhub.impl.system.SystemUtils;
import org.sensorhub.impl.system.wrapper.SmlFeatureWrapper;


public class SystemHandler extends AbstractFeatureHandler<ISystemWithDesc, SystemFilter, SystemFilter.Builder, ISystemDescStore>
{
    public static final String[] NAMES = { "systems" };
    
    public static final String REL_PARENT = "parent";
    public static final String REL_SUBSYSTEMS = "subsystems";
    public static final String REL_SF = "samplingFeatures";
    public static final String REL_DATASTREAMS = "datastreams";
    public static final String REL_CONTROLSTREAMS = "controlstreams";
    public static final String REL_HISTORY = "history";
    
    final IEventBus eventBus;
    final IObsSystemDatabase db;
    final SystemDatabaseTransactionHandler transactionHandler;
    final SystemEventsHandler eventsHandler;
    
    
    public SystemHandler(IEventBus eventBus, ObsSystemDbWrapper db, ResourcePermissions permissions)
    {
        super(db.getReadDb().getSystemDescStore(), db.getSystemIdEncoder(), db.getIdEncoders(), permissions);
        this.db = db.getReadDb();
        this.eventBus = eventBus;
        this.transactionHandler = new SystemDatabaseTransactionHandler(eventBus, db.getWriteDb());
        
        this.eventsHandler = new SystemEventsHandler(eventBus, db, permissions);
        addSubResource(eventsHandler);
    }


    @Override
    protected ResourceBinding<FeatureKey, ISystemWithDesc> getBinding(RequestContext ctx, boolean forReading) throws IOException
    {
        var format = ctx.getFormat();
        
        if (format.equals(ResourceFormat.HTML) || (format.equals(ResourceFormat.AUTO) && ctx.isBrowserHtmlRequest()))
            return new SystemBindingHtml(ctx, idEncoders, db, true);
        else if (format.isOneOf(ResourceFormat.AUTO, ResourceFormat.JSON, ResourceFormat.GEOJSON))
            return new SystemBindingGeoJson(ctx, idEncoders, db, forReading);
        else if (format.equals(ResourceFormat.SML_JSON))
            return new SystemBindingSmlJson(ctx, idEncoders, forReading);
        else if (format.equals(ResourceFormat.SML_XML))
            return new SystemBindingSmlXml(ctx, idEncoders, forReading);
        else
            throw ServiceErrors.unsupportedFormat(format);
    }


    @Override
    protected void subscribeToEvents(final RequestContext ctx) throws InvalidRequestException, IOException
    {
        eventsHandler.doGet(ctx);
    }
    
    
    @Override
    protected boolean isValidID(BigId internalID)
    {
        return dataStore.contains(internalID);
    }


    @Override
    protected void buildFilter(final ResourceRef parent, final Map<String, String[]> queryParams, final SystemFilter.Builder builder) throws InvalidRequestException
    {
        super.buildFilter(parent, queryParams, builder);
        
        var val = getSingleParam("searchMembers", queryParams);
        boolean searchMembers =  (val != null && !val.equalsIgnoreCase("false"));
        boolean parentSelected = false;
        
        // parent ID
        var ids = parseResourceIds("parentId", queryParams, idEncoders.getSystemIdEncoder());
        if (ids != null && !ids.isEmpty())
        {
            parentSelected = true;
            builder.withParents()
                .withInternalIDs(ids)
                .done();
        }
        
        // parent UID
        var uids = parseMultiValuesArg("parentUid", queryParams);
        if (uids != null && !uids.isEmpty())
        {
            parentSelected = true;
            builder.withParents()
                .withUniqueIDs(uids)
                .done();
        }
        
        // foi param
        var foiIDs = parseResourceIds("foi", queryParams, idEncoders.getFoiIdEncoder());
        if (foiIDs != null && !foiIDs.isEmpty())
        {
            builder.withFois()
                    .withInternalIDs(foiIDs)
                    .done();
        }
        
        // list only top level systems by default unless specific IDs are requested
        if (!parentSelected && !searchMembers &&
            !queryParams.containsKey("id") && !queryParams.containsKey("uid") && !queryParams.containsKey("foi"))
            builder.withNoParent();
        
        // procedure UID
        var procUids = parseMultiValuesArg("procedure", queryParams);
        if (procUids != null && !procUids.isEmpty())
        {
            builder.withProcedures()
                .withUniqueIDs(procUids)
                .done();
        }
    }


    @Override
    protected void validate(ISystemWithDesc resource) throws ResourceParseException
    {
        super.validate(resource);
    }
    
    
    @Override
    protected FeatureKey addEntry(final RequestContext ctx, ISystemWithDesc res) throws DataStoreException
    {        
        // cleanup sml description before storage
        var sml = res.getFullDescription();
        if (sml != null)
        {
            res = new SmlFeatureWrapper(res.getFullDescription())
                .hideOutputs()
                .hideTaskableParams()
                .defaultToValidFromNow();
        }
        
        var sysHandler = transactionHandler.addSystemOrReturnExisting(res);

        // also add datastreams if outputs were specified in SML description
        if (sml != null)
            SystemUtils.addDatastreamsFromOutputs(sysHandler, sml.getOutputList());
        
        return sysHandler.getSystemKey();
    }
    
    
    @Override
    protected boolean updateEntry(final RequestContext ctx, final FeatureKey key, ISystemWithDesc res) throws DataStoreException
    {        
        var sysHandler = transactionHandler.getSystemHandler(key.getInternalID());
        if (sysHandler == null)
            return false;
        
        // cleanup sml description before storage
        var sml = res.getFullDescription();
        if (sml != null)
        {
            res = new SmlFeatureWrapper(res.getFullDescription())
                .hideOutputs()
                .hideTaskableParams()
                .defaultToValidFromNow();
        }
        
        return sysHandler.update(res);
    }
    
    
    @Override
    protected boolean deleteEntry(final RequestContext ctx, final FeatureKey key) throws DataStoreException
    {
        var sysHandler = transactionHandler.getSystemHandler(key.getInternalID());
        if (sysHandler == null)
            return false;
        else
        {
            var paramStr = ctx.getParameter("cascade");
            var deleteNested = paramStr != null ? Boolean.parseBoolean(paramStr) : false;
            return sysHandler.delete(deleteNested);
        }
    }
    
    
    @Override
    protected void addOwnerPermissions(RequestContext ctx, String id)
    {
        var sec = (ConSysApiSecurity)ctx.getSecurityHandler();
        
        addPermissionsToCurrentUser(ctx,
            new ItemWithIdPermission(permissions.allOps, id),
            new ItemWithParentPermission(sec.system_permissions.allOps, id),
            new ItemWithParentPermission(sec.foi_permissions.allOps, id),
            new ItemWithParentPermission(sec.datastream_permissions.allOps, id),
            new ItemWithParentPermission(sec.commandstream_permissions.allOps, id)
        );
    }
    
    
    @Override
    public String[] getNames()
    {
        return NAMES;
    }
}
