/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2016 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.sensor;

import org.sensorhub.api.sensor.PositionConfig;
import org.sensorhub.api.sensor.PositionConfig.LLALocation;
import org.sensorhub.api.sensor.PositionConfig.EulerOrientation;
import org.sensorhub.api.sensor.SensorConfig;


public class SensorConfigWithPos extends SensorConfig
{
    public PositionConfig position;


    @Override
    public LLALocation getLocation()
    {
        if (position == null)
            return null;
        return position.location;
    }


    @Override
    public EulerOrientation getOrientation()
    {
        if (position == null)
            return null;
        return position.orientation;
    }
    
    
    public void setLocation(double lat, double lon, double alt)
    {
        if (position == null)
            position = new PositionConfig();
        
        LLALocation location = position.location = new LLALocation();
        location.lat = lat;
        location.lon = lon;
        location.alt = alt;
    }
    
    
    public void setOrientation(double yaw, double pitch, double roll)
    {
        if (position == null)
            position = new PositionConfig();
        
        EulerOrientation orientation = position.orientation = new EulerOrientation();
        orientation.heading = yaw;
        orientation.pitch = pitch;
        orientation.roll = roll;
    }
}
