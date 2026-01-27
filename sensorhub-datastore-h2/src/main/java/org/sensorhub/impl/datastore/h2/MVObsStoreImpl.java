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
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.h2.mvstore.MVBTreeMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.RangeCursor;
import org.sensorhub.api.command.ICommandData;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.data.IObsData;
import org.sensorhub.api.datastore.feature.IFoiStore;
import org.sensorhub.api.datastore.obs.DataStreamKey;
import org.sensorhub.api.datastore.obs.IDataStreamStore;
import org.sensorhub.api.datastore.obs.IObsStore;
import org.sensorhub.api.datastore.obs.ObsFilter;
import org.sensorhub.api.datastore.obs.ObsStats;
import org.sensorhub.api.datastore.obs.ObsStatsQuery;
import org.sensorhub.api.feature.FeatureId;
import org.sensorhub.impl.datastore.DataStoreUtils;
import org.sensorhub.impl.datastore.MergeSortSpliterator;
import org.sensorhub.impl.datastore.h2.MVDatabaseConfig.IdProviderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.util.Asserts;
import org.vast.util.TimeExtent;
import com.google.common.collect.Range;
import net.opengis.swe.v20.DataBlock;


/**
 * <p>
 * Implementation of obs store based on H2 MVStore.
 * </p><p>
 * Several instances of this store can be contained in the same MVStore
 * as long as they have different names.
 * </p>
 *
 * @author Alex Robin
 * @date Apr 7, 2018
 */
public class MVObsStoreImpl implements IObsStore
{
    static Logger logger = LoggerFactory.getLogger(MVObsStoreImpl.class);
    
    private static final String OBS_RECORDS_MAP_NAME = "obs_records";
    private static final String OBS_SERIES_MAP_NAME = "obs_series";
    private static final String OBS_SERIES_FOI_MAP_NAME = "obs_series_foi";
    
    protected MVStore mvStore;
    protected MVDataStoreInfo dataStoreInfo;
    protected MVDataStreamStoreImpl dataStreamStore;
    protected MVBTreeMap<MVTimeSeriesRecordKey, IObsData> obsRecordsIndex;
    protected MVBTreeMap<MVTimeSeriesKey, MVTimeSeriesInfo> obsSeriesMainIndex;
    protected MVBTreeMap<MVTimeSeriesKey, Boolean> obsSeriesByFoiIndex;
    protected int idScope;
    
    protected IFoiStore foiStore;
    protected int maxOrderedSeries = 20000;
    
    
    static class TimeParams
    {
        Range<Instant> phenomenonTimeRange;
        Range<Instant> resultTimeRange;
        boolean currentTimeOnly;
        boolean latestResultOnly;
        
        
        TimeParams(ObsFilter filter)
        {
            // get phenomenon time range
            phenomenonTimeRange = filter.getPhenomenonTime() != null ?
                filter.getPhenomenonTime().getRange() : H2Utils.ALL_TIMES_RANGE;
            
            // get result time range
            resultTimeRange = filter.getResultTime() != null ?
                filter.getResultTime().getRange() : H2Utils.ALL_TIMES_RANGE;
                
            latestResultOnly = filter.getResultTime() != null && filter.getResultTime().isLatestTime();
            currentTimeOnly = filter.getPhenomenonTime() != null && filter.getPhenomenonTime().isCurrentTime();
        }
    }
    
    
    private MVObsStoreImpl()
    {
    }


    /**
     * Opens an existing obs store or create a new one with the specified info
     * @param mvStore MVStore instance containing the required maps
     * @param idScope Internal ID scope (database num)
     * @param dsIdProviderType Type of ID provider to use to generate new datastream IDs
     * @param newStoreInfo Data store info to use if a new store needs to be created
     * @return The existing datastore instance 
     */
    public static MVObsStoreImpl open(MVStore mvStore, int idScope, IdProviderType dsIdProviderType, MVDataStoreInfo newStoreInfo)
    {
        var dataStoreInfo = H2Utils.getDataStoreInfo(mvStore, newStoreInfo.getName());
        if (dataStoreInfo == null)
        {
            dataStoreInfo = newStoreInfo;
            H2Utils.addDataStoreInfo(mvStore, dataStoreInfo);
        }
        
        return new MVObsStoreImpl().init(mvStore, idScope, dsIdProviderType, dataStoreInfo);
    }
    
    
    private MVObsStoreImpl init(MVStore mvStore, int idScope, IdProviderType dsIdProviderType, MVDataStoreInfo dataStoreInfo)
    {
        this.mvStore = Asserts.checkNotNull(mvStore, MVStore.class);
        this.dataStoreInfo = Asserts.checkNotNull(dataStoreInfo, MVDataStoreInfo.class);
        this.idScope = idScope;
        this.dataStreamStore = new MVDataStreamStoreImpl(this, dsIdProviderType);
        
        // persistent class mappings for Kryo
        var kryoClassMap = mvStore.openMap(MVObsSystemDatabase.KRYO_CLASS_MAP_NAME, new MVBTreeMap.Builder<String, Integer>());
        
        // open observation map
        String mapName = dataStoreInfo.getName() + ":" + OBS_RECORDS_MAP_NAME;
        this.obsRecordsIndex = mvStore.openMap(mapName, new MVBTreeMap.Builder<MVTimeSeriesRecordKey, IObsData>()
                .keyType(new MVTimeSeriesRecordKeyDataType(idScope))
                .valueType(new ObsDataType(kryoClassMap, idScope)));
        
        // open observation series map
        mapName = dataStoreInfo.getName() + ":" + OBS_SERIES_MAP_NAME;
        this.obsSeriesMainIndex = mvStore.openMap(mapName, new MVBTreeMap.Builder<MVTimeSeriesKey, MVTimeSeriesInfo>()
                .keyType(new MVObsSeriesKeyByDataStreamDataType())
                .valueType(new MVTimeSeriesInfoDataType()));
        
        mapName = dataStoreInfo.getName() + ":" + OBS_SERIES_FOI_MAP_NAME;
        this.obsSeriesByFoiIndex = mvStore.openMap(mapName, new MVBTreeMap.Builder<MVTimeSeriesKey, Boolean>()
                .keyType(new MVObsSeriesKeyByFoiDataType())
                .valueType(new MVVoidDataType()));
        
        return this;
    }


