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
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.feature.FeatureKey;
import org.sensorhub.api.datastore.feature.FoiFilter;
import org.sensorhub.api.datastore.feature.IFoiStore;
import org.sensorhub.api.event.IEventBus;
import org.sensorhub.impl.service.consys.ObsSystemDbWrapper;
import org.sensorhub.impl.service.consys.ServiceErrors;
import org.sensorhub.impl.service.consys.RestApiServlet.ResourcePermissions;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceBinding;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.vast.ogc.gml.IFeature;


public class FoiHistoryHandler extends AbstractFeatureHistoryHandler<IFeature, FoiFilter, FoiFilter.Builder, IFoiStore>
{
    final IObsSystemDatabase db;
    
    
    public FoiHistoryHandler(IEventBus eventBus, ObsSystemDbWrapper db, ResourcePermissions permissions)
    {
        super(db.getReadDb().getFoiStore(), db.getFoiIdEncoder(), db, permissions);
        this.db = db.getReadDb();
    }


    @Override
    protected ResourceBinding<FeatureKey, IFeature> getBinding(RequestContext ctx, boolean forReading) throws IOException
    {
        var format = ctx.getFormat();
        
        if (format.equals(ResourceFormat.AUTO) && ctx.isBrowserHtmlRequest())
            return new FoiBindingHtml(ctx, idEncoders, db, true);
        else if (format.isOneOf(ResourceFormat.JSON, ResourceFormat.GEOJSON))
            return new FoiBindingGeoJson(ctx, idEncoders, db, forReading);
        else
            throw ServiceErrors.unsupportedFormat(format);
    }
    
    
    @Override
    protected boolean isValidID(BigId internalID)
    {
        return dataStore.contains(internalID);
    }
    

    @Override
    protected void validate(IFeature resource)
    {        
    }
}
