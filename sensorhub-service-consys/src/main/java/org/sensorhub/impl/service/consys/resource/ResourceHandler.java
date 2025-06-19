/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.resource;

import java.util.LinkedHashSet;
import java.util.Map;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.common.IdEncoder;
import org.sensorhub.api.datastore.feature.FeatureFilterBase.FeatureFilterBaseBuilder;
import org.sensorhub.api.datastore.resource.IResourceStore;
import org.sensorhub.api.resource.ResourceFilter;
import org.sensorhub.api.resource.ResourceFilter.ResourceFilterBuilder;
import org.sensorhub.impl.service.consys.InvalidRequestException;
import org.sensorhub.impl.service.consys.HandlerContext;
import org.sensorhub.impl.service.consys.RestApiServlet.ResourcePermissions;
import org.sensorhub.impl.service.consys.resource.RequestContext.ResourceRef;
import org.sensorhub.api.resource.ResourceKey;
import org.vast.util.IResource;


/**
 * <p>
 * Base resource handler, maintaining connection to a datastore
 * </p>
 *
 * @author Alex Robin
 * @param <K> 
 * @param <V> 
 * @param <F> 
 * @param <B> 
 * @param <S> 
 * @date Nov 15, 2018
 */
public abstract class ResourceHandler<
    K extends ResourceKey<K>,
    V extends IResource,
    F extends ResourceFilter<? super V>,
    B extends ResourceFilterBuilder<B, ? super V, F>,
    S extends IResourceStore<K,V,?,F>> extends BaseResourceHandler<K, V, F, S>
{
    public static final int NO_PARENT = 0;
    
    
    protected ResourceHandler(S dataStore, IdEncoder idEncoder, HandlerContext ctx, ResourcePermissions permissions)
    {
        super(dataStore, idEncoder, ctx, permissions);
    }
    
    
    protected abstract K getKey(BigId internalID);
    
    
    @Override
    protected K getKey(final RequestContext ctx, final String id) throws InvalidRequestException
    {
        var decodedID = decodeID(id);
        return getKey(decodedID);
    }
        
    
    /*protected long getParentInternalID(final ResourceContext ctx, final String id, final HttpServletResponse resp)
    {
        long internalID = getInternalID(ctx, id, resp);
        
        if (internalID > 0 && !dataStore.containsKey(getKey(internalID)))
        {
            ctx.sendError(404, String.format(NOT_FOUND_ERROR_MSG, id), resp);
            return 0;
        }        
            
        return internalID;
    }*/
    
    
    @SuppressWarnings({ "unchecked" })
    public F getFilter(final ResourceRef parent, final Map<String, String[]> queryParams, long offset, long limit) throws InvalidRequestException
    {
        B builder = (B)dataStore.filterBuilder();
        if (queryParams != null)
        {
            buildFilter(parent, queryParams, builder);
            
            // limit
            // need to limit to offset+limit+1 since we rescan from the beginning for now
            // remove the limit here since it can interfere with post filtering in postProcessResultList
            //builder.withLimit(offset+limit+1);
        }
        return builder.build();
    }
    
    
    protected void buildFilter(final ResourceRef parent, final Map<String, String[]> queryParams, final B builder) throws InvalidRequestException
    {
        // id param
        var resourceIds = parseResourceIdsOrUids("id", queryParams, idEncoder);
        if (resourceIds != null && !resourceIds.isEmpty())
        {
            if (resourceIds.isUids()) {
                if (builder instanceof FeatureFilterBaseBuilder)
                    ((FeatureFilterBaseBuilder<?,?,?>)builder).withUniqueIDs(resourceIds.getUids());
                else {
                    var internalIDs = new LinkedHashSet<BigId>();
                    for (var uid: resourceIds.getUids())
                        internalIDs.add(decodeID(uid));
                    builder.withInternalIDs(internalIDs);
                }
            }
            else
                builder.withInternalIDs(resourceIds.getBigIds());
        }
        
        // keyword search
        var keywords = parseMultiValuesArg("q", queryParams);
        if (keywords != null && !keywords.isEmpty())
            builder.withKeywords(keywords);
    }
    
    
    @Override
    protected String encodeKey(final RequestContext ctx, K key)
    {
        return idEncoder.encodeID(key.getInternalID());
    }
}