    @Override
    public String getDatastoreName()
    {
        return dataStoreInfo.getName();
    }
    

    @Override
    public IDataStreamStore getDataStreams()
    {
        return dataStreamStore;
    }


    @Override
    public long getNumRecords()
    {
        return obsRecordsIndex.sizeAsLong();
    }
    
    
    Stream<MVTimeSeriesInfo> getAllObsSeries(Range<Instant> resultTimeRange)
    {
        MVTimeSeriesKey first = new MVTimeSeriesKey(0, 0, resultTimeRange.lowerEndpoint());
        MVTimeSeriesKey last = new MVTimeSeriesKey(Long.MAX_VALUE, Long.MAX_VALUE, resultTimeRange.upperEndpoint());
        RangeCursor<MVTimeSeriesKey, MVTimeSeriesInfo> cursor = new RangeCursor<>(obsSeriesMainIndex, first, last);
        
        return cursor.entryStream()
            .filter(e -> resultTimeRange.contains(e.getKey().resultTime))
            .map(e -> {
                e.getValue().key = e.getKey();
                return e.getValue();
            });
    }
    
    
    Stream<MVTimeSeriesInfo> getObsSeriesByDataStream(long dataStreamID, Range<Instant> resultTimeRange, boolean latestResultOnly)
    {
        // special case when latest result is requested
        if (latestResultOnly)
        {
            MVTimeSeriesKey key = new MVTimeSeriesKey(dataStreamID, Long.MAX_VALUE, Instant.MAX);
            MVTimeSeriesKey lastKey = obsSeriesMainIndex.floorKey(key);
            if (lastKey == null || lastKey.dataStreamID != dataStreamID)
                return null;
            resultTimeRange = Range.singleton(lastKey.resultTime);
        }
       
        // scan series for all FOIs of the selected system and result times
        MVTimeSeriesKey first = new MVTimeSeriesKey(dataStreamID, 0, Instant.MIN);
        MVTimeSeriesKey last = new MVTimeSeriesKey(dataStreamID, Long.MAX_VALUE, resultTimeRange.upperEndpoint());
        RangeCursor<MVTimeSeriesKey, MVTimeSeriesInfo> cursor = new RangeCursor<>(obsSeriesMainIndex, first, last);
        
        final Range<Instant> finalResultTimeRange = resultTimeRange;
        return cursor.entryStream()
            .filter(e -> {
                // filter out series with result time not matching filter
                // but always select series that have multiple result times (e.g. result time = phenomenonTime)
                var resultTime = e.getKey().resultTime;
                return resultTime == Instant.MIN || finalResultTimeRange.contains(resultTime);
            })
            .map(e -> {
                MVTimeSeriesInfo series = e.getValue();
                series.key = e.getKey();
                return series;
            });
    }
    
    
    Stream<MVTimeSeriesInfo> getObsSeriesByFoi(long foiID, Range<Instant> resultTimeRange, boolean latestResultOnly)
    {
        // special case when latest result is requested
        if (latestResultOnly)
        {
            MVTimeSeriesKey key = new MVTimeSeriesKey(Long.MAX_VALUE, foiID, Instant.MAX);
            MVTimeSeriesKey lastKey = obsSeriesByFoiIndex.floorKey(key);
            if (lastKey == null || lastKey.foiID != foiID)
                return Stream.empty();
            resultTimeRange = Range.singleton(lastKey.resultTime);
        }
       
        // scan series for all systems that produced observations of the selected FOI
        MVTimeSeriesKey first = new MVTimeSeriesKey(0, foiID, Instant.MIN);
        MVTimeSeriesKey last = new MVTimeSeriesKey(Long.MAX_VALUE, foiID, resultTimeRange.upperEndpoint());
        RangeCursor<MVTimeSeriesKey, Boolean> cursor = new RangeCursor<>(obsSeriesByFoiIndex, first, last);
        
        final Range<Instant> finalResultTimeRange = resultTimeRange;
        return cursor.keyStream()
            .filter(k -> {
                // filter out series with result time not matching filter
                // but always select series that have multiple result times (e.g. result time = phenomenonTime)
                var resultTime = k.resultTime;
                return resultTime == Instant.MIN || finalResultTimeRange.contains(resultTime);
            })
            .map(k -> {
                MVTimeSeriesInfo series = obsSeriesMainIndex.get(k);
                if (series != null)
                    series.key = k;
                return series;
            })
            .filter(Objects::nonNull);
    }
    
    
    RangeCursor<MVTimeSeriesRecordKey, IObsData> getObsCursor(long seriesID, Range<Instant> phenomenonTimeRange)
    {
        MVTimeSeriesRecordKey first = new MVTimeSeriesRecordKey(seriesID, phenomenonTimeRange.lowerEndpoint());
        MVTimeSeriesRecordKey last = new MVTimeSeriesRecordKey(seriesID, phenomenonTimeRange.upperEndpoint());
        return new RangeCursor<>(obsRecordsIndex, first, last);
    }
    
    
    Stream<Entry<MVTimeSeriesRecordKey, IObsData>> getObsStream(MVTimeSeriesInfo series, Range<Instant> resultTimeRange, Range<Instant> phenomenonTimeRange, boolean currentTimeOnly, boolean latestResultOnly)
    {
        // if series is a special case where all obs have resultTime = phenomenonTime
        if (series.key.resultTime == Instant.MIN)
        {
            // if request is for current time only, get only the obs with
            // phenomenon time right before current time
            if (currentTimeOnly)
            {
                MVTimeSeriesRecordKey maxKey = new MVTimeSeriesRecordKey(series.id, Instant.now());
                Entry<MVTimeSeriesRecordKey, IObsData> e = obsRecordsIndex.floorEntry(maxKey);
                if (e != null && e.getKey().seriesID == series.id)
                    return Stream.of(e);
                else
                    return Stream.empty();
            }
            
            // if request if for latest result only, get only the latest obs in series
            if (latestResultOnly)
            {
                MVTimeSeriesRecordKey maxKey = new MVTimeSeriesRecordKey(series.id, Instant.MAX);
                Entry<MVTimeSeriesRecordKey, IObsData> e = obsRecordsIndex.floorEntry(maxKey);
                if (e != null && e.getKey().seriesID == series.id)
                    return Stream.of(e);
                else
                    return Stream.empty();
            }
            
            // else further restrict the requested time range using result time filter
            phenomenonTimeRange = resultTimeRange.intersection(phenomenonTimeRange);
        }
        
        // scan using a cursor on main obs index
        // recreating full entries in the process
        RangeCursor<MVTimeSeriesRecordKey, IObsData> cursor = getObsCursor(series.id, phenomenonTimeRange);
        return cursor.entryStream();
    }
    
    
    MVTimeSeriesRecordKey toInternalKey(Object keyObj)
    {
        Asserts.checkArgument(keyObj instanceof BigId, "key must be a BigId");
        BigId key = (BigId)keyObj;

        try
        {
            // parse from BigId bytes
            ByteBuffer buf = ByteBuffer.wrap(key.getIdAsBytes());
            return MVTimeSeriesRecordKeyDataType.decode(idScope, buf);
        }
        catch (Exception e)
        {
            // invalid bigint key
            return null;
        }
    }
    
    
    public Stream<MVTimeSeriesInfo> selectObsSeries(ObsFilter filter)
    {
        var timeParams = new TimeParams(filter);
        return selectObsSeries(filter, timeParams);
    }
    
    
    /*
     * Select all obs series matching the filter
     */
    protected Stream<MVTimeSeriesInfo> selectObsSeries(ObsFilter filter, TimeParams timeParams)
    {
        // otherwise prepare stream of matching obs series
        Stream<MVTimeSeriesInfo> obsSeries = null;
        
        // if no datastream nor FOI filter used, scan all obs
        if (filter.getDataStreamFilter() == null && filter.getFoiFilter() == null)
        {
            obsSeries = getAllObsSeries(timeParams.resultTimeRange);
        }
        
        // only datastream filter used
        else if (filter.getDataStreamFilter() != null && filter.getFoiFilter() == null)
        {
            // stream directly from list of selected datastreams
            obsSeries = DataStoreUtils.selectDataStreamIDs(dataStreamStore, filter.getDataStreamFilter())
                .flatMap(id -> getObsSeriesByDataStream(id.getIdAsLong(), timeParams.resultTimeRange, timeParams.latestResultOnly));
        }
        
        // only FOI filter used
        else if (filter.getFoiFilter() != null && filter.getDataStreamFilter() == null)
        {
            // stream directly from list of selected fois
            obsSeries = DataStoreUtils.selectFeatureIDs(foiStore, filter.getFoiFilter())
                .flatMap(id -> getObsSeriesByFoi(id.getIdAsLong(), timeParams.resultTimeRange, timeParams.latestResultOnly));
        }
        
        // both datastream and FOI filters used
        else
        {
            // create set of selected datastreams
            AtomicInteger counter = new AtomicInteger();
            var dataStreamIDs = DataStoreUtils.selectDataStreamIDs(dataStreamStore, filter.getDataStreamFilter())
                .peek(s -> {
                    // make sure set size cannot go over a threshold
                    if (counter.incrementAndGet() >= 100*maxOrderedSeries)
                        throw new IllegalStateException("Too many datastreams selected. Please refine your filter");
                })
                .map(id -> id.getIdAsLong())
                .collect(Collectors.toSet());

            if (dataStreamIDs.isEmpty())
                return Stream.empty();
            
            // stream from fois and filter on datastream IDs
            obsSeries = DataStoreUtils.selectFeatureIDs(foiStore, filter.getFoiFilter())
                .flatMap(id -> {
                    return getObsSeriesByFoi(id.getIdAsLong(), timeParams.resultTimeRange, timeParams.latestResultOnly)
                        .filter(s -> dataStreamIDs.contains(s.key.dataStreamID));
                });
        }
        
        return obsSeries;
    }


