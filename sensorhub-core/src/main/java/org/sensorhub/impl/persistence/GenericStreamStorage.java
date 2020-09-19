/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Timer;
import java.util.TimerTask;
import net.opengis.gml.v32.AbstractFeature;
import net.opengis.sensorml.v20.AbstractProcess;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import org.sensorhub.api.common.Event;
import org.sensorhub.api.common.IEventListener;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.api.data.FoiEvent;
import org.sensorhub.api.data.IDataProducerModule;
import org.sensorhub.api.data.IMultiSourceDataInterface;
import org.sensorhub.api.data.IMultiSourceDataProducer;
import org.sensorhub.api.data.IStreamingDataInterface;
import org.sensorhub.api.module.IModule;
import org.sensorhub.api.module.ModuleEvent;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.sensorhub.api.persistence.DataKey;
import org.sensorhub.api.persistence.IBasicStorage;
import org.sensorhub.api.persistence.IFoiFilter;
import org.sensorhub.api.persistence.IMultiSourceStorage;
import org.sensorhub.api.persistence.IObsFilter;
import org.sensorhub.api.persistence.IObsStorage;
import org.sensorhub.api.persistence.IRecordStorageModule;
import org.sensorhub.api.persistence.IDataFilter;
import org.sensorhub.api.persistence.IDataRecord;
import org.sensorhub.api.persistence.IStorageModule;
import org.sensorhub.api.persistence.IRecordStoreInfo;
import org.sensorhub.api.persistence.ObsKey;
import org.sensorhub.api.persistence.ObsPeriod;
import org.sensorhub.api.persistence.StorageConfig;
import org.sensorhub.api.persistence.StorageException;
import org.sensorhub.api.sensor.SensorEvent;
import org.sensorhub.impl.SensorHub;
import org.sensorhub.impl.module.AbstractModule;
import org.sensorhub.impl.module.ModuleRegistry;
import org.sensorhub.utils.MsgUtils;
import org.sensorhub.utils.SWEDataUtils;
import org.vast.swe.ScalarIndexer;
import org.vast.util.Bbox;


/**
 * <p>
 * Generic wrapper/adapter enabling any storage implementation to store data
 * coming from data events (e.g. sensor data, processed data, etc.)<br/>
 * This class takes care of registering with the appropriate producers and
 * uses the storage API to store records in the underlying storage.
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since Feb 21, 2015
 */
public class GenericStreamStorage extends AbstractModule<StreamStorageConfig> implements IRecordStorageModule<StreamStorageConfig>, IObsStorage, IMultiSourceStorage<IObsStorage>, IEventListener
{
    IRecordStorageModule<StorageConfig> storage;
    WeakReference<IDataProducerModule<?>> dataSourceRef;
    Map<String, ScalarIndexer> timeStampIndexers = new HashMap<>();
    Map<String, String> currentFoiMap = new HashMap<>(); // entity ID -> current FOI ID
    
