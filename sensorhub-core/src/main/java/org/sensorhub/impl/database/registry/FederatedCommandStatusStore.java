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
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.sensorhub.api.command.ICommandData;
import org.sensorhub.api.command.ICommandStatus;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.datastore.command.CommandFilter;
import org.sensorhub.api.datastore.command.CommandStatusFilter;
import org.sensorhub.api.datastore.command.ICommandStatusStore;
import org.sensorhub.api.datastore.command.ICommandStatusStore.CommandStatusField;
import org.sensorhub.impl.database.registry.FederatedDatabase.ObsSystemDbFilterInfo;
import org.sensorhub.impl.datastore.MergeSortSpliterator;
import org.sensorhub.impl.datastore.ReadOnlyDataStore;
import org.vast.util.Asserts;


/**
 * <p>
 * Implementation of command status store that provides federated read-only
 * access to several underlying databases.
 * </p>
 *
 * @author Alex Robin
 * @date Mar 24, 2021
 */
public class FederatedCommandStatusStore extends ReadOnlyDataStore<BigId, ICommandStatus, CommandStatusField, CommandStatusFilter> implements ICommandStatusStore
{
    final FederatedDatabase parentDb;
    final FederatedCommandStore commandStore;
    
    
    FederatedCommandStatusStore(FederatedDatabase db, FederatedCommandStore cmdStore)
    {
        this.parentDb = Asserts.checkNotNull(db, FederatedDatabase.class);
        this.commandStore = cmdStore;
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
    public ICommandStatus get(Object obj)
    {
        BigId key = ensureCommandKey(obj);
        
        // delegate to database identified by id scope
        var db = parentDb.getObsSystemDatabase(key);
        if (db != null)
            return db.getCommandStatusStore().get(key);
        else
            return null;
    }
    
    
    protected Map<Integer, ObsSystemDbFilterInfo> getFilterDispatchMap(CommandStatusFilter filter)
    {
        if (filter.getCommandFilter() != null)
        {
            // delegate to command store to get filter dispatch map
            var filterDispatchMap = commandStore.getFilterDispatchMap(filter.getCommandFilter());
            if (filterDispatchMap != null)
            {
                for (var filterInfo: filterDispatchMap.values())
                {
                    filterInfo.filter = CommandStatusFilter.Builder
                        .from(filter)
                        .withCommands((CommandFilter)filterInfo.filter)
                        .build();
                }
            }
            
            return filterDispatchMap;
        }
        
        return null;
    }


    @Override
    public Stream<Entry<BigId, ICommandStatus>> selectEntries(CommandStatusFilter filter, Set<CommandStatusField> fields)
    {
        final var cmdStreams = new ArrayList<Stream<Entry<BigId, ICommandStatus>>>(100);
        
        // if any kind of internal IDs are used, we need to dispatch the correct filter
        // to the corresponding DB so we create this map
        var filterDispatchMap = getFilterDispatchMap(filter);
        
        if (filterDispatchMap != null)
        {
            filterDispatchMap.values().stream()
                .forEach(v -> {
                    var cmdStream = v.db.getCommandStatusStore().selectEntries((CommandStatusFilter)v.filter, fields);
                    cmdStreams.add(cmdStream);
                });
        }
        else
        {
            parentDb.getAllObsDatabases().stream()
                .forEach(db -> {
                    var cmdStream = db.getCommandStatusStore().selectEntries(filter, fields);
                    cmdStreams.add(cmdStream);
                });
        }
        
        if (cmdStreams.isEmpty())
            return Stream.empty();

        Comparator<Entry<BigId, ICommandStatus>> comparator = Comparator.comparing(e -> e.getValue().getReportTime());
        if (filter.getReportTime() != null && filter.getReportTime().descendingOrder())
            comparator = comparator.reversed();
        
        // stream and merge commands from all selected command streams and time periods
        var mergeSortIt = new MergeSortSpliterator<Entry<BigId, ICommandStatus>>(cmdStreams, comparator);
               
        // stream output of merge sort iterator + apply limit
        return StreamSupport.stream(mergeSortIt, false)
            .limit(filter.getLimit())
            .onClose(() -> mergeSortIt.close());
    }
    
    
    @Override
    public BigId add(ICommandStatus cmd)
    {
        throw new UnsupportedOperationException(READ_ONLY_ERROR_MSG);
    }

}
