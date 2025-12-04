/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.database.registry;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.data.IObsData;
import org.sensorhub.api.datastore.feature.FoiFilter;
import org.sensorhub.api.datastore.feature.IFoiStore;
import org.sensorhub.api.datastore.obs.DataStreamFilter;
import org.sensorhub.api.datastore.obs.IDataStreamStore;
import org.sensorhub.api.datastore.obs.IObsStore;
import org.sensorhub.api.datastore.obs.ObsFilter;
import org.sensorhub.api.datastore.obs.ObsStats;
import org.sensorhub.api.datastore.obs.ObsStatsQuery;
import org.sensorhub.api.datastore.obs.IObsStore.ObsField;
import org.sensorhub.impl.database.registry.FederatedDatabase.ObsSystemDbFilterInfo;
import org.sensorhub.impl.datastore.MergeSortSpliterator;
import org.sensorhub.impl.datastore.ReadOnlyDataStore;
import org.vast.util.Asserts;


/**
 * <p>
 * Implementation of observation store that provides federated read-only access
 * to several underlying databases.
 * </p>
 *
 * @author Alex Robin
 * @date Oct 3, 2019
 */
public class FederatedObsStore extends ReadOnlyDataStore<BigId, IObsData, ObsField, ObsFilter> implements IObsStore
{
    final FederatedDatabase parentDb;
    final FederatedDataStreamStore dataStreamStore;
    
    
    FederatedObsStore(FederatedDatabase db)
    {
        this.parentDb = Asserts.checkNotNull(db, FederatedDatabase.class);
        this.dataStreamStore = new FederatedDataStreamStore(db);
    }


    @Override
    public String getDatastoreName()
    {
        return getClass().getSimpleName();
    }


    @Override
    public long getNumRecords()
    {
        long count = 0;
        for (var db: parentDb.getAllObsDatabases())
            count += db.getObservationStore().getNumRecords();
        return count;
    }
    
    
    protected BigId ensureObsKey(Object obj)
    {
        Asserts.checkArgument(obj instanceof BigId, "Key must be a BigId");
        return (BigId)obj;
    }


    @Override
    public boolean containsKey(Object obj)
    {
        BigId key = ensureObsKey(obj);
        
        // delegate to database identified by id scope
        var db = parentDb.getObsSystemDatabase(key);
        if (db != null)
            return db.getObservationStore().containsKey(key);
        else
            return false;
    }


    @Override
    public boolean containsValue(Object value)
    {
        for (var db: parentDb.getAllObsDatabases())
        {
            if (db.getObservationStore().containsValue(value))
                return true;
        }
        
        return false;
    }


    @Override
    public IObsData get(Object obj)
    {
        BigId key = ensureObsKey(obj);
        
        // delegate to database identified by id scope
        var db = parentDb.getObsSystemDatabase(key);
        if (db != null)
            return db.getObservationStore().get(key);
        else
            return null;
    }
    
    
    protected Map<Integer, ObsSystemDbFilterInfo> getFilterDispatchMap(ObsFilter filter)
    {
        Map<Integer, ObsSystemDbFilterInfo> dataStreamFilterDispatchMap = null;
        Map<Integer, ObsSystemDbFilterInfo> foiFilterDispatchMap = null;
        Map<Integer, ObsSystemDbFilterInfo> obsFilterDispatchMap = new TreeMap<>();
        
        // use internal IDs if present
        if (filter.getInternalIDs() != null)
        {
            var filterDispatchMap = parentDb.getObsDbFilterDispatchMap(filter.getInternalIDs());
            for (var filterInfo: filterDispatchMap.values())
            {
                filterInfo.filter = ObsFilter.Builder
                    .from(filter)
                    .withInternalIDs(filterInfo.ids)
                    .build();
            }
            
            return filterDispatchMap;
        }
        
        // otherwise get dispatch map for datastreams and fois
        if (filter.getDataStreamFilter() != null)
            dataStreamFilterDispatchMap = dataStreamStore.getFilterDispatchMap(filter.getDataStreamFilter());
        
        if (filter.getFoiFilter() != null)
            foiFilterDispatchMap = parentDb.foiStore.getFilterDispatchMap(filter.getFoiFilter());
        
        // merge both maps
        if (dataStreamFilterDispatchMap != null)
        {
            for (var entry: dataStreamFilterDispatchMap.entrySet())
            {
                var dataStreamFilterInfo = entry.getValue();
                                
                var builder = ObsFilter.Builder
                    .from(filter)
                    .withDataStreams((DataStreamFilter)dataStreamFilterInfo.filter);
                
                var foifilterInfo = foiFilterDispatchMap != null ? foiFilterDispatchMap.get(entry.getKey()) : null;
                if (foifilterInfo != null)
                    builder.withFois((FoiFilter)foifilterInfo.filter);
                    
                var filterInfo = new ObsSystemDbFilterInfo();
                filterInfo.db = dataStreamFilterInfo.db;
                filterInfo.filter = builder.build();
                obsFilterDispatchMap.put(entry.getKey(), filterInfo);
            }
        }
        
        if (foiFilterDispatchMap != null)
        {
            for (var entry: foiFilterDispatchMap.entrySet())
            {
                var foiFilterInfo = entry.getValue();
                
                // only process DBs not already processed in first loop above
                if (!obsFilterDispatchMap.containsKey(entry.getKey()))
                {
                    var filterInfo = new ObsSystemDbFilterInfo();
                    filterInfo.db = foiFilterInfo.db;
                    filterInfo.filter = ObsFilter.Builder.from(filter)
                        .withFois((FoiFilter)foiFilterInfo.filter)
                        .build();
                    obsFilterDispatchMap.put(entry.getKey(), filterInfo);
                }
            }
        }
        
        if (!obsFilterDispatchMap.isEmpty())
            return obsFilterDispatchMap;
        else
            return null;
    }


