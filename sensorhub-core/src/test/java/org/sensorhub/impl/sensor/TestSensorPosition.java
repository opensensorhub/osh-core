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

import static org.junit.Assert.*;
import org.junit.Before;
import net.opengis.gml.v32.AbstractFeature;
import net.opengis.gml.v32.Point;
import net.opengis.sensorml.v20.AbstractPhysicalProcess;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataRecord;
import net.opengis.swe.v20.Vector;
import org.junit.Test;
import org.sensorhub.api.ISensorHub;
import org.sensorhub.impl.SensorHub;
import org.vast.ogc.gml.GMLUtils;
import org.vast.sensorML.SMLUtils;
import org.vast.swe.SWEConstants;


public class TestSensorPosition
{
    double lat = 34.56;
    double lon = 1.2;
    double alt = 568;
    double heading = 56.0;
    double pitch = -12.3;
    double roll = 3.6;
    ISensorHub hub;
    
    
    @Before
    public void setup() throws Exception
    {
        // init sensorhub with in-memory config
        hub = new SensorHub();
        hub.start();
    }
    
    protected void checkFoiLocation(FakeSensorWithPos sensor) throws Exception
    {
        var fois = sensor.getCurrentFeaturesOfInterest().values();
        var f = fois.stream().findFirst().get();
        new GMLUtils(GMLUtils.V3_2).writeFeature(System.out, (AbstractFeature)f, true);
        System.out.println('\n');
        
        assertTrue("FoI must be a point", f.getGeometry() instanceof Point);
        double[] loc = ((Point)f.getGeometry()).getPos();
        assertEquals("Wrong latitude value", lat, loc[0], 0.0);
        assertEquals("Wrong longitude value", lon, loc[1], 0.0);
        assertEquals("Wrong altitude value", alt, loc[2], 0.0);
        assertEquals("Wrong CRS", SWEConstants.REF_FRAME_4979, f.getGeometry().getSrsName());
    }
    
    
    protected void checkSmlLocationVector(Vector vector) throws Exception
    {
        DataBlock posData = vector.getData();
        assertEquals("Wrong latitude value", lat, posData.getDoubleValue(0), 1e-12);
        assertEquals("Wrong longitude value", lon, posData.getDoubleValue(1), 1e-12);
        assertEquals("Wrong altitude value", alt, posData.getDoubleValue(2), 1e-12);
    }
    
    
    protected void checkSmlOrientationVector(Vector vector) throws Exception
    {
        DataBlock posData = vector.getData();
        assertEquals("Wrong heading value", heading, posData.getDoubleValue(0), 1e-6);
        assertEquals("Wrong pitch value", pitch, posData.getDoubleValue(1), 1e-6);
        assertEquals("Wrong roll value", roll, posData.getDoubleValue(2), 1e-6);
    }
    
    
    @Test
    public void testStaticLocation() throws Exception
    {
        FakeSensorWithPos sensor = new FakeSensorWithPos();
        sensor.setParentHub(hub);
        SensorConfigWithPos config = new SensorConfigWithPos();
        config.id = "TEST_SENSOR";
        config.name = "Temp Sensor";
        config.setLocation(lat, lon, alt);
        sensor.init(config);
        
        // check feature of interest
        checkFoiLocation(sensor);
        
        // check SensorML position
        AbstractPhysicalProcess sensorDesc = (AbstractPhysicalProcess)sensor.getCurrentDescription();
        new SMLUtils(SMLUtils.V2_0).writeProcess(System.out, sensorDesc, true);
        System.out.println('\n');
        
        assertTrue("Location must be a SWE Vector", sensorDesc.getPositionList().get(0) instanceof Vector);
        checkSmlLocationVector((Vector)sensorDesc.getPositionList().get(0));
    }
    
    
    @Test
    public void testStaticOrientation() throws Exception
    {
        FakeSensorWithPos sensor = new FakeSensorWithPos();
        sensor.setParentHub(hub);
        SensorConfigWithPos config = new SensorConfigWithPos();
        config.id = "TEST_SENSOR";
        config.name = "Temp Sensor";
        config.setOrientation(heading, pitch, roll);
        sensor.init(config);
        
        // check there is no feature of interest
        assertTrue("FoI must be null", sensor.getCurrentFeaturesOfInterest().isEmpty());
        
        // check SensorML position
        AbstractPhysicalProcess sensorDesc = (AbstractPhysicalProcess)sensor.getCurrentDescription();
        new SMLUtils(SMLUtils.V2_0).writeProcess(System.out, sensorDesc, true);
        System.out.println('\n');
        
        assertTrue("Location must be a SWE Vector", sensorDesc.getPositionList().get(0) instanceof Vector);
        checkSmlOrientationVector((Vector)sensorDesc.getPositionList().get(0));
    }
    
    
    @Test
    public void testStaticLocationAndOrientation() throws Exception
    {
        FakeSensorWithPos sensor = new FakeSensorWithPos();
        sensor.setParentHub(hub);
        SensorConfigWithPos config = new SensorConfigWithPos();
        config.id = "TEST_SENSOR";
        config.name = "Video Camera";        
        config.setLocation(lat, lon, alt);
        config.setOrientation(heading, pitch, roll);
        sensor.init(config);
        
        // check feature of interest
        checkFoiLocation(sensor);
        
        // check SensorML position
        AbstractPhysicalProcess sensorDesc = (AbstractPhysicalProcess)sensor.getCurrentDescription();
        new SMLUtils(SMLUtils.V2_0).writeProcess(System.out, sensorDesc, true);
        System.out.println('\n');
        
        assertTrue("Location must be a SWE DataRecord", sensorDesc.getPositionList().get(0) instanceof DataRecord);
        DataRecord rec = (DataRecord)sensorDesc.getPositionList().get(0);
        checkSmlLocationVector((Vector)rec.getComponent(0));
        checkSmlOrientationVector((Vector)rec.getComponent(1));
    }
}
