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
import java.util.AbstractMap.SimpleEntry;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.h2.mvstore.MVBTreeMap;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.RangeCursor;
import org.h2.mvstore.rtree.SpatialKey;
import org.h2.mvstore.type.DataType;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.IdProvider;
import org.sensorhub.api.datastore.TemporalFilter;
import org.sensorhub.api.datastore.feature.FeatureFilterBase;
import org.sensorhub.api.datastore.feature.FeatureKey;
import org.sensorhub.api.datastore.feature.IFeatureStoreBase;
import org.sensorhub.api.datastore.feature.IFeatureStoreBase.FeatureField;
import org.sensorhub.impl.datastore.DataStoreUtils;
import org.sensorhub.impl.datastore.SpliteratorWrapper;
import org.sensorhub.impl.datastore.h2.MVDatabaseConfig.IdProviderType;
import org.sensorhub.impl.datastore.h2.index.FullTextIndex;
import org.sensorhub.impl.datastore.h2.index.SpatialIndex;
import org.sensorhub.utils.FilterUtils;
import org.vast.ogc.gml.IFeature;
import org.vast.util.Asserts;
import org.vast.util.Bbox;


/**
 * <p>
 * Feature Store implementation based on H2 MVStore.
 * </p>
 * 
 * @param <V> Feature Type 
 * @param <VF> Feature Field Type
 * @param <F> Filter Type
 * 
 * @author Alex Robin
 * @date Apr 8, 2018
 */
public abstract class MVBaseFeatureStoreImpl<V extends IFeature, VF extends FeatureField, F extends FeatureFilterBase<? super V>> implements IFeatureStoreBase<V, VF, F>
{
    private static final String FEATURE_IDS_MAP_NAME = "feature_ids";
    private static final String FEATURE_UIDS_MAP_NAME = "feature_uids";
    private static final String FEATURE_RECORDS_MAP_NAME = "feature_records";
    private static final String FEATURE_SPATIAL_INDEX_MAP_NAME = "feature_geom";
    private static final String FEATURE_FULLTEXT_MAP_NAME = "feature_text";

    protected MVStore mvStore;
    protected MVDataStoreInfo dataStoreInfo;
    protected IdProvider<V> idProvider;
    protected int idScope;
    
    /*
     * Main index holding feature objects
     * We use MVFeatureParentKey as keys sorted by parent ID, internal ID, and then validTime
     * This allows efficient retrieval of all children attached to a parent
     */
    protected MVBTreeMap<MVFeatureParentKey, V> featuresIndex;
    
    /*
     * ID index pointing to main index
     * Map of internal ID to parent ID for lookup by ID
     * We store both IDs in the key and no value (use MVVoidDataType for efficiency)
     * The map also accepts a Long object as key for retrieval
     */
    protected MVBTreeMap<MVFeatureParentKey, Boolean> idsIndex;
    
    /*
     * UID index pointing to main index
     * Map of unique ID to parentID/internalID pairs
     */
    protected MVBTreeMap<String, MVFeatureParentKey> uidsIndex;
    
    /*
     * Spatial index pointing to main index
     * Key references are parentID/internalID/validTime
     */
    protected SpatialIndex<V, MVFeatureParentKey> spatialIndex;
    
