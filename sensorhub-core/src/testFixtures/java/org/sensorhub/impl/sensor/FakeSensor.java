/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.sensor;

import net.opengis.gml.v32.Point;
import net.opengis.gml.v32.impl.GMLFactory;
import net.opengis.sensorml.v20.PhysicalSystem;
import org.sensorhub.api.command.IStreamingControlInterface;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.data.IStreamingDataInterface;
import org.sensorhub.api.sensor.SensorConfig;
import org.sensorhub.api.sensor.SensorException;
import org.sensorhub.impl.sensor.AbstractSensorModule;
import org.sensorhub.impl.sensor.IFakeSensorOutput;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;


public class FakeSensor extends AbstractSensorModule<SensorConfig>
{
    public static final String SENSOR_UID = "urn:sensors:mysensor:001";
    
    
    public void setSensorUID(String sensorUID)
    {
        this.uniqueID = sensorUID;
    }
    
    
    public void setDataInterfaces(IStreamingDataInterface... outputs) throws SensorException
    {
        for (IStreamingDataInterface o: outputs)
            addOutput(o, false);
    }
    
    
    public void setControlInterfaces(IStreamingControlInterface... inputs) throws SensorException
    {
        for (IStreamingControlInterface i: inputs)
            addControlInput(i);
    }
    

    @Override
    public void updateConfig(SensorConfig config) throws SensorHubException
    {
    }


    @Override
    protected void doInit() throws SensorHubException
    {
        super.doInit();
        this.uniqueID = SENSOR_UID;
        this.xmlID = "SENSOR1";
    }


    @Override
    protected void doStart() throws SensorHubException
    {        
    }
    
    
    @Override
    protected void doStop() throws SensorHubException
    {
        for (IStreamingDataInterface o: getObservationOutputs().values())
            ((IFakeSensorOutput)o).stop();
    }


    @Override
    protected void updateSensorDescription()
    {
        synchronized (sensorDescLock)
        {
            super.updateSensorDescription();
            Point pos = new GMLFactory(true).newPoint();
            pos.setId("P01");
            pos.setSrsName("http://www.opengis.net/def/crs/EPSG/0/4979");
            pos.setPos(new double[] {45.6, 2.3, 193.2});
            ((PhysicalSystem)sensorDescription).addPositionAsPoint(pos);
        }
    }


    @Override
    public boolean isConnected()
    {
        return true;
    }
    
    
    @Override
    public void cleanup() throws SensorHubException
    {
    }


    public CompletableFuture<Void> startSendingData()
    {
        return startSendingData(0L);
    }
    
    
    public CompletableFuture<Void> startSendingData(long delay)
    {
        var outputFutures = new ArrayList<CompletableFuture<?>>();
            
        for (IStreamingDataInterface o: getObservationOutputs().values())
        {
            o.getLatestRecord();
            var f = ((IFakeSensorOutput)o).start(delay);
            outputFutures.add(f);
        }
        
        return CompletableFuture.allOf(outputFutures.toArray(new CompletableFuture[0]));
    }
    
    
    public boolean hasMoreData()
    {
        for (IStreamingDataInterface o: getOutputs().values())
            if (o.isEnabled())
                return true;            
        
        return false;
    }
    
    
    public String getFoiUID(int foiNum)
    {
        return uniqueID + ":foi";
    }
    
    
    @Override
    public String getName()
    {
        return getClass().getSimpleName();
    }
}
