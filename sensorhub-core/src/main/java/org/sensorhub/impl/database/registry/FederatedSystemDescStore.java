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

import java.util.Map;
import java.util.TreeMap;
import org.sensorhub.api.database.IDatabaseRegistry;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.obs.DataStreamFilter;
import org.sensorhub.api.datastore.obs.IDataStreamStore;
import org.sensorhub.api.datastore.system.ISystemDescStore;
import org.sensorhub.api.datastore.system.SystemFilter;
import org.sensorhub.api.datastore.system.ISystemDescStore.SystemField;
import org.sensorhub.api.system.ISystemWithDesc;
import org.sensorhub.impl.database.registry.FederatedObsDatabase.LocalFilterInfo;


/**
 * <p>
 * Implementation of system store that provides federated read-only access
 * to several underlying databases.
 * </p>
 *
 * @author Alex Robin
 * @date Oct 3, 2019
 */
public class FederatedSystemDescStore extends FederatedBaseFeatureStore<ISystemWithDesc, SystemField, SystemFilter> implements ISystemDescStore
{
    
    FederatedSystemDescStore(IDatabaseRegistry registry, FederatedObsDatabase db)
    {
        super(registry, db);
    }
    
    
    protected ISystemDescStore getFeatureStore(IObsSystemDatabase db)
    {
        return db.getSystemDescStore();
    }
    
    
    protected Map<Integer, LocalFilterInfo> getFilterDispatchMap(SystemFilter filter)
    {
        Map<Integer, LocalFilterInfo> dataStreamFilterDispatchMap = null;
        Map<Integer, LocalFilterInfo> parentFilterDispatchMap = null;
        Map<Integer, LocalFilterInfo> procFilterDispatchMap = new TreeMap<>();
        
        if (filter.getInternalIDs() != null)
        {
            var filterDispatchMap = parentDb.getFilterDispatchMap(filter.getInternalIDs());
            for (var filterInfo: filterDispatchMap.values())
            {
                filterInfo.filter = SystemFilter.Builder
                    .from(filter)
                    .withInternalIDs(filterInfo.internalIds)
                    .build();
            }
            
            return filterDispatchMap;
        }
        
        // otherwise get dispatch map for datastreams and parent systems
        if (filter.getDataStreamFilter() != null)
            dataStreamFilterDispatchMap = parentDb.obsStore.dataStreamStore.getFilterDispatchMap(filter.getDataStreamFilter());
        
        if (filter.getParentFilter() != null)
        {
            // if parent ID 0 is selected (meaning top level resource)
            // skip because we need to search all databases
            var parentFilter = filter.getParentFilter();
            if (parentFilter.getInternalIDs() == null || parentFilter.getInternalIDs().first() != 0)
                parentFilterDispatchMap = getFilterDispatchMap(filter.getParentFilter());
        }
        
        // merge both maps
        if (dataStreamFilterDispatchMap != null)
        {
            for (var entry: dataStreamFilterDispatchMap.entrySet())
            {
                var dataStreamFilterInfo = entry.getValue();
                                
                var builder = SystemFilter.Builder
                    .from(filter)
                    .withDataStreams((DataStreamFilter)dataStreamFilterInfo.filter);
                
                var parentfilterInfo = parentFilterDispatchMap != null ? parentFilterDispatchMap.get(entry.getKey()) : null;
                if (parentfilterInfo != null)
                    builder.withParents((SystemFilter)parentfilterInfo.filter);
                    
                var filterInfo = new LocalFilterInfo();
                filterInfo.databaseNum = dataStreamFilterInfo.databaseNum;
                filterInfo.db = dataStreamFilterInfo.db;
                filterInfo.filter = builder.build();
                procFilterDispatchMap.put(entry.getKey(), filterInfo);
            }
        }
        
        if (parentFilterDispatchMap != null)
        {
            for (var entry: parentFilterDispatchMap.entrySet())
            {
                var parentFilterInfo = entry.getValue();
                
                // only process DBs not already processed in first loop above
                if (!procFilterDispatchMap.containsKey(entry.getKey()))
                {
                    var filterInfo = new LocalFilterInfo();
                    filterInfo.databaseNum = parentFilterInfo.databaseNum;
                    filterInfo.db = parentFilterInfo.db;
                    filterInfo.filter = SystemFilter.Builder.from(filter)
                        .withParents((SystemFilter)parentFilterInfo.filter)
                        .build();
                    procFilterDispatchMap.put(entry.getKey(), filterInfo);
                }
            }
        }
        
        if (!procFilterDispatchMap.isEmpty())
            return procFilterDispatchMap;
        else
            return null;
    }


    @Override
    public void linkTo(IDataStreamStore dataStreamStore)
    {
        throw new UnsupportedOperationException();        
    }

}