    /*
     * Full text index pointing to main index
     * Key references are parentID/internalID pairs
     */
    protected FullTextIndex<V, MVFeatureParentKey> fullTextIndex;
    
    
    protected MVBaseFeatureStoreImpl()
    {
    }
    
    
    protected MVBaseFeatureStoreImpl<V, VF, F> init(MVStore mvStore, int idScope, IdProviderType idProviderType, MVDataStoreInfo dataStoreInfo)
    {
        Asserts.checkNotNull(idProviderType, IdProviderType.class);
        
        // create ID provider
        IdProvider<V> idProvider = null;
        switch (idProviderType)
        {
            case UID_HASH:
                idProvider = DataStoreUtils.getFeatureHashIdProvider(212158449);
                break;
                
            default:
            case SEQUENTIAL:
                idProvider = dsInfo -> {
                    if (featuresIndex.isEmpty())
                        return 1;
                    else
                        return idsIndex.lastKey().getInternalID().getIdAsLong()+1;
                };
        }
        
        return init(mvStore, idScope, idProvider, dataStoreInfo);
    }
    
    
    protected MVBaseFeatureStoreImpl<V, VF, F> init(MVStore mvStore, int idScope, IdProvider<V> idProvider, MVDataStoreInfo dataStoreInfo)
    {
        this.mvStore = Asserts.checkNotNull(mvStore, MVStore.class);
        this.dataStoreInfo = Asserts.checkNotNull(dataStoreInfo, MVDataStoreInfo.class);
        this.idProvider = Asserts.checkNotNull(idProvider, IdProvider.class);
        this.idScope = idScope;
        
        // persistent class mappings for Kryo
        var kryoClassMap = mvStore.openMap(MVObsSystemDatabase.KRYO_CLASS_MAP_NAME, new MVBTreeMap.Builder<String, Integer>());
        
        // feature records map
        String mapName = dataStoreInfo.getName() + ":" + FEATURE_RECORDS_MAP_NAME;
        this.featuresIndex = mvStore.openMap(mapName, 
                new MVBTreeMap.Builder<MVFeatureParentKey, V>()
                         .keyType(new MVFeatureParentKeyDataType(idScope, true))
                         .valueType(getFeatureDataType(kryoClassMap)));
                
        // feature IDs to main index
        mapName = dataStoreInfo.getName() + ":" + FEATURE_IDS_MAP_NAME;
        this.idsIndex = mvStore.openMap(mapName, 
                new MVBTreeMap.Builder<MVFeatureParentKey, Boolean>()
                        .keyType(new MVFeatureParentKeyByIdDataType(idScope))
                        .valueType(new MVVoidDataType()));
        
        // feature unique IDs to main index
        mapName = dataStoreInfo.getName() + ":" + FEATURE_UIDS_MAP_NAME;
        this.uidsIndex = mvStore.openMap(mapName, 
                new MVBTreeMap.Builder<String, MVFeatureParentKey>()
                        .valueType(new MVFeatureParentKeyDataType(idScope, false)));
                
        // spatial index
        mapName = dataStoreInfo.getName() + ":" + FEATURE_SPATIAL_INDEX_MAP_NAME;
        this.spatialIndex = new SpatialIndex<>(mvStore, mapName, new MVFeatureParentKeyDataType(idScope, true)) {
            @Override
            protected SpatialKey getSpatialKey(MVFeatureParentKey key, IFeature f)
            {
                if (f.getGeometry() == null)
                    return null;
                
                int hashID = Objects.hash(key.getInternalID().getIdAsLong(), key.getValidStartTime());
                return H2Utils.getBoundingRectangle(hashID, f.getGeometry());
            }
        };
        
        // full-text index
        mapName = dataStoreInfo.getName() + ":" + FEATURE_FULLTEXT_MAP_NAME;
        this.fullTextIndex = new FullTextIndex<>(mvStore, mapName, new MVFeatureParentKeyDataType(idScope, false));
        
        return this;
    }
    
    
    protected DataType getFeatureDataType(MVMap<String, Integer> kryoClassMap)
    {
        return new FeatureDataType(kryoClassMap);
    }


    @Override
    public String getDatastoreName()
    {
        return dataStoreInfo.getName();
    }


    @Override
    public long getNumRecords()
    {
        return featuresIndex.sizeAsLong();
    }


    @Override
    public long getNumFeatures()
    {
        return idsIndex.sizeAsLong();
    }


