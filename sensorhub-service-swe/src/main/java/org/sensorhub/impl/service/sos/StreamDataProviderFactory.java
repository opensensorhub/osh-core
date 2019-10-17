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

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map.Entry;
import java.util.Set;
import net.opengis.gml.v32.AbstractFeature;
import net.opengis.sensorml.v20.AbstractProcess;
import net.opengis.swe.v20.DataArray;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataRecord;
import org.sensorhub.api.common.Event;
import org.sensorhub.api.common.IEventListener;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.data.IDataProducerModule;
import org.sensorhub.api.data.IStreamingDataInterface;
import org.sensorhub.api.module.ModuleEvent;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.sensorhub.api.persistence.IFoiFilter;
import org.sensorhub.api.service.ServiceException;
import org.sensorhub.impl.SensorHub;
import org.sensorhub.utils.MsgUtils;
import org.vast.data.DataIterator;
import org.vast.ogc.om.IObservation;
import org.vast.ows.OWSException;
import org.vast.ows.sos.SOSOfferingCapabilities;
import org.vast.swe.SWEConstants;
import org.vast.util.TimeExtent;


/**
 * <p>
 * Base factory for streaming data providers.
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @param <ProducerType> Type of producer handled by this provider
 * @since Feb 28, 2015
 */
public class StreamDataProviderFactory implements ISOSDataProviderFactory, IEventListener
{
    final SOSServlet service;
    final StreamDataProviderConfig config;
    final IDataProducerModule<?> producer;
    long liveDataTimeOut;
    long refTimeOut;
    SOSOfferingCapabilities caps;
    boolean disableEvents;
    
    
    public StreamDataProviderFactory(SOSServlet service, StreamDataProviderConfig config) throws SensorHubException
    {
        this.service = service;
        this.config = config;
        this.liveDataTimeOut = (long)(config.liveDataTimeout * 1000);
        this.refTimeOut = System.currentTimeMillis(); // initial ref for timeout is SOS startup time
        
        // get handle to producer object
        try
        {
            this.producer = (IDataProducerModule<?>)SensorHub.getInstance().getModuleRegistry().getModuleById(config.getProducerID());
        }
        catch (Exception e)
        {
            throw new ServiceException("Producer " + config.getProducerID() + " is not available", e);
        }
        
        // listen to producer lifecycle events
        disableEvents = true; // disable events on startup
        producer.registerListener(this);
        disableEvents = false;
    }
    
    
    /*
     * Constructor for use as alt provider
     * In this mode, we purposely don't handle events
     */
    protected StreamDataProviderFactory(StreamDataProviderConfig config, IDataProducerModule<?> producer)
    {
        this.service = null;
        this.config = config;
        this.producer = producer;
    }
    
    
    @Override
    public SOSOfferingCapabilities generateCapabilities() throws SensorHubException
    {
        checkEnabled();
        
        try
        {
            caps = new SOSOfferingCapabilities();
            
            // identifier
            if (config.offeringID != null)
                caps.setIdentifier(config.offeringID);
            else
                caps.setIdentifier("urn:offering:" + producer.getLocalID());
            
            // name + description
            updateNameAndDescription();
            
            // observable properties
            Set<String> sensorOutputDefs = getObservablePropertiesFromProducer();
            caps.getObservableProperties().addAll(sensorOutputDefs);
            
            // phenomenon time
            // enable real-time requests only if streaming data source is enabled
            TimeExtent timeExtent = new TimeExtent();
            if (producer.isStarted())
            {
                timeExtent.setBeginNow(true);
                timeExtent.setEndNow(true);
                //timeExtent.setTimeStep(getLowestSamplingPeriodFromProducer());
            }
            caps.setPhenomenonTime(timeExtent);
        
            // use producer uniqueID as procedure ID
            caps.getProcedures().add(producer.getCurrentDescription().getUniqueIdentifier());
            
            // FOI IDs and BBOX
            SOSProviderUtils.updateFois(caps, producer, config.maxFois);
            
            // obs types
            Set<String> obsTypes = getObservationTypesFromProducer();
            caps.getObservationTypes().addAll(obsTypes);
            
            return caps;
        }
        catch (SensorHubException e)
        {
            throw new ServiceException("Cannot generate capabilities for stream provider " + MsgUtils.moduleString(producer), e);
        }
    }
    
    
    protected void updateNameAndDescription()
    {
        // name
        if (config.name != null)
            caps.setTitle(config.name);
        else
            caps.setTitle(producer.getName());
        
        // description
        if (config.description != null)
            caps.setDescription(config.description);
        else
            caps.setDescription("Live data from " + producer.getName());
    }
    
    
    @Override
    public void updateCapabilities() throws SensorHubException
    {
        checkEnabled();
        if (caps == null)
            return;
        
        try
        {
            service.capabilitiesLock.writeLock().lock();
            
            updateNameAndDescription();
            SOSProviderUtils.updateFois(caps, producer, config.maxFois);
            
            // enable real-time requests if streaming data source is enabled
            if (producer.isStarted())
            {
                // if latest record is not too old, enable real-time
                if (getTimeSinceLastRecord() < liveDataTimeOut)
                {
                    caps.getPhenomenonTime().setBeginNow(true);
                    caps.getPhenomenonTime().setEndNow(true);
                }
                else
                    caps.getPhenomenonTime().nullify();
            }
        }
        finally
        {
            service.capabilitiesLock.writeLock().unlock();
        }
    }
    
    
    @Override
    public boolean checkQueryTime(TimeExtent requestTime)
    {
        return (requestTime.isBaseAtNow() || requestTime.isBeginNow()) &&
                getTimeSinceLastRecord() < liveDataTimeOut;
    }
    
    
    protected long getTimeSinceLastRecord()
    {
        long now =  System.currentTimeMillis();
        
        // check latest record time
        long lastRecordTime = refTimeOut;
        for (IStreamingDataInterface output: producer.getAllOutputs().values())
        {
            // skip excluded outputs
            if (config.excludedOutputs != null && config.excludedOutputs.contains(output.getName()))
                continue;
            
            long recTime = output.getLatestRecordTime();
            if (recTime > lastRecordTime)
                lastRecordTime = recTime;
        }
        
        return now - lastRecordTime;
    }


