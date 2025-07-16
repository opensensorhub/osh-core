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
import java.util.Map;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.database.IFeatureDatabase;
import org.sensorhub.api.datastore.feature.FeatureFilter;
import org.sensorhub.api.datastore.feature.IFeatureStore;
import org.sensorhub.api.datastore.feature.FeatureFilter.Builder;
import org.sensorhub.api.datastore.feature.FeatureKey;
import org.sensorhub.impl.service.consys.InvalidRequestException;
import org.sensorhub.impl.service.consys.HandlerContext;
import org.sensorhub.impl.service.consys.ResourceParseException;
import org.sensorhub.impl.service.consys.ServiceErrors;
import org.sensorhub.impl.service.consys.RestApiServlet.ResourcePermissions;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceBinding;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.sensorhub.impl.service.consys.resource.RequestContext.ResourceRef;
import org.vast.ogc.gml.IFeature;


public class FeatureHandler extends AbstractFeatureHandler<IFeature, FeatureFilter, FeatureFilter.Builder, IFeatureStore>
{
    public static final int EXTERNAL_ID_SEED = 815420;
    public static final String[] NAMES = { "features" };
    
    final IFeatureDatabase db;
    
    
    public FeatureHandler(HandlerContext ctx, ResourcePermissions permissions)
    {
        super(ctx.getFeatureStore(), ctx.getFeatureIdEncoder(), ctx, permissions);
        this.db = ctx;
    }


    @Override
    protected ResourceBinding<FeatureKey, IFeature> getBinding(RequestContext ctx, boolean forReading) throws IOException
    {
        var format = ctx.getFormat();
        
        // default to GeoJSON
        if (format.equals(ResourceFormat.AUTO))
            format = ResourceFormat.GEOJSON;
        
        if (format.equals(ResourceFormat.AUTO) && ctx.isBrowserHtmlRequest())
            return new FeatureBindingHtml(ctx, idEncoders, db, true);
        else if (format.isOneOf(ResourceFormat.JSON, ResourceFormat.GEOJSON))
            return new FeatureBindingGeoJson(ctx, idEncoders, db, forReading);
        else
            throw ServiceErrors.unsupportedFormat(format);
    }
    
    
    @Override
    protected boolean isValidID(BigId internalID)
    {
        return dataStore.contains(internalID);
    }
    
    
    @Override
    public void doPost(RequestContext ctx) throws IOException
    {
        if (ctx.isEndOfPath() && !(ctx.getParentRef().type instanceof FeatureHandler))
            throw ServiceErrors.unsupportedOperation("Features can only be created within Feature Collections");
        
        super.doPost(ctx);
    }


    @Override
    protected void buildFilter(ResourceRef parent, Map<String, String[]> queryParams, Builder builder) throws InvalidRequestException
    {
        super.buildFilter(parent, queryParams, builder);
    }


    @Override
    public String[] getNames()
    {
        return NAMES;
    }


    @Override
    protected void validate(IFeature resource) throws ResourceParseException
    {
        super.validate(resource);
    }
}
