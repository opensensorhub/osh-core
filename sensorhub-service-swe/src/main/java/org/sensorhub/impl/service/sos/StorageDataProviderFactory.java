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

import java.util.Collections;
import java.util.Iterator;
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
import org.sensorhub.api.module.ModuleEvent;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.sensorhub.api.persistence.FoiFilter;
import org.sensorhub.api.persistence.IFoiFilter;
import org.sensorhub.api.persistence.IObsStorage;
import org.sensorhub.api.persistence.IRecordStorageModule;
import org.sensorhub.api.persistence.IRecordStoreInfo;
import org.sensorhub.api.persistence.IStorageModule;
import org.sensorhub.api.sensor.SensorException;
import org.sensorhub.api.service.ServiceException;
import org.sensorhub.impl.SensorHub;
import org.sensorhub.utils.MsgUtils;
import org.vast.data.DataIterator;
import org.vast.ogc.om.IObservation;
import org.vast.ows.OWSException;
import org.vast.ows.sos.SOSOfferingCapabilities;
import org.vast.swe.SWEConstants;
import org.vast.swe.SWEHelper;
import org.vast.util.Bbox;
import org.vast.util.TimeExtent;


/**
 * <p>
 * Factory for storage data providers.
 * </p>
 * <p>
 * This factory is associated to an SOS offering and is persistent
 * throughout the lifetime of the service, so it must be threadsafe.
 * </p>
 * <p>
 * However, the server will obtain a new data provider instance from this
 * factory for each incoming request so the providers themselves don't need
 * to be threadsafe. 
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since Sep 15, 2013
 */
public class StorageDataProviderFactory implements ISOSDataProviderFactory, IEventListener
{
    static final String FOI_TIME_PERIODS = SWEHelper.getPropertyUri("PhenomenonTimeRange");
    
    final SOSServlet service;
    final StorageDataProviderConfig config;
    final IRecordStorageModule<?> storage;
    SOSOfferingCapabilities caps;
    boolean disableEvents;
    
    
    protected StorageDataProviderFactory(SOSServlet service, StorageDataProviderConfig config) throws SensorHubException
    {
        this.service = service;
        this.config = config;
        IStorageModule<?> storageModule = null;
        
        try
        {
            // get handle to data storage instance
            storageModule = SensorHub.getInstance().getPersistenceManager().getModuleById(config.storageID);
        }
        catch (Exception e)
        {
            throw new ServiceException("Storage " + config.storageID + " is not available", e);
        }
        
        try
        {
            this.storage = (IRecordStorageModule<?>)storageModule;
            
            // listen to storage lifecycle events
            disableEvents = true; // disable events on startup
            storage.registerListener(this);
            disableEvents = false;
        }
        catch (ClassCastException e)
        {
            throw new ServiceException("Storage " + MsgUtils.moduleString(storageModule) + " is not a supported data storage", e);
        }
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
                caps.setIdentifier("urn:offering:" + storage.getLocalID());
            
            // name
            if (config.name != null)
                caps.setTitle(config.name);
            else
                caps.setTitle(storage.getName());
            
            // description
            if (config.description != null)
                caps.setDescription(config.description);
            else
                caps.setDescription("Archive data from " + storage.getName());
            
            // observable properties
            getObservablePropertiesFromStorage(caps.getObservableProperties());
            
            // add phenomenon time = period of data available in storage
            caps.setPhenomenonTime(getTimeExtentFromStorage());
        
            // add procedure ID
            if (storage.getLatestDataSourceDescription() == null)
                throw new ServiceException("Storage is empty");
            caps.getProcedures().add(storage.getLatestDataSourceDescription().getUniqueIdentifier());
            
            // FOI stuff
            if (storage instanceof IObsStorage)
            {
                // FOI IDs
                getFoisFromStorage(caps.getRelatedFeatures());
                
                // observed area = bounding rectangle of all FOIs
                getFoisSpatialExtentFromStorage();
            }
            
            // obs types
            getObservationTypesFromStorage(caps.getObservationTypes());
            
            return caps;
        }
        catch (Exception e)
        {
            throw new ServiceException("Cannot generate capabilities for storage provider " + MsgUtils.moduleString(storage), e);
        }
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
            
            // update time extent
            TimeExtent newTimeExtent = getTimeExtentFromStorage();
            if (!newTimeExtent.isNull())
                caps.setPhenomenonTime(newTimeExtent);
            