    long lastCommitTime = Long.MIN_VALUE;
    String currentFoi;
    Timer autoPurgeTimer;
    
    
    @Override
    public void start() throws SensorHubException
    {
        if (config.storageConfig == null)
            throw new StorageException("Underlying storage configuration must be provided");
        
        // instantiate and start underlying storage
        StorageConfig storageConfig = null;
        try
        {
            storageConfig = (StorageConfig)config.storageConfig.clone();
            storageConfig.id = getLocalID();
            storageConfig.name = getName();
            Class<?> clazz = Class.forName(storageConfig.moduleClass);
            storage = (IRecordStorageModule<StorageConfig>)clazz.newInstance();
            storage.init(storageConfig);
            storage.start();
        }
        catch (Exception e)
        {
            throw new StorageException("Cannot instantiate underlying storage " + storageConfig.moduleClass, e);
        }
        
        // start auto-purge timer thread if policy is specified and enabled
        if (config.autoPurgeConfig != null && config.autoPurgeConfig.enabled)
        {
            final IStorageAutoPurgePolicy policy = config.autoPurgeConfig.getPolicy();
            autoPurgeTimer = new Timer();
            TimerTask task = new TimerTask() {
                @Override
                public void run()
                {
                    policy.trimStorage(storage, logger);
                }
            };
            
            autoPurgeTimer.schedule(task, 0, (long)(config.autoPurgeConfig.purgePeriod*1000)); 
        }
        
        // retrieve reference to data source
        ModuleRegistry moduleReg = SensorHub.getInstance().getModuleRegistry();
        dataSourceRef = (WeakReference<IDataProducerModule<?>>)moduleReg.getModuleRef(config.dataSourceID);
        
        // register to receive data source events
        IDataProducerModule<?> dataSource = dataSourceRef.get();
        if (dataSource != null)
        {
            dataSource.registerListener(this);
            if (!dataSource.isStarted())
                disconnectDataSource(dataSource);
        }
    }
    
    
    /*
     * Gets the list of selected outputs (i.e. a subset of all data source outputs)
     */
    protected Collection<? extends IStreamingDataInterface> getSelectedOutputs(IDataProducerModule<?> dataSource)
    {
        if (config.excludedOutputs == null || config.excludedOutputs.isEmpty())
        {
            return dataSource.getAllOutputs().values();
        }
        else
        {
            List <IStreamingDataInterface> selectedOutputs = new ArrayList<>();
            
            for (IStreamingDataInterface outputInterface : dataSource.getAllOutputs().values())
            {
                // skip excluded outputs
                if (!config.excludedOutputs.contains(outputInterface.getName()))                                    
                    selectedOutputs.add(outputInterface);
            }
            
            return selectedOutputs;
        }
    }
    
    
    /*
     * Connects to data source and store initial metadata for all selected streams
     */
    protected void connectDataSource(IDataProducerModule<?> dataSource)
    {
        ensureProducerInfo(dataSource.getUniqueIdentifier(), true);
        
        // set data source description
        AbstractProcess sml = dataSource.getCurrentDescription();
        if (sml != null)
        {
            // if no description yet, initialize it
            if (storage.getLatestDataSourceDescription() == null)
                storage.storeDataSourceDescription(sml);
            
            // otherwise just get the latest sensor description in case we were down during the last update
            else if (dataSource.getLastDescriptionUpdate() != Long.MIN_VALUE)
                storage.updateDataSourceDescription(sml);
        }
        
        // create one data store for each sensor output that's not yet registered
        // we do that in multi source storage even if it's also done in each provider data store
        for (IStreamingDataInterface output: getSelectedOutputs(dataSource))
        {
            if (!storage.getRecordStores().containsKey(output.getName()))
                storage.addRecordStore(output.getName(), output.getRecordDescription(), output.getRecommendedEncoding());
            else
                storage.updateRecordStore(output.getName(), output.getRecordDescription());
        }
        
        // set current FOI
        String producerID = dataSource.getCurrentDescription().getUniqueIdentifier();
        AbstractFeature foi = dataSource.getCurrentFeatureOfInterest();
        if (foi != null)
        {
            currentFoi = foi.getUniqueIdentifier();
            currentFoiMap.put(producerID, currentFoi);
            if (storage instanceof IObsStorage)
                ((IObsStorage)storage).storeFoi(producerID, foi);
        }
        
        // register to data events
        for (IStreamingDataInterface output: getSelectedOutputs(dataSource))
            prepareToReceiveEvents(output);
        
        // make sure data source info can be read back
        storage.commit();
        
        setState(ModuleState.STARTED);
        clearStatus();
    }
    
    
    /*
     * Listen to events and prepare to index time stamps for given stream
     */
    protected void prepareToReceiveEvents(IStreamingDataInterface output)
    {
        // create time stamp indexer
        String outputName = output.getName();
        ScalarIndexer timeStampIndexer = timeStampIndexers.get(outputName);
        if (timeStampIndexer == null)
        {
            timeStampIndexer = SWEDataUtils.getTimeStampIndexer(output.getRecordDescription());
            timeStampIndexers.put(outputName, timeStampIndexer);
        }
        
        // fetch latest record(s)
        if (output instanceof IMultiSourceDataInterface)
        {
            for (Entry<String, DataBlock> rec: ((IMultiSourceDataInterface) output).getLatestRecords().entrySet())
            {
                String producerID = rec.getKey();
                ensureProducerInfo(producerID, true);
                handleEvent(new DataEvent(System.currentTimeMillis(), producerID, output, rec.getValue()));
            }
        }
        else
        {
            DataBlock rec = output.getLatestRecord();
            if (rec != null)
                this.handleEvent(new DataEvent(System.currentTimeMillis(), output, rec));
        }
        
        // register to receive future events
        output.registerListener(this);
    }
    
    
    /*
     * Ensures metadata for the new producer is stored (for multi-producer sources)
     */
    protected void ensureProducerInfo(String producerID, boolean updateAll)
    {
        if (storage instanceof IMultiSourceStorage)
        {
            boolean hasDataStore = ((IMultiSourceStorage<?>)storage).getProducerIDs().contains(producerID);
            if (hasDataStore && !updateAll)
                return;
            
            // create producer data store if needed
            IBasicStorage dataStore;
            if (!hasDataStore)
                dataStore = ((IMultiSourceStorage<?>)storage).addDataStore(producerID);
            else
                dataStore = ((IMultiSourceStorage<?>)storage).getDataStore(producerID);
            
            // handle multisource producers
            IDataProducerModule<?> dataSource = dataSourceRef.get();
            if (dataSource != null && dataSource instanceof IMultiSourceDataProducer)
            {
                // save producer SensorML description
                AbstractProcess sml = ((IMultiSourceDataProducer) dataSource).getCurrentDescription(producerID);
                if (sml != null)
                {
                    if (dataStore.getLatestDataSourceDescription() == null)
                        dataStore.storeDataSourceDescription(sml);
                    else
                        dataStore.updateDataSourceDescription(sml);
                }
                
                // record current FOI
                AbstractFeature foi = ((IMultiSourceDataProducer)dataSource).getCurrentFeatureOfInterest(producerID);
                if (foi != null)
                {
                    currentFoiMap.put(producerID, foi.getUniqueIdentifier());
                    if (storage instanceof IObsStorage)
                        ((IObsStorage)storage).storeFoi(producerID, foi);
                }
                
                // create or update record store for each output
                for (IStreamingDataInterface output: getSelectedOutputs(dataSource))
                {
                    if (!dataStore.getRecordStores().containsKey(output.getName()))
                        dataStore.addRecordStore(output.getName(), output.getRecordDescription(), output.getRecommendedEncoding());
                    else
                        dataStore.updateRecordStore(output.getName(), output.getRecordDescription());
                }
            }
        }
    }
    
    
    protected void disconnectDataSource(IDataProducerModule<?> dataSource)
    {
        for (IStreamingDataInterface output: getSelectedOutputs(dataSource))
            output.unregisterListener(this);
        reportStatus("Waiting for data source " + MsgUtils.moduleString(dataSource));
    }
    
        
    @Override
    public synchronized void stop() throws SensorHubException
    {
        if (dataSourceRef != null)
        {
            // unregister all listeners
            IDataProducerModule<?> dataSource = dataSourceRef.get();
            if (dataSource != null)
            {
                dataSource.unregisterListener(this);
                disconnectDataSource(dataSource);
            }
            
            dataSourceRef = null;
        }
        
        if (autoPurgeTimer != null)
            autoPurgeTimer.cancel();

        if (storage != null)
            storage.stop();
    }


