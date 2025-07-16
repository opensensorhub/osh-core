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

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.database.IDatabaseRegistry;
import org.sensorhub.api.database.IFeatureDatabase;
import org.sensorhub.api.database.IFederatedDatabase;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.database.IProcedureDatabase;
import org.sensorhub.api.datastore.IQueryFilter;
import org.sensorhub.api.datastore.command.ICommandStore;
import org.sensorhub.api.datastore.deployment.IDeploymentStore;
import org.sensorhub.api.datastore.feature.IFeatureStore;
import org.sensorhub.api.datastore.feature.IFoiStore;
import org.sensorhub.api.datastore.obs.IObsStore;
import org.sensorhub.api.datastore.procedure.IProcedureStore;
import org.sensorhub.api.datastore.property.IPropertyStore;
import org.sensorhub.api.datastore.system.ISystemDescStore;
import org.sensorhub.impl.datastore.ReadOnlyDataStore;


public class FederatedDatabase implements IFederatedDatabase
{
    final FederatedSystemDescStore systemStore;
    final FederatedFoiStore foiStore;
    final FederatedObsStore obsStore;
    final FederatedCommandStore commandStore;
    final FederatedDeploymentStore deploymentStore;
    final FederatedProcedureStore procedureStore;
    final FederatedPropertyStore propertyStore;
    final FederatedFeatureStore featureStore;
    final IDatabaseRegistry registry;
    
    
    static class LocalFilterInfo<T>
    {
        T db;
        Set<BigId> ids = new TreeSet<>();
        IQueryFilter filter;
    }
    
    static class ObsSystemDbFilterInfo extends LocalFilterInfo<IObsSystemDatabase> { }
    static class ProcedureDbFilterInfo extends LocalFilterInfo<IProcedureDatabase> { }
    static class FeatureDbFilterInfo extends LocalFilterInfo<IFeatureDatabase> { }
    

    public FederatedDatabase(IDatabaseRegistry registry)
    {
        this.registry = registry;
        this.systemStore = new FederatedSystemDescStore(this);
        this.foiStore = new FederatedFoiStore(this);
        this.obsStore = new FederatedObsStore(this);
        this.commandStore = new FederatedCommandStore(this);
        this.deploymentStore = new FederatedDeploymentStore(this);
        this.procedureStore = new FederatedProcedureStore(this);
        this.propertyStore = new FederatedPropertyStore(this);
        this.featureStore = new FederatedFeatureStore(this);
    }
    
    
    protected IObsSystemDatabase getObsSystemDatabase(BigId id)
    {
        return registry.getObsDatabaseByNum(id.getScope());
    }
    
    
    protected IProcedureDatabase getProcedureDatabase(BigId id)
    {
        return registry.getProcedureDatabaseByNum(id.getScope());
    }
    
    
    protected IFeatureDatabase getFeatureDatabase(BigId id)
    {
        return registry.getFeatureDatabaseByNum(id.getScope());
    }
    
    
    /*
     * Get map with an entry for each local DB
     */
    protected Map<Integer, ObsSystemDbFilterInfo> getObsDbFilterDispatchMap(Set<BigId> idList)
    {
        Map<Integer, ObsSystemDbFilterInfo> map = new LinkedHashMap<>();
        
        for (var id: idList)
        {
            var db = getObsSystemDatabase(id);
            if (db == null)
                continue;
                
            var filterInfo = map.computeIfAbsent(db.getDatabaseNum(), k -> new ObsSystemDbFilterInfo());
            filterInfo.db = db;
            filterInfo.ids.add(id);
        }
        
        return map;
    }
    
    
    protected Map<Integer, ProcedureDbFilterInfo> getProcDbFilterDispatchMap(Set<BigId> idList)
    {
        Map<Integer, ProcedureDbFilterInfo> map = new LinkedHashMap<>();
        
        for (var id: idList)
        {
            var db = getProcedureDatabase(id);
            if (db == null)
                continue;
                
            var filterInfo = map.computeIfAbsent(db.getDatabaseNum(), k -> new ProcedureDbFilterInfo());
            filterInfo.db = db;
            filterInfo.ids.add(id);
        }
        
        return map;
    }
    
    
    protected Map<Integer, FeatureDbFilterInfo> getFeatureDbFilterDispatchMap(Set<BigId> idList)
    {
        Map<Integer, FeatureDbFilterInfo> map = new LinkedHashMap<>();
        
        for (var id: idList)
        {
            var db = getFeatureDatabase(id);
            if (db == null)
                continue;
                
            var filterInfo = map.computeIfAbsent(db.getDatabaseNum(), k -> new FeatureDbFilterInfo());
            filterInfo.db = db;
            filterInfo.ids.add(id);
        }
        
        return map;
    }
    
    
    @Override
    public ISystemDescStore getSystemDescStore()
    {
        return systemStore;
    }


    @Override
    public IObsStore getObservationStore()
    {
        return obsStore;
    }


    @Override
    public IFoiStore getFoiStore()
    {
        return foiStore;
    }


    @Override
    public ICommandStore getCommandStore()
    {
        return commandStore;
    }


    @Override
    public IDeploymentStore getDeploymentStore()
    {
        return deploymentStore;
    }


    @Override
    public IProcedureStore getProcedureStore()
    {
        return procedureStore;
    }


    @Override
    public IPropertyStore getPropertyStore()
    {
        return propertyStore;
    }


    @Override
    public IFeatureStore getFeatureStore()
    {
        return featureStore;
    }
    
    
    protected Collection<IObsSystemDatabase> getAllObsDatabases()
    {
        return registry.getObsSystemDatabases();
    }
    
    
    protected Collection<IProcedureDatabase> getAllProcDatabases()
    {
        return registry.getProcedureDatabases();
    }
    
    
    protected Collection<IFeatureDatabase> getAllFeatureDatabases()
    {
        return registry.getFeatureDatabases();
    }


    @Override
    public void commit()
    {
        throw new UnsupportedOperationException(ReadOnlyDataStore.READ_ONLY_ERROR_MSG);
    }


    @Override
    public <T> T executeTransaction(Callable<T> transaction) throws Exception
    {
        throw new UnsupportedOperationException(ReadOnlyDataStore.READ_ONLY_ERROR_MSG);
    }


    @Override
    public Integer getDatabaseNum()
    {
        // should never be called on the federated database
        throw new UnsupportedOperationException();
    }


    @Override
    public boolean isOpen()
    {
        return true;
    }


    @Override
    public boolean isReadOnly()
    {
        return true;
    }
}