    @Override
    public Stream<Entry<BigId, IObsData>> selectEntries(ObsFilter filter, Set<ObsField> fields)
    {
        final var obsStreams = new ArrayList<Stream<Entry<BigId, IObsData>>>(100);
        
        // if any kind of internal IDs are used, we need to dispatch the correct filter
        // to the corresponding DB so we create this map
        var filterDispatchMap = getFilterDispatchMap(filter);
        
        if (filterDispatchMap != null)
        {
            filterDispatchMap.values().stream()
                .forEach(v -> {
                    var obsStream = v.db.getObservationStore().selectEntries((ObsFilter)v.filter, fields);
                    obsStreams.add(obsStream);
                });
        }
        else
        {
            parentDb.getAllObsDatabases().stream()
                .forEach(db -> {
                    var obsStream = db.getObservationStore().selectEntries(filter, fields);
                    obsStreams.add(obsStream);
                });
        }
        
        if (obsStreams.isEmpty())
            return Stream.empty();
        
        // stream and merge obs from all selected datastreams and time periods
        var mergeSortIt = new MergeSortSpliterator<Entry<BigId, IObsData>>(obsStreams,
            (e1, e2) -> e1.getValue().getPhenomenonTime().compareTo(e2.getValue().getPhenomenonTime()));
               
        // stream output of merge sort iterator + apply limit
        return StreamSupport.stream(mergeSortIt, false)
            .limit(filter.getLimit())
            .onClose(() -> mergeSortIt.close());
    }


    @Override
    public long countMatchingEntries(ObsFilter filter)
    {
        // if any kind of internal IDs are used, we need to dispatch the correct filter
        // to the corresponding DB so we create this map
        var filterDispatchMap = getFilterDispatchMap(filter);

        if (filterDispatchMap != null)
        {
            return filterDispatchMap.values().stream()
                    .mapToLong(v -> v.db.getObservationStore().countMatchingEntries((ObsFilter)v.filter))
                    .reduce(0, Long::sum);
        }

        // otherwise scan all DBs
        else
        {
            return parentDb.getAllObsDatabases().stream()
                    .mapToLong(db -> db.getObservationStore().countMatchingEntries(filter))
                    .reduce(0, Long::sum);
        }
    }

    @Override
    public Stream<BigId> selectObservedFois(ObsFilter filter)
    {
        // if any kind of internal IDs are used, we need to dispatch the correct filter
        // to the corresponding DB so we create this map
        var filterDispatchMap = getFilterDispatchMap(filter);
        
        if (filterDispatchMap != null)
        {
            return filterDispatchMap.values().stream()
                .flatMap(v -> v.db.getObservationStore().selectObservedFois((ObsFilter)v.filter))
                .limit(filter.getLimit());
        }
        else
        {
            return parentDb.getAllObsDatabases().stream()
                .flatMap(db -> db.getObservationStore().selectObservedFois(filter))
                .limit(filter.getLimit());
        }
    }


    @Override
    public Stream<ObsStats> getStatistics(ObsStatsQuery query)
    {
        var filter = query.getObsFilter();
        
        // if any kind of internal IDs are used, we need to dispatch the correct filter
        // to the corresponding DB so we create this map
        var filterDispatchMap = getFilterDispatchMap(filter);
        
        if (filterDispatchMap != null)
        {
            return filterDispatchMap.values().stream()
                .flatMap(v -> {
                    var dbQuery = ObsStatsQuery.Builder.from(query)
                        .selectObservations((ObsFilter)v.filter)
                        .build();
                    return v.db.getObservationStore().getStatistics(dbQuery);
                })
                .limit(filter.getLimit());
        }
        else
        {
            return parentDb.getAllObsDatabases().stream()
                .flatMap(db -> db.getObservationStore().getStatistics(query))
                .limit(filter.getLimit());
        }
    }
    

    @Override
    public IDataStreamStore getDataStreams()
    {
        return dataStreamStore;
    }
    
    
    @Override
    public BigId add(IObsData obs)
    {
        throw new UnsupportedOperationException(READ_ONLY_ERROR_MSG);
    }


    @Override
    public void linkTo(IFoiStore foiStore)
    {
        throw new UnsupportedOperationException();        
    }

}