    @Override
    public Bbox getFeaturesBbox()
    {
        return spatialIndex.getFullExtent();
    }
    
    
    @Override
    public synchronized FeatureKey add(BigId parentID, V feature) throws DataStoreException
    {
        DataStoreUtils.checkFeatureObject(feature);
        checkParentFeatureExists(parentID);
        
        var existingKey = uidsIndex.get(feature.getUniqueIdentifier());
        if (existingKey != null && parentID != null && existingKey.getParentID() != parentID.getIdAsLong())
            throw new DataStoreException("Feature is already associated to another parent");
        
        var newKey = generateKey(parentID.getIdAsLong(), existingKey, feature);
        
        // add to store
        put(newKey, feature, existingKey == null, false);
        return newKey;
    }
    
    
    protected void checkParentFeatureExists(BigId parentID) throws DataStoreException
    {
        DataStoreUtils.checkParentFeatureExists(this, parentID);
    }
    
    
    protected MVFeatureParentKey generateKey(long parentID, MVFeatureParentKey existingKey, V f)
    {
        // use existing IDs if feature is already known
        // otherwise generate new one
        BigId internalID;
        if (existingKey != null)
        {
            internalID = existingKey.getInternalID();
            parentID = existingKey.getParentID();
        }
        else
            internalID = BigId.fromLong(idScope, idProvider.newInternalID(f));
        
        // get valid start time from feature object
        // or use default value if no valid time is set
        Instant validStartTime;
        if (f.getValidTime() != null)
            validStartTime = f.getValidTime().begin();
        else
            validStartTime = FeatureKey.TIMELESS; // default value meaning feature is always valid
        
        // generate full key
        return new MVFeatureParentKey(parentID, internalID, validStartTime);
    }
    
    
    @Override
    @SuppressWarnings("unlikely-arg-type")
    public boolean contains(BigId internalID)
    {
        DataStoreUtils.checkInternalID(internalID);
        return idsIndex.containsKey(internalID);
    }


    @Override
    public boolean contains(String uid)
    {
        DataStoreUtils.checkUniqueID(uid);
        return uidsIndex.containsKey(uid);
    }
    
    
    @Override
    public FeatureKey getCurrentVersionKey(BigId internalID)
    {
        DataStoreUtils.checkInternalID(internalID);
        
        var fk = idsIndex.getFullKey(internalID);
        if (fk != null)
            return getCurrentVersionKey(fk);
        return null;
    }