    @Override
    public Stream<Entry<BigId, IObsData>> selectEntries(ObsFilter filter, Set<ObsField> fields)
    {        
        // stream obs directly in case of filtering by internal IDs
        if (filter.getInternalIDs() != null)
        {
            var obsStream = filter.getInternalIDs().stream()
                .map(k -> toInternalKey(k))
                .filter(Objects::nonNull)
                .map(k -> obsRecordsIndex.getEntry(k))
                .filter(Objects::nonNull);
            
            return getPostFilteredResultStream(obsStream, filter);
        }
        
        // select obs series matching the filter
        var timeParams = new TimeParams(filter);
        var numSeries = (int)selectObsSeries(filter, timeParams).count();
        
        // if too many series selected, don't try to order by time
        // just get one record per series alternatively
        if (numSeries > maxOrderedSeries)
        {
            logger.warn("Query hits a large number of observation series: time sorting disabled");
            return Stream.iterate(0, i -> i++)
                .flatMap(i -> {
                    return selectObsSeries(filter, timeParams)
                        .flatMap(series -> {
                            var obsStream = getObsStream(series, 
                                timeParams.resultTimeRange,
                                timeParams.phenomenonTimeRange,
                                timeParams.currentTimeOnly,
                                timeParams.latestResultOnly);
                            return getPostFilteredResultStream(obsStream, filter).skip(i).limit(1);
                        }); 
                })
                .limit(filter.getLimit());
        }
        
        // otherwise order by phenomenon time
        else
        {
            var obsSeries = selectObsSeries(filter, timeParams);
            final var obsStreams = new ArrayList<Stream<Entry<BigId, IObsData>>>(numSeries);
            
            // create obs streams for each selected series
            // and keep all spliterators in array list
            obsSeries.forEach(series -> {
                    var obsStream = getObsStream(series, 
                        timeParams.resultTimeRange,
                        timeParams.phenomenonTimeRange,
                        timeParams.currentTimeOnly,
                        timeParams.latestResultOnly);
                    obsStreams.add(getPostFilteredResultStream(obsStream, filter));
                });
            
            if (obsStreams.isEmpty())
                return Stream.empty();
            
            // TODO group by result time when series with different result times are selected

            Comparator<Entry<BigId, IObsData>> comparator = Comparator.comparing(e -> e.getValue().getPhenomenonTime());
            if (filter.getPhenomenonTime() != null && filter.getPhenomenonTime().descendingOrder())
                comparator = comparator.reversed();
            
            // stream and merge obs from all selected datastreams and time periods
            var mergeSortIt = new MergeSortSpliterator<Entry<BigId, IObsData>>(obsStreams, comparator);
            
            // stream output of merge sort iterator + apply limit
            return StreamSupport.stream(mergeSortIt, false)
                .limit(filter.getLimit())
                .onClose(() -> mergeSortIt.close());
        }
    }
    
    
    Stream<Entry<BigId, IObsData>> getPostFilteredResultStream(Stream<Entry<MVTimeSeriesRecordKey, IObsData>> resultStream, ObsFilter filter)
    {
        if (filter.getValuePredicate() != null)
            resultStream = resultStream.filter(e -> filter.testValuePredicate(e.getValue()));
        
        // casting is ok since keys are subtypes of BigId
        @SuppressWarnings({ "unchecked" })
        var castedStream = (Stream<Entry<BigId, IObsData>>)(Stream<?>)resultStream;
        return castedStream;
    }
    
    
    @Override
    public Stream<BigId> selectObservedFois(ObsFilter filter)
    {
        var timeParams = new TimeParams(filter);
        
        if (filter.getDataStreamFilter() != null)
        {
            return DataStoreUtils.selectDataStreamIDs(dataStreamStore, filter.getDataStreamFilter())
                .flatMap(dsID -> {
                    return getObsSeriesByDataStream(dsID.getIdAsLong(), timeParams.resultTimeRange, timeParams.latestResultOnly)
                        .filter(s -> {
                            if (s.key.foiID == 0) // skip if no FOI
                                return false;
                            var timeRange = getObsSeriesPhenomenonTimeRange(s.id);
                            return timeRange != null && timeRange.isConnected(timeParams.phenomenonTimeRange);
                        })
                        .map(s -> BigId.fromLong(idScope, s.key.foiID))
                        .distinct();
                });
        }
        
        return IObsStore.super.selectObservedFois(filter);
    }
    

