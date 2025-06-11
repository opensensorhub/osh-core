/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.ui;

import java.util.concurrent.Callable;
import org.sensorhub.api.ISensorHub;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.database.DatabaseConfig;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.database.IObsSystemDatabaseModule;
import org.sensorhub.api.datastore.command.ICommandStore;
import org.sensorhub.api.datastore.deployment.IDeploymentStore;
import org.sensorhub.api.datastore.feature.IFoiStore;
import org.sensorhub.api.datastore.obs.IDataStreamStore;
import org.sensorhub.api.datastore.obs.IObsStore;
import org.sensorhub.api.datastore.system.ISystemDescStore;
import org.sensorhub.api.event.IEventListener;
import org.sensorhub.api.module.IModuleStateManager;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.slf4j.Logger;
import org.vast.util.Asserts;


public class FederatedDbModuleAdapter implements IObsSystemDatabaseModule<DatabaseConfig>
{
    final IObsSystemDatabase delegate;
    final ISensorHub hub;
    
    
    public FederatedDbModuleAdapter(ISensorHub hub)
    {
        this.hub = Asserts.checkNotNull(hub, ISensorHub.class);
        this.delegate = Asserts.checkNotNull(hub.getDatabaseRegistry().getFederatedDatabase(), IObsSystemDatabase.class);
    }
    

    public Integer getDatabaseNum()
    {
        return delegate.getDatabaseNum();
    }


    public <T> T executeTransaction(Callable<T> transaction) throws Exception
    {
        return delegate.executeTransaction(transaction);
    }


    public void commit()
    {
        delegate.commit();
    }


    public ISystemDescStore getSystemDescStore()
    {
        return delegate.getSystemDescStore();
    }


    @Override
    public IDeploymentStore getDeploymentStore()
    {
        return delegate.getDeploymentStore();
    }


    public IFoiStore getFoiStore()
    {
        return delegate.getFoiStore();
    }


    public IObsStore getObservationStore()
    {
        return delegate.getObservationStore();
    }


    @Override
    public ICommandStore getCommandStore()
    {
        return delegate.getCommandStore();
    }


    public boolean isOpen()
    {
        return delegate.isOpen();
    }


    public IDataStreamStore getDataStreamStore()
    {
        return delegate.getDataStreamStore();
    }


    public boolean isReadOnly()
    {
        return delegate.isReadOnly();
    }


    @Override
    public void setParentHub(ISensorHub hub)
    {                
    }


    @Override
    public ISensorHub getParentHub()
    {
        return hub;
    }


    @Override
    public void setConfiguration(DatabaseConfig config)
    {
    }


    @Override
    public DatabaseConfig getConfiguration()
    {
        return new DatabaseConfig();
    }


    @Override
    public String getName()
    {
        return "Federated Database";
    }


    @Override
    public String getDescription()
    {
        return "The federated database that provides access to all observation data and system metadata on this hub";
    }


    @Override
    public String getLocalID()
    {
        return "$$FEDERATED_DB";
    }


    @Override
    public boolean isInitialized()
    {
        return true;
    }


    @Override
    public boolean isStarted()
    {
        return true;
    }


    @Override
    public ModuleState getCurrentState()
    {
        return ModuleState.STARTED;
    }


    @Override
    public boolean waitForState(ModuleState state, long timeout)
    {
        return false;
    }


    @Override
    public String getStatusMessage()
    {
        return null;
    }


    @Override
    public Throwable getCurrentError()
    {
        return null;
    }


    @Override
    public void init() throws SensorHubException
    {        
    }


    @Override
    public void init(DatabaseConfig config) throws SensorHubException
    {        
    }


    @Override
    public void updateConfig(DatabaseConfig config) throws SensorHubException
    {        
    }


    @Override
    public void start() throws SensorHubException
    {        
    }


    @Override
    public void stop() throws SensorHubException
    {        
    }


    @Override
    public void saveState(IModuleStateManager saver) throws SensorHubException
    {        
    }


    @Override
    public void loadState(IModuleStateManager loader) throws SensorHubException
    {        
    }


    @Override
    public void cleanup() throws SensorHubException
    {        
    }


    @Override
    public void registerListener(IEventListener listener)
    {        
    }


    @Override
    public void unregisterListener(IEventListener listener)
    {        
    }


    @Override
    public Logger getLogger()
    {
        return null;
    }
    
}
