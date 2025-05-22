/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.datastore.h2;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.h2.mvstore.DataUtils;
import org.h2.mvstore.MVBTreeMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.MVVarLongDataType;
import org.h2.mvstore.RangeCursor;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.IdProvider;
import org.sensorhub.api.datastore.TemporalFilter;
import org.sensorhub.api.datastore.obs.DataStreamFilter;
import org.sensorhub.api.datastore.obs.DataStreamKey;
import org.sensorhub.api.datastore.obs.IDataStreamStore;
import org.sensorhub.api.datastore.system.ISystemDescStore;
import org.sensorhub.impl.datastore.DataStoreUtils;
import org.sensorhub.impl.datastore.h2.H2Utils.Holder;
import org.sensorhub.impl.datastore.h2.MVDatabaseConfig.IdProviderType;
import org.sensorhub.impl.datastore.h2.index.FullTextIndex;
import org.sensorhub.impl.datastore.obs.DataStreamInfoWrapper;
import org.vast.util.Asserts;
import org.vast.util.TimeExtent;


/**
 * <p>
 * Datastream Store implementation based on H2 MVStore.
 * </p>
 *
 * @author Alex Robin
 * @date Sep 19, 2019
 */
public class MVDataStreamStoreImpl implements IDataStreamStore
{
    private static final String DATASTREAM_MAP_NAME = "datastreams_records";
    private static final String DATASTREAM_SYSTEM_MAP_NAME = "datastreams_sys";
    private static final String DATASTREAM_FULLTEXT_MAP_NAME = "datastreams_text";

    protected MVStore mvStore;
    protected MVObsStoreImpl obsStore;
    protected IdProvider<IDataStreamInfo> idProvider;
    protected int idScope;
    protected ISystemDescStore systemStore;
    
    /*
     * Main index
     */
    protected MVBTreeMap<DataStreamKey, IDataStreamInfo> dataStreamIndex;
    
    /*
     * System/output index
     * Map of {system ID, output name, validTime} to datastream ID
     * Everything is stored in the key with no value (use MVVoidDataType for efficiency)
     */
    protected MVBTreeMap<MVTimeSeriesSystemKey, Boolean> dataStreamBySystemIndex;
    
    /*
     * Full text index pointing to main index
     * Key references are parentID/internalID pairs
     */
    protected FullTextIndex<IDataStreamInfo, Long> fullTextIndex;
    
    
    /*
     * DataStreamInfo object wrapper used to compute time ranges lazily
     */
    protected class DataStreamInfoWithTimeRanges extends DataStreamInfoWrapper
    {
        Long dsID;
        TimeExtent validTime;
        TimeExtent phenomenonTimeRange;
        TimeExtent resultTimeRange;
                
        DataStreamInfoWithTimeRanges(Long internalID, IDataStreamInfo dsInfo)
        {
            super(dsInfo);
            this.dsID = internalID;
        }

        @Override
        public TimeExtent getValidTime()
        {
            if (validTime == null)
            {
                validTime = super.getValidTime();
                
                // if valid time ends at now and there is a more recent version, compute the actual end time
                if (validTime.endsNow())
                {
                    var sysDsKey = new MVTimeSeriesSystemKey(
                        getSystemID().getInternalID().getIdAsLong(),
                        getOutputName(),
                        getValidTime().begin());
                    
                    var nextKey = dataStreamBySystemIndex.lowerKey(sysDsKey); // use lower cause time sorting is reversed
                    if (nextKey != null &&
                        nextKey.systemID == sysDsKey.systemID &&
                        nextKey.signalName.equals(sysDsKey.signalName))
                        validTime = TimeExtent.period(validTime.begin(), Instant.ofEpochSecond(nextKey.validStartTime));
                }
            }
            
            return validTime;
        }     
        
        @Override
        public TimeExtent getPhenomenonTimeRange()
        {
            if (phenomenonTimeRange == null)
                phenomenonTimeRange = obsStore.getDataStreamPhenomenonTimeRange(dsID);
            
            return phenomenonTimeRange;
        }        
        