    @Override
    public FeatureKey getCurrentVersionKey(String uid)
    {
        DataStoreUtils.checkUniqueID(uid);
        
        var fk = uidsIndex.get(uid);
        if (fk != null)
            return getCurrentVersionKey(fk);
        return null;
    }
    
    
    protected FeatureKey getCurrentVersionKey(MVFeatureParentKey fk)
    {
        var keyAtNow = new MVFeatureParentKey(fk.getParentID(), fk.getInternalID(), Instant.now());
        var currentVersionKey = featuresIndex.floorKey(keyAtNow);
        
        if (currentVersionKey != null &&
            currentVersionKey.getInternalID().getIdAsLong() == fk.getInternalID().getIdAsLong())
            return currentVersionKey;
        else
            return null;
    }
    
    
    @Override
    public Entry<FeatureKey, V> getCurrentVersionEntry(BigId internalID)
    {
        DataStoreUtils.checkInternalID(internalID);
        
        var fk = idsIndex.getFullKey(internalID);
        if (fk != null)
            return getCurrentVersionEntry(fk);
        return null;
    }
    
    
    @Override
    public Entry<FeatureKey, V> getCurrentVersionEntry(String uid)
    {
        DataStoreUtils.checkUniqueID(uid);
        
        var fk = uidsIndex.get(uid);
        if (fk != null)
            return getCurrentVersionEntry(fk);
        return null;
    }
    
    
    protected Entry<FeatureKey, V> getCurrentVersionEntry(MVFeatureParentKey fk)
    {
        var keyAtNow = new MVFeatureParentKey(fk.getParentID(), fk.getInternalID(), Instant.now());
        var currentVersionKey = featuresIndex.floorKey(keyAtNow);
        
        if (currentVersionKey != null &&
            currentVersionKey.getInternalID().getIdAsLong() == fk.getInternalID().getIdAsLong())
        {
            // down casting is ok since keys are always subtypes of FeatureKey
            @SuppressWarnings({ "unchecked" })
            var entry = (Entry<FeatureKey, V>)(Entry<?,?>)featuresIndex.getEntry(currentVersionKey);
            return entry;
        }
        else
            return null;
    }
    
    
    protected RangeCursor<MVFeatureParentKey, V> getFeatureCursor(MVFeatureParentKey fk, TemporalFilter timeFilter)
    {
        var first = new MVFeatureParentKey(fk.getParentID(), fk.getInternalID(), timeFilter.getMin());
        var last = new MVFeatureParentKey(fk.getParentID(), fk.getInternalID(), timeFilter.getMax());
        
        // start from first key before selected time range to make sure we include
        // any intersecting feature validity period
        var before = (MVFeatureParentKey)featuresIndex.floorKey(first);
        if (before != null && before.getInternalID().getIdAsLong() == fk.getInternalID().getIdAsLong())
            first = before;
        
        return new RangeCursor<>(featuresIndex, first, last);
    }
    
    
    protected Stream<MVFeatureParentKey> getKeyStreamForUniqueIdOrPrefix(String uid)
    {
        // case of wildcard
        if (uid.endsWith(FilterUtils.WILDCARD_CHAR))
        {
            var first = uid.substring(0, uid.length()-1);
            var last = first + '\uffff';
            return new RangeCursor<>(uidsIndex, first, last).valueStream();
        }
        
        // case of exact UID
        else
        {
            var fk = uidsIndex.get(uid);
            if (fk == null)
                return Stream.empty(); // return empty stream if uid is not found 
            return Stream.of(fk);
        }
    }
    
    
    protected Stream<Entry<MVFeatureParentKey, V>> getIndexedStream(F filter)
    {
        Stream<MVFeatureParentKey> fkStream = null;
        
        // get time filter, default to ALL_TIMES if none provided
        var timeFilter = filter.getValidTime() != null ?
            filter.getValidTime() : H2Utils.ALL_TIMES_FILTER;
        
        // if filtering by internal IDs, use IDs index as primary
        if (filter.getInternalIDs() != null)
        {
            fkStream = filter.getInternalIDs().stream()
                .map(id -> idsIndex.getFullKey(id));
        }
        
        // if filtering by UID, use UIDs index as primary
        else if (filter.getUniqueIDs() != null)
        {
            // concatenate streams for each selected feature UID
            fkStream = filter.getUniqueIDs().stream()
                .flatMap(this::getKeyStreamForUniqueIdOrPrefix);
        }
        
        // if spatial filter is used, use spatial index as primary
        else if (filter.getLocationFilter() != null)
        {
            fkStream = spatialIndex.selectKeys(filter.getLocationFilter());
                    
            // post-filter on other fields to avoid unnecessary lookups in featuresIndex
            // valid time 
            if (!timeFilter.isLatestTime() && timeFilter != H2Utils.ALL_TIMES_FILTER)
                fkStream = fkStream.filter(fk -> timeFilter.getMax().isAfter(fk.getValidStartTime()));
            
            // full-text
            if (filter.getFullTextFilter() != null)
                fkStream = fullTextIndex.addFullTextPostFilter(fkStream, filter.getFullTextFilter());
            
            // spatial index ref keys contain valid time
            // so we can poll each entry directly from main index
            return fkStream.map(fk -> featuresIndex.getEntry(fk))
                .filter(Objects::nonNull);
        }
        
        // if full-text filter is used, use full-text index as primary
        else if (filter.getFullTextFilter() != null)
        {
            fkStream = fullTextIndex.selectKeys(filter.getFullTextFilter());
        }
        
        // if some features were selected by ID
        if (fkStream != null)
        {
            return fkStream
                .filter(Objects::nonNull)
                .flatMap(fk -> getFeatureCursor(fk, timeFilter).entryStream());
        }
        
        return null;
    }
    
    
    protected Stream<Entry<MVFeatureParentKey, V>> postFilterKeyValidTime(Stream<Entry<MVFeatureParentKey, V>> resultStream, TemporalFilter timeFilter)
    {
        Asserts.checkNotNull(timeFilter);
        
        // handle special case of current time & latest time
        Instant filterStartTime, filterEndTime;
        if (timeFilter.isCurrentTime()) {
            filterStartTime = filterEndTime = Instant.now();
        } else if (timeFilter.isLatestTime()) {
            filterStartTime = filterEndTime = Instant.MAX;
        } else {
            filterStartTime = timeFilter.getMin();
            filterEndTime = timeFilter.getMax();
        }
        
        // custom statefull filter
        return StreamSupport.stream(new SpliteratorWrapper<>(resultStream.spliterator()) {
            Entry<MVFeatureParentKey, V> previous;
            
            @Override
            public boolean tryAdvance(Consumer<? super Entry<MVFeatureParentKey, V>> action)
            {
                if (previous == null)
                {
                    var more = super.tryAdvance(e -> previous = e);
                    if (!more)
                        return false;
                }
                
                var more = super.tryAdvance(e -> {
                    FeatureKey prevK = previous.getKey();
                    FeatureKey k = e.getKey();
                    
                    var prevAfterStart = prevK.getValidStartTime().compareTo(filterStartTime) >= 0;
                    var prevBeforeEnd = prevK.getValidStartTime().compareTo(filterEndTime) <= 0;
                    var nextAfterStart = k.getValidStartTime().compareTo(filterStartTime) > 0;
                    var nextHasNewId = k.getInternalID() != prevK.getInternalID();
                    if ((prevAfterStart && prevBeforeEnd) ||
                        (!prevAfterStart && nextAfterStart) ||
                        (!prevAfterStart && nextHasNewId))
                      action.accept(previous);
                    
                    previous = e;
                });
                
                if (!more)
                {
                    // special case for last entry
                    FeatureKey prevK = previous.getKey();
                    var prevBeforeEnd = prevK.getValidStartTime().compareTo(filterEndTime) <= 0;
                    if (prevBeforeEnd)
                        action.accept(previous);
                }
                
                return more;
            }
        }, false);
    }
    
    
    protected Stream<Entry<MVFeatureParentKey, V>> getParentResultStream(BigId parentID, TemporalFilter timeFilter)
    {
        return getParentResultStream(parentID.getIdAsLong(), timeFilter);
    }
    
    
    protected Stream<Entry<MVFeatureParentKey, V>> getParentResultStream(long parentID, TemporalFilter timeFilter)
    {
        var first = new MVFeatureParentKey(0, parentID, 1, Instant.MIN);
        var last = new MVFeatureParentKey(0, parentID, Long.MAX_VALUE, Instant.MAX);
        
        // scan all features that are members of the selected parentID
        var resultStream = new RangeCursor<>(featuresIndex, first, last).entryStream();
        
        // post filter using keys valid time if needed
        if (timeFilter != null)
            resultStream = postFilterKeyValidTime(resultStream, timeFilter);
        
        return resultStream;
    }


