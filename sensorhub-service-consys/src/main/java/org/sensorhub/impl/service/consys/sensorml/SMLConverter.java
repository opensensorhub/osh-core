/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2024 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.sensorml;

import java.time.ZoneOffset;
import javax.xml.namespace.QName;
import org.vast.ogc.gml.IFeature;
import org.vast.ogc.xlink.IXlinkReference;
import org.vast.sensorML.SMLBuilders.AbstractProcessBuilder;
import org.vast.sensorML.SMLBuilders.DeploymentBuilder;
import org.vast.sensorML.SMLBuilders.PhysicalSystemBuilder;
import org.vast.swe.SWEConstants;
import org.vast.sensorML.SMLFactory;
import org.vast.sensorML.SMLHelper;
import net.opengis.gml.v32.Point;
import net.opengis.sensorml.v20.AbstractProcess;
import net.opengis.sensorml.v20.Deployment;


public class SMLConverter extends SMLHelper
{
    
    public SMLConverter()
    {
        super();
    }
    
    
    public SMLConverter(SMLFactory fac)
    {
        super(fac);
    }
    
    
    protected String checkFeatureType(IFeature f)
    {
        var type = f.getType();
        if (type == null)
            throw new IllegalStateException("Missing feature type");
        return type;
    }
    
    
    public AbstractProcess genericFeatureToSystem(IFeature f)
    {
        AbstractProcessBuilder<?,?> builder = null;
        var type = checkFeatureType(f);
        
        // resolve CURIEs
        type = type.replaceFirst("sosa:", SWEConstants.SOSA_URI_PREFIX)
                   .replaceFirst("ssn:", SWEConstants.SSN_URI_PREFIX);
        
        var assetType = (String)f.getProperties().get(new QName("assetType"));
        
        if (SWEConstants.DEF_SYSTEM.equals(type) ||
            SWEConstants.DEF_SENSOR.equals(type) ||
            SWEConstants.DEF_ACTUATOR.equals(type) ||
            SWEConstants.DEF_SAMPLER.equals(type) ||
            SWEConstants.DEF_PLATFORM.equals(type) ||
            SWEConstants.DEF_SYSTEM_SSN.equals(type) ||
            SWEConstants.DEF_PROCESS.equals(type))
        {
            builder = SWEConstants.DEF_PROCESS.equals(type) ||
                SWEConstants.ASSET_TYPE_PROCESS.equals(assetType) ||
                SWEConstants.ASSET_TYPE_SIMULATION.equals(assetType) ?
                createSimpleProcess() : createPhysicalSystem();
            
            builder.uniqueID(f.getUniqueIdentifier())
                .name(f.getName())
                .description(f.getDescription())
                .definition(f.getType());
            
            var validTime = f.getValidTime();
            if (f.getValidTime() != null)
            {
                builder.validTimePeriod(
                    validTime.begin().atOffset(ZoneOffset.UTC),
                    validTime.end().atOffset(ZoneOffset.UTC));
            }
            
            if (f.getGeometry() != null && builder instanceof PhysicalSystemBuilder)
            {
                if (f.getGeometry() instanceof Point)
                    ((PhysicalSystemBuilder)builder).location((Point)f.getGeometry());
                else
                    throw new IllegalStateException("Unsupported System geometry: " + f.getGeometry());
            }
            
            var systemKindLink = (IXlinkReference<?>)f.getProperties().get(new QName("systemKind"));
            if (systemKindLink != null)
            {
                var href = systemKindLink.getHref().replace("f=json", "f=sml");
                var title = systemKindLink.getTitle();
                builder.typeOf(href, title);
            }
        }
        
        if (builder == null)
            throw new IllegalStateException("Unsupported feature type: " + f.getType());
        
        return builder.build();
    }
    
    
    public AbstractProcess genericFeatureToProcedure(IFeature f)
    {
        AbstractProcessBuilder<?,?> builder = null;
        var type = checkFeatureType(f);
        
        // resolve CURIEs
        type = type.replaceFirst("sosa:", SWEConstants.SOSA_URI_PREFIX)
                   .replaceFirst("ssn:", SWEConstants.SSN_URI_PREFIX);
        
        if (SWEConstants.DEF_SYSTEM.equals(type) ||
            SWEConstants.DEF_SENSOR.equals(type) ||
            SWEConstants.DEF_ACTUATOR.equals(type) ||
            SWEConstants.DEF_SAMPLER.equals(type) ||
            SWEConstants.DEF_PLATFORM.equals(type) ||
            SWEConstants.DEF_SYSTEM_SSN.equals(type))
        {
            builder = createPhysicalSystem()
                .uniqueID(f.getUniqueIdentifier())
                .name(f.getName())
                .description(f.getDescription())
                .definition(f.getType());
        }
        else if (SWEConstants.DEF_PROCEDURE.equals(type) ||
                 SWEConstants.DEF_OBS_PROCEDURE.equals(type) ||
                 SWEConstants.DEF_ACT_PROCEDURE.equals(type) ||
                 SWEConstants.DEF_SAM_PROCEDURE.equals(type))
        {
            builder = createSimpleProcess()
                .uniqueID(f.getUniqueIdentifier())
                .name(f.getName())
                .description(f.getDescription())
                .definition(f.getType());
        }
        
        if (builder == null)
            throw new IllegalStateException("Unsupported feature type: " + f.getType());
        
        var validTime = f.getValidTime();
        if (f.getValidTime() != null)
        {
            builder.validTimePeriod(
                validTime.begin().atOffset(ZoneOffset.UTC),
                validTime.end().atOffset(ZoneOffset.UTC));
        }
        
        return builder.build();
    }
    
    
    public Deployment genericFeatureToDeployment(IFeature f)
    {
        DeploymentBuilder builder = null;
        var type = checkFeatureType(f);
        
        if (SWEConstants.DEF_DEPLOYMENT.equals(type) ||
            SWEConstants.DEF_DEPLOYMENT_SSN.equals(type))
        {
            builder = createDeployment()
                .uniqueID(f.getUniqueIdentifier())
                .name(f.getName())
                .description(f.getDescription());
            
            if (f.getGeometry() != null)
                ((DeploymentBuilder)builder).location(f.getGeometry());
        }
        
        if (builder == null)
            throw new IllegalStateException("Unsupported feature type: " + f.getType());
        
        return builder.build();
    }
    
}