        @Override
        public TimeExtent getResultTimeRange()
        {
            if (resultTimeRange == null)
                resultTimeRange = obsStore.getDataStreamResultTimeRange(dsID);
            
            return resultTimeRange;
        }
    }
    
    
    public MVDataStreamStoreImpl(MVObsStoreImpl obsStore, IdProvider<IDataStreamInfo> idProvider)
    {
        init(obsStore, idProvider);
    }
    
    
    public MVDataStreamStoreImpl(MVObsStoreImpl obsStore, IdProviderType idProviderType)
    {
        // create ID provider
        IdProvider<IDataStreamInfo> idProvider;
        switch (idProviderType)
        {
            case UID_HASH:
                idProvider = DataStoreUtils.getDataStreamHashIdProvider(741532149);
                break;
                
            default:
            case SEQUENTIAL:
                idProvider = dsInfo -> {
                    if (dataStreamIndex.isEmpty())
                        return 1;
                    else
                        return dataStreamIndex.lastKey().getInternalID().getIdAsLong()+1;
                };
        }
        
        init(obsStore, idProvider);
    }


    protected void init(MVObsStoreImpl obsStore, IdProvider<IDataStreamInfo> idProvider)
    {
        this.mvStore = Asserts.checkNotNull(obsStore.mvStore, MVStore.class);
        this.obsStore = Asserts.checkNotNull(obsStore, MVObsStoreImpl.class);
        this.idProvider = Asserts.checkNotNull(idProvider, IdProvider.class);
        this.idScope = obsStore.idScope;
        
        // persistent class mappings for Kryo
        var kryoClassMap = mvStore.openMap(MVObsSystemDatabase.KRYO_CLASS_MAP_NAME, new MVBTreeMap.Builder<String, Integer>());

        // open observation map
        String mapName = obsStore.getDatastoreName() + ":" + DATASTREAM_MAP_NAME;
        this.dataStreamIndex = mvStore.openMap(mapName, new MVBTreeMap.Builder<DataStreamKey, IDataStreamInfo>()
                .keyType(new MVDataStreamKeyDataType(idScope))
                .valueType(new DataStreamInfoDataType(kryoClassMap, idScope)));

        // open observation series map
        mapName = obsStore.getDatastoreName() + ":" + DATASTREAM_SYSTEM_MAP_NAME;
        this.dataStreamBySystemIndex = mvStore.openMap(mapName, new MVBTreeMap.Builder<MVTimeSeriesSystemKey, Boolean>()
                .keyType(new MVTimeSeriesSystemKeyDataType())
                .valueType(new MVVoidDataType()));
        
        // full-text index
        mapName = obsStore.getDatastoreName() + ":" + DATASTREAM_FULLTEXT_MAP_NAME;
        this.fullTextIndex = new FullTextIndex<>(mvStore, mapName, new MVVarLongDataType()) {
            @Override
            protected void addToTokenSet(IDataStreamInfo dsInfo, Set<String> tokenSet)
            {
                super.addToTokenSet(dsInfo, tokenSet);
                
                // add observable names and descriptions to full text index
                DataStreamFilter.getTextContent(dsInfo).forEach(text -> {
                    super.addToTokenSet(text, tokenSet);
                });
            }
        };
    }
    
    
    @Override
    public synchronized DataStreamKey add(IDataStreamInfo dsInfo) throws DataStoreException
    {
        DataStoreUtils.checkDataStreamInfo(systemStore, dsInfo);
        
        // use valid time of parent system or current time if none was set
        dsInfo = DataStoreUtils.ensureValidTime(systemStore, dsInfo);

        // create key
        var newKey = generateKey(dsInfo);

        // add to store
        put(newKey, dsInfo, false);
        return newKey;
    }
    
    
    protected DataStreamKey generateKey(IDataStreamInfo dsInfo)
    {
        return new DataStreamKey(idScope, idProvider.newInternalID(dsInfo));
    }


