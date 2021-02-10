/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.sos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.opengis.gml.v32.AbstractFeature;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import org.sensorhub.api.common.Event;
import org.sensorhub.api.common.IEventListener;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.api.data.FoiEvent;
import org.sensorhub.api.data.IDataProducerModule;
import org.sensorhub.api.data.IMultiSourceDataInterface;
import org.sensorhub.api.data.IMultiSourceDataProducer;
import org.sensorhub.api.data.IStreamingDataInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.data.DataIterator;
import org.vast.ogc.om.IObservation;
import org.vast.ows.OWSException;
import org.vast.ows.sos.SOSException;
import org.vast.swe.SWEConstants;
import org.vast.util.Asserts;
import org.vast.util.TimeExtent;


/**
 * <p>
 * Implementation of SOS data provider connecting to a streaming data source
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since Sep 7, 2013
 */
public class StreamDataProvider implements ISOSDataProvider, IEventListener
{
    private static final Logger log = LoggerFactory.getLogger(StreamDataProvider.class);
    private static final int DEFAULT_QUEUE_SIZE = 200;

    IDataProducerModule<?> dataSource;
    List<IStreamingDataInterface> sourceOutputs;
    BlockingQueue<DataEvent> eventQueue;
    long timeOut;
    long stopTime;
    long lastQueueErrorTime = Long.MIN_VALUE;
    boolean latestRecordOnly;

    DataComponent resultStruct;
    DataEvent lastDataEvent;
    int nextEventRecordIndex = 0;
    Set<String> requestedFois;
    Map<String, String> currentFoiMap = new LinkedHashMap<>(); // entity ID -> current FOI ID
    

    public StreamDataProvider(IDataProducerModule<?> dataSource, StreamDataProviderConfig config, SOSDataFilter filter) throws OWSException
    {
        this.dataSource = dataSource;
        this.sourceOutputs = new ArrayList<>();
        
        // figure out stop time (if any)
        stopTime = ((long) filter.getTimeRange().getStopTime()) * 1000L;

        // get list of desired stream outputs
        dataSource.getConfiguration(); // why do we call this?

        // loop through all outputs and connect to the ones containing observables we need
        for (IStreamingDataInterface outputInterface : dataSource.getAllOutputs().values())
        {
            // skip excluded outputs
            if (config.excludedOutputs != null && config.excludedOutputs.contains(outputInterface.getName()))
                continue;

            // keep it if we can find one of the observables
            DataIterator it = new DataIterator(outputInterface.getRecordDescription());
            while (it.hasNext())
            {
                String defUri = it.next().getDefinition();
                if (filter.getObservables().contains(defUri))
                {
                    // time out after a certain period if no sensor data is produced
                    timeOut = (long) (config.liveDataTimeout * 1000);
                    sourceOutputs.add(outputInterface);
                    resultStruct = outputInterface.getRecordDescription().copy();

                    // break for now since we support only requesting data from one output at a time
                    // TODO support case of multiple outputs since it is technically possible with GetObservation
                    break;
                }
            }
        }

        // error if no output was selected
        Asserts.checkArgument(!sourceOutputs.isEmpty(), "No output selected");

        // scan FOIs
        if (!filter.getFoiIds().isEmpty())
        {
            requestedFois = filter.getFoiIds();
            String badFoi = null;
            
            // fill up initial FOI map
            if (dataSource instanceof IMultiSourceDataProducer)
            {
                for (String foiID : filter.getFoiIds())
                {
                    Collection<String> entityIDs = ((IMultiSourceDataProducer) dataSource).getEntitiesWithFoi(foiID);
                    if (entityIDs.isEmpty())
                        badFoi = foiID;

                    for (String entityID : entityIDs)
                        currentFoiMap.put(entityID, foiID);
                }
            }
            else
            {
                // error if no FOI is currently being observed
                AbstractFeature foi = dataSource.getCurrentFeatureOfInterest();
                if (foi == null || !requestedFois.contains(foi.getUniqueIdentifier()))
                    badFoi = requestedFois.iterator().next();
                
                currentFoiMap.put(null, dataSource.getCurrentFeatureOfInterest().getUniqueIdentifier());
            }
            
            // send error if foi is not currently observed
            if (badFoi != null)
                throw new SOSException(SOSException.invalid_param_code, "featureOfInterest", badFoi, "No real-time data available for FOI " + badFoi);
        }

        // listen for events on the selected outputs
        for (final IStreamingDataInterface outputInterface : sourceOutputs)
        {
            // always send latest record(s) if available
            // if multi-source send latest record of all selected FOIs
            if (outputInterface instanceof IMultiSourceDataInterface)
            {
                if (!currentFoiMap.isEmpty())
                {
                    int queueSize = DEFAULT_QUEUE_SIZE * currentFoiMap.size();
                    eventQueue = new LinkedBlockingQueue<>(queueSize);

                    for (String entityID : currentFoiMap.keySet())
                    {
                        DataBlock data = ((IMultiSourceDataInterface) outputInterface).getLatestRecord(entityID);
                        eventQueue.offer(new DataEvent(System.currentTimeMillis(), entityID, outputInterface, data));
                    }
                }
                
                else // if no FOIs were specified, send data for all
                {
                    int queueSize = DEFAULT_QUEUE_SIZE * ((IMultiSourceDataInterface) outputInterface).getEntityIDs().size();
                    eventQueue = new LinkedBlockingQueue<>(Math.max(queueSize, 1));

                    Map<String, DataBlock> data = ((IMultiSourceDataInterface) outputInterface).getLatestRecords();
                    for (Entry<String, DataBlock> rec : data.entrySet())
                        eventQueue.offer(new DataEvent(System.currentTimeMillis(), rec.getKey(), outputInterface, rec.getValue()));
                }
            }
            
            // otherwise send latest record of single source 
            else
            {
                eventQueue = new LinkedBlockingQueue<>(DEFAULT_QUEUE_SIZE);
                DataBlock data = outputInterface.getLatestRecord();
                if (data != null)
                    eventQueue.offer(new DataEvent(System.currentTimeMillis(), outputInterface, data));
            }

            // don't register and use timeout in case of time instant = now
            if (isNowTimeInstant(filter.getTimeRange()))
            {
                stopTime = Long.MAX_VALUE; // make sure stoptime does not cause us to return null
                timeOut = 0L;
                latestRecordOnly = true;
            }

            // otherwise register listener to stream next records
            else
                outputInterface.registerListener(this);
        }
    }


