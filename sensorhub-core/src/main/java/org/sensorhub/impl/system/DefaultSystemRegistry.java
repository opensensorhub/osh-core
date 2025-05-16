/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.system;

import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.sensorhub.api.ISensorHub;
import org.sensorhub.api.command.IStreamingControlInterface;
import org.sensorhub.api.data.DataStreamEvent;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.data.IStreamingDataInterface;
import org.sensorhub.api.database.DatabaseConfig;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.database.ISystemStateDatabase;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.command.CommandStreamFilter;
import org.sensorhub.api.datastore.obs.DataStreamFilter;
import org.sensorhub.api.datastore.obs.DataStreamKey;
import org.sensorhub.api.datastore.system.SystemFilter;
import org.sensorhub.api.event.EventUtils;
import org.sensorhub.api.procedure.IProcedureWithDesc;
import org.sensorhub.api.system.ISystemDriver;
import org.sensorhub.api.system.ISystemDriverRegistry;
import org.sensorhub.api.utils.OshAsserts;
import org.sensorhub.impl.database.system.SystemDriverDatabase;
import org.sensorhub.impl.database.system.SystemDriverDatabaseConfig;
import org.sensorhub.impl.system.wrapper.SystemWrapper;
import org.sensorhub.utils.MapWithWildcards;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.ogc.gml.IFeature;
import org.vast.util.Asserts;


/**
 * <p>
 * Implementation of system registry backed by configurable datastores.
 * </p><p>
 * Transaction handlers are used to handle events received from all registered
 * drivers, persist data to the configured databases and send corresponding
 * events to the hub event bus.
 * </p>
 *
 * @author Alex Robin
 * @date Sep 7, 2019
 */
public class DefaultSystemRegistry implements ISystemDriverRegistry
{
    static final Logger log = LoggerFactory.getLogger(DefaultSystemRegistry.class);
    
    ISensorHub hub;
    IObsSystemDatabase federatedDb;
    SystemDriverDatabase systemStateDb;
    MapWithWildcards<IObsSystemDatabase> obsSystemDatabases;
    ReadWriteLock lock = new ReentrantReadWriteLock();
    Map<String, SystemDriverTransactionHandler> driverHandlers = new ConcurrentSkipListMap<>();
    Executor executor = ForkJoinPool.commonPool();
    

    public DefaultSystemRegistry(ISensorHub hub, DatabaseConfig stateDbConfig)
    {
        this.hub = Asserts.checkNotNull(hub, ISensorHub.class);
        initDatabase(stateDbConfig);
    }


    void initDatabase(DatabaseConfig stateDbConfig)
    {
        this.federatedDb = hub.getDatabaseRegistry().getFederatedDatabase();
        this.obsSystemDatabases = new MapWithWildcards<>();
        
        try
        {
            SystemDriverDatabaseConfig dbListenerConfig = new SystemDriverDatabaseConfig();
            dbListenerConfig.databaseNum = 0;
            dbListenerConfig.dbConfig = stateDbConfig;

            systemStateDb = new SystemDriverDatabase();
            systemStateDb.setParentHub(hub);
            systemStateDb.init(dbListenerConfig);
            systemStateDb.start();
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Error initializing system state database", e);
        }
    }


    @Override
    public CompletableFuture<Void> register(IProcedureWithDesc proc)
    {
        // TODO Auto-generated method stub
        return null;
    }