    @Override
    public Stream<DataBlock> selectResults(ObsFilter filter)
    {
        return select(filter).map(obs -> obs.getResult());
    }
        
    
    TimeExtent getDataStreamResultTimeRange(long dataStreamID)
    {
        MVTimeSeriesKey firstKey = obsSeriesMainIndex.ceilingKey(new MVTimeSeriesKey(dataStreamID, 0, Instant.MIN));
        MVTimeSeriesKey lastKey = obsSeriesMainIndex.floorKey(new MVTimeSeriesKey(dataStreamID, Long.MAX_VALUE, Instant.MAX));
        
        if (firstKey == null || lastKey == null)
            return null;
        else if (firstKey.resultTime == Instant.MIN)
            return getDataStreamPhenomenonTimeRange(dataStreamID);
        else            
            return TimeExtent.period(firstKey.resultTime, lastKey.resultTime);
    }
    
    
    TimeExtent getDataStreamPhenomenonTimeRange(long dataStreamID)
    {
        Instant[] timeRange = new Instant[] {Instant.MAX, Instant.MIN};
        getObsSeriesByDataStream(dataStreamID, H2Utils.ALL_TIMES_RANGE, false)
            .forEach(s -> {
                var seriesTimeRange = getObsSeriesPhenomenonTimeRange(s.id);
                if (seriesTimeRange == null)
                    return;
                
                if (timeRange[0].isAfter(seriesTimeRange.lowerEndpoint()))
                    timeRange[0] = seriesTimeRange.lowerEndpoint();
                if (timeRange[1].isBefore(seriesTimeRange.upperEndpoint()))
                    timeRange[1] = seriesTimeRange.upperEndpoint();
            });
        
        if (timeRange[0] == Instant.MAX || timeRange[1] == Instant.MIN)
            return null;
        else
            return TimeExtent.period(timeRange[0], timeRange[1]);
    }
    
    
    Range<Instant> getObsSeriesPhenomenonTimeRange(long seriesID)
    {
        MVTimeSeriesRecordKey firstKey = obsRecordsIndex.ceilingKey(new MVTimeSeriesRecordKey(seriesID, Instant.MIN));
        MVTimeSeriesRecordKey lastKey = obsRecordsIndex.floorKey(new MVTimeSeriesRecordKey(seriesID, Instant.MAX));
        
        if (firstKey == null || lastKey == null ||
            firstKey.seriesID != seriesID || lastKey.seriesID != seriesID)
            return null;
        else
            return Range.closed(firstKey.timeStamp, lastKey.timeStamp);
    }
    
    
    long getObsSeriesCount(long seriesID, Range<Instant> phenomenonTimeRange)
    {
        MVTimeSeriesRecordKey firstKey = obsRecordsIndex.ceilingKey(new MVTimeSeriesRecordKey(seriesID, phenomenonTimeRange.lowerEndpoint()));
        MVTimeSeriesRecordKey lastKey = obsRecordsIndex.floorKey(new MVTimeSeriesRecordKey(seriesID, phenomenonTimeRange.upperEndpoint()));
        
        if (firstKey == null || lastKey == null ||
            firstKey.seriesID != seriesID || lastKey.seriesID != seriesID)
            return 0;
        else
            return obsRecordsIndex.getKeyIndex(lastKey) - obsRecordsIndex.getKeyIndex(firstKey) + 1;
    }
    
    
    int[] getObsSeriesHistogram(long seriesID, Range<Instant> phenomenonTimeRange, Duration binSize)
    {
        long start = phenomenonTimeRange.lowerEndpoint().getEpochSecond();
        long end = phenomenonTimeRange.upperEndpoint().getEpochSecond();
        long dt = binSize.getSeconds();
        long t = start;
        int numBins = (int)Math.ceil((double)(end - start)/dt);
        int[] counts = new int[numBins];
        
        for (int i = 0; i < counts.length; i++)
        {
            var beginBin = Instant.ofEpochSecond(t);
            MVTimeSeriesRecordKey k1 = obsRecordsIndex.ceilingKey(new MVTimeSeriesRecordKey(seriesID, beginBin));
            
            t += dt;
            var endBin = Instant.ofEpochSecond(t);
            MVTimeSeriesRecordKey k2 = obsRecordsIndex.floorKey(new MVTimeSeriesRecordKey(seriesID, endBin));
            
            if (k1 != null && k2 != null && k1.seriesID == seriesID && k2.seriesID == seriesID)
            {
                long idx1 = obsRecordsIndex.getKeyIndex(k1);
                long idx2 = obsRecordsIndex.getKeyIndex(k2);
                
                // only compute count if key2 is after key1
                // otherwise it means there was no matching key inside this bin
                if (idx2 >= idx1)
                {
                    int count = (int)(idx2-idx1);
                    
                    // need to add one unless end of bin falls exactly on a key 
                    if (!endBin.equals(k2.timeStamp))
                        count++;
                    
                    counts[i] = count;
                }
            }
        }
        
        return counts;
    }
    
    
    static class AccumulatedStats
    {
        long dsID;
        Range<Instant> phenTimeRange;
        Range<Instant> resultTimeRange;
        long obsCount;
        int[] obsCountByTime;
        