    @Override
    public IDataStreamInfo get(Object key)
    {
        var dsKey = DataStoreUtils.checkDataStreamKey(key);
        
        var dsInfo = dataStreamIndex.get(dsKey);
        if (dsInfo == null)
            return null;
        
        return new DataStreamInfoWithTimeRanges(dsKey.getInternalID().getIdAsLong(), dsInfo);
    }


    Stream<Long> getDataStreamIdsBySystem(long sysID, Set<String> outputNames, TemporalFilter validTime)
    {
        MVTimeSeriesSystemKey first = new MVTimeSeriesSystemKey(sysID, "", Instant.MIN);
        RangeCursor<MVTimeSeriesSystemKey, Boolean> cursor = new RangeCursor<>(dataStreamBySystemIndex, first);

        Stream<MVTimeSeriesSystemKey> keyStream = cursor.keyStream()
            .takeWhile(k -> k.systemID == sysID);

        // we post filter output names and versions during the scan
        // since number of outputs and versions is usually small, this should
        // be faster than looking up each output separately
        return postFilterKeyStream(keyStream, outputNames, validTime)
            .map(k -> k.internalID);
    }


    Stream<Long> getDataStreamIdsFromAllSystems(Set<String> outputNames, TemporalFilter validTime)
    {
        Stream<MVTimeSeriesSystemKey> keyStream = dataStreamBySystemIndex.keySet().stream();

        // yikes we're doing a full index scan here!
        return postFilterKeyStream(keyStream, outputNames, validTime)
            .map(k -> k.internalID);
    }


    Stream<MVTimeSeriesSystemKey> postFilterKeyStream(Stream<MVTimeSeriesSystemKey> keyStream, Set<String> outputNames, TemporalFilter validTime)
    {
        if (outputNames != null)
            keyStream = keyStream.filter(k -> outputNames.contains(k.signalName));

        if (validTime != null)
        {
            // handle special case of current time & latest time
            long filterStartTime, filterEndTime;
            if (validTime.isCurrentTime()) {
                filterStartTime = filterEndTime = Instant.now().getEpochSecond();
            } else if (validTime.isLatestTime()) {
                filterStartTime = filterEndTime = Instant.MAX.getEpochSecond();
            } else {
                filterStartTime = validTime.getMin().getEpochSecond();
                filterEndTime = validTime.getMax().getEpochSecond();
            }
            
            // get all datastream with validStartTime within the filter range + 1 before
            // recall that datastreams are ordered in reverse valid time order
            Holder<MVTimeSeriesSystemKey> lastKey = new Holder<>();
            keyStream = keyStream
                .filter(k -> {
                    MVTimeSeriesSystemKey saveLastKey = lastKey.value;
                    lastKey.value = k;
                    
                    if (k.validStartTime > filterEndTime)
                        return false;
                    
                    if (k.validStartTime >= filterStartTime)
                        return true;
                    
                    if (saveLastKey == null ||
                        k.systemID != saveLastKey.systemID ||
                        !k.signalName.equals(saveLastKey.signalName) ||
                        saveLastKey.validStartTime > filterStartTime) {
                        return true;
                    }
                    
                    return false;
                });
        }

        return keyStream;
    }


