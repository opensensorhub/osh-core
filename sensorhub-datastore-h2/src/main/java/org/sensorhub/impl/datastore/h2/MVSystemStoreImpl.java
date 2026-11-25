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

import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.h2.mvstore.MVBTreeMap;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.type.DataType;
import org.sensorhub.api.datastore.deployment.IDeploymentStore;
import org.sensorhub.api.datastore.obs.IDataStreamStore;
import org.sensorhub.api.datastore.procedure.IProcedureStore;
import org.sensorhub.api.datastore.system.ISystemDescStore;
import org.sensorhub.api.datastore.system.SystemFilter;
import org.sensorhub.api.datastore.system.ISystemDescStore.SystemField;
import org.sensorhub.api.system.ISystemWithDesc;
import org.vast.util.Asserts;
import net.opengis.sensorml.v20.AbstractProcess;
import org.sensorhub.impl.datastore.DataStoreUtils;
import org.sensorhub.impl.datastore.h2.MVDatabaseConfig.IdProviderType;


/**
 * <p>
 * System description store implementation based on H2 MVStore.<br/>
 * Most of the work is done in {@link MVBaseFeatureStoreImpl} 
 * </p>
 *
 * @author Alex Robin
 * @date Apr 8, 2018
 */
public class MVSystemStoreImpl extends MVBaseFeatureStoreImpl<ISystemWithDesc, SystemField, SystemFilter> implements ISystemDescStore
{
    IDataStreamStore dataStreamStore;
    IDeploymentStore deploymentStore;
    IProcedureStore procedureStore;
    
    
    static class SystemValidTimeAdapter extends FeatureValidTimeAdapter<ISystemWithDesc> implements ISystemWithDesc
    {
        public SystemValidTimeAdapter(MVFeatureParentKey fk, ISystemWithDesc f, MVBTreeMap<MVFeatureParentKey, ISystemWithDesc> featuresIndex)
        {
            super(fk, f, featuresIndex);
        }
        
        @Override
        public AbstractProcess getFullDescription()
        {
            return ((ISystemWithDesc)f).getFullDescription();
        }
    }
    
    
    protected MVSystemStoreImpl()
    {
    }
    
    
    /**
     * Opens an existing system store or create a new one with the specified name
     * @param mvStore MVStore instance containing the required maps
     * @param idScope Internal ID scope (database num)
     * @param idProviderType Type of ID provider to use to generate new IDs
     * @param newStoreInfo Data store info to use if a new store needs to be created
     * @return The existing datastore instance 
     */
    public static MVSystemStoreImpl open(MVStore mvStore, int idScope, IdProviderType idProviderType, MVDataStoreInfo newStoreInfo)
    {
        var dataStoreInfo = H2Utils.getDataStoreInfo(mvStore, newStoreInfo.getName());
        if (dataStoreInfo == null)
        {
            dataStoreInfo = newStoreInfo;
            H2Utils.addDataStoreInfo(mvStore, dataStoreInfo);
        }
        
        return (MVSystemStoreImpl)new MVSystemStoreImpl().init(mvStore, idScope, idProviderType, dataStoreInfo);
    }
    
    
    @Override
    protected DataType<Object> getFeatureDataType(MVMap<String, Integer> kryoClassMap)
    {
        return new SensorMLDataType(kryoClassMap);
    }
    
    
    @Override
    protected Stream<Entry<MVFeatureParentKey, ISystemWithDesc>> getIndexedStream(SystemFilter filter)
    {
        var resultStream = super.getIndexedStream(filter);
        
        if (filter.getParentFilter() != null)
        {
            var parentIDStream = DataStoreUtils.selectFeatureIDs(this, filter.getParentFilter());
            
            if (resultStream == null)
            {
                resultStream = parentIDStream
                    .flatMap(id -> getParentResultStream(id, filter.getValidTime()));
            }
            else
            {
                var parentIDs = parentIDStream
                    .map(id -> id.getIdAsLong())
                    .collect(Collectors.toSet());
                
                resultStream = resultStream.filter(
                    e -> parentIDs.contains(e.getKey().getParentID()));
                
                // post filter using keys valid time if needed
                if (filter.getValidTime() != null)
                    resultStream = postFilterKeyValidTime(resultStream, filter.getValidTime());
            }
        }
        
        if (filter.getDataStreamFilter() != null)
        {
            var sysIDs = DataStoreUtils.selectDataStreams(dataStreamStore, filter.getDataStreamFilter())
                .map(ds -> ds.getSystemID().getInternalID())
                .collect(Collectors.toSet());
            
            if (resultStream == null)
            {            
                return super.getIndexedStream(SystemFilter.Builder.from(filter)
                    .withInternalIDs(sysIDs)
                    .build());
            }
            else
            {
                resultStream = resultStream
                    .filter(e -> sysIDs.contains(e.getKey().getInternalID()));
            }
        }
        
        if (filter.getProcedureFilter() != null)
        {
            var procUIDs = DataStoreUtils.selectProcedureUIDs(procedureStore, filter.getProcedureFilter())
                .collect(Collectors.toSet());
            
            if (resultStream == null)
                resultStream = featuresIndex.entrySet().stream();
            
            resultStream = resultStream
               .filter(e -> {
                   var sml = e.getValue().getFullDescription();
                   if (sml == null)
                       return false;
                   var typeOf = e.getValue().getFullDescription().getTypeOf();
                   if (typeOf == null)
                       return false;
                   return (typeOf.getHref() != null && procUIDs.contains(typeOf.getHref())) ||
                          (typeOf.getTitle() != null && procUIDs.contains(typeOf.getTitle())) ||
                          (typeOf.getTargetUID() != null && procUIDs.contains(typeOf.getTargetUID()));
               });
        }
        
        return resultStream;
    }


