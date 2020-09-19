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

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import net.opengis.gml.v32.AbstractTimeGeometricPrimitive;
import net.opengis.gml.v32.TimeInstant;
import net.opengis.gml.v32.TimePeriod;
import net.opengis.sensorml.v20.AbstractProcess;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import org.sensorhub.api.common.IEventListener;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.persistence.DataKey;
import org.sensorhub.api.persistence.IRecordStorageModule;
import org.sensorhub.api.persistence.IDataFilter;
import org.sensorhub.api.persistence.IDataRecord;
import org.sensorhub.api.persistence.IRecordStoreInfo;
import org.sensorhub.api.persistence.IStorageModule;
import org.sensorhub.api.persistence.StorageException;
import org.sensorhub.impl.module.AbstractModule;
import org.sensorhub.utils.DataComponentChecks;
import org.vast.util.NumberUtils;


/**
 * <p>
 * In-memory basic storage implementation.
 * This is used mainly for test purposes but could perhaps be improved to be
 * used as a local memory cache of a remote storage.
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since Nov 8, 2013
 */
public class InMemoryBasicStorage extends AbstractModule<InMemoryStorageConfig> implements IRecordStorageModule<InMemoryStorageConfig>
{
    Map<String, TimeSeriesImpl> dataStores = new LinkedHashMap<>();
    ConcurrentSkipListMap<Double, AbstractProcess> dataSourceDescriptions = new ConcurrentSkipListMap<>();
    
    
    public InMemoryBasicStorage()
    {
    }
    
    
    @Override
    public void start() throws SensorHubException
    {
    }


    @Override
    public void stop() throws SensorHubException
    {                
    }


    @Override
    public AbstractProcess getLatestDataSourceDescription()
    {
        if (dataSourceDescriptions.isEmpty())
            return null;
        return dataSourceDescriptions.lastEntry().getValue();
    }


    @Override
    public List<AbstractProcess> getDataSourceDescriptionHistory(double startTime, double endTime)
    {
        ArrayList<AbstractProcess> smlList = new ArrayList<>();
        smlList.addAll(dataSourceDescriptions.subMap(startTime, endTime).values());
        return smlList;
    }


    @Override
    public AbstractProcess getDataSourceDescriptionAtTime(double time)
    {
        if (dataSourceDescriptions.isEmpty())
            return null;
        return dataSourceDescriptions.floorEntry(time).getValue();
    }


    @Override
    public void storeDataSourceDescription(AbstractProcess process)
    {
        double key = System.currentTimeMillis() / 1000.0;
        
        if (!process.getValidTimeList().isEmpty())
        {        
            AbstractTimeGeometricPrimitive validTime = process.getValidTimeList().get(0);
            
            if (validTime instanceof TimeInstant)
                key = ((TimeInstant)validTime).getTimePosition().getDecimalValue();
            else if (validTime instanceof TimePeriod)
                key = ((TimePeriod)validTime).getBeginPosition().getDecimalValue();
        }
        
        dataSourceDescriptions.put(key, process);     
    }


    @Override
    public void updateDataSourceDescription(AbstractProcess process)
    {
        storeDataSourceDescription(process);
    }


    @Override
    public void removeDataSourceDescription(double time)
    {
        dataSourceDescriptions.remove(time);
    }


    @Override
    public void removeDataSourceDescriptionHistory(double startTime, double endTime)
    {
        dataSourceDescriptions.subMap(startTime, endTime).clear();      
    }


    @Override
    public Map<String, ? extends IRecordStoreInfo> getRecordStores()
    {
        return Collections.unmodifiableMap(dataStores);
    }
    
    
    protected final TimeSeriesImpl getRecordStore(String recordType)
    {
        TimeSeriesImpl dataStore = dataStores.get(recordType);
        if (dataStore == null)
            throw new IllegalArgumentException("Record type not found in this storage: " + recordType);
        return dataStore;
    }


    @Override
    public void addRecordStore(String name, DataComponent recordStructure, DataEncoding recommendedEncoding)
    {
        TimeSeriesImpl timeSeries = new TimeSeriesImpl(recordStructure.copy(), recommendedEncoding);
        dataStores.put(name, timeSeries);
    }