    public CompletableFuture<Boolean> register(ISystemDriver driver)
    {
        Asserts.checkNotNull(driver, ISystemDriver.class);
        String sysUID = OshAsserts.checkValidUID(driver.getUniqueIdentifier());
        log.debug("Registering driver {}", sysUID);
        
        // if member of a group, parent should have been registered already
        // so register it as member of the group
        if (driver.getParentSystemUID() != null)
        {
            // get parent handler and register as member
            var parentHandler = getDriverHandler(driver.getParentSystemUID());
            return parentHandler.registerMember(driver);
        }
        
        // add or replace handler for this system driver
        return CompletableFuture.supplyAsync(() -> {
            
            var oldHandler = driverHandlers.remove(sysUID);
            var isNew = oldHandler == null;
            if (!isNew)
            {
                // only allow the same driver to re-register with same UID
                ISystemDriver registeredDriver = oldHandler.driver;
                if (registeredDriver != null && registeredDriver != driver)
                    throw new IllegalArgumentException("A system with UID " + sysUID + " is already registered");
                
                // cleanup previous handler
                oldHandler.doUnregister(false);
            }
            
            DefaultSystemRegistry.log.info("Registering system {}", driver.getUniqueIdentifier());
            var db = getDatabaseForDriver(driver);
            var baseHandler = new SystemRegistryTransactionHandler(hub, db, executor);
            
            // create or update entry in DB
            try
            {
                var procWrapper = new SystemWrapper(driver.getCurrentDescription())
                    .hideOutputs()
                    .hideTaskableParams()
                    .defaultToValidFromNow();
                    
                var newHandler = (SystemDriverTransactionHandler)baseHandler.addOrUpdateSystem(procWrapper);
                newHandler.doFinishRegister(driver);
                driverHandlers.put(sysUID,  newHandler);
            }
            catch (DataStoreException e)
            {
                throw new CompletionException("Error registering system " + sysUID, e);
            }
            
            return isNew;
        }, executor);
    }
    
    
    public boolean isRegistered(String uid)
    {
        // Check if registered as top level system
        if(driverHandlers.containsKey(uid))
            return true;

        // Check if registered under a system group driver
        for(var handler : driverHandlers.values())
        {
            if(handler.memberHandlers.containsKey(uid))
                return true;
        }

        return false;
    }
    
    
    protected SystemDriverTransactionHandler getDriverHandler(String sysUID)
    {
        var handler = driverHandlers.get(sysUID);
        if (handler == null)
            throw new IllegalStateException("No driver registered for system " + sysUID + ", or it's managed by a parent system");
        
        return handler;
    }


    @Override
    public CompletableFuture<Void> unregister(ISystemDriver proc)
    {
        Asserts.checkNotNull(proc, ISystemDriver.class);
        String sysUID = OshAsserts.checkValidUID(proc.getUniqueIdentifier());
        
        var handler = driverHandlers.remove(sysUID);
        if (handler != null)
            handler.doUnregister(true);
        DefaultSystemRegistry.log.debug("System {} disconnected", sysUID);
        
        return CompletableFuture.completedFuture(null);
    }
    
    
    @Override
    public CompletableFuture<Boolean> register(IStreamingDataInterface dataStream)
    {
        Asserts.checkNotNull(dataStream, IStreamingDataInterface.class);
        var proc = Asserts.checkNotNull(dataStream.getParentProducer(), ISystemDriver.class);
        var sysUID = Asserts.checkNotNull(proc.getUniqueIdentifier());
        
        return getDriverHandler(sysUID).register(dataStream);
    }
    
    
    @Override
    public CompletableFuture<Boolean> register(IStreamingControlInterface controlStream)
    {
        Asserts.checkNotNull(controlStream, IStreamingControlInterface.class);
        var proc = Asserts.checkNotNull(controlStream.getParentProducer(), ISystemDriver.class);
        var sysUID = OshAsserts.checkValidUID(proc.getUniqueIdentifier());
        
        return getDriverHandler(sysUID).register(controlStream);
    }
    
    
    @Override
    public CompletableFuture<Boolean> register(ISystemDriver sys, IFeature foi)
    {
        Asserts.checkNotNull(sys, ISystemDriver.class);
        var sysUID = OshAsserts.checkValidUID(sys.getUniqueIdentifier());
        
        return register(sysUID, foi);
    }
    
    
    @Override
    public CompletableFuture<Boolean> register(String sysUID, IFeature foi)
    {
        Asserts.checkNotNull(foi, IFeature.class);
        
        return getDriverHandler(sysUID).register(foi);
    }
    
    
    /*
     * Databases registered to handle data from drivers
     */
    