    @Override
    public Stream<Entry<DataStreamKey, IDataStreamInfo>> selectEntries(DataStreamFilter filter, Set<DataStreamInfoField> fields)
    {
        Stream<Long> idStream = null;
        Stream<Entry<DataStreamKey, IDataStreamInfo>> resultStream;
        boolean fullTextFilterApplied = false;
        
        // if filtering by internal IDs, use these IDs directly
        if (filter.getInternalIDs() != null)
        {
            idStream = filter.getInternalIDs().stream()
                .map(id -> id.getIdAsLong());
        }

        // if system filter is used
        else if (filter.getSystemFilter() != null)
        {
            // first select systems and fetch corresponding datastreams
            idStream = DataStoreUtils.selectSystemIDs(systemStore, filter.getSystemFilter())
                .flatMap(id -> getDataStreamIdsBySystem(id.getIdAsLong(), filter.getOutputNames(), filter.getValidTimeFilter()));
        }

        // if observation filter is used
        else if (filter.getObservationFilter() != null)
        {
            // get all data stream IDs referenced by observations matching the filter
            idStream = obsStore.selectObsSeries(filter.getObservationFilter())
                .map(s -> s.key.dataStreamID);
        }
        
        // if full-text filter is used, use full-text index as primary
        else if (filter.getFullTextFilter() != null)
        {
            idStream = fullTextIndex.selectKeys(filter.getFullTextFilter());
            fullTextFilterApplied = true;
        }

        // else filter data stream only by output name and valid time
        else
        {
            idStream = getDataStreamIdsFromAllSystems(filter.getOutputNames(), filter.getValidTimeFilter());
        }

        resultStream = idStream
            .map(id -> dataStreamIndex.getEntry(new DataStreamKey(idScope, id)))
            .filter(Objects::nonNull);
        
        if (filter.getFullTextFilter() != null && !fullTextFilterApplied)
            resultStream = resultStream.filter(e -> filter.testFullText(e.getValue()));
        
        if (filter.getObservedProperties() != null)
            resultStream = resultStream.filter(e -> filter.testObservedProperty(e.getValue()));

        if (filter.getValuePredicate() != null)
            resultStream = resultStream.filter(e -> filter.testValuePredicate(e.getValue()));

        // apply limit
        if (filter.getLimit() < Long.MAX_VALUE)
            resultStream = resultStream.limit(filter.getLimit());

        // always wrap with dynamic datastream object
        resultStream = resultStream.map(e -> {
            return new DataUtils.MapEntry<DataStreamKey, IDataStreamInfo>(
                e.getKey(),
                new DataStreamInfoWithTimeRanges(e.getKey().getInternalID().getIdAsLong(), e.getValue())
            ); 
        });
        
        // apply time filter once validTime is computed correctly
        if (filter.getValidTimeFilter() != null)
            resultStream = resultStream.filter(e -> filter.testValidTime(e.getValue()));
        
        return resultStream;
    }


    @Override
    public IDataStreamInfo put(DataStreamKey key, IDataStreamInfo dsInfo)
    {
        DataStoreUtils.checkDataStreamKey(key);
        try {
            
            DataStoreUtils.checkDataStreamInfo(systemStore, dsInfo);
            return put(key, dsInfo, true);
        }
        catch (DataStoreException e) 
        {
            throw new IllegalArgumentException(e);
        }
    }
    
    
    protected synchronized IDataStreamInfo put(DataStreamKey key, IDataStreamInfo dsInfo, boolean replace) throws DataStoreException
    {
        var dsID = key.getInternalID().getIdAsLong();
        
        // store current version so we can rollback if an error occurs
        long currentVersion = mvStore.getCurrentVersion();

        try
        {
            // add to main index
            var oldValue = dataStreamIndex.put(key, dsInfo);
            
            // check if we're allowed to replace existing entry
            boolean isNewEntry = (oldValue == null);
            if (!isNewEntry && !replace)
                throw new DataStoreException(DataStoreUtils.ERROR_EXISTING_DATASTREAM);
            
            // update sys/output index
            // remove old entry if needed
            if (oldValue != null && replace)
            {
                MVTimeSeriesSystemKey procKey = new MVTimeSeriesSystemKey(dsID,
                    oldValue.getSystemID().getInternalID().getIdAsLong(),
                    oldValue.getOutputName(),
                    oldValue.getValidTime().begin().getEpochSecond());
                dataStreamBySystemIndex.remove(procKey);
            }

            // add new entry
            MVTimeSeriesSystemKey procKey = new MVTimeSeriesSystemKey(dsID,
                dsInfo.getSystemID().getInternalID().getIdAsLong(),
                dsInfo.getOutputName(),
                dsInfo.getValidTime().begin().getEpochSecond());
            var oldProcKey = dataStreamBySystemIndex.put(procKey, Boolean.TRUE);
            if (oldProcKey != null && !replace)
                throw new DataStoreException(DataStoreUtils.ERROR_EXISTING_DATASTREAM);
            
            // update full-text index
            if (isNewEntry)
                fullTextIndex.add(dsID, dsInfo);
            else
                fullTextIndex.update(dsID, oldValue, dsInfo);
            
            return oldValue;
        }
        catch (Exception e)
        {
            mvStore.rollbackTo(currentVersion);
            throw e;
        }
    }


