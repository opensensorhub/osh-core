/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.database.system;

import java.util.Collection;
import java.util.Collections;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.database.DatabaseConfig;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.database.IObsSystemDatabaseModule;
import org.sensorhub.api.database.IObsSystemDbAutoPurgePolicy;
import org.sensorhub.api.database.IProcedureDatabase;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.command.ICommandStore;
import org.sensorhub.api.datastore.deployment.IDeploymentStore;
import org.sensorhub.api.datastore.feature.IFoiStore;
import org.sensorhub.api.datastore.obs.IObsStore;
import org.sensorhub.api.datastore.procedure.EmptyProcedureStore;
import org.sensorhub.api.datastore.procedure.IProcedureStore;
import org.sensorhub.api.datastore.property.EmptyPropertyStore;
import org.sensorhub.api.datastore.property.IPropertyStore;
import org.sensorhub.api.datastore.system.ISystemDescStore;
import org.sensorhub.api.module.IModule;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.sensorhub.api.system.ISystemDriverDatabase;
import org.sensorhub.impl.module.AbstractModule;
import org.vast.util.Asserts;


public class SystemDriverDatabase extends AbstractModule<SystemDriverDatabaseConfig>
    implements ISystemDriverDatabase, IObsSystemDatabaseModule<SystemDriverDatabaseConfig>
{
    IObsSystemDatabase db;
    long lastCommitTime = Long.MIN_VALUE;
    Timer autoPurgeTimer;
    
    
    @Override
    protected void doStart() throws SensorHubException
    {
        if (config.dbConfig == null)
            throw new DataStoreException("Underlying database configuration must be provided");
        
        // instantiate and start underlying storage
        DatabaseConfig dbConfig = null;
        try
        {
            dbConfig = (DatabaseConfig)config.dbConfig.clone();
            dbConfig.id = getLocalID();
            dbConfig.name = getName();
            dbConfig.databaseNum = config.databaseNum;
            
            var dbModule = getParentHub().getModuleRegistry().loadSubModule(dbConfig, true);
            dbModule.waitForState(ModuleState.INITIALIZED, 10000);
            dbModule.start();
            dbModule.waitForState(ModuleState.STARTED, 10000);
            
            this.db = (IObsSystemDatabase)dbModule;
            Asserts.checkNotNull(db.getSystemDescStore(), ISystemDescStore.class);
            Asserts.checkNotNull(db.getFoiStore(), IFoiStore.class);
            Asserts.checkNotNull(db.getObservationStore(), IObsStore.class);
            Asserts.checkState(!db.isReadOnly(), "Database is read-only");
        }
        catch (Exception e)
        {
            throw new DataStoreException("Cannot instantiate underlying database " + config.dbConfig.moduleClass, e);
        }

        if(!config.autoPurgeConfig.isEmpty())
            autoPurgeTimer = new Timer();
        
        // start auto-purge timer thread if policy is specified and enabled
        for(var autoPurgeConfig : config.autoPurgeConfig)
        {
            if (autoPurgeConfig != null && autoPurgeConfig.enabled)
            {
                var uids = Collections.unmodifiableCollection(autoPurgeConfig.systemUIDs);
                final IObsSystemDbAutoPurgePolicy policy = autoPurgeConfig.getPolicy();
                TimerTask task = new TimerTask() {
                    public void run()
                    {
                        if (!db.isReadOnly())
                            policy.trimStorage(db, logger, uids);
                    }
                };

                autoPurgeTimer.schedule(task, 0, (long)(autoPurgeConfig.purgePeriod*1000));
            }
        }
    }
    
    
    @Override
    protected void afterStart()
    {
        if (hasParentHub())
        {
            var systemUIDs = getHandledSystems();
            if (!systemUIDs.isEmpty())
                getParentHub().getSystemDriverRegistry().registerDatabase(systemUIDs, this);
            
            if (config.databaseNum != null)
                getParentHub().getDatabaseRegistry().register(this);
        }
    }
    
    
    @Override
    protected void beforeStop()
    {
        if (hasParentHub())
        {
            getParentHub().getSystemDriverRegistry().unregisterDatabase(this);
            
            if (config.databaseNum != null)
                getParentHub().getDatabaseRegistry().unregister(this);
        }
    }
    
    
    @Override
    protected synchronized void doStop() throws SensorHubException
    {
        if (autoPurgeTimer != null)
        {
            autoPurgeTimer.cancel();
            autoPurgeTimer = null;
        }
        
        if (db != null && db instanceof IModule<?>)
            ((IModule<?>)db).stop();
    }


    @Override
    public Collection<String> getHandledSystems()
    {
        return Collections.unmodifiableCollection(config.systemUIDs);
    }
    

    public Integer getDatabaseNum()
    {
        return config.databaseNum;
    }


    public <T> T executeTransaction(Callable<T> transaction) throws Exception
    {
        return db.executeTransaction(transaction);
    }


    public ISystemDescStore getSystemDescStore()
    {
        return db.getSystemDescStore();
    }


    public IFoiStore getFoiStore()
    {
        return db.getFoiStore();
    }


    public IDeploymentStore getDeploymentStore()
    {
        return db.getDeploymentStore();
    }


    public void commit()
    {
        db.commit();
    }


    @Override
    public boolean isOpen()
    {
        return db != null && db.isOpen();
    }


    @Override
    public boolean isReadOnly()
    {
        return db.isReadOnly();
    }


    public IObsStore getObservationStore()
    {
        return db.getObservationStore();
    }


    @Override
    public ICommandStore getCommandStore()
    {
        return db.getCommandStore();
    }


    @Override
    public IProcedureStore getProcedureStore()
    {
        return db instanceof IProcedureDatabase ?
            ((IProcedureDatabase)db).getProcedureStore() :
            new EmptyProcedureStore();
    }


    @Override
    public IPropertyStore getPropertyStore()
    {
        return db instanceof IProcedureDatabase ?
            ((IProcedureDatabase)db).getPropertyStore() :
            new EmptyPropertyStore();
    }
    
    
    public IObsSystemDatabase getWrappedDatabase()
    {
        return db;
    }
}