        ObsStats toObsStats(int idScope)
        {
            var obsStats = new ObsStats.Builder()
                .withDataStreamID(BigId.fromLong(idScope, dsID))
                .withPhenomenonTimeRange(TimeExtent.period(phenTimeRange))
                .withResultTimeRange(TimeExtent.period(resultTimeRange))
                .withTotalObsCount(obsCount);
            
            if (obsCountByTime != null)
                obsStats.withObsCountByTime(obsCountByTime);
            
            return obsStats.build();
        }
    }


    @Override
    public Stream<ObsStats> getStatistics(ObsStatsQuery query)
    {
        var filter = query.getObsFilter();
        var timeParams = new TimeParams(filter);
        var accStatsRef = new AtomicReference<AccumulatedStats>();
        
        var seriesStats = selectObsSeries(filter, timeParams)
            .map(series -> {
               var dsID = series.key.dataStreamID;
               var foiID = series.key.foiID > 0 ?
                   new FeatureId(BigId.fromLong(idScope, series.key.foiID), "urn:foi:unknown") :
                   FeatureId.NULL_FEATURE;
               
               var seriesTimeRange = getObsSeriesPhenomenonTimeRange(series.id);
               
               // skip if requested phenomenon time range doesn't intersect series time range
               var statsTimeRange = timeParams.phenomenonTimeRange;
               if (seriesTimeRange == null || !statsTimeRange.isConnected(seriesTimeRange))
                   return null;
               
               statsTimeRange = seriesTimeRange.intersection(statsTimeRange);
               
               var resultTimeRange = series.key.resultTime != Instant.MIN ?
                   Range.singleton(series.key.resultTime) : statsTimeRange;
               
               var obsCount = getObsSeriesCount(series.id, statsTimeRange);
               
               // compute histogram
               int[] obsCountByTime = null;
               if (query.getHistogramBinSize() != null)
               {
                   var histogramTimeRange = timeParams.phenomenonTimeRange;
                   if (histogramTimeRange.lowerEndpoint() == Instant.MIN || histogramTimeRange.upperEndpoint() == Instant.MAX)
                       histogramTimeRange = seriesTimeRange;
                   
                   obsCountByTime = getObsSeriesHistogram(series.id,
                       histogramTimeRange, query.getHistogramBinSize());
               }
               
               if (query.isAggregateFois())
               {
                   var accStats = accStatsRef.get();
                   var currentStats = accStats;
                   
                   if (accStats == null || accStats.dsID != dsID)
                   {
                       accStats = new AccumulatedStats();
                       accStats.dsID = dsID;
                       accStatsRef.set(accStats);
                   }
                   
                   // accumulate stats
                   accStats.phenTimeRange = accStats.phenTimeRange == null ?
                       statsTimeRange : accStats.phenTimeRange.span(statsTimeRange);
                   accStats.resultTimeRange = accStats.resultTimeRange == null ?
                       statsTimeRange : accStats.resultTimeRange.span(resultTimeRange);
                   accStats.obsCount += obsCount;
                   if (obsCountByTime != null)
                   {
                       if (accStats.obsCountByTime == null)
                           accStats.obsCountByTime = obsCountByTime.clone();
                       else
                       {
                           for (int i = 0; i < obsCountByTime.length; i++)
                               accStats.obsCountByTime[i] += obsCountByTime[i];
                       }
                   }
                   if (currentStats != null && currentStats.dsID != dsID)
                       return currentStats.toObsStats(idScope);
                   else
                       return null;
               }
               else
               {
                   var obsStats = new ObsStats.Builder()
                       .withDataStreamID(BigId.fromLong(idScope, dsID))
                       .withFoiID(foiID)
                       .withPhenomenonTimeRange(TimeExtent.period(statsTimeRange))
                       .withResultTimeRange(TimeExtent.period(resultTimeRange))
                       .withTotalObsCount(obsCount);
                   
                   if (obsCountByTime != null)
                       obsStats.withObsCountByTime(obsCountByTime);
                   
                   return obsStats.build();
               }
            })
            .filter(Objects::nonNull);
        
        if (query.isAggregateFois())
            return Stream.concat(seriesStats,
                Stream.of(1).flatMap(k -> {
                    var lastStats = accStatsRef.get();
                    if (lastStats != null)
                        return Stream.of(lastStats.toObsStats(idScope));
                    else
                        return Stream.empty();
                }));
        else
            return seriesStats;
    }


