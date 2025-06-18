/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.obs;

import java.io.IOException;
import java.util.Map;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.datastore.obs.DataStreamFilter;
import org.sensorhub.api.datastore.obs.DataStreamKey;
import org.sensorhub.api.datastore.obs.IDataStreamStore;
import org.sensorhub.api.event.IEventBus;
import org.sensorhub.impl.service.consys.InvalidRequestException;
import org.sensorhub.impl.service.consys.ObsSystemDbWrapper;
import org.sensorhub.impl.service.consys.ServiceErrors;
import org.sensorhub.impl.service.consys.RestApiServlet.ResourcePermissions;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceBinding;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.sensorhub.impl.service.consys.resource.ResourceHandler;
import org.sensorhub.impl.service.consys.resource.RequestContext.ResourceRef;
import org.vast.util.Asserts;


public class DataStreamSchemaHandler extends ResourceHandler<DataStreamKey, IDataStreamInfo, DataStreamFilter, DataStreamFilter.Builder, IDataStreamStore>
{
    public static final String[] NAMES = { "schema" };
    
    
    public DataStreamSchemaHandler(IEventBus eventBus, ObsSystemDbWrapper db, ResourcePermissions permissions)
    {
        super(db.getReadDb().getDataStreamStore(), db.getDataStreamIdEncoder(), db, permissions);
    }
    
    
    @Override
    protected ResourceBinding<DataStreamKey, IDataStreamInfo> getBinding(RequestContext ctx, boolean forReading) throws IOException
    {
        var format = ctx.getFormat();
        if (!format.isOneOf(ResourceFormat.AUTO, ResourceFormat.JSON))
            throw ServiceErrors.unsupportedFormat(format);
        
        // generate proper schema depending on obs format
        var obsFormat = parseFormat("obsFormat", ctx.getParameterMap());
        if (obsFormat == null)
            obsFormat = ResourceFormat.JSON;
        
        if (obsFormat.getMimeType().equals("logical"))
            return new DataStreamSchemaBindingLogicalJsonSchema(ctx, idEncoders, forReading);
        else if (format.equals(ResourceFormat.AUTO) && ctx.isBrowserHtmlRequest())
            return new DataStreamBindingHtml(ctx, idEncoders, obsFormat);
        else if (obsFormat.isOneOf(ResourceFormat.JSON, ResourceFormat.OM_JSON))
            return new DataStreamSchemaBindingOmJson(ctx, idEncoders, forReading);
        else if (obsFormat.getMimeType().startsWith(ResourceFormat.SWE_FORMAT_PREFIX))
            return new DataStreamSchemaBindingSweCommon(obsFormat, ctx, idEncoders, forReading);
        else
            throw ServiceErrors.unsupportedFormat(obsFormat);
    }
    
    
    @Override
    protected boolean isValidID(BigId internalID)
    {
        return dataStore.containsKey(new DataStreamKey(internalID));
    }
    
    
    @Override
    public void doPost(RequestContext ctx) throws IOException
    {
        throw ServiceErrors.unsupportedOperation("Cannot POST here, use PUT on main resource URL");
    }
    
    
    @Override
    public void doPut(final RequestContext ctx) throws IOException
    {
        throw ServiceErrors.unsupportedOperation("Cannot PUT here, use PUT on main resource URL");
    }
    
    
    @Override
    public void doDelete(final RequestContext ctx) throws IOException
    {
        throw ServiceErrors.unsupportedOperation("Cannot DELETE here, use DELETE on main resource URL");
    }
    
    
    @Override
    public void doGet(RequestContext ctx) throws IOException
    {
        if (ctx.isEndOfPath())
            getById(ctx, "");
        else
            throw ServiceErrors.badRequest(INVALID_URI_ERROR_MSG);
    }
    
    
    @Override
    protected void getById(final RequestContext ctx, final String id) throws IOException
    {
        // check permissions
        var parentId = ctx.getParentRef().id;
        ctx.getSecurityHandler().checkParentPermission(permissions.get, parentId);
                
        ResourceRef parent = ctx.getParentRef();
        Asserts.checkNotNull(parent, "parent");
        
        // get resource key
        var key = getKey(parent.internalID);
        if (key != null)
            getByKey(ctx, key);
        else
            throw ServiceErrors.notFound();
    }


    @Override
    protected void buildFilter(final ResourceRef parent, final Map<String, String[]> queryParams, final DataStreamFilter.Builder builder) throws InvalidRequestException
    {
        super.buildFilter(parent, queryParams, builder);
    }


    @Override
    protected void validate(IDataStreamInfo resource)
    {
        // TODO Auto-generated method stub
        
    }


    @Override
    protected DataStreamKey getKey(BigId publicID)
    {
        return new DataStreamKey(publicID);
    }
    
    
    @Override
    public String[] getNames()
    {
        return NAMES;
    }
}