    protected boolean isNowTimeInstant(TimeExtent timeFilter)
    {
        if (timeFilter.isTimeInstant() && timeFilter.isBaseAtNow())
            return true;

        return false;
    }


    @Override
    public IObservation getNextObservation()
    {
        DataBlock rec = getNextResultRecord();
        if (rec == null)
            return null;
        
        return buildObservation(rec);
    }
    
    
    protected IObservation buildObservation(DataBlock rec)
    {
        resultStruct.setData(rec);
        
        // FOI
        AbstractFeature foi = dataSource.getCurrentFeatureOfInterest();
        if (dataSource instanceof IMultiSourceDataProducer)
        {
            String entityID = lastDataEvent.getRelatedEntityID();
            foi = ((IMultiSourceDataProducer) dataSource).getCurrentFeatureOfInterest(entityID);
        }

        String foiID;
        if (foi != null)
            foiID = foi.getUniqueIdentifier();
        else
            foiID = SWEConstants.NIL_UNKNOWN;

        return SOSProviderUtils.buildObservation(resultStruct, foiID, dataSource.getCurrentDescription().getUniqueIdentifier());
    }


    @Override
    public DataBlock getNextResultRecord()
    {
        if (!hasMoreData())
            return null;

        try
        {
            // only poll next event from queue once we have returned all records associated to last event
            if (lastDataEvent == null || nextEventRecordIndex >= lastDataEvent.getRecords().length)
            {
                lastDataEvent = eventQueue.poll(timeOut, TimeUnit.MILLISECONDS);
                if (lastDataEvent == null)
                    return null;

                // we stop if record is passed the given stop date
                if (lastDataEvent.getTimeStamp() > stopTime)
                    return null;

                nextEventRecordIndex = 0;
            }

            //System.out.println("->" + new DateTimeFormat().formatIso(lastDataEvent.getTimeStamp()/1000., 0));
            return lastDataEvent.getRecords()[nextEventRecordIndex++];

            // TODO add choice token value if request includes several outputs
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return null;
        }
    }


    /*
     * For real-time streams, more data is always available unless
     * sensor is disabled or all sensor outputs are disabled
     */
    private boolean hasMoreData()
    {
        if (!dataSource.isStarted())
            return false;

        boolean interfaceActive = false;
        for (IStreamingDataInterface source : sourceOutputs)
        {
            if (source.isEnabled())
            {
                interfaceActive = true;
                break;
            }
        }

        return interfaceActive;
    }


    @Override
    public DataComponent getResultStructure()
    {
        // TODO generate choice if request includes several outputs

        return resultStruct;
    }


    @Override
    public DataEncoding getDefaultResultEncoding()
    {
        return sourceOutputs.get(0).getRecommendedEncoding();
    }


    @Override
    public void handleEvent(Event<?> e)
    {
        if (e instanceof DataEvent)
        {
            if (((DataEvent) e).getType() == DataEvent.Type.NEW_DATA_AVAILABLE)
            {
                // check foi if filtering on it
                if (requestedFois != null)
                {
                    // skip if entity/foi was not selected
                    String entityID = ((DataEvent) e).getRelatedEntityID();
                    String foiID = currentFoiMap.get(entityID);
                    if (!requestedFois.contains(foiID))
                        return;
                }

                // try to add to queue
                if (!eventQueue.offer((DataEvent) e))
                {
                    long now = System.currentTimeMillis();
                    if (now - lastQueueErrorTime > 10000)
                    {
                        log.warn("Maximum queue size reached while streaming data from {}. "
                               + "Some records will be discarded. This is often due to insufficient bandwidth", dataSource);
                        lastQueueErrorTime = now;
                    }
                }
            }
        }

        else if (e instanceof FoiEvent && requestedFois != null)
        {
            // remember current FOI of each entity
            FoiEvent foiEvent = (FoiEvent) e;
            String producerID = ((FoiEvent) e).getRelatedEntityID();
            currentFoiMap.put(producerID, foiEvent.getFoiID());
        }
    }


    @Override
    public void close()
    {
        if (!latestRecordOnly)
        {
            for (IStreamingDataInterface outputInterface : sourceOutputs)
                outputInterface.unregisterListener(this);
        }

        eventQueue.clear();
    }
}