    @Override
    public long countMatchingEntries(ObsFilter filter)
    {
        var timeParams = new TimeParams(filter);
        
        // if no predicate or spatial query is used, we can optimize
        // by scanning only observation series
        if (filter.getValuePredicate() == null && filter.getPhenomenonLocation() == null)
        {
            // special case to count per series
            return selectObsSeries(filter, timeParams)
                .mapToLong(series -> {
                    return getObsSeriesCount(series.id, timeParams.phenomenonTimeRange);
                })
                .sum();
        }
        
        // else use full select and count items
        else
            return selectKeys(filter).limit(filter.getLimit()).count();
    }


    @Override
    public synchronized void clear()
    {
        obsRecordsIndex.clear();
        obsSeriesByFoiIndex.clear();
        obsSeriesMainIndex.clear();
    }


    @Override
    public boolean containsKey(Object key)
    {
        MVTimeSeriesRecordKey obsKey = toInternalKey(key);
        return obsKey == null ? false : obsRecordsIndex.containsKey(obsKey);
    }


    @Override
    public boolean containsValue(Object value)
    {
        return obsRecordsIndex.containsValue(value);
    }


    @Override
    public IObsData get(Object key)
    {
        MVTimeSeriesRecordKey obsKey = toInternalKey(key);
        return obsKey == null ? null : obsRecordsIndex.get(obsKey);
    }