    @Override
    public Stream<Entry<FeatureKey, V>> selectEntries(F filter, Set<VF> fields)
    {
        var resultStream = getIndexedStream(filter);
        
        // if no suitable index was found, just stream all features
        if (resultStream == null)
            resultStream = featuresIndex.entrySet().stream();
        
        // add exact geo predicate
        if (filter.getLocationFilter() != null)
            resultStream = resultStream.filter(e -> filter.testLocation(e.getValue()));
        
        // add full-text predicate
        if (filter.getFullTextFilter() != null)
            resultStream = resultStream.filter(e -> filter.testFullText(e.getValue()));
        
        // apply value predicate
        if (filter.getValuePredicate() != null)
            resultStream = resultStream.filter(e -> filter.testValuePredicate(e.getValue()));
        
        // if including group members
        if (filter.includeMembers())
        {
            resultStream = resultStream
                .flatMap(e -> {
                    var parentID = e.getKey().getInternalID().getIdAsLong();
                    var s1 = Stream.of(e);
                    var s2 = getParentResultStream(parentID, filter.getValidTime());
                    return Stream.concat(s1, s2);
                });
        }
        
        // adjust validTime in the case it ends at now and there is a
        // more recent version of the feature description available
        resultStream = resultStream.map(e -> {
            var wf = getFeatureWithAdjustedValidTime((MVFeatureParentKey)e.getKey(), e.getValue());
            return new SimpleEntry<>(e.getKey(), wf);
        });
        
        // apply post filter on time now that we computed the correct valid time period
        if (filter.getValidTime() != null)
            resultStream = resultStream.filter(e -> filter.testValidTime(e.getValue()));
        
        // apply limit
        if (filter.getLimit() < Long.MAX_VALUE)
            resultStream = resultStream.limit(filter.getLimit());
        
        // casting is ok since keys are subtypes of FeatureKey
        @SuppressWarnings({ "unchecked", })
        var castedResultStream = (Stream<Entry<FeatureKey, V>>)(Stream<?>)resultStream;
        return castedResultStream;
    }
    
    
    protected V getFeatureWithAdjustedValidTime(MVFeatureParentKey fk, V f)
    {
        return f;
    }