    protected Set<String> getObservablePropertiesFromProducer() throws SensorHubException
    {
        HashSet<String> observableUris = new LinkedHashSet<>();
        
        // scan outputs descriptions
        for (Entry<String, ? extends IStreamingDataInterface> entry: producer.getAllOutputs().entrySet())
        {
            // skip excluded outputs
            if (config.excludedOutputs != null && config.excludedOutputs.contains(entry.getKey()))
                continue;
            
            // iterate through all SWE components and add all definition URIs as observables
            // this way only composites with URI will get added
            IStreamingDataInterface output = entry.getValue();
            DataIterator it = new DataIterator(output.getRecordDescription());
            while (it.hasNext())
            {
                String defUri = it.next().getDefinition();
                if (defUri != null && !defUri.equals(SWEConstants.DEF_SAMPLING_TIME))
                    observableUris.add(defUri);
            }
        }
        
        return observableUris;
    }
    
    
    protected Set<String> getObservationTypesFromProducer() throws SensorHubException
    {
        HashSet<String> obsTypes = new HashSet<>();
        obsTypes.add(IObservation.OBS_TYPE_GENERIC);
        obsTypes.add(IObservation.OBS_TYPE_SCALAR);
        
        // process outputs descriptions
        for (Entry<String, ? extends IStreamingDataInterface> entry: producer.getAllOutputs().entrySet())
        {
            // skip excluded outputs
            if (config.excludedOutputs != null && config.excludedOutputs.contains(entry.getKey()))
                continue;
            
            // obs type depends on top-level component
            IStreamingDataInterface output = entry.getValue();
            DataComponent dataStruct = output.getRecordDescription();
            if (dataStruct instanceof DataRecord)
                obsTypes.add(IObservation.OBS_TYPE_RECORD);
            else if (dataStruct instanceof DataArray)
                obsTypes.add(IObservation.OBS_TYPE_ARRAY);
        }
        
        return obsTypes;
    }
    
    
    protected double getLowestSamplingPeriodFromProducer() throws SensorHubException
    {
        double lowestSamplingPeriod = Double.POSITIVE_INFINITY;
        
        // process outputs descriptions
        for (Entry<String, ? extends IStreamingDataInterface> entry: producer.getAllOutputs().entrySet())
        {
            // skip excluded outputs
            if (config.excludedOutputs != null && config.excludedOutputs.contains(entry.getKey()))
                continue;
            
            double samplingPeriod = entry.getValue().getAverageSamplingPeriod();
            if (samplingPeriod < lowestSamplingPeriod)
                lowestSamplingPeriod = samplingPeriod;
        }
        
        return lowestSamplingPeriod;
    }
    
    
    @Override
    public AbstractProcess generateSensorMLDescription(double time) throws SensorHubException
    {
        checkEnabled();
        return producer.getCurrentDescription();
    }
    
    
    @Override
    public Iterator<AbstractFeature> getFoiIterator(final IFoiFilter filter) throws SensorHubException
    {
        checkEnabled();
        return SOSProviderUtils.getFilteredFoiIterator(producer, filter);
    }
    
    
    /*
     * Checks if provider and underlying sensor are enabled
     */
    protected void checkEnabled() throws SensorHubException
    {
        if (!config.enabled)
            throw new ServiceException("Offering " + config.offeringID + " is disabled");
                
        if (!producer.isStarted())
            throw new ServiceException("Data source " + MsgUtils.moduleString(producer) + " is disabled");
    }


    @Override
    public void handleEvent(Event<?> e)
    {
        if (disableEvents)
            return;
        
        // producer events
        if (e instanceof ModuleEvent && e.getSource() == producer)
        {
            switch (((ModuleEvent)e).getType())
            {
                // show/hide offering when enabled/disabled
                case STATE_CHANGED:
                    ModuleState state = ((ModuleEvent)e).getNewState();
                    if (state == ModuleState.STARTED || state == ModuleState.STOPPING)
                    {
                        if (isEnabled())
                            service.showProviderCaps(this);
                        else
                            service.hideProviderCaps(this);
                    }
                    break;
                
                // cleanly remove provider when producer is deleted
                case DELETED:
                    service.removeProvider(config.offeringID);
                    break;
                    
                default:
                    return;
            }
        }      
    }


    @Override
    public ISOSDataProvider getNewDataProvider(SOSDataFilter filter) throws OWSException, SensorHubException
    {
        checkEnabled();
        return new StreamDataProvider(producer, config, filter);
    }


    @Override
    public void cleanup()
    {
        producer.unregisterListener(this);
    }


    @Override
    public boolean isEnabled()
    {
        return (config.enabled && producer.isStarted());
    }
    
    
    @Override
    public StreamDataProviderConfig getConfig()
    {
        return this.config;
    }
}