    @Override
    public void updateRecordStore(String name, DataComponent recordStructure)
    {
        getRecordStore(name).updateRecordStructure(recordStructure);
    }


    @Override
    public void backup(OutputStream os)
    {
    }


    @Override
    public void restore(InputStream is)
    {
    }


    @Override
    public void sync(IStorageModule<?> storage)
    {
    }
    

    @Override
    public void commit()
    {
    }


    @Override
    public void rollback()
    {
    }
    
    
    @Override
    public void cleanup() throws StorageException
    {
        dataStores.clear();
        dataSourceDescriptions.clear();
    }
    
    
    @Override
    public DataBlock getDataBlock(DataKey key)
    {
        TimeSeriesImpl dataStore = dataStores.get(key.recordType);
        if (dataStore == null)
            return null;
        
        return dataStore.getDataBlock(key);
    }


    @Override
    public Iterator<DataBlock> getDataBlockIterator(IDataFilter filter)
    {
        return getRecordStore(filter.getRecordType()).getDataBlockIterator(filter);
    }


    @Override
    public Iterator<? extends IDataRecord> getRecordIterator(IDataFilter filter)
    {
        return getRecordStore(filter.getRecordType()).getRecordIterator(filter);
    }


    @Override
    public int getNumMatchingRecords(IDataFilter filter, long maxCount)
    {
        return getRecordStore(filter.getRecordType()).getNumMatchingRecords(filter);
    }


    @Override
    public int getNumRecords(String recordType)
    {
        return getRecordStore(recordType).getNumRecords();
    }

    
    @Override
    public double[] getRecordsTimeRange(String recordType)
    {
        return getRecordStore(recordType).getDataTimeRange();
    }
    
    
    @Override
    public int[] getEstimatedRecordCounts(String recordType, double[] timeStamps)
    {
        return StorageUtils.computeDefaultRecordCounts(this, recordType, timeStamps);
    }
    

    @Override
    public void storeRecord(DataKey key, DataBlock data)
    {
        getRecordStore(key.recordType).store(key, data);
    }


    @Override
    public void updateRecord(DataKey key, DataBlock data)
    {
        getRecordStore(key.recordType).update(key, data);        
    }


    @Override
    public void removeRecord(DataKey key)
    {
        getRecordStore(key.recordType).remove(key);
    }