    @Override
    public boolean isEmpty()
    {
        return obsRecordsIndex.isEmpty();
    }


    @Override
    public Set<Entry<BigId, IObsData>> entrySet()
    {
        return new AbstractSet<>() {
            @Override
            public Iterator<Entry<BigId, IObsData>> iterator() {
                return getAllObsSeries(H2Utils.ALL_TIMES_RANGE)
                    .flatMap(series -> {
                        var cursor = getObsCursor(series.id, H2Utils.ALL_TIMES_RANGE);
                        
                        // casting is ok since set is read-only and keys are subtypes of BigId
                        @SuppressWarnings({ "unchecked" })
                        var castedStream = (Stream<Entry<BigId, IObsData>>)(Stream<?>)cursor.entryStream();
                        return castedStream;
                    }).iterator();
            }

            @Override
            public int size() {
                return obsRecordsIndex.size();
            }

            @Override
            public boolean contains(Object o) {
                return MVObsStoreImpl.this.containsKey(o);
            }
        };
    }


    @Override
    public Set<BigId> keySet()
    {
        return new AbstractSet<>() {
            @Override
            public Iterator<BigId> iterator() {
                return getAllObsSeries(H2Utils.ALL_TIMES_RANGE)
                    .flatMap(series -> {
                        RangeCursor<MVTimeSeriesRecordKey, IObsData> cursor = getObsCursor(series.id, H2Utils.ALL_TIMES_RANGE);
                        
                        // casting is ok since set is read-only and keys are subtypes of BigId
                        @SuppressWarnings({ "unchecked" })
                        var castedStream = (Stream<BigId>)(Stream<?>)cursor.keyStream();
                        return castedStream;
                    }).iterator();
            }

            @Override
            public int size() {
                return obsRecordsIndex.size();
            }

            @Override
            public boolean contains(Object o) {
                return MVObsStoreImpl.this.containsKey(o);
            }
        };
    }
    
    
    @Override
    public synchronized BigId add(IObsData obs)
    {
        // check that datastream exists
        if (!dataStreamStore.containsKey(new DataStreamKey(obs.getDataStreamID())))
            throw new IllegalStateException("Unknown datastream" + obs.getDataStreamID());
        
        // check that FOI exists
        if (obs.hasFoi() && foiStore != null && !foiStore.contains(obs.getFoiID()))
            throw new IllegalStateException("Unknown FOI: " + obs.getFoiID());
        
        MVTimeSeriesKey seriesKey = new MVTimeSeriesKey(
            obs.getDataStreamID().getIdAsLong(),
            obs.getFoiID().getIdAsLong(),
            obs.getResultTime().equals(obs.getPhenomenonTime()) ? Instant.MIN : obs.getResultTime());
        
        MVTimeSeriesInfo series = obsSeriesMainIndex.computeIfAbsent(seriesKey, k -> {
            // also update the FOI to series mapping if needed
            obsSeriesByFoiIndex.putIfAbsent(seriesKey, Boolean.TRUE);
            
            return new MVTimeSeriesInfo(
                obsRecordsIndex.isEmpty() ? 1 : obsRecordsIndex.lastKey().seriesID + 1);
        });
        
        // add to main obs index
        MVTimeSeriesRecordKey obsKey = new MVTimeSeriesRecordKey(idScope, series.id, obs.getPhenomenonTime());
        obsRecordsIndex.put(obsKey, obs);
        
        return obsKey;
    }


