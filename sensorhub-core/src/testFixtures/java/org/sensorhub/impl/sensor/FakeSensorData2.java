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

import net.opengis.swe.v20.*;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.api.data.IDataProducer;
import org.vast.data.BinaryComponentImpl;
import org.vast.data.DataBlockByte;
import org.vast.ogc.gml.IFeature;
import org.vast.swe.SWEHelper;

import java.time.Instant;
import java.util.NavigableMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;


/**
 * <p>
 * Fake array sensor implementation for testing SOS service
 * </p>
 *
 * @author Alex Robin
 * @since Sep 20, 2013
 */
public class FakeSensorData2 extends AbstractSensorOutput<IDataProducer> implements IFakeSensorOutput
{
    public static final String URI_OUTPUT1 = "urn:blabla:image";
    public static int ARRAY_SIZE = 1024;
    
    DataComponent outputStruct;
    DataEncoding outputEncoding;
    int maxSampleCount;
    int sampleCount;
    double samplingPeriod; // seconds
    NavigableMap<Integer, Integer> obsFoiMap;
    Timer timer;
    TimerTask sendTask;
    boolean started;
    
    
    public FakeSensorData2(IDataProducer sensor, String name, double samplingPeriod, int maxSampleCount)
    {
        this(sensor, name, samplingPeriod, maxSampleCount, null);
    }
    
    
    public FakeSensorData2(IDataProducer sensor, String name, double samplingPeriod, int maxSampleCount, NavigableMap<Integer, Integer> obsFoiMap)
    {
        super(name, sensor);
        this.samplingPeriod = samplingPeriod;
        this.maxSampleCount = maxSampleCount;
        this.obsFoiMap = obsFoiMap;
        init();
    }
    
    
    public void init()
    {        
        // generate output structure and encoding
        SWEHelper fac = new SWEHelper();
        
        DataArray img = fac.createArray()
            .name(this.name)
            .label("Image Data")
            .definition(URI_OUTPUT1)
            .withFixedSize(ARRAY_SIZE)
            .withElement("pixel", fac.createRecord()
                .addField("red", fac.createCount()
                    .definition("urn:blabla:RedChannel")
                    .label("Red Channel")
                    .dataType(DataType.BYTE))
                .addField("green", fac.createCount()
                    .definition("urn:blabla:GreenChannel")
                    .label("Green Channel")
                    .dataType(DataType.BYTE))
                .addField("blue", fac.createCount()
                    .definition("urn:blabla:BlueChannel")
                    .label("Blue Channel")
                    .dataType(DataType.BYTE))
            )
            .build();   
        this.outputStruct = img; 
        
        BinaryEncoding dataEnc = fac.newBinaryEncoding(ByteOrder.BIG_ENDIAN, ByteEncoding.RAW);
        dataEnc.addMemberAsComponent(new BinaryComponentImpl("pixel/red", DataType.BYTE));
        dataEnc.addMemberAsComponent(new BinaryComponentImpl("pixel/green", DataType.BYTE));
        dataEnc.addMemberAsComponent(new BinaryComponentImpl("pixel/blue", DataType.BYTE));
        this.outputEncoding = dataEnc;
    }
    
    
    public int getMaxSampleCount()
    {
        return maxSampleCount;
    }


    @Override
    public double getAverageSamplingPeriod()
    {
        return 0.01;
    }


    @Override
    public DataComponent getRecordDescription()
    {
        return outputStruct;
    }
    
    
    @Override
    public DataEncoding getRecommendedEncoding()
    {
        return outputEncoding;
    }


    @Override
    public CompletableFuture<Integer> start(long delay)
    {   
        var future = new CompletableFuture<Integer>();
        
        sendTask = new TimerTask()
        {
            @Override
            public void run()
            {
                // safety to make sure we don't output more samples than requested
                // cancel does not seem to be taken into account early enough with high rates
                if (sampleCount >= maxSampleCount)
                    return;
                
                DataBlock data = new DataBlockByte(3*ARRAY_SIZE);
                for (int i=0; i<ARRAY_SIZE; i++)
                    data.setByteValue(i, (byte)(i%255));
                           
                sampleCount++;
                latestRecordTime = System.currentTimeMillis();
                latestRecord = data;
                
                // if testing multisource producer
                String foiUID = null;
                IDataProducer producer = FakeSensorData2.this.getParentProducer();
                if (obsFoiMap != null)
                {
                    var foiEntry = obsFoiMap.floorEntry(sampleCount);
                    if (foiEntry != null)
                    {
                        Integer foiNum = foiEntry.getValue();
                        foiUID = ((FakeSensor)producer).getFoiUID(foiNum);
                        IFeature foi = producer.getCurrentFeaturesOfInterest().get(foiUID);
                        
                        // create feature dynamically if it doesn't exist
                        if (foi == null)
                        {
                            var parentProducer = (FakeSensorNetOnlyFois)producer;
                            foi = parentProducer.addFoi(foiNum);
                            try {
                                System.out.println("Registering new FOI #" + foiNum);
                                parentProducer.getParentHub().getSystemDriverRegistry().register(parentProducer, foi).get();
                            }catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }
                        else
                            
                        
                        if (foi != null)
                            System.out.println("Observing FOI #" + foiNum + " (" + foiUID + ")");
                    }
                }
                
                System.out.println("Image record #" + sampleCount + " generated @ " +
                    Instant.ofEpochMilli(latestRecordTime));
                
                if (foiUID == null)
                    eventHandler.publish(new DataEvent(latestRecordTime, FakeSensorData2.this, latestRecord));
                else
                    eventHandler.publish(new DataEvent(latestRecordTime, producer.getUniqueIdentifier(), getName(), foiUID, latestRecord));
                
                if (sampleCount >= maxSampleCount)
                {
                    cancel();
                    future.complete(sampleCount);
                }
            }                
        };
        
        timer = new Timer(name, true);
        timer.scheduleAtFixedRate(sendTask, delay, (long)(samplingPeriod * 1000));
        started = true;
        
        return future;
    }
    
    
    protected void startSending()
    {
        if (timer == null)
        {
            
        }
    }
    
    
    @Override
    public DataBlock getLatestRecord()
    {
        // make sure the first record is produced
        if (sendTask != null)
            sendTask.run();
        
        return latestRecord;
    }


    @Override
    public void stop()
    {
        if (timer != null)
        {
            timer.cancel();
            timer.purge();
            timer = null;
        }
    }


    @Override
    public boolean isEnabled()
    {
        if (sampleCount >= maxSampleCount)
            return false;
        else
            return true;
    }
}