    @Override
    public int removeRecords(IDataFilter filter)
    {
        return getRecordStore(filter.getRecordType()).remove(filter);
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


    /*
     * Implementation of individual time series {key,record} pair
     */
    private class DBRecord implements IDataRecord
    {
        DataKey key;
        DataBlock data;
        
        public DBRecord(DataKey key, DataBlock data)
        {
            this.key = key;
            this.data = data;
        }

        @Override
        public final DataKey getKey()
        {
            return key;
        }
        
        @Override
        public final DataBlock getData()
        {
            return data;
        }
        
        protected final boolean matches(DataKey key)
        {
            return ( (key.producerID == null || key.producerID.equals(this.key.producerID)) &&
                 (Double.isNaN(key.timeStamp) || NumberUtils.ulpEquals(key.timeStamp, this.key.timeStamp)) );
        }
        
        protected final boolean matches(IDataFilter filter)
        {
            return ( (filter.getProducerIDs() == null || filter.getProducerIDs().contains(this.key.producerID)) &&
                 (filter.getTimeStampRange() == null || (filter.getTimeStampRange()[0] <= this.key.timeStamp && filter.getTimeStampRange()[1] >= this.key.timeStamp)) );
        }
    }
    
    
    /*
     * Implementation of an individual time series data store
     */
    public class TimeSeriesImpl implements IRecordStoreInfo
    {
        ConcurrentSkipListMap<Double, DBRecord> recordList = new ConcurrentSkipListMap<>();
        DataComponent recordDescription;
        DataEncoding recommendedEncoding;
        
        TimeSeriesImpl(DataComponent recordDescription, DataEncoding recommendedEncoding)
        {
            this.recordDescription = recordDescription;
            this.recommendedEncoding = recommendedEncoding;
        }
        
        @Override
        public String getName()
        {
            return recordDescription.getName();
        }
        
        @Override
        public DataComponent getRecordDescription()
        {
            return recordDescription;
        }
        
        @Override
        public DataEncoding getRecommendedEncoding()
        {
            return recommendedEncoding;
        }
        
        public int getNumRecords()
        {
            return recordList.size();
        }

        public DataBlock getDataBlock(DataKey key)
        {
            IDataRecord rec = getRecord(key);
            return rec.getData();
        }

        public Iterator<DataBlock> getDataBlockIterator(IDataFilter filter)
        {
            final Iterator<DBRecord> it = getRecordIterator(filter);
            return new Iterator<DataBlock>() {

                @Override
                public final boolean hasNext()
                {
                    return it.hasNext();
                }

                @Override
                public final DataBlock next()
                {
                    return it.next().getData();
                }

                @Override
                public final void remove()
                {
                    it.remove();                    
                }                
            };
        }

        public IDataRecord getRecord(DataKey key)
        {
            Iterator<DBRecord> it = recordList.values().iterator();
            while (it.hasNext())
            {
                DBRecord rec = it.next();
                if (rec.matches(key))
                    return rec;
            }
            
            return null;
        }

        public int getNumMatchingRecords(IDataFilter filter)
        {
            final Iterator<DBRecord> it = getRecordIterator(filter);
            int matchCount = 0;
            while (it.hasNext())
            {
                if (it.next().matches(filter))
                    matchCount++;
            }
            return matchCount;
        }
        
        public Iterator<DBRecord> getRecordIterator(final IDataFilter filter)
        {
            final Iterator<DBRecord> it = recordList.values().iterator();
            return new Iterator<DBRecord>() {
                DBRecord nextRec;
                
                @Override
                public final boolean hasNext()
                {
                    nextRec = fetchNext();
                    return (nextRec != null);
                }

                @Override
                public final DBRecord next()
                {
                    if (nextRec != null)
                    {
                        // get already fetched record and clear it
                        DBRecord rec = nextRec;
                        nextRec = null;
                        return rec;
                    }
                    
                    return fetchNext();
                }
                
                protected final DBRecord fetchNext()
                {
                    while (it.hasNext())
                    {
                        DBRecord rec = it.next();
                        if (rec.matches(filter))
                            return rec;
                    }
                    
                    return null;
                }

                @Override
                public final void remove()
                {
                    it.remove();                    
                }                
            };
        }

        public DataKey store(DataKey key, DataBlock data)
        {
            recordList.put(key.timeStamp, new DBRecord(key, data));
            return key;
        }

        public void update(DataKey key, DataBlock data)
        {
            Iterator<DBRecord> it = recordList.values().iterator();
            while (it.hasNext())
            {
                DBRecord rec = it.next();                
                if (rec.matches(key))
                    rec.data = data;
            }
        }

        public void remove(DataKey key)
        {
            Iterator<DBRecord> it = recordList.values().iterator();
            while (it.hasNext())
            {
                DBRecord rec = it.next();                
                if (rec.matches(key))
                    it.remove();
            }
        }

        public int remove(IDataFilter filter)
        {
            Iterator<DBRecord> it = recordList.values().iterator();
            int count = 0;
            while (it.hasNext())
            {
                DBRecord rec = it.next();
                if (rec.matches(filter))
                {
                    it.remove();
                    count++;
                }
            }
            
            return count;
        }
        
        public double[] getDataTimeRange()
        {
            if (recordList.isEmpty())
                return new double[] { Double.NaN, Double.NaN};
            else
                return new double[] {recordList.firstKey(), recordList.lastKey()};
        }
        
        void updateRecordStructure(DataComponent newDataStruct)
        {
            // check that new structure is compatible with previous one
            if (!DataComponentChecks.checkStructCompatible(recordDescription, newDataStruct))
                throw new IllegalStateException("New data structure for record store " + getName() + 
                        " is not compatible with the one already in storage");
            
            this.recordDescription = newDataStruct;
        }
    }


    @Override
    public boolean isReadSupported()
    {
        return true;
    }


    @Override
    public boolean isWriteSupported()
    {
        return true;
    }
}
