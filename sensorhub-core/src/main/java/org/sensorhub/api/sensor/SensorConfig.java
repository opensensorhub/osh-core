/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.sensor;

import java.net.URL;
import java.util.Date;
import org.sensorhub.api.config.DisplayInfo;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.api.sensor.PositionConfig.LLALocation;
import org.sensorhub.api.sensor.PositionConfig.EulerOrientation;


/**
 * <p>
 * Configuration options for sensors/actuators
 * </p>
 *
 * @author Alex Robin
 * @since Nov 5, 2010
 */
public class SensorConfig extends ModuleConfig
{
    
    @DisplayInfo(label="SensorML URL", desc="URL of SensorML file providing the base description of the sensor")
    public String sensorML;
    
    
    @DisplayInfo(desc="Time at which the SensorML description was last updated")
    public Date lastUpdated = new Date();


    @DisplayInfo(label = "Identification Info", desc="Information used to uniquely identify this system and its responsible parties")
    public IdentificationInfo identificationInfo;
    
    
    /**
     * Gets the URL of the SensorML template.<br/>
     * If the {@link #sensorML} field is not a URL, it is interpreted as a
     * relative path relative to the config class.
     * @return URL of template SensorML description
     */
    public String getSensorDescriptionURL()
    {
        if (sensorML == null)
            return null;
        
        if (sensorML.contains(":"))
            return sensorML;
        
        // else try to get java resource
        URL resourceUrl = getClass().getResource(sensorML);
        return (resourceUrl == null) ? null : resourceUrl.toString();
    }
    
    
    /**
     * This method must be overriden to give access to static sensor
     * location provided in specific sensor configuration
     * @return null in the default implementation
     */
    public LLALocation getLocation()
    {
        return null;
    }
    
    
    /**
     * This method must be overriden to give access to static sensor
     * orientation provided in specific sensor configuration
     * @return null in the default implementation
     */
    public EulerOrientation getOrientation()
    {
        return null;
    }
}
