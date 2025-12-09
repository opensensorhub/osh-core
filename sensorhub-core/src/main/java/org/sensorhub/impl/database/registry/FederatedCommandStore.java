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

import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.sensorhub.api.command.ICommandData;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.datastore.command.CommandFilter;
import org.sensorhub.api.datastore.command.CommandStats;
import org.sensorhub.api.datastore.command.CommandStatsQuery;
import org.sensorhub.api.datastore.command.CommandStreamFilter;
import org.sensorhub.api.datastore.command.ICommandStatusStore;
import org.sensorhub.api.datastore.command.ICommandStore;
import org.sensorhub.api.datastore.command.ICommandStore.CommandField;
import org.sensorhub.api.datastore.feature.IFoiStore;
import org.sensorhub.api.datastore.command.ICommandStreamStore;
import org.sensorhub.impl.database.registry.FederatedDatabase.ObsSystemDbFilterInfo;
import org.sensorhub.impl.datastore.MergeSortSpliterator;
import org.sensorhub.impl.datastore.ReadOnlyDataStore;
import org.vast.util.Asserts;


/**
 * <p>
 * Implementation of command store that provides federated read-only access
 * to several underlying databases.
 * </p>
 *
 * @author Alex Robin
 * @date Mar 24, 2021
 */
public class FederatedCommandStore extends ReadOnlyDataStore<BigId, ICommandData, CommandField, CommandFilter> implements ICommandStore
{
    final FederatedDatabase parentDb;
    final FederatedCommandStreamStore commandStreamStore;
    final FederatedCommandStatusStore commandStatusStore;
    
    
    FederatedCommandStore(FederatedDatabase db)
    {
        this.parentDb = Asserts.checkNotNull(db, FederatedDatabase.class);
        this.commandStreamStore = new FederatedCommandStreamStore(db);
        this.commandStatusStore = new FederatedCommandStatusStore(db, this);
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
            count += db.getCommandStore().getNumRecords();
        return count;
    }
    
    
    protected BigId ensureCommandKey(Object obj)
    {
        Asserts.checkArgument(obj instanceof BigId, "Key must be a BigId");
        return (BigId)obj;
    }


    @Override
    public boolean containsKey(Object obj)
    {
        BigId key = ensureCommandKey(obj);
        
        // delegate to database identified by id scope
        var db = parentDb.getObsSystemDatabase(key);
        if (db != null)
            return db.getCommandStore().containsKey(key);
        else
            return false;
    }


    @Override
    public boolean containsValue(Object value)
    {
        for (var db: parentDb.getAllObsDatabases())
        {
            if (db.getCommandStore().containsValue(value))
                return true;
        }
        
        return false;
    }


    @Override
    public ICommandData get(Object obj)
    {
        BigId key = ensureCommandKey(obj);
        
        // delegate to database identified by id scope
        var db = parentDb.getObsSystemDatabase(key);
        if (db != null)
            return db.getCommandStore().get(key);
        else
            return null;
    }
    
    
    protected Map<Integer, ObsSystemDbFilterInfo> getFilterDispatchMap(CommandFilter filter)
    {
        Map<Integer, ObsSystemDbFilterInfo> commandStreamFilterDispatchMap = null;
        Map<Integer, ObsSystemDbFilterInfo> commandFilterDispatchMap = new TreeMap<>();
        
        // use internal IDs if present
        if (filter.getInternalIDs() != null)
        {
            var filterDispatchMap = parentDb.getObsDbFilterDispatchMap(filter.getInternalIDs());
            for (var filterInfo: filterDispatchMap.values())
            {
                filterInfo.filter = CommandFilter.Builder
                    .from(filter)
                    .withInternalIDs(filterInfo.ids)
                    .build();
            }
            
            return filterDispatchMap;
        }
        
        // otherwise get dispatch map for command streams
        if (filter.getCommandStreamFilter() != null)
            commandStreamFilterDispatchMap = commandStreamStore.getFilterDispatchMap(filter.getCommandStreamFilter());
        
        if (commandStreamFilterDispatchMap != null)
        {
            for (var entry: commandStreamFilterDispatchMap.entrySet())
            {
                var commandStreamFilterInfo = entry.getValue();
                
                // only process DBs not already processed in first loop above
                if (!commandFilterDispatchMap.containsKey(entry.getKey()))
                {
                    var filterInfo = new ObsSystemDbFilterInfo();
                    filterInfo.db = commandStreamFilterInfo.db;
                    filterInfo.filter = CommandFilter.Builder.from(filter)
                        .withCommandStreams((CommandStreamFilter)commandStreamFilterInfo.filter)
                        .build();
                    commandFilterDispatchMap.put(entry.getKey(), filterInfo);
                }
            }
        }
        
        if (!commandFilterDispatchMap.isEmpty())
            return commandFilterDispatchMap;
        else
            return null;
    }