    @Override
    public long countMatchingEntries(F filter)
    {
        if (filter.getValuePredicate() == null &&
            filter.getLocationFilter() == null &&
            filter.getFullTextFilter() == null &&
            filter.getValidTime() == null)
        {
            return featuresIndex.sizeAsLong();
        }
        
        return selectEntries(filter).count();
    }


    @Override
    public void commit()
    {
        featuresIndex.getStore().commit();
        featuresIndex.getStore().sync();
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
    public synchronized void clear()
    {
        // store current version so we can rollback if an error occurs
        long currentVersion = mvStore.getCurrentVersion();
        
        try
        {
            idsIndex.clear();
            spatialIndex.clear();
            featuresIndex.clear();
            fullTextIndex.clear();
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
        FeatureKey fk = ensureFullFeatureKey(key);
        if (fk == null)
            return false;
        return featuresIndex.containsKey(fk);
    }


    @Override
    public boolean containsValue(Object value)
    {
        return featuresIndex.containsValue(value);
    }
    
    
    @Override
    public BigId getParent(BigId internalID)
    {
        var fk = idsIndex.getFullKey(internalID);
        var parentId = fk != null ? fk.getParentID() : 0;
        return parentId != 0 ? BigId.fromLong(idScope, parentId) : null;
    }


    @Override
    public V get(Object key)
    {
        var fk = ensureFullFeatureKey(key);
        return fk != null ? featuresIndex.get(fk) : null;
    }
    
    
    protected MVFeatureParentKey ensureFullFeatureKey(Object key)
    {
        DataStoreUtils.checkFeatureKey(key);
        
        var fk = idsIndex.getFullKey(key);
        if (fk == null)
            return null;
        
        return new MVFeatureParentKey(
            fk.getParentID(),
            fk.getInternalID(),
            ((FeatureKey)key).getValidStartTime());
    }


    @Override
    public boolean isEmpty()
    {
        return featuresIndex.isEmpty();
    }


    @Override
    public synchronized V put(FeatureKey key, V feature)
    {
        try
        {
            ensureFullFeatureKey(key);
            DataStoreUtils.checkFeatureObject(feature);
            
            // check that no other feature with same UID exists
            var uid = feature.getUniqueIdentifier();
            var existingKey = uidsIndex.get(uid);
            if (existingKey != null && existingKey.getInternalID().getIdAsLong() != key.getInternalID().getIdAsLong())
                throw new IllegalArgumentException(DataStoreUtils.ERROR_EXISTING_FEATURE + uid);
            
            // use parent from existing mapping or create new one w/o parent
            MVFeatureParentKey fk;
            if (existingKey == null)
                fk = new MVFeatureParentKey(0L, key.getInternalID(), key.getValidStartTime());
            else
                fk = new MVFeatureParentKey(existingKey.getParentID(), key.getInternalID(), key.getValidStartTime());
                
            return put(fk, feature, existingKey == null, true);
        }
        catch (DataStoreException e)
        {
            throw new IllegalArgumentException(e);
        }
    }
    
    
    protected V put(MVFeatureParentKey fk, V f, boolean isNewFeature, boolean replaceVersion) throws DataStoreException
    {
        long currentVersion = mvStore.getCurrentVersion();
        
        try
        {
            // add to main index
            V oldValue = featuresIndex.put(fk, f);
            
            // check if we're allowed to replace existing entry
            boolean isNewEntry = (oldValue == null);
            if (!isNewEntry)
            {
                if (!replaceVersion)
                    throw new DataStoreException(DataStoreUtils.ERROR_EXISTING_FEATURE_VERSION);
                
                if (!oldValue.getUniqueIdentifier().equals(f.getUniqueIdentifier()))
                    throw new DataStoreException(DataStoreUtils.ERROR_CHANGED_FEATURE_UID);
            }
            
            // update ID and UID indexes
            if (isNewFeature)
            {
                idsIndex.put(fk, Boolean.TRUE);
                uidsIndex.put(f.getUniqueIdentifier(), fk);
            }
            
            // update spatial index
            if (isNewEntry)
                spatialIndex.add(fk, f);
            else
                spatialIndex.update(fk, oldValue, f);
            
            // update full-text index
            if (isNewEntry)
                fullTextIndex.add(fk, f);
            else
                fullTextIndex.update(fk, oldValue, f);
            
            return oldValue;
        }
        catch (Exception e)
        {
            mvStore.rollbackTo(currentVersion);
            throw e;
        }
    }
    
    
    @Override
    public synchronized V remove(Object key)
    {
        var fk = ensureFullFeatureKey(key);
        if (fk == null)
            return null;        
        
        long currentVersion = mvStore.getCurrentVersion();
        
        try
        {
            // remove from main index
            V oldValue = featuresIndex.remove(fk);
            if (oldValue == null)
                return null;
            
            // remove entry from ID and UIDs index if no more feature entries are present
            var internalID = fk.getInternalID();
            var firstKey = new MVFeatureParentKey(fk.getParentID(), internalID, Instant.MIN);
            var nextKey = featuresIndex.ceilingKey(firstKey);
            if (nextKey == null || internalID.getIdAsLong() != nextKey.getInternalID().getIdAsLong())
            {
                idsIndex.remove(firstKey);
                uidsIndex.remove(oldValue.getUniqueIdentifier());
            }
            
            // remove from spatial index
            spatialIndex.remove(fk, oldValue);
            
            // remove from full-text index
            fullTextIndex.remove(fk, oldValue);
            
            return oldValue;
        }
        catch (Exception e)
        {
            mvStore.rollbackTo(currentVersion);
            throw e;
        }
    }


    @Override
    public int size()
    {
        return featuresIndex.size();
    }


    @Override
    public Set<FeatureKey> keySet()
    {
        return new AbstractSet<>() {
            @Override
            public Iterator<FeatureKey> iterator()
            {
                // down casting is ok since keys are always subtypes of FeatureKey
                @SuppressWarnings({ "unchecked", })
                var castedIterator = (Iterator<FeatureKey>)(Iterator<?>)featuresIndex.keySet().iterator();
                return castedIterator;
            }

            @Override
            public boolean contains(Object k)
            {
                return MVBaseFeatureStoreImpl.this.containsKey(k);
            }

            @Override
            public int size()
            {
                return MVBaseFeatureStoreImpl.this.size();
            }
        };
    }


    @Override
    public Set<Entry<FeatureKey, V>> entrySet()
    {
        // down casting is ok since keys are always subtypes of FeatureKey
        @SuppressWarnings({ "unchecked", })
        var castedEntrySet = (Set<Entry<FeatureKey, V>>)(Set<?>)featuresIndex.entrySet();
        return castedEntrySet;
    }


    @Override
    public Collection<V> values()
    {
        return featuresIndex.values();
    }
}