    protected IObsSystemDatabase getDatabaseForDriver(ISystemDriver driver)
    {
        var sysUID = driver.getUniqueIdentifier();
        
        // get DB handling this system
        // this call with return the dedicated DB if available, or the default state DB
        IObsSystemDatabase db = obsSystemDatabases.get(sysUID);
        if (db == null)
            db = systemStateDb;
        
        // error if DB is not an event handler DB
        log.info("System driver {} handled by DB {} (#{})", sysUID, db, db.getDatabaseNum());
        
        return db;
    }
    
    
    @Override
    public synchronized void registerDatabase(String uidPattern, IObsSystemDatabase db)
    {
        Asserts.checkNotNull(uidPattern, "systemUID");
        Asserts.checkNotNull(db, IObsSystemDatabase.class);

        if (db.isReadOnly())
            throw new IllegalStateException("Cannot use a read-only database to collect system driver data");
        
        // only insert mapping if not already registered by another database
        if (obsSystemDatabases.putIfAbsent(uidPattern, db) != null)
            throw new IllegalStateException("System " + uidPattern + " already handled by another database");
        
        // remove all entries from default state DB since it's now handled by another DB
        if (systemStateDb != null)
        {
            // replace driver's transaction handler so that new IObsSystemDatabase handles driver
            // do this only for top level systems
            var topLevelSystemsFilter = new SystemFilter.Builder()
                    .withUniqueIDs(uidPattern)
                    .withNoParent()
                    .build();
            
            systemStateDb.getSystemDescStore().selectEntries(topLevelSystemsFilter).forEach(desc ->
                    register(getDriverHandler(desc.getValue().getUniqueIdentifier()).driver));
            
            // remove all selected systems from state DB (including subsystems this time)
            var sysFilter = new SystemFilter.Builder()
                .withUniqueIDs(uidPattern)
                .includeMembers(true)
                .build();
            
            var dsFilter = new DataStreamFilter.Builder()
                .withSystems(sysFilter)
                .build();
            
            var csFilter = new CommandStreamFilter.Builder()
                .withSystems(sysFilter)
                .build();

            systemStateDb.getDataStreamStore().removeEntries(dsFilter);
            systemStateDb.getCommandStreamStore().removeEntries(csFilter);

            var count = systemStateDb.getSystemDescStore().removeEntries(sysFilter);
            if (count > 0)
                log.info("Database #{} now handles system(s) {}. Removing all records from state DB", db.getDatabaseNum(), uidPattern);
        }
    }


    @Override
    public synchronized void unregisterDatabase(IObsSystemDatabase db)
    {
        Asserts.checkNotNull(db, IObsSystemDatabase.class);
        
        var it = obsSystemDatabases.values().iterator();
        while (it.hasNext())
        {
            if (it.next() == db)
                it.remove();
            
            // TODO update DB used by transaction handlers on the fly!
        }
    }


    @Override
    public boolean hasDatabase(String systemUID)
    {
        return obsSystemDatabases.get(systemUID) != null;
    }


    @Override
    public IObsSystemDatabase getDatabase(String systemUID)
    {
        return obsSystemDatabases.get(systemUID);
    }
    
    
    @Override
    public ISystemStateDatabase getSystemStateDatabase()
    {
        return (ISystemStateDatabase)systemStateDb.getWrappedDatabase();
    }
    
    
    @Override
    public CompletableFuture<Entry<DataStreamKey, IDataStreamInfo>> waitForDataStream(String sysUid, String outputName)
    {
        var sysTopic = EventUtils.getSystemStatusTopicID(sysUid);
        Asserts.checkNotNullOrBlank(outputName, "outputName");
        
        var cf = new CompletableFuture<Entry<DataStreamKey, IDataStreamInfo>>();
        
        hub.getEventBus().newSubscription(DataStreamEvent.class)
            .withTopicID(sysTopic)
            .subscribe(new Subscriber<DataStreamEvent>() {
                volatile Subscription subscription;
                
                @Override
                public void onSubscribe(Subscription subscription)
                {
                    this.subscription = subscription;
                    fetchFromDataStore();
                }
    
                @Override
                public void onNext(DataStreamEvent e)
                {
                    if (sysUid.equals(e.getSystemUID()) && outputName.equals(e.getOutputName()))
                        fetchFromDataStore();
                }
                
                void fetchFromDataStore()
                {
                    var dsStore = hub.getDatabaseRegistry().getFederatedDatabase().getDataStreamStore();
                    
                    var filter = new DataStreamFilter.Builder()
                        .withSystems().withUniqueIDs(sysUid).done()
                        .withOutputNames(outputName)
                        .withCurrentVersion()
                        .build();
                    
                    dsStore.selectEntries(filter).findFirst().ifPresentOrElse(
                        e -> {
                          cf.complete(e);
                          subscription.cancel();
                        },
                        () -> subscription.request(1)
                    );
                }
    
                @Override
                public void onError(Throwable throwable)
                {
                    log.error("Error receiving data from event bus", throwable);
                }
    
                @Override
                public void onComplete()
                {
                }
            });
        
        return cf;
    }

}
