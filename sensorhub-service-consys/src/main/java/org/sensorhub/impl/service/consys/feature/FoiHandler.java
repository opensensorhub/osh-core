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
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.feature.FeatureKey;
import org.sensorhub.api.datastore.feature.FoiFilter;
import org.sensorhub.api.datastore.feature.IFoiStore;
import org.sensorhub.api.event.IEventBus;
import org.sensorhub.impl.service.consys.InvalidRequestException;
import org.sensorhub.impl.service.consys.ObsSystemDbWrapper;
import org.sensorhub.impl.service.consys.ResourceParseException;
import org.sensorhub.impl.service.consys.ServiceErrors;
import org.sensorhub.impl.service.consys.RestApiServlet.ResourcePermissions;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceBinding;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.sensorhub.impl.service.consys.resource.RequestContext.ResourceRef;
import org.sensorhub.impl.system.SystemDatabaseTransactionHandler;
import org.vast.ogc.gml.IFeature;


public class FoiHandler extends AbstractFeatureHandler<IFeature, FoiFilter, FoiFilter.Builder, IFoiStore>
{
    public static final String[] NAMES = { "samplingFeatures", "fois" };
    
    final IObsSystemDatabase db;
    final SystemDatabaseTransactionHandler transactionHandler;
    
    
    public FoiHandler(IEventBus eventBus, ObsSystemDbWrapper db, ResourcePermissions permissions)
    {
        super(db.getFoiStore(), db.getFoiIdEncoder(), db, permissions);
        this.db = db.getReadDb();
        this.transactionHandler = new SystemDatabaseTransactionHandler(eventBus, db.getWriteDb());
    }


    @Override
    protected ResourceBinding<FeatureKey, IFeature> getBinding(RequestContext ctx, boolean forReading) throws IOException
    {
        var format = ctx.getFormat();
        
        if (format.equals(ResourceFormat.HTML) || (format.equals(ResourceFormat.AUTO) && ctx.isBrowserHtmlRequest()))
            return new FoiBindingHtml(ctx, idEncoders, db, true);
        else if (format.isOneOf(ResourceFormat.AUTO, ResourceFormat.JSON, ResourceFormat.GEOJSON))
        {
            if (ctx.getParameterMap().containsKey("snapshot"))
                return new DynamicFoiBindingGeoJson(ctx, idEncoders, db, forReading);
            else
                return new FoiBindingGeoJson(ctx, idEncoders, db, forReading);
        }
        else
            throw ServiceErrors.unsupportedFormat(format);
    }
    
    
    @Override
    protected boolean isValidID(BigId internalID)
    {
        return dataStore.contains(internalID);
    }


    @Override
    protected void buildFilter(ResourceRef parent, Map<String, String[]> queryParams, FoiFilter.Builder builder) throws InvalidRequestException
    {
        super.buildFilter(parent, queryParams, builder);
        
        if (parent.internalID != null)
        {
            builder.withParents()
                .withInternalIDs(parent.internalID)
                .includeMembers(true)
                .done();
        }
        else
        {
            var parentIDs = parseResourceIdsOrUids("parent", queryParams, idEncoders.getSystemIdEncoder());
            
            if (parentIDs != null && !parentIDs.isEmpty())
            {
                if (parentIDs.isUids())
                    builder.withParents().withUniqueIDs(parentIDs.getUids()).done();
                else
                    builder.withParents().withInternalIDs(parentIDs.getBigIds()).done();
            }
        }
    }


    @Override
    protected void validate(IFeature resource) throws ResourceParseException
    {
        super.validate(resource);
    }
    
    
    @Override
    protected FeatureKey addEntry(final RequestContext ctx, final IFeature foi) throws DataStoreException
    {
        if (ctx.getParentID() != null)
        {
            var sysHandler = transactionHandler.getSystemHandler(ctx.getParentID());
            return sysHandler.addFoi(foi);
        }
        
        return transactionHandler.addFoi(BigId.NONE, foi);
    }
    
    
    @Override
    protected boolean updateEntry(final RequestContext ctx, final FeatureKey key, final IFeature f) throws DataStoreException
    {        
        //return dataStore.put(key, f) != null;
        return transactionHandler.updateFoi(key.getInternalID(), f);
    }


    @Override
    public String[] getNames()
    {
        return NAMES;
    }
}
