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
import org.sensorhub.api.datastore.deployment.DeploymentFilter;
import org.sensorhub.api.datastore.deployment.IDeploymentStore;
import org.sensorhub.api.datastore.deployment.IDeploymentStore.DeploymentField;
import org.sensorhub.api.datastore.system.ISystemDescStore;
import org.sensorhub.api.system.IDeploymentWithDesc;
import org.vast.util.Asserts;
import net.opengis.sensorml.v20.Deployment;
import org.sensorhub.impl.datastore.DataStoreUtils;
import org.sensorhub.impl.datastore.h2.MVDatabaseConfig.IdProviderType;


/**
 * <p>
 * Deployment description store implementation based on H2 MVStore.<br/>
 * Most of the work is done in {@link MVBaseFeatureStoreImpl} 
 * </p>
 *
 * @author Alex Robin
 * @since June 22, 2023
 */
public class MVDeploymentStoreImpl extends MVBaseFeatureStoreImpl<IDeploymentWithDesc, DeploymentField, DeploymentFilter> implements IDeploymentStore
{
    ISystemDescStore systemStore;
    
    
    static class DeploymentValidTimeAdapter extends FeatureValidTimeAdapter<IDeploymentWithDesc> implements IDeploymentWithDesc
    {
        public DeploymentValidTimeAdapter(MVFeatureParentKey fk, IDeploymentWithDesc f, MVBTreeMap<MVFeatureParentKey, IDeploymentWithDesc> featuresIndex)
        {
            super(fk, f, featuresIndex);
        }
        
        @Override
        public Deployment getFullDescription()
        {
            return ((IDeploymentWithDesc)f).getFullDescription();
        }
    }
    
    
    protected MVDeploymentStoreImpl()
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
    public static MVDeploymentStoreImpl open(MVStore mvStore, int idScope, IdProviderType idProviderType, MVDataStoreInfo newStoreInfo)
    {
        var dataStoreInfo = H2Utils.getDataStoreInfo(mvStore, newStoreInfo.getName());
        if (dataStoreInfo == null)
        {
            dataStoreInfo = newStoreInfo;
            H2Utils.addDataStoreInfo(mvStore, dataStoreInfo);
        }
        
        return (MVDeploymentStoreImpl)new MVDeploymentStoreImpl().init(mvStore, idScope, idProviderType, dataStoreInfo);
    }
    
    
    @Override
    protected DataType<Object> getFeatureDataType(MVMap<String, Integer> kryoClassMap)
    {
        return new SensorMLDataType(kryoClassMap);
    }
    
    
    @Override
    protected Stream<Entry<MVFeatureParentKey, IDeploymentWithDesc>> getIndexedStream(DeploymentFilter filter)
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
        
        if (filter.getSystemFilter() != null)
        {
            var sysUIDs = DataStoreUtils.selectSystemUIDs(systemStore, filter.getSystemFilter())
                .collect(Collectors.toSet());
            
            if (resultStream == null)
                resultStream = featuresIndex.entrySet().stream();
            
            resultStream = resultStream
               .filter(e -> {
                   var members = e.getValue().getFullDescription().getDeployedSystemList();
                   return members.stream()
                       .anyMatch(sys -> sysUIDs.contains(sys.getSystemRef().getTargetUID()));
               });
        }
        
        return resultStream;
    }
    
    
    protected IDeploymentWithDesc getFeatureWithAdjustedValidTime(MVFeatureParentKey fk, IDeploymentWithDesc sys)
    {
        return new DeploymentValidTimeAdapter(fk, sys, featuresIndex);
    }


    @Override
    public long countMatchingEntries(DeploymentFilter filter)
    {
        if (filter.getValuePredicate() == null &&
            filter.getLocationFilter() == null &&
            filter.getFullTextFilter() == null &&
            filter.getValidTime() == null &&
            filter.getParentFilter() == null &&
            filter.getSystemFilter() == null)
        {
            return featuresIndex.sizeAsLong();
        }
        
        return selectEntries(filter).count();
    }


    @Override
    public void linkTo(ISystemDescStore systemStore)
    {
        this.systemStore = Asserts.checkNotNull(systemStore, ISystemDescStore.class);
    }

}