            // update FOI list and BBOX
            if (storage instanceof IObsStorage)
            {
                getFoisFromStorage(caps.getRelatedFeatures());
                getFoisSpatialExtentFromStorage();
            }
        }
        catch (Exception e)
        {
            throw new ServiceException("Cannot update capabilities for storage provider " + MsgUtils.moduleString(storage), e);
        }
        finally
        {
            service.capabilitiesLock.writeLock().unlock();
        }
    }
    
    
    @Override
    public boolean checkQueryTime(TimeExtent requestTime)
    {
        TimeExtent storageTimeExtent = getTimeExtentFromStorage();
        return !storageTimeExtent.isNull() && storageTimeExtent.intersects(requestTime);
    }


    /*
     * Gets time extents for all records from storage 
     */
    protected TimeExtent getTimeExtentFromStorage()
    {
        TimeExtent timeExtent = new TimeExtent();
        
        // process outputs descriptions
        for (Entry<String, ? extends IRecordStoreInfo> entry: storage.getRecordStores().entrySet())
        {
            // skip excluded outputs
            if (config.excludedOutputs != null && config.excludedOutputs.contains(entry.getKey()))
                continue;
            
            double[] storedPeriod = storage.getRecordsTimeRange(entry.getKey());
            
            if (!Double.isNaN(storedPeriod[0]))
            {
                timeExtent.resizeToContain(storedPeriod[0]);
                timeExtent.resizeToContain(storedPeriod[1]);
            }
        }
        
        return timeExtent;
    }
    
    
    /*
     * Builds list of FOI IDs from storage
     */
    protected void getFoisFromStorage(Set<String> foiIDs)
    {
        FoiFilter filter = new FoiFilter();
        int numFois = ((IObsStorage) storage).getNumFois(filter);
        if (numFois <= config.maxFois)
        {
            Iterator<String> it = ((IObsStorage)storage).getFoiIDs(filter);
            while (it.hasNext())
                foiIDs.add(it.next());
        }
    }
    
    
    /*
     * Gets FOIs bounding rectangle from storage
     */
    protected void getFoisSpatialExtentFromStorage()
    {
        Bbox bbox = ((IObsStorage) storage).getFoisSpatialExtent();
        if (bbox != null && !bbox.isNull())
        {
            if (caps.getObservedAreas().isEmpty())
                caps.getObservedAreas().add(bbox);
            else
                caps.getObservedAreas().set(0, bbox);
        }
    }
    
    
    /*
     * Builds list of observable properties by scanning record structure of each data store
     */
    protected void getObservablePropertiesFromStorage(Set<String> observables)
    {
        // process outputs descriptions
        for (Entry<String, ? extends IRecordStoreInfo> entry: storage.getRecordStores().entrySet())
        {
            // skip excluded outputs
            if (config.excludedOutputs != null && config.excludedOutputs.contains(entry.getKey()))
                continue;
            
            // iterate through all SWE components and add all definition URIs as observables
            // this way only composites with URI will get added
            DataComponent recordStruct = entry.getValue().getRecordDescription();
            DataIterator it = new DataIterator(recordStruct);
            while (it.hasNext())
            {
                String defUri = it.next().getDefinition();
                if (defUri != null && !defUri.equals(SWEConstants.DEF_SAMPLING_TIME))
                    observables.add(defUri);
            }
            
            // also add special "data availability" observable
            if (storage instanceof IObsStorage)
                observables.add(FOI_TIME_PERIODS);
        }
    }
    
    
    /*
     * Build list of observation types by scanning record structure of each data store
     */
    protected void getObservationTypesFromStorage(Set<String> obsTypes)
    {
        obsTypes.add(IObservation.OBS_TYPE_GENERIC);
        obsTypes.add(IObservation.OBS_TYPE_SCALAR);
        
        // process outputs descriptions
        for (Entry<String, ? extends IRecordStoreInfo> entry: storage.getRecordStores().entrySet())
        {
            // skip excluded outputs
            if (config.excludedOutputs != null && config.excludedOutputs.contains(entry.getKey()))
                continue;
            
            // obs type depends on top-level component
            DataComponent recordStruct = entry.getValue().getRecordDescription();
            if (recordStruct instanceof DataRecord)
                obsTypes.add(IObservation.OBS_TYPE_RECORD);
            else if (recordStruct instanceof DataArray)
                obsTypes.add(IObservation.OBS_TYPE_ARRAY);
        }
    }
    
    
    @Override
    public AbstractProcess generateSensorMLDescription(double time)
    {
        if (Double.isNaN(time))
            return storage.getLatestDataSourceDescription();
        else
            return storage.getDataSourceDescriptionAtTime(time);
    }    
    
    
    @Override
    public Iterator<AbstractFeature> getFoiIterator(final IFoiFilter filter) throws SensorHubException
    {
        if (storage instanceof IObsStorage)
            return ((IObsStorage) storage).getFois(filter);
        
        return Collections.emptyIterator();
    }
    
    
    /**
     * Checks if provider and underlying sensor are enabled
     * @throws SensorException
     */
    protected void checkEnabled() throws SensorHubException
    {
        if (!config.enabled)
            throw new ServiceException("Offering " + config.offeringID + " is disabled");
        
        if (!storage.isStarted())
            throw new ServiceException("Storage " + MsgUtils.moduleString(storage) + " is disabled");
    }
    
    
    @Override
    public void handleEvent(Event<?> e)
    {
        if (disableEvents)
            return;
                
        if (e instanceof ModuleEvent && e.getSource() == storage)
        {
            switch (((ModuleEvent)e).getType())
            {
                // show/hide offering if storage is enabled/disabled
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
                
                // cleanly remove provider when storage is deleted
                case DELETED:
                    service.removeProvider(config.offeringID);
                    break;
                    
                default:
                    return;
            }
        } 
    }
    
    
    @Override
    public ISOSDataProvider getNewDataProvider(SOSDataFilter filter) throws SensorHubException, OWSException
    {
        checkEnabled();
        
        if (storage instanceof IObsStorage && filter.getObservables().contains(FOI_TIME_PERIODS))
            return new StorageFoiTimePeriodsProvider((IObsStorage)storage, config, filter);
        else
            return new StorageDataProvider(storage, config, filter);
    }


    @Override
    public boolean isEnabled()
    {
        return (config.enabled && storage.isStarted());
    }


    @Override
    public void cleanup()
    {
        storage.unregisterListener(this);
    }
    
    
    @Override
    public SOSProviderConfig getConfig()
    {
        return this.config;
    }
}
