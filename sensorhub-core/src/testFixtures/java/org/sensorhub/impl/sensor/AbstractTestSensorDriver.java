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

import org.junit.Test;
import org.sensorhub.api.sensor.ISensorModule;
import org.sensorhub.api.sensor.SensorConfig;


public abstract class AbstractTestSensorDriver<SensorType extends ISensorModule<ConfigType>, ConfigType extends SensorConfig>
{
    protected ConfigType config;
    protected SensorType sensor;
    
    
    
    @Test
    public void testInit() throws Exception
    {
        sensor.init(config);
    }
    
    
    @Test
    public void testStart() throws Exception
    {
        sensor.start();
    }
}