    /*@Override
    public Stream<Entry<FeatureKey, ISystemWithDesc>> selectEntries(SystemFilter filter, Set<SystemField> fields)
    {
        // update validTime in the case it ends at now and there is a
        // more recent version of the system description available
        Stream<Entry<FeatureKey, ISystemWithDesc>> resultStream = super.selectEntriesNoLimit(filter, fields).map(e -> {
            var sys = e.getValue();
            var sysWrapper = new ISystemWithDesc()
            {
                TimeExtent validTime;
                
                public String getId() { return sys.getId(); }
                public String getUniqueIdentifier() { return sys.getUniqueIdentifier(); }
                public String getName() { return sys.getName(); }
                public String getDescription() { return sys.getDescription(); }
                public Map<QName, Object> getProperties() { return sys.getProperties(); }
                public AbstractProcess getFullDescription() { return sys.getFullDescription(); }  
                
                public TimeExtent getValidTime()
                {
                    if (validTime == null)
                    {
                        var nextKey = featuresIndex.higherKey((MVFeatureParentKey)e.getKey());
                        if (nextKey != null && sys.getValidTime() != null && sys.getValidTime().endsNow() &&
                            nextKey.getInternalID().getIdAsLong() == e.getKey().getInternalID().getIdAsLong())
                        {
                            validTime = TimeExtent.period(sys.getValidTime().begin(), nextKey.getValidStartTime());
                        }
                        else
                            validTime = sys.getValidTime();
                    }
                    
                    return validTime;
                }
            };
            
            return new DataUtils.MapEntry<>(e.getKey(), sysWrapper);
        });
        
        // apply post filter on time now that we computed the correct valid time period
        if (filter.getValidTime() != null)
            resultStream = resultStream.filter(e -> filter.testValidTime(e.getValue()));
        
        // apply limit
        if (filter.getLimit() < Long.MAX_VALUE)
            resultStream = resultStream.limit(filter.getLimit());
        
        return resultStream;
    }*/
    
    
    protected ISystemWithDesc getFeatureWithAdjustedValidTime(MVFeatureParentKey fk, ISystemWithDesc sys)
    {
        return new SystemValidTimeAdapter(fk, sys, featuresIndex);
    }


    @Override
    public long countMatchingEntries(SystemFilter filter)
    {
        if (filter.getValuePredicate() == null &&
            filter.getLocationFilter() == null &&
            filter.getFullTextFilter() == null &&
            filter.getValidTime() == null &&
            filter.getParentFilter() == null &&
            filter.getDataStreamFilter() == null &&
            filter.getProcedureFilter() == null)
        {
            return featuresIndex.sizeAsLong();
        }
        
        return selectEntries(filter).count();
    }


    @Override
    public void linkTo(IDataStreamStore dataStreamStore)
    {
        this.dataStreamStore = Asserts.checkNotNull(dataStreamStore, IDataStreamStore.class);
    }


    @Override
    public void linkTo(IProcedureStore procedureStore)
    {
        this.procedureStore = Asserts.checkNotNull(procedureStore, IProcedureStore.class);
    }


    @Override
    public void linkTo(IDeploymentStore deploymentStore)
    {
        this.deploymentStore = Asserts.checkNotNull(deploymentStore, IDeploymentStore.class);
    }

}