    @Override
    public void cleanup() throws SensorHubException
    {
        if (storage != null)
            storage.cleanup();
        super.cleanup();
    }
    
    
    @Override
    public synchronized void handleEvent(Event<?> e)
    {
        // don't do anything if stop was called
        if (dataSourceRef == null)
            return;
        
        if (e instanceof ModuleEvent)
        {
            IModule<?> eventSrc = (IModule<?>)e.getSource();
            ModuleState state = ((ModuleEvent) e).getNewState();
            
            try
            {
                // connect to data source only when it's started
                IDataProducerModule<?> dataSource = dataSourceRef.get();
                if (dataSource == eventSrc)
                {
                    if (state == ModuleState.STARTED)
                        connectDataSource(dataSource);
                    else if (state == ModuleState.STOPPED)
                        disconnectDataSource(dataSource);
                }
            }
            catch (Exception ex)
            {
                getLogger().error("Error while connecting to data source", ex);
            }
        }
        
        else if (config.processEvents)
        {
            // new data events
            if (e instanceof DataEvent)
            {
                DataEvent dataEvent = (DataEvent)e;
                
                // get indexer for looking up time stamp value
                String outputName = dataEvent.getSource().getName();
                ScalarIndexer timeStampIndexer = timeStampIndexers.get(outputName);
                
                // get entity and FOI ID
                String foiID;
                String entityID = dataEvent.getRelatedEntityID();
                if (entityID != null)
                {
                    ensureProducerInfo(entityID, false); // to handle new producer
                    foiID = currentFoiMap.get(entityID);
                }
                else
                    foiID = currentFoi; 
                
                // process all records
                for (DataBlock record: dataEvent.getRecords())
                {
                    // get time stamp
                    double time;
                    if (timeStampIndexer != null)
                        time = timeStampIndexer.getDoubleValue(record);
                    else
                        time = e.getTimeStamp() / 1000.;
                    
                    // store record with proper key
                    ObsKey key = new ObsKey(outputName, entityID, foiID, time);
                    storage.storeRecord(key, record);
                    
                    if (getLogger().isTraceEnabled())
                        getLogger().trace("Storing record " + key.timeStamp + " for output " + outputName);
                }
            }
            
            else if (e instanceof SensorEvent)
            {
                if (((SensorEvent) e).getType() == SensorEvent.Type.SENSOR_CHANGED)
                {
                    // TODO check that description was actually updated?
                    // in the current state, the same description would be added at each restart
                    // should we compare contents? if not, on what time tag can we rely on?
                    // AbstractSensorModule implementation of getLastSensorDescriptionUpdate() is
                    // only useful between restarts since it will be resetted to current time at startup...
                    
                    // TODO to manage this issue, first check that no other description is valid at the same time
                    storage.storeDataSourceDescription(dataSourceRef.get().getCurrentDescription());
                }
            }
            
            else if (e instanceof FoiEvent && storage instanceof IObsStorage)
            {
                FoiEvent foiEvent = (FoiEvent)e;
                String producerID = ((FoiEvent) e).getRelatedEntityID();
                
                // store feature object if specified
                if (foiEvent.getFoi() != null)
                {
                    if (producerID != null)
                        ensureProducerInfo(producerID, false); // in case no data has been received for this producer yet
                    ((IObsStorage) storage).storeFoi(producerID, foiEvent.getFoi());
                }
                
                // also remember as current FOI
                if (producerID != null)
                    currentFoiMap.put(producerID, foiEvent.getFoiID());
                else
                    currentFoi = foiEvent.getFoiID();
            }
            
            // commit only when necessary
            long now = System.currentTimeMillis();
            if (lastCommitTime == Long.MIN_VALUE || (now - lastCommitTime) > config.minCommitPeriod)
            {
                storage.commit();
                lastCommitTime = now;
            }
        }
    }
    

