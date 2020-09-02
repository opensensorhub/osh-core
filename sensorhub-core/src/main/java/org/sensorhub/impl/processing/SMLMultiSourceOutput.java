/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.processing;

import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import org.sensorhub.api.common.Event;
import org.sensorhub.api.common.IEventHandler;
import org.sensorhub.api.common.IEventListener;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.api.data.IDataProducerModule;
import org.sensorhub.api.data.IMultiSourceDataInterface;
import org.sensorhub.api.processing.ProcessException;
import org.sensorhub.impl.SensorHub;
import org.sensorhub.impl.processing.SMLMultiStreamProcess.SMLStreamProcessExt;
import org.vast.swe.SWEHelper;


/*
 * Implementation of streaming data interface that forwards data obtained from
 * SensorML process output data queues as SensorHub DataEvents
 */
class SMLMultiSourceOutput implements IMultiSourceDataInterface, IEventListener
{
    SMLMultiStreamProcess parentProcess;
    IEventHandler eventHandler;
    DataComponent outputDef;
    DataEncoding outputEncoding;
    DataBlock latestRecord;
    Map<String, DataBlock> latestRecords = new ConcurrentSkipListMap<>();
    long lastRecordTime = Long.MIN_VALUE;
    double avgSamplingPeriod = 1.0;
    int avgSampleCount = 0;


    protected SMLMultiSourceOutput(SMLMultiStreamProcess parentProcess, DataComponent outputDef) throws ProcessException
    {
        this.parentProcess = parentProcess;
        this.outputDef = outputDef;
        this.outputEncoding = SWEHelper.getDefaultEncoding(outputDef);

        // obtain an event handler for this output
        String moduleID = parentProcess.getLocalID();
        String topic = getName();
        this.eventHandler = SensorHub.getInstance().getEventBus().registerProducer(moduleID, topic);
    }


    @Override
    public IDataProducerModule<?> getParentModule()
    {
        return parentProcess;
    }


    @Override
    public String getName()
    {
        return outputDef.getName();
    }


    @Override
    public boolean isEnabled()
    {
        return true;
    }


    @Override
    public DataComponent getRecordDescription()
    {
        return outputDef;
    }


    @Override
    public DataEncoding getRecommendedEncoding()
    {
        return outputEncoding;
    }


    @Override
    public DataBlock getLatestRecord()
    {
        return latestRecord;
    }


    @Override
    public long getLatestRecordTime()
    {
        return lastRecordTime;
    }


    @Override
    public double getAverageSamplingPeriod()
    {
        return avgSamplingPeriod;
    }


    @Override
    public void registerListener(IEventListener listener)
    {
        eventHandler.registerListener(listener);
    }


    @Override
    public void unregisterListener(IEventListener listener)
    {
        eventHandler.unregisterListener(listener);
    }


    @Override
    public Collection<String> getEntityIDs()
    {
        return Collections.unmodifiableCollection(latestRecords.keySet());
    }


    @Override
    public Map<String, DataBlock> getLatestRecords()
    {
        return Collections.unmodifiableMap(latestRecords);
    }


    @Override
    public DataBlock getLatestRecord(String entityID)
    {
        return latestRecords.get(entityID);
    }


    @Override
    public void handleEvent(Event<?> e)
    {
        if (e instanceof DataEvent)
        {
            SMLStreamProcessExt subProcess = (SMLStreamProcessExt) ((DataEvent) e).getSource().getParentModule();
            String entityID = subProcess.getEntityID();

            // save last record and forward event
            latestRecord = ((DataEvent) e).getRecords()[0];
            latestRecords.put(entityID, latestRecord);
            lastRecordTime = e.getTimeStamp();
            eventHandler.publishEvent(new DataEvent(e.getTimeStamp(), entityID, SMLMultiSourceOutput.this, latestRecord));
        }
    }

}