    @Override
    public synchronized IObsData put(BigId key, IObsData obs)
    {        
        MVTimeSeriesRecordKey obsKey = toInternalKey(key);
        IObsData oldObs = obsRecordsIndex.replace(obsKey, obs);
        if (oldObs == null)
            throw new UnsupportedOperationException("put can only be used to update existing observations");
        return oldObs;
    }


    @Override
    public synchronized IObsData remove(Object keyObj)
    {
        MVTimeSeriesRecordKey key = toInternalKey(keyObj);
        IObsData oldObs = obsRecordsIndex.remove(key);
        
        // don't check and remove empty obs series here since in many cases they will be reused.
        // it can be done automatically during cleanup/compaction phase or with specific method.
        
        return oldObs;     
    }


    @Override
    public synchronized long removeEntries(ObsFilter filter)
    {
        // stream obs directly in case of filtering by internal IDs
        if (filter.getInternalIDs() != null)
        {
            var obsStream = filter.getInternalIDs().stream()
                .map(k -> toInternalKey(k))
                .filter(Objects::nonNull)
                .map(k -> obsRecordsIndex.getEntry(k))
                .filter(Objects::nonNull);
            
            return getPostFilteredResultStream(obsStream, filter)
                .peek(e -> remove(e.getKey()))
                .count();
        }
        
        // select obs series matching the filter
        var timeParams = new TimeParams(filter);
        return selectObsSeries(filter, timeParams)
            .mapToLong(series -> {
                var obsStream = getObsStream(series, 
                    timeParams.resultTimeRange,
                    timeParams.phenomenonTimeRange,
                    timeParams.currentTimeOnly,
                    timeParams.latestResultOnly);
                
                // delete all matching record in series
                var numRemoved = getPostFilteredResultStream(obsStream, filter)
                    .peek(e -> remove(e.getKey()))
                    .count();
                
                // delete series if it has no more records
                if (getObsSeriesCount(series.id, H2Utils.ALL_TIMES_RANGE) == 0) {
                    obsSeriesByFoiIndex.remove(series.key);
                    obsSeriesMainIndex.remove(series.key);
                }   
                
                return numRemoved;
            }).sum();
    }
    

    protected synchronized void removeAllObsAndSeries(long datastreamID)
    {
        // remove a)ll series and obs
        MVTimeSeriesKey first = new MVTimeSeriesKey(datastreamID, 0, Instant.MIN);
        MVTimeSeriesKey last = new MVTimeSeriesKey(datastreamID, Long.MAX_VALUE, Instant.MAX);
        
        new RangeCursor<>(obsSeriesMainIndex, first, last).entryStream().forEach(entry -> {

            // remove all obs in series
            var seriesId = entry.getValue().id;
            MVTimeSeriesRecordKey k1 = new MVTimeSeriesRecordKey(seriesId, Instant.MIN);
            MVTimeSeriesRecordKey k2 = new MVTimeSeriesRecordKey(seriesId, Instant.MAX);
            new RangeCursor<>(obsRecordsIndex, k1, k2).keyStream().forEach(k -> {
                obsRecordsIndex.remove(k);
            });
            
            // remove series from index
            obsSeriesByFoiIndex.remove(entry.getKey());
            obsSeriesMainIndex.remove(entry.getKey());
        });
    }


    @Override
    public int size()
    {
        return obsRecordsIndex.size();
    }


    @Override
    public Collection<IObsData> values()
    {
        return obsRecordsIndex.values();
    }


    @Override
    public void commit()
    {
        obsRecordsIndex.getStore().commit();
        obsRecordsIndex.getStore().sync();
    }


    @Override
    public void backup(OutputStream output) throws IOException
    {
        // TODO Auto-generated method stub
    }


    @Override
    public void restore(InputStream input) throws IOException
    {
        // TODO Auto-generated method stub
    }


    @Override
    public boolean isReadOnly()
    {
        return mvStore.isReadOnly();
    }
    
    
    @Override
    public void linkTo(IFoiStore foiStore)
    {
        this.foiStore = Asserts.checkNotNull(foiStore, IFoiStore.class);
    }
}