    @Override
    public void addRecordStore(String name, DataComponent recordStructure, DataEncoding recommendedEncoding)
    {
        checkStarted();
        
        // register new record type with underlying storage
        if (!storage.getRecordStores().containsKey(name))
            storage.addRecordStore(name, recordStructure, recommendedEncoding);
        
        // prepare to receive events
        IDataProducerModule<?> dataSource = dataSourceRef.get();
        if (dataSource != null)
            prepareToReceiveEvents(dataSource.getAllOutputs().get(name));
    }


    @Override
    public void updateRecordStore(String name, DataComponent recordStructure)
    {
        throw new UnsupportedOperationException();        
    }


    @Override
    public void backup(OutputStream os) throws IOException
    {
        checkStarted();
        storage.backup(os);        
    }


    @Override
    public void restore(InputStream is) throws IOException
    {
        checkStarted();
        storage.restore(is);        
    }


    @Override
    public void commit()
    {
        checkStarted();
        storage.commit();        
    }


    @Override
    public void rollback()
    {
        checkStarted();
        storage.rollback();        
    }


    @Override
    public void sync(IStorageModule<?> storage) throws StorageException
    {
        checkStarted();
        this.storage.sync(storage);        
    }


    @Override
    public AbstractProcess getLatestDataSourceDescription()
    {
        checkStarted();
        return storage.getLatestDataSourceDescription();
    }


    @Override
    public List<AbstractProcess> getDataSourceDescriptionHistory(double startTime, double endTime)
    {
        checkStarted();
        return storage.getDataSourceDescriptionHistory(startTime, endTime);
    }


    @Override
    public AbstractProcess getDataSourceDescriptionAtTime(double time)
    {
        checkStarted();
        return storage.getDataSourceDescriptionAtTime(time);
    }


    @Override
    public void storeDataSourceDescription(AbstractProcess process)
    {
        checkStarted();
        storage.storeDataSourceDescription(process);        
    }


    @Override
    public void updateDataSourceDescription(AbstractProcess process)
    {
        checkStarted();
        storage.updateDataSourceDescription(process);        
    }


    @Override
    public void removeDataSourceDescription(double time)
    {
        checkStarted();
        storage.removeDataSourceDescription(time);        
    }


    @Override
    public void removeDataSourceDescriptionHistory(double startTime, double endTime)
    {
        checkStarted();
        storage.removeDataSourceDescriptionHistory(startTime, endTime);
    }


    @Override
    public Map<String, ? extends IRecordStoreInfo> getRecordStores()
    {
        checkStarted();
        return storage.getRecordStores();
    }


    @Override
    public DataBlock getDataBlock(DataKey key)
    {
        checkStarted();
        return storage.getDataBlock(key);
    }


