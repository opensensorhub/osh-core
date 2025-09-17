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
import java.util.ArrayList;
import java.util.Map;
import javax.xml.namespace.QName;
import org.sensorhub.api.common.IdEncoders;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.feature.FeatureKey;
import org.sensorhub.api.system.ISystemWithDesc;
import org.sensorhub.impl.service.consys.LinkResolver;
import org.sensorhub.impl.service.consys.feature.AbstractFeatureBindingGeoJson;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.sensorhub.impl.service.consys.resource.ResourceLink;
import org.sensorhub.impl.service.consys.sensorml.SystemAdapter;
import org.vast.ogc.gml.GeoJsonBindings;
import org.vast.ogc.gml.IFeature;
import org.vast.ogc.xlink.IXlinkReference;
import org.vast.swe.SWEConstants;
import org.vast.util.Asserts;
import org.vast.util.TimeExtent;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import net.opengis.gml.v32.AbstractGeometry;
import net.opengis.sensorml.v20.AbstractPhysicalProcess;
import net.opengis.sensorml.v20.AbstractProcess;


/**
 * <p>
 * GeoJSON formatter for system resources
 * </p>
 *
 * @author Alex Robin
 * @since Jan 26, 2021
 */
public class SystemBindingGeoJson extends AbstractFeatureBindingGeoJson<ISystemWithDesc, IObsSystemDatabase>
{
    final SystemAssocs assocs;
    
    
    public SystemBindingGeoJson(RequestContext ctx, IdEncoders idEncoders, IObsSystemDatabase db, boolean forReading) throws IOException
    {
        super(ctx, idEncoders, db, forReading);
        this.assocs = new SystemAssocs(db, idEncoders);
    }
    
    
    @Override
    protected GeoJsonBindings getJsonBindings()
    {
        return new GeoJsonBindings() {
            
            @Override
            public IFeature readFeature(JsonReader reader) throws IOException
            {
                var f = super.readFeature(reader);
                return new SystemAdapter(f);
            }
            
            @Override
            public void writeLink(JsonWriter writer, IXlinkReference<?> link) throws IOException
            {
                LinkResolver.resolveLink(ctx, link, db, idEncoders);
                super.writeLink(writer, link);
            }
            
            @Override
            protected void writeCustomFeatureProperties(JsonWriter writer, IFeature bean) throws IOException
            {
                super.writeCustomFeatureProperties(writer, bean);
                
                var sml = ((ISystemWithDesc)bean).getFullDescription();
                if (sml != null)
                {
                    var assetType = sml.getAssetType();
                    if (assetType != null)
                        writer.name("assetType").value(assetType);
                    else if (!(sml instanceof AbstractPhysicalProcess))
                        writer.name("assetType").value(SWEConstants.ASSET_TYPE_PROCESS);
                    
                    var typeOf = sml.getTypeOf();
                    if (typeOf != null)
                    {
                        writer.name("systemKind@link");
                        writeLink(writer, typeOf);
                    }
                }
            }
            
            @Override
            protected void writeCustomJsonProperties(JsonWriter writer, IFeature bean) throws IOException
            {
                super.writeCustomJsonProperties(writer, bean);
                
                if (showLinks)
                {
                    var links = new ArrayList<ResourceLink>();
                    
                    links.add(assocs.getCanonicalLink(bean.getId()));
                    links.add(assocs.getAlternateLink(bean.getId(), ResourceFormat.SML_JSON, "SensorML"));
                    links.add(assocs.getAlternateLink(bean.getId(), ResourceFormat.HTML, "HTML"));
                    assocs.getParentLink(bean.getId(), ResourceFormat.GEOJSON).ifPresent(links::add);
                    assocs.getSubsystemsLink(bean.getId(), ResourceFormat.GEOJSON).ifPresent(links::add);
                    assocs.getSamplingFeaturesLink(bean.getId(), ResourceFormat.GEOJSON).ifPresent(links::add);
                    assocs.getDataStreamsLink(bean.getId(), ResourceFormat.JSON).ifPresent(links::add);
                    assocs.getControlStreamsLink(bean.getId(), ResourceFormat.JSON).ifPresent(links::add);
                    
                    writeLinksAsJson(writer, links);
                }
            }
        };
    }
    
    
    @Override
    protected ISystemWithDesc getFeatureWithId(FeatureKey key, ISystemWithDesc proc)
    {
        Asserts.checkNotNull(key, FeatureKey.class);
        Asserts.checkNotNull(proc, ISystemWithDesc.class);
        
        return new ISystemWithDesc()
        {
            public String getUniqueIdentifier() { return proc.getUniqueIdentifier(); }
            public String getType() { return proc.getType(); }
            public String getName() { return proc.getName(); }
            public String getDescription() { return proc.getDescription(); }
            public Map<QName, Object> getProperties() { return proc.getProperties(); }  
            public TimeExtent getValidTime() { return proc.getValidTime(); }
            public AbstractProcess getFullDescription() { return proc.getFullDescription(); }
            public AbstractGeometry getGeometry() { return proc.getGeometry(); }
        
            public String getId()
            {
                return idEncoders.getSystemIdEncoder().encodeID(key.getInternalID());
            }
        };
    }
}
