/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.feature;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.common.IdEncoder;
import org.sensorhub.api.datastore.feature.FeatureFilterBase;
import org.sensorhub.api.datastore.feature.FeatureKey;
import org.sensorhub.api.datastore.feature.IFeatureStoreBase;
import org.sensorhub.api.datastore.feature.FeatureFilterBase.FeatureFilterBaseBuilder;
import org.sensorhub.impl.service.consys.InvalidRequestException;
import org.sensorhub.impl.service.consys.ObsSystemDbWrapper;
import org.sensorhub.impl.service.consys.ServiceErrors;
import org.sensorhub.impl.service.consys.RestApiServlet.ResourcePermissions;
import org.sensorhub.impl.service.consys.resource.IResourceHandler;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceHandler;
import org.sensorhub.impl.service.consys.resource.RequestContext.ResourceRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.ogc.gml.IFeature;


public abstract class AbstractFeatureHistoryHandler<
        V extends IFeature,
        F extends FeatureFilterBase<? super V>,
        B extends FeatureFilterBaseBuilder<B,? super V,F>,
        S extends IFeatureStoreBase<V,?,F>> 
    extends ResourceHandler<FeatureKey, V, F, B, S>
{
    static final Logger log = LoggerFactory.getLogger(AbstractFeatureHistoryHandler.class);
    
    public static final String[] NAMES = { "history" };
    
    
    public AbstractFeatureHistoryHandler(S dataStore, IdEncoder idEncoder, ObsSystemDbWrapper db, ResourcePermissions permissions)
    {
        super(dataStore, idEncoder, db, permissions);
    }
    
    
    @Override
    public void doPost(RequestContext ctx) throws IOException
    {
        throw ServiceErrors.unsupportedOperation("Cannot POST in history collection, use PUT on main resource URL with a new validTime");
    }
    
    
    @Override
    public void doPut(RequestContext ctx) throws IOException
    {
        throw ServiceErrors.unsupportedOperation("Cannot PUT in history collection, use PUT on main resource URL with a new validTime");
    }
    
    
    @Override
    protected void getById(final RequestContext ctx, final String id) throws IOException
    {
        // check permissions
        var parentId = ctx.getParentRef().id;
        ctx.getSecurityHandler().checkParentPermission(permissions.get, parentId);
        
        // internal ID & version number
        var internalID = ctx.getParentID();
        var validTime = getValidTime(ctx, id);
        
        var key = getKey(internalID, validTime);
        V res = dataStore.get(key);
        if (res == null)
            throw ServiceErrors.notFound();
        
        var queryParams = ctx.getParameterMap();
        var responseFormat = parseFormat(queryParams);
        ctx.setFormatOptions(responseFormat, parseSelectArg(queryParams));
        var binding = getBinding(ctx, false);
        
        ctx.setResponseContentType(responseFormat.getMimeType());
        binding.serialize(key, res, true);
    }
    
    
    @Override
    protected void delete(final RequestContext ctx, final String id) throws IOException
    {
        // check permissions
        ctx.getSecurityHandler().checkResourcePermission(permissions.delete, id);
        
        // internal ID & version number
        var internalID = ctx.getParentID();
        var validTime = getValidTime(ctx, id);
        
        // delete resource
        IFeature res = dataStore.remove(getKey(internalID, validTime));
        if (res == null)
            throw ServiceErrors.notFound();
    }
    
    
    protected Instant getValidTime(final RequestContext ctx, final String timeStamp) throws InvalidRequestException
    {
        try
        {
            return Instant.parse(timeStamp);
        }
        catch (DateTimeParseException e)
        {
            throw ServiceErrors.badRequest(INVALID_TIMESTAMP_ERROR_MSG + timeStamp);
        }
    }
    
    
    @Override
    protected IResourceHandler getSubResource(RequestContext ctx, String id) throws InvalidRequestException
    {
        IResourceHandler resource = getSubResource(ctx);
        
        var internalID = ctx.getParentRef().internalID;
        var validTime = getValidTime(ctx, id);
        ctx.setParent(this, id, internalID, validTime);
        
        return resource;
    }


    @Override
    protected void buildFilter(final ResourceRef parent, final Map<String, String[]> queryParams, final B builder) throws InvalidRequestException
    {
        super.buildFilter(parent, queryParams, builder);
        
        builder.withInternalIDs(parent.internalID);
        
        // validTime param
        var validTime = parseTimeStampArg("validTime", queryParams);
        if (validTime != null)
            builder.withValidTime(validTime);
    }


    @Override
    protected FeatureKey getKey(BigId internalID)
    {
        throw new UnsupportedOperationException();
    }
    
    
    protected FeatureKey getKey(BigId internalID, Instant validTime)
    {
        return dataStore.selectKeys(dataStore.filterBuilder()
                .withInternalIDs(internalID)
                .validAtTime(validTime)
                .build())
            .findFirst()
            .orElse(null);
    }
    
    
    @Override
    public String[] getNames()
    {
        return NAMES;
    }
}