    @Override
    public synchronized IDataStreamInfo remove(Object key)
    {
        var dsKey = DataStoreUtils.checkDataStreamKey(key);
        var dsID = dsKey.getInternalID().getIdAsLong();

        // store current version so we can rollback if an error occurs
        long currentVersion = mvStore.getCurrentVersion();

        try
        {
            // remove all obs
            if (obsStore != null)
                obsStore.removeAllObsAndSeries(dsID);
            
            // remove from main index
            IDataStreamInfo oldValue = dataStreamIndex.remove(dsKey);
            if (oldValue == null)
                return null;

            // remove entry in secondary index
            dataStreamBySystemIndex.remove(new MVTimeSeriesSystemKey(
                oldValue.getSystemID().getInternalID().getIdAsLong(),
                oldValue.getOutputName(),
                oldValue.getValidTime().begin()));
            
            // remove from full-text index
            fullTextIndex.remove(dsID, oldValue);

            return oldValue;
        }
        catch (Exception e)
        {
            mvStore.rollbackTo(currentVersion);
            throw e;
        }
    }


    @Override
    public synchronized void clear()
    {
        // store current version so we can rollback if an error occurs
        long currentVersion = mvStore.getCurrentVersion();

        try
        {
            obsStore.clear();
            dataStreamBySystemIndex.clear();
            dataStreamIndex.clear();
        }
        catch (Exception e)
        {
            mvStore.rollbackTo(currentVersion);
            throw e;
        }
    }


    @Override
    public boolean containsKey(Object key)
    {
        var dsKey = DataStoreUtils.checkDataStreamKey(key);
        return dataStreamIndex.containsKey(dsKey);
    }


    @Override
    public boolean containsValue(Object val)
    {
        return dataStreamIndex.containsValue(val);
    }


    @Override
    public Set<Entry<DataStreamKey, IDataStreamInfo>> entrySet()
    {
        return dataStreamIndex.entrySet();
    }


    @Override
    public boolean isEmpty()
    {
        return dataStreamIndex.isEmpty();
    }


    @Override
    public Set<DataStreamKey> keySet()
    {
        return dataStreamIndex.keySet();
    }


    @Override
    public String getDatastoreName()
    {
        return obsStore.getDatastoreName();
    }


    @Override
    public long getNumRecords()
    {
        return dataStreamIndex.sizeAsLong();
    }


    @Override
    public int size()
    {
        return dataStreamIndex.size();
    }


    @Override
    public Collection<IDataStreamInfo> values()
    {
        return dataStreamIndex.values();
    }


    @Override
    public void commit()
    {
        obsStore.commit();
    }


    @Override
    public void backup(OutputStream is) throws IOException
    {
        throw new UnsupportedOperationException("Call backup on the parent observation store");
    }


    @Override
    public void restore(InputStream os) throws IOException
    {
        throw new UnsupportedOperationException("Call restore on the parent observation store");
    }


    @Override
    public boolean isReadOnly()
    {
        return mvStore.isReadOnly();
    }
    
    
    @Override
    public void linkTo(ISystemDescStore systemStore)
    {
        this.systemStore = Asserts.checkNotNull(systemStore, ISystemDescStore.class);
    }
}