    @Override
    public Stream<Entry<BigId, ICommandData>> selectEntries(CommandFilter filter, Set<CommandField> fields)
    {
        final var cmdStreams = new ArrayList<Stream<Entry<BigId, ICommandData>>>(100);
        
        // if any kind of internal IDs are used, we need to dispatch the correct filter
        // to the corresponding DB so we create this map
        var filterDispatchMap = getFilterDispatchMap(filter);
        
        if (filterDispatchMap != null)
        {
            filterDispatchMap.values().stream()
                .forEach(v -> {
                    var cmdStream = v.db.getCommandStore().selectEntries((CommandFilter)v.filter, fields);
                    cmdStreams.add(cmdStream);
                });
        }
        else
        {
            parentDb.getAllObsDatabases().stream()
                .forEach(db -> {
                    var cmdStream = db.getCommandStore().selectEntries(filter, fields);
                    cmdStreams.add(cmdStream);
                });
        }
        
        if (cmdStreams.isEmpty())
            return Stream.empty();

        Comparator<Entry<BigId, ICommandData>> comparator = Comparator.comparing(e -> e.getValue().getIssueTime());
        if (filter.getIssueTime() != null && filter.getIssueTime().descendingOrder())
            comparator = comparator.reversed();

        // stream and merge commands from all selected command streams and time periods
        var mergeSortIt = new MergeSortSpliterator<Entry<BigId, ICommandData>>(cmdStreams, comparator);
               
        // stream output of merge sort iterator + apply limit
        return StreamSupport.stream(mergeSortIt, false)
            .limit(filter.getLimit())
            .onClose(() -> mergeSortIt.close());
    }


    @Override
    public Stream<CommandStats> getStatistics(CommandStatsQuery query)
    {
        var filter = query.getCommandFilter();
        
        // if any kind of internal IDs are used, we need to dispatch the correct filter
        // to the corresponding DB so we create this map
        var filterDispatchMap = getFilterDispatchMap(filter);
        
        if (filterDispatchMap != null)
        {
            return filterDispatchMap.values().stream()
                .flatMap(v -> {
                    var dbQuery = CommandStatsQuery.Builder.from(query)
                        .selectCommands((CommandFilter)v.filter)
                        .build();
                    return v.db.getCommandStore().getStatistics(dbQuery);
                })
                .limit(filter.getLimit());
        }
        else
        {
            return parentDb.getAllObsDatabases().stream()
                .flatMap(db -> db.getCommandStore().getStatistics(query))
                .limit(filter.getLimit());
        }
    }
    

    @Override
    public ICommandStreamStore getCommandStreams()
    {
        return commandStreamStore;
    }


    @Override
    public ICommandStatusStore getStatusReports()
    {
        return commandStatusStore;
    }
    
    
    @Override
    public BigId add(ICommandData cmd)
    {
        throw new UnsupportedOperationException(READ_ONLY_ERROR_MSG);
    }


    @Override
    public void linkTo(IFoiStore foiStore)
    {
        throw new UnsupportedOperationException();
    }

}