    @Override
    public Iterator<DataBlock> getDataBlockIterator(IDataFilter filter)
    {
        checkStarted();
        return storage.getDataBlockIterator(filter);
    }


    @Override
    public Iterator<? extends IDataRecord> getRecordIterator(IDataFilter filter)
    {
        checkStarted();
        return storage.getRecordIterator(filter);
    }


    @Override
    public int getNumMatchingRecords(IDataFilter filter, long maxCount)
    {
        checkStarted();
        return storage.getNumMatchingRecords(filter, maxCount);
    }

    
    @Override
    public int getNumRecords(String recordType)
    {
        checkStarted();
        return storage.getNumRecords(recordType);
    }


    @Override
    public double[] getRecordsTimeRange(String recordType)
    {
        checkStarted();
        return storage.getRecordsTimeRange(recordType);
    }
    
    
    @Override
    public int[] getEstimatedRecordCounts(String recordType, double[] timeStamps)
    {
        checkStarted();
        return storage.getEstimatedRecordCounts(recordType, timeStamps);
    }


    @Override
    public void storeRecord(DataKey key, DataBlock data)
    {
        checkStarted();
        storage.storeRecord(key, data);
    }


    @Override
    public void updateRecord(DataKey key, DataBlock data)
    {
        checkStarted();
        storage.updateRecord(key, data);
    }


    @Override
    public void removeRecord(DataKey key)
    {
        checkStarted();
        storage.removeRecord(key);
    }


    @Override
    public int removeRecords(IDataFilter filter)
    {
        checkStarted();
        return storage.removeRecords(filter);
    }


    @Override
    public int getNumFois(IFoiFilter filter)
    {
        checkStarted();
        
        if (storage instanceof IObsStorage)
            return ((IObsStorage) storage).getNumFois(filter);
        
        return 0;
    }
    
    
    @Override
    public Bbox getFoisSpatialExtent()
    {
        checkStarted();
        
        if (storage instanceof IObsStorage)
            return ((IObsStorage) storage).getFoisSpatialExtent();
        
        return null;
    }


    @Override
    public Iterator<String> getFoiIDs(IFoiFilter filter)
    {
        checkStarted();
        
        if (storage instanceof IObsStorage)
            return ((IObsStorage) storage).getFoiIDs(filter);
        
        return Collections.<String>emptyList().iterator();
    }


    @Override
    public Iterator<AbstractFeature> getFois(IFoiFilter filter)
    {
        checkStarted();
        
        if (storage instanceof IObsStorage)
            return ((IObsStorage) storage).getFois(filter);
        
        return Collections.<AbstractFeature>emptyList().iterator();
    }


    @Override
    public Iterator<ObsPeriod> getFoiTimeRanges(IObsFilter filter)
    {
        checkStarted();
        
        if (storage instanceof IObsStorage)
            return ((IObsStorage) storage).getFoiTimeRanges(filter);
        
        return Collections.<ObsPeriod>emptyList().iterator();
    }


    @Override
    public void storeFoi(String producerID, AbstractFeature foi)
    {
        checkStarted();
        if (storage instanceof IObsStorage)
            storeFoi(producerID, foi);        
    }
    
    
    private void checkStarted()
    {
        if (storage == null)
            throw new IllegalStateException("Storage is disabled");
    }


    @Override
    protected void setState(ModuleState newState)
    {
        // switch to started only if we already have data in storage
        // otherwise we have to wait for data source to start
        if (newState == ModuleState.STARTED && storage.getLatestDataSourceDescription() == null)
            return;
            
        super.setState(newState);
    }


    @Override
    public boolean isReadSupported()
    {
        checkStarted();
        return storage.isReadSupported();
    }


    @Override
    public boolean isWriteSupported()
    {
        return true;
    }
    
    
    public boolean isMultiSource()
    {
        checkStarted();
        return storage instanceof IMultiSourceStorage;
    }


    @Override
    public Collection<String> getProducerIDs()
    {
        checkStarted();
        
        if (storage instanceof IMultiSourceStorage)
            return ((IMultiSourceStorage<?>)storage).getProducerIDs();
        
        return Collections.<String>emptyList();
    }


    @Override
    public IObsStorage getDataStore(String producerID)
    {
        checkStarted();
        
        if (storage instanceof IMultiSourceStorage)
            return (IObsStorage) ((IMultiSourceStorage<?>)storage).getDataStore(producerID);
        
        return null;
    }


    @Override
    public IObsStorage addDataStore(String producerID)
    {
        throw new UnsupportedOperationException();
    }
}
