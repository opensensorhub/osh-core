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
import org.sensorhub.api.datastore.obs.IObsStore.ObsField;
import org.sensorhub.api.datastore.procedure.IProcedureStore;
import org.sensorhub.impl.datastore.DataStoreUtils;
import org.sensorhub.impl.datastore.h2.H2Utils.Holder;
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
    private static final String DATASTREAM_PROC_MAP_NAME = "datastreams_proc";
    private static final String DATASTREAM_FULLTEXT_MAP_NAME = "datastreams_text";

    protected MVStore mvStore;
    protected MVObsStoreImpl obsStore;
    protected IProcedureStore procedureStore;
    protected IdProvider<IDataStreamInfo> idProvider;
    
    /*
     * Main index
     */
    protected MVBTreeMap<DataStreamKey, IDataStreamInfo> dataStreamIndex;
    
    /*
     * Procedure/output index
     * Map of {procedure ID, output name, validTime} to datastream ID
     * Everything is stored in the key with no value (use MVVoidDataType for efficiency)
     */
    protected MVBTreeMap<MVTimeSeriesProcKey, Boolean> dataStreamByProcIndex;
    
    /*
     * Full text index pointing to main index
     * Key references are parentID/internalID pairs
     */
    protected FullTextIndex<IDataStreamInfo, Long> fullTextIndex;
    
    
    /*
     * DataStreamInfo object wrapper used to compute time ranges lazily
     */
    class DataStreamInfoWithTimeRanges extends DataStreamInfoWrapper
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
                    var procDsKey = new MVTimeSeriesProcKey(
                        getProcedureID().getInternalID(),
                        getOutputName(),
                        getValidTime().begin());
                    
                    var nextKey = dataStreamByProcIndex.lowerKey(procDsKey); // use lower cause time sorting is reversed
                    if (nextKey != null &&
                        nextKey.procedureID == procDsKey.procedureID &&
                        nextKey.signalName.equals(procDsKey.signalName))
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
        this.obsStore = Asserts.checkNotNull(obsStore, MVObsStoreImpl.class);
        this.mvStore = Asserts.checkNotNull(obsStore.mvStore, MVStore.class);
        
        // persistent class mappings for Kryo
        var kryoClassMap = mvStore.openMap(MVObsDatabase.KRYO_CLASS_MAP_NAME, new MVBTreeMap.Builder<String, Integer>());

        // open observation map
        String mapName = obsStore.getDatastoreName() + ":" + DATASTREAM_MAP_NAME;
        this.dataStreamIndex = mvStore.openMap(mapName, new MVBTreeMap.Builder<DataStreamKey, IDataStreamInfo>()
                .keyType(new MVDataStreamKeyDataType())
                .valueType(new DataStreamInfoDataType(kryoClassMap)));

        // open observation series map
        mapName = obsStore.getDatastoreName() + ":" + DATASTREAM_PROC_MAP_NAME;
        this.dataStreamByProcIndex = mvStore.openMap(mapName, new MVBTreeMap.Builder<MVTimeSeriesProcKey, Boolean>()
                .keyType(new MVTimeSeriesProcKeyDataType())
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

        // ID provider
        this.idProvider = idProvider;
        if (idProvider == null) // use default if nothing is set
        {
            this.idProvider = dsInfo -> {
                if (dataStreamIndex.isEmpty())
                    return 1;
                else
                    return dataStreamIndex.lastKey().getInternalID()+1;
            };
        }
    }
    
    
    @Override
    public synchronized DataStreamKey add(IDataStreamInfo dsInfo) throws DataStoreException
    {
        DataStoreUtils.checkDataStreamInfo(procedureStore, dsInfo);
        
        // use valid time of parent procedure or current time if none was set
        dsInfo = DataStoreUtils.ensureValidTime(procedureStore, dsInfo);

        // create key
        var newKey = generateKey(dsInfo);

        // add to store
        put(newKey, dsInfo, false);
        return newKey;
    }
    
    
    protected DataStreamKey generateKey(IDataStreamInfo dsInfo)
    {
        return new DataStreamKey(idProvider.newInternalID(dsInfo));
    }


    @Override
    public IDataStreamInfo get(Object key)
    {
        var dsKey = DataStoreUtils.checkDataStreamKey(key);
        
        var dsInfo = dataStreamIndex.get(dsKey);
        if (dsInfo == null)
            return null;
        
        return new DataStreamInfoWithTimeRanges(dsKey.getInternalID(), dsInfo);
    }


    Stream<Long> getDataStreamIdsByProcedure(long procID, Set<String> outputNames, TemporalFilter validTime)
    {
        MVTimeSeriesProcKey first = new MVTimeSeriesProcKey(procID, "", Instant.MIN);
        RangeCursor<MVTimeSeriesProcKey, Boolean> cursor = new RangeCursor<>(dataStreamByProcIndex, first);

        Stream<MVTimeSeriesProcKey> keyStream = cursor.keyStream()
            .takeWhile(k -> k.procedureID == procID);

        // we post filter output names and versions during the scan
        // since number of outputs and versions is usually small, this should
        // be faster than looking up each output separately
        return postFilterKeyStream(keyStream, outputNames, validTime)
            .map(k -> k.internalID);
    }


    Stream<Long> getDataStreamIdsFromAllProcedures(Set<String> outputNames, TemporalFilter validTime)
    {
        Stream<MVTimeSeriesProcKey> keyStream = dataStreamByProcIndex.keySet().stream();

        // yikes we're doing a full index scan here!
        return postFilterKeyStream(keyStream, outputNames, validTime)
            .map(k -> k.internalID);
    }


    Stream<MVTimeSeriesProcKey> postFilterKeyStream(Stream<MVTimeSeriesProcKey> keyStream, Set<String> outputNames, TemporalFilter validTime)
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
            Holder<MVTimeSeriesProcKey> lastKey = new Holder<>();
            keyStream = keyStream
                .filter(k -> {
                    MVTimeSeriesProcKey saveLastKey = lastKey.value;
                    lastKey.value = k;
                                            
                    if (k.validStartTime > filterEndTime)
                        return false;
                    
                    if (k.validStartTime >= filterStartTime)
                        return true;
                    
                    if (saveLastKey == null ||
                        k.procedureID != saveLastKey.procedureID ||
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
            idStream = filter.getInternalIDs().stream();
        }

        // if procedure filter is used
        else if (filter.getProcedureFilter() != null)
        {
            // first select procedures and fetch corresponding datastreams
            idStream = DataStoreUtils.selectProcedureIDs(procedureStore, filter.getProcedureFilter())
                .flatMap(id -> getDataStreamIdsByProcedure(id, filter.getOutputNames(), filter.getValidTimeFilter()));            
        }

        // if observation filter is used
        else if (filter.getObservationFilter() != null)
        {
            // get all data stream IDs referenced by observations matching the filter
            idStream = obsStore.select(filter.getObservationFilter(), ObsField.DATASTREAM_ID)
                .map(obs -> obs.getDataStreamID());
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
            idStream = getDataStreamIdsFromAllProcedures(filter.getOutputNames(), filter.getValidTimeFilter());
        }

        resultStream = idStream
            .map(id -> dataStreamIndex.getEntry(new DataStreamKey(id)))
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
                new DataStreamInfoWithTimeRanges(e.getKey().getInternalID(), e.getValue())
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
            
            DataStoreUtils.checkDataStreamInfo(procedureStore, dsInfo);
            return put(key, dsInfo, true);
        }
        catch (DataStoreException e) 
        {
            throw new IllegalArgumentException(e);
        }
    }
    
    
    protected synchronized IDataStreamInfo put(DataStreamKey key, IDataStreamInfo dsInfo, boolean replace) throws DataStoreException
    {
        // synchronize on MVStore to avoid autocommit in the middle of things
        synchronized (mvStore)
        {
            long currentVersion = mvStore.getCurrentVersion();

            try
            {
                // add to main index
                IDataStreamInfo oldValue = dataStreamIndex.put(key, dsInfo);
                
                // check if we're allowed to replace existing entry
                boolean isNewEntry = (oldValue == null);
                if (!isNewEntry && !replace)
                    throw new DataStoreException(DataStoreUtils.ERROR_EXISTING_DATASTREAM);
                
                // update proc/output index
                // remove old entry if needed
                if (oldValue != null && replace)
                {
                    MVTimeSeriesProcKey procKey = new MVTimeSeriesProcKey(key.getInternalID(),
                        oldValue.getProcedureID().getInternalID(),
                        oldValue.getOutputName(),
                        oldValue.getValidTime().begin().getEpochSecond());
                    dataStreamByProcIndex.remove(procKey);
                }

                // add new entry
                MVTimeSeriesProcKey procKey = new MVTimeSeriesProcKey(key.getInternalID(),
                    dsInfo.getProcedureID().getInternalID(),
                    dsInfo.getOutputName(),
                    dsInfo.getValidTime().begin().getEpochSecond());
                var oldProcKey = dataStreamByProcIndex.put(procKey, Boolean.TRUE);
                if (oldProcKey != null && !replace)
                    throw new DataStoreException(DataStoreUtils.ERROR_EXISTING_DATASTREAM);
                
                // update full-text index
                if (isNewEntry)
                    fullTextIndex.add(key.getInternalID(), dsInfo);
                else
                    fullTextIndex.update(key.getInternalID(), oldValue, dsInfo);
                
                return oldValue;
            }
            catch (Exception e)
            {
                mvStore.rollbackTo(currentVersion);
                throw e;
            }
        }
    }


    @Override
    public synchronized IDataStreamInfo remove(Object key)
    {
        var dsKey = DataStoreUtils.checkDataStreamKey(key);

        // synchronize on MVStore to avoid autocommit in the middle of things
        synchronized (mvStore)
        {
            long currentVersion = mvStore.getCurrentVersion();

            try
            {
                // remove all obs
                if (obsStore != null)
                    obsStore.removeAllObsAndSeries(dsKey.getInternalID());
                
                // remove from main index
                IDataStreamInfo oldValue = dataStreamIndex.remove(dsKey);
                if (oldValue == null)
                    return null;

                // remove entry in secondary index
                dataStreamByProcIndex.remove(new MVTimeSeriesProcKey(
                    oldValue.getProcedureID().getInternalID(),
                    oldValue.getName(),
                    oldValue.getValidTime().begin()));
                
                // remove from full-text index
                fullTextIndex.remove(dsKey.getInternalID(), oldValue);

                return oldValue;
            }
            catch (Exception e)
            {
                mvStore.rollbackTo(currentVersion);
                throw e;
            }
        }
    }


    @Override
    public synchronized void clear()
    {
        // synchronize on MVStore to avoid autocommit in the middle of things
        synchronized (mvStore)
        {
            long currentVersion = mvStore.getCurrentVersion();

            try
            {
                obsStore.clear();
                dataStreamByProcIndex.clear();
                dataStreamIndex.clear();
            }
            catch (Exception e)
            {
                mvStore.rollbackTo(currentVersion);
                throw e;
            }
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
    public void linkTo(IProcedureStore procedureStore)
    {
        this.procedureStore = Asserts.checkNotNull(procedureStore, IProcedureStore.class);
    }
}
