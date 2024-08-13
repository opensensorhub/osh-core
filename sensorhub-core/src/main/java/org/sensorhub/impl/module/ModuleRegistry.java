/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.module;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.sensorhub.api.ISensorHub;
import org.sensorhub.api.ISensorHubConfig;
import org.sensorhub.api.event.Event;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.database.DatabaseConfig;
import org.sensorhub.api.database.IDatabase;
import org.sensorhub.api.datastore.IDataStore;
import org.sensorhub.api.event.IEventListener;
import org.sensorhub.api.event.IEventPublisher;
import org.sensorhub.api.module.IModule;
import org.sensorhub.api.module.IModuleConfigRepository;
import org.sensorhub.api.module.IModuleManager;
import org.sensorhub.api.module.IModuleProvider;
import org.sensorhub.api.module.IModuleStateManager;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.api.module.ModuleEvent;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.sensorhub.api.module.ModuleEvent.Type;
import org.sensorhub.api.system.ISystemDriver;
import org.sensorhub.utils.Async;
import org.sensorhub.utils.FileUtils;
import org.sensorhub.utils.MsgUtils;
import org.sensorhub.utils.NamedThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.util.Asserts;
import com.google.common.collect.Sets;


/**
 * <p>
 * This class is in charge of loading all configured modules on startup
 * as well as dynamically loading/unloading modules on demand.
 * It also keeps lists of all loaded and available modules.
 * </p>
 *
 * @author Alex Robin
 * @since Sep 2, 2013
 */
public class ModuleRegistry implements IModuleManager<IModule<?>>, IEventListener
{
    private static final Logger log = LoggerFactory.getLogger(ModuleRegistry.class);
    private static final String REGISTRY_SHUTDOWN_MSG = "Registry was shut down";
    private static final String TIMEOUT_MSG = " in the requested time frame";
    public static final String EVENT_GROUP_ID = "urn:osh:modules";
    public static final long DEFAULT_TIMEOUT_MS = 5000L;
    public static final long SHUTDOWN_TIMEOUT_MS = 10000L;
    

    ISensorHub hub;
    IModuleConfigRepository configRepo;
    Map<String, IModule<?>> loadedModules;
    ExecutorService asyncExec;
    Set<WaitFuture<?>> waitForModuleFutures = Sets.newConcurrentHashSet();
    volatile boolean allModulesLoaded = true;
    volatile boolean shutdownCalled;
    
    
    static class WaitFuture<T> extends CompletableFuture<T>
    {
        Predicate<IModule<?>> predicate;
        
        WaitFuture(Predicate<IModule<?>> predicate)
        {
            this.predicate = predicate;
        }
    }
    
    
    public ModuleRegistry(ISensorHub hub, IModuleConfigRepository configRepos)
    {
        this.hub = hub;
        this.configRepo = configRepos;
        this.loadedModules = Collections.synchronizedMap(new LinkedHashMap<String, IModule<?>>());
        this.asyncExec = new ThreadPoolExecutor(0, Integer.MAX_VALUE,
                                                10L, TimeUnit.SECONDS,
                                                new SynchronousQueue<Runnable>(),
                                                new NamedThreadFactory("ModuleRegistry"));
    }
    
    
    /**
     * Loads all enabled modules from configuration entries provided
     * by the specified IModuleConfigRepository
     * @throws SensorHubException 
     */
    public synchronized void loadAllModules() throws SensorHubException
    {
        // load all modules
        log.info("Loading modules...");
        var dbIds = new HashSet<Integer>();
        for (ModuleConfig config: configRepo.getAllModulesConfigurations())
        {
            // check database IDs are unique
            if (config instanceof DatabaseConfig)
            {
                var dbID = ((DatabaseConfig) config).databaseNum;
                if (dbID != null && !dbIds.add(dbID))
                    throw new IllegalStateException("Duplicate database number: " + dbID + ". Check your configuration");
            }
            
            try
            {
                // load module but don't autostart yet
                loadModuleAsync(config.clone(), null, false);
            }
            catch (Exception e)
            {
                // log error and continue loading other modules
                log.error(IModule.CANNOT_LOAD_MSG, e);
            }
        }
        
        // separate datastore/database modules from other modules
        var dataStores = new ArrayList<IModule<?>>();
        var otherModules = new ArrayList<IModule<?>>();
        for (IModule<?> module: loadedModules.values())
        {
            if (module instanceof IDatabase || module instanceof IDataStore)
                dataStores.add(module);
            else
                otherModules.add(module);
        }
        
        // First start all datastore modules to ensure they are registered
        // with database registry and ready to record data before any
        // other module start producing data.
        log.info("Starting datastore connectors");
        var waitFutures= new ArrayList<CompletableFuture<?>>();
        for (IModule<?> module: dataStores)
        {
            if (module.getConfiguration().autoStart)
            {
                startModuleAsync(module);
                var f = waitForModule(module.getLocalID(), ModuleState.STARTED);
                waitFutures.add(f);
            }
        }
        
        // wait for all datastores to start
        try
        {
            var numStartedDatabases = waitFutures.size();
            CompletableFuture.allOf(
                waitFutures.toArray(new CompletableFuture[numStartedDatabases]))
                .get(DEFAULT_TIMEOUT_MS*4, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e)
        {
            log.error("Interrupted while starting datastores", e);
            Thread.currentThread().interrupt();
        }
        catch (Exception e)
        {
            throw new SensorHubException("Error starting datastores", e);
        }
        
        // then load all other modules
        log.info("All datastores ready. Starting all other modules");
        for (IModule<?> module: otherModules)
        {
            if (module.getConfiguration().autoStart)
                startModuleAsync(module);
        }
    }
    
    
    protected boolean isDataStoreModule(ModuleConfig config) throws SensorHubException
    {
        try
        {
            Class<?> moduleClazz = configRepo.getModuleClassFinder().findModuleClass(config.moduleClass);
            return IDatabase.class.isAssignableFrom(moduleClazz) ||
                   IDataStore.class.isAssignableFrom(moduleClazz);
        }
        catch (Exception e)
        {
            throw new SensorHubException(IModule.CANNOT_LOAD_MSG  + MsgUtils.moduleString(config), e);
        }
    }
    
    
    /**
     * Instantiates and loads a module using the given configuration<br/>
     * This method is synchronous so it will block forever until the module is actually
     * loaded, and it will also wait for it to be started if 'autostart' was requested.
     * @param config Configuration class to use to instantiate the module
     * @return loaded module instance
     * @throws SensorHubException 
     */
    public IModule<?> loadModule(ModuleConfig config) throws SensorHubException
    {
        return loadModule(config, Long.MAX_VALUE);
    }
    
    
    /**
     * Instantiates and loads a module using the given configuration<br/>
     * This method is synchronous so it will block until the module is actually loaded,
     * (and started if 'autostart' was true), the timeout occurs or an exception is thrown
     * @param config Configuration class to use to instantiate the module
     * @param timeOut Maximum time to wait for load and startup to complete (or 0 to wait forever)
     * @return loaded module instance
     * @throws SensorHubException 
     */
    public IModule<?> loadModule(ModuleConfig config, long timeOut) throws SensorHubException
    {
        IModule<?> module = loadModuleAsync(config, null);
        
        if (config.autoStart && !module.waitForState(ModuleState.STARTED, timeOut))
            throw new SensorHubException(IModule.CANNOT_START_MSG + MsgUtils.moduleString(module) + TIMEOUT_MSG);
                
        return module;
    }
    
    
    /**
     * Instantiates and loads a module using the given configuration<br/>
     * This method is asynchronous so, when it returns without error, the module is guaranteed
     * to be loaded but not necessarily initialized or started. The listener will be notified
     * when the module's state changes further.
     * @param config Configuration class to use to instantiate the module
     * @param listener Listener to register for receiving the module's events
     * @return loaded module instance (may not yet be started when this method returns)
     * @throws SensorHubException if no module with given ID can be found
     */
    public IModule<?> loadModuleAsync(ModuleConfig config, IEventListener listener) throws SensorHubException
    {
        return loadModuleAsync(config, listener, true);
    }
    
    
    @SuppressWarnings({ "rawtypes", "unchecked" })
    protected IModule<?> loadModuleAsync(ModuleConfig config, IEventListener listener, boolean doAutoStart) throws SensorHubException
    {
        if (config.id != null && loadedModules.containsKey(config.id))
            return loadedModules.get(config.id);
        
        IModule module = null;
        try
        {
            // generate a new ID if non was provided
            if (config.id == null)
                config.id = UUID.randomUUID().toString();
                        
            // instantiate module class
            module = (IModule)loadModuleClass(config.moduleClass);
            if (log.isDebugEnabled())
                log.debug("Module {} loaded", MsgUtils.moduleString(config));
            module.setParentHub(hub);
            
            // set config
            module.setConfiguration(config);
            
            // register to receive module events
            module.registerListener(this);
            
            // also register additional local listener if specified
            if (listener != null)
                module.registerListener(listener);
            
            // keep track of what modules are loaded
            loadedModules.put(config.id, module);
            
            // send event
            handleEvent(new ModuleEvent(module, Type.LOADED));
            
            // also init & start if autostart is set
            if (doAutoStart && config.autoStart)
                startModuleAsync(config.id, null);
        }
        catch (Exception e)
        {
            throw new SensorHubException(IModule.CANNOT_LOAD_MSG  + MsgUtils.moduleString(config), e);
        }
        
        return module;
    }
    
    
    /**
     * Attempts to find a class by name.<br/>
     * If using OSGi this will scan all resolved bundles and attempt to load the class
     * using the bundle's classloader.
     * @param className Fully qualified name of the class to load
     * @return Loaded class
     * @throws SensorHubException
     */
    @SuppressWarnings("unchecked")
    public <T> Class<T> findClass(String className) throws SensorHubException
    {
        try
        {
            return (Class<T>)configRepo.getModuleClassFinder().findClass(className);
        }
        catch (NoClassDefFoundError | ClassNotFoundException e)
        {
            throw new SensorHubException("Cannot find class " + className, e);
        }
    }
    
    
    /**
     * Instantiate any class by reflection using the default constructor.<br/>
     * The class is first loaded using {@link ModuleRegistry#findClass(String)}.
     * @param className Fully qualified name of the class to instantiate
     * @return New object instantiated
     * @throws SensorHubException
     */
    @SuppressWarnings("unchecked")
    public <T> T loadClass(String className) throws SensorHubException
    {
        try
        {
            var clazz = (Class<T>)configRepo.getModuleClassFinder().findClass(className);
            return (T)clazz.getDeclaredConstructor().newInstance();
        }
        catch (NoClassDefFoundError | ReflectiveOperationException e)
        {
            throw new SensorHubException("Cannot instantiate class " + className, e);
        }
    }
    
    
    /**
     * Finds a module class.
     * If using OSGi, this will attempt to find a service providing the module.
     * @param className Fully qualified name of the module class to load
     * @return new object instantiated
     * @throws SensorHubException
     */
    @SuppressWarnings("unchecked")
    public <T> Class<T> findModuleClass(String className) throws SensorHubException
    {
        try
        {
            return (Class<T>)configRepo.getModuleClassFinder().findModuleClass(className);
        }
        catch (NoClassDefFoundError | ClassNotFoundException e)
        {
            throw new SensorHubException("Cannot instantiate class " + className, e);
        }
    }
    
    
    /**
     * Loads a module class by reflection.
     * If using OSGi, this will attempt to find a service providing the module
     * @param className Fully qualified name of the module class to instantiate
     * @return new object instantiated
     * @throws SensorHubException
     */
    @SuppressWarnings("unchecked")
    public <T> T loadModuleClass(String className) throws SensorHubException
    {
        try
        {
            var clazz = findModuleClass(className);
            return (T)clazz.getDeclaredConstructor().newInstance();
        }
        catch (NoClassDefFoundError | ReflectiveOperationException e)
        {
            throw new SensorHubException("Cannot instantiate class " + className, e);
        }
    }
    
    
    /**
     * Helper method to load and optionally initialize a sub module.
     * A sub module is a module loaded by another module. Once this method returns, the
     * caller (i.e. usually the parent module) is responsible for managing the submodule
     * life cycle, not the module registry itself.
     * @param <T> Type of module configuration
     * @param config Sub module configuration class
     * @param init If set to true, also initialize the module
     * @return The module instance
     * @throws SensorHubException
     */
    @SuppressWarnings("unchecked")
    public <T extends IModule<C>, C extends ModuleConfig> T loadSubModule(C config, boolean init) throws SensorHubException
    {
        Asserts.checkNotNull(config, ModuleConfig.class);
        Asserts.checkNotNullOrBlank(config.moduleClass, "moduleClass");
        
        try
        {
            var comp = (T)loadModuleClass(config.moduleClass);
            
            if (init)
                comp.init(config);
            else
                comp.setConfiguration(config);
            
            return comp;
        }
        catch (Exception e)
        {
            throw new SensorHubException("Cannot load submodule " + config.moduleClass, e);
        }
    }
    
    
    /**
     * Creates a new module config class using information from a module provider
     * @param provider
     * @return the new configuration class
     * @throws SensorHubException
     */
    public ModuleConfig createModuleConfig(IModuleProvider provider) throws SensorHubException
    {
        try
        {
            Class<?> configClass = provider.getModuleConfigClass();
            ModuleConfig config = (ModuleConfig)configClass.getDeclaredConstructor().newInstance();
            config.id = UUID.randomUUID().toString();
            config.moduleClass = provider.getModuleClass().getCanonicalName();
            config.name = "New " + provider.getModuleName();
            config.autoStart = false;
            return config;
        }
        catch (NoClassDefFoundError | ReflectiveOperationException e)
        {
            String msg = "Cannot create configuration class for module " + provider.getModuleName();
            log.error(msg, e);
            throw new SensorHubException(msg, e);
        }
    }
    
    
    @Override
    public boolean isModuleLoaded(String moduleID)
    {
        return loadedModules.containsKey(moduleID);
    }
    
    
    /**
     * Unloads a module instance.<br/>
     * This causes the module to be removed from registry but its last saved configuration
     * is kept as-is. Call {@link #saveConfiguration(ModuleConfig...)} first if you want to
     * keep the current config. 
     * @param moduleID
     * @throws SensorHubException
     */
    public void unloadModule(String moduleID) throws SensorHubException
    {
        stopModule(moduleID);
        IModule<?> module = loadedModules.remove(moduleID);
        
        IEventPublisher modulePublisher = hub.getEventBus().getPublisher(moduleID);
        modulePublisher.publish(new ModuleEvent(module, Type.UNLOADED));
        
        if (log.isDebugEnabled())
            log.debug("Module {} unloaded", MsgUtils.moduleString(module));
    }
    
    
    /**
     * Initializes the module with the given local ID<br/>
     * This method is synchronous so it will block forever until the module is actually
     * initialized or an exception is thrown
     * @param moduleID Local ID of module to initialize
     * @return module instance corresponding to moduleID
     * @throws SensorHubException if an error occurs during init
     */
    public IModule<?> initModule(String moduleID) throws SensorHubException
    {
        return initModule(moduleID, Long.MAX_VALUE);
    }
    
    
    /**
     * Initializes the module with the given local ID<br/>
     * This method is synchronous so it will block until the module is actually initialized,
     * the timeout occurs or an exception is thrown
     * @param moduleID Local ID of module to initialize
     * @param timeOut Maximum time to wait for init to complete (or 0 to wait forever)
     * @return module Loaded module with the given moduleID
     * @throws SensorHubException if an error occurs during init
     */
    public IModule<?> initModule(String moduleID, long timeOut) throws SensorHubException
    {
        IModule<?> module = initModuleAsync(moduleID, null);
        if (!module.waitForState(ModuleState.INITIALIZED, timeOut))
            throw new SensorHubException(IModule.CANNOT_INIT_MSG + MsgUtils.moduleString(module) + TIMEOUT_MSG);
        return module;
    }
    
    
    /**
     * Initializes the module with the given local ID<br/>
     * This method is asynchronous so it returns immediately and the listener will be notified
     * when the module is actually initialized
     * @param moduleID Local ID of module to initialize
     * @param listener Listener to register for receiving the module's events
     * @return the module instance (may not yet be initialized when this method returns)
     * @throws SensorHubException if no module with given ID can be found
     */
    public IModule<?> initModuleAsync(String moduleID, IEventListener listener) throws SensorHubException
    {
        @SuppressWarnings("rawtypes")
        final IModule module = getModuleById(moduleID);
        if (listener != null)
            module.registerListener(listener);
        
        initModuleAsync(module);
        return module;
    }
    
    
    
    /**
     * Initializes the module asynchronously in a separate thread
     * @param module module instance to initialize
     * @throws SensorHubException
     */
    public void initModuleAsync(final IModule<?> module) throws SensorHubException
    {   
        try
        {
            // init module in separate thread
            asyncExec.submit(() -> {
                try
                {
                    // if forced, try to stop first
                    if (module.isInitialized())
                        module.stop();
                }
                catch (Exception e)
                {
                    log.error(IModule.CANNOT_STOP_MSG + MsgUtils.moduleString(module), e);
                }
                
                try
                {
                    module.init();
                }
                catch (Exception e)
                {
                    log.error(IModule.CANNOT_INIT_MSG + MsgUtils.moduleString(module), e);
                }          
            });
        }
        catch (RejectedExecutionException e)
        {
            throw new SensorHubException(REGISTRY_SHUTDOWN_MSG, e);
        }
    }
        
    
    /**
     * Starts the module with the given local ID<br/>
     * This method is synchronous so it will block forever until the module is actually
     * started or an exception is thrown
     * @param moduleID Local ID of module to start
     * @return module instance corresponding to moduleID
     * @throws SensorHubException if an error occurs during startup
     */
    public IModule<?> startModule(String moduleID) throws SensorHubException
    {
        return startModule(moduleID, Long.MAX_VALUE);
    }
    
    
    /**
     * Starts the module with the given local ID<br/>
     * This method is synchronous so it will block until the module is actually started,
     * the timeout occurs or an exception is thrown
     * @param moduleID Local ID of module to start
     * @param timeOut Maximum time to wait for startup to complete (or 0 to wait forever)
     * @return module Loaded module with the given moduleID
     * @throws SensorHubException if an error occurs during startup
     */
    public IModule<?> startModule(String moduleID, long timeOut) throws SensorHubException
    {
        IModule<?> module = startModuleAsync(moduleID, null);
        if (!module.waitForState(ModuleState.STARTED, timeOut))
            throw new SensorHubException(IModule.CANNOT_START_MSG + MsgUtils.moduleString(module) + TIMEOUT_MSG);
        return module;
    }
    
    
    /**
     * Starts the module with the given local ID<br/>
     * This method is asynchronous so it returns immediately and the listener will be notified
     * when the module is actually started
     * @param moduleID Local ID of module to start
     * @param listener Listener to register for receiving the module's events
     * @return the module instance (may not yet be started when this method returns)
     * @throws SensorHubException if no module with given ID can be found
     */
    public IModule<?> startModuleAsync(final String moduleID, IEventListener listener) throws SensorHubException
    {
        final IModule<?> module = getModuleById(moduleID);
        if (listener != null)
            module.registerListener(listener);
        
        startModuleAsync(module);
        return module;
    }
    
    
    /**
     * Starts the module asynchronously in a separate thread
     * @param module module instance to start
     * @throws SensorHubException
     */
    public void startModuleAsync(final IModule<?> module) throws SensorHubException
    {        
        try
        {
            // start module in separate thread
            asyncExec.submit(() -> {
                
                // set current thread classloader to the module classloader
                // needed to use proper classloader when booting using OSGi
                var prevCl = Thread.currentThread().getContextClassLoader();
                Thread.currentThread().setContextClassLoader(module.getClass().getClassLoader());
                
                try
                {
                    if (!module.isInitialized())
                    {
                        if (module.getCurrentState() != ModuleState.INITIALIZING)
                            module.init();
                        module.waitForState(ModuleState.INITIALIZED, DEFAULT_TIMEOUT_MS);
                    }
                }
                catch (Exception e)
                {
                    log.error(IModule.CANNOT_INIT_MSG + MsgUtils.moduleString(module), e);
                }
                
                try
                {
                    module.start();
                }
                catch (Exception e)
                {
                    log.error(IModule.CANNOT_START_MSG + MsgUtils.moduleString(module), e);
                }
                
                Thread.currentThread().setContextClassLoader(prevCl);
            });
        }
        catch (RejectedExecutionException e)
        {
            throw new SensorHubException(REGISTRY_SHUTDOWN_MSG, e);
        }
    }
    
    
    /**
     * Stops the module with the given local ID<br/>
     * This method is synchronous so it will block forever until the module is actually
     * stopped or an exception is thrown
     * @param moduleID Local ID of module to disable
     * @return module instance corresponding to moduleID
     * @throws SensorHubException if an error occurs during shutdown
     */
    public IModule<?> stopModule(String moduleID) throws SensorHubException
    {
        return stopModule(moduleID, Long.MAX_VALUE);
    }
    
    
    /**
     * Stops the module with the given local ID<br/>
     * This method is synchronous so it will block until the module is actually stopped,
     * the timeout occurs or an exception is thrown
     * @param moduleID Local ID of module to enable
     * @param timeOut Maximum time to wait for shutdown to complete (or 0 to wait forever)
     * @return module Loaded module with the given moduleID
     * @throws SensorHubException if an error occurs during shutdown
     */
    public IModule<?> stopModule(String moduleID, long timeOut) throws SensorHubException
    {
        IModule<?> module = stopModuleAsync(moduleID, null);
        if (!module.waitForState(ModuleState.STOPPED, timeOut))
            throw new SensorHubException(IModule.CANNOT_STOP_MSG + MsgUtils.moduleString(module) + TIMEOUT_MSG);
        return module;
    }
    
    
    /**
     * Stops the module with the given local ID<br/>
     * This method is asynchronous so it returns immediately and the listener will be notified
     * when the module is actually stopped
     * @param moduleID Local ID of module to stop
     * @param listener Listener to register for receiving the module's events
     * @return the module instance (may not yet be stopped when this method returns)
     * @throws SensorHubException if no module with given ID can be found
     */
    public IModule<?> stopModuleAsync(final String moduleID, IEventListener listener) throws SensorHubException
    {
        final IModule<?> module = getModuleById(moduleID);
        if (listener != null)
            module.registerListener(listener);
        
        stopModuleAsync(module);
        return module;
    }
    
    
    /**
     * Stops the module asynchronously in a separate thread
     * @param module module instance to stop
     * @throws SensorHubException
     */
    public void stopModuleAsync(final IModule<?> module) throws SensorHubException
    {        
        try
        {
            // stop module in separate thread
            asyncExec.submit(() -> {
                try
                {
                    module.stop();
                }
                catch (Exception e)
                {
                    log.error(IModule.CANNOT_STOP_MSG + MsgUtils.moduleString(module), e);
                }
            });
        }
        catch (Exception e)
        {
            throw new SensorHubException(REGISTRY_SHUTDOWN_MSG, e);
        }
    }
    
    
    /**
     * Restarts the module with the given local ID<br/>
     * This method is asynchronous so it returns immediately and the listener will be notified
     * when the module is actually restarted
     * @param moduleID Local ID of module to restart
     * @param listener Listener to register for receiving the module's events
     * @throws SensorHubException if no module with given ID can be found
     */
    public void restartModuleAsync(final String moduleID, IEventListener listener) throws SensorHubException
    {        
        final IModule<?> module = getModuleById(moduleID);
        if (listener != null)
            module.registerListener(listener);
        
        restartModuleAsync(module);
    }
    
    
    /**
     * Restarts the module asynchronously in a separate thread<br/>
     * This will actually called requestStop() and then requestStart()
     * @param module module instance to restart
     * @throws SensorHubException
     */
    public void restartModuleAsync(final IModule<?> module) throws SensorHubException
    {        
        try
        {
            // restart module in separate thread
            asyncExec.submit(() -> {
                try
                {
                    module.stop();
                    module.waitForState(ModuleState.STOPPED, DEFAULT_TIMEOUT_MS);
                    module.start();
                }
                catch (Exception e)
                {
                    log.error("Cannot restart module " + MsgUtils.moduleString(module), e);
                }
            });
        }
        catch (Exception e)
        {
            throw new SensorHubException(REGISTRY_SHUTDOWN_MSG, e);
        }
    }
    
    
    /**
     * Updates the configuration of the module with the given local ID<br/>
     * @param config new configuration (must contain the valid local ID of the module to update)
     * @throws SensorHubException if no module with given ID can be found
     */
    public void updateModuleConfigAsync(final ModuleConfig config) throws SensorHubException
    {
        @SuppressWarnings("rawtypes")
        IModule module = getModuleById(config.id);
        updateModuleConfigAsync(module, config);
    }
        
        
    /**
     * Updates the module configuration asynchronously in a separate thread
     * @param module module instance to update
     * @param config new module configuration
     * @throws SensorHubException
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void updateModuleConfigAsync(final IModule module, final ModuleConfig config) throws SensorHubException
    {
        try
        {
            // stop module in separate thread
            asyncExec.submit(() -> {
                try
                {
                    module.updateConfig(config);
                }
                catch (Exception e)
                {
                    log.error(IModule.CANNOT_UPDATE_MSG + MsgUtils.moduleString(module), e);
                }
            });
        }
        catch (Exception e)
        {
            throw new SensorHubException(REGISTRY_SHUTDOWN_MSG, e);
        }
    }

    
    
    /**
     * Removes the module with the given id
     * @param moduleID Local ID of module to delete
     * @throws SensorHubException 
     */
    public void destroyModule(String moduleID) throws SensorHubException
    {
        // we check both in live table and in config repository
        if (!loadedModules.containsKey(moduleID) && !configRepo.contains(moduleID))
            throw new SensorHubException("Unknown module " + moduleID);
        
        try
        {
            // remove from repository
            if (configRepo.contains(moduleID))
                configRepo.remove(moduleID);
            
            // stop it and call cleanup if it was loaded
            IModule<?> module = loadedModules.remove(moduleID);
            if (module != null)
            {
                module.stop();
                getStateManager(moduleID).cleanup();
                module.cleanup();
                
                if (module.isInitialized() && module instanceof ISystemDriver)
                    hub.getSystemDriverRegistry().unregister((ISystemDriver)module);
                
                handleEvent(new ModuleEvent(module, Type.DELETED));
            }
            
            if (log.isDebugEnabled())
                log.debug("Module {} deleted", MsgUtils.moduleString(module));
        }
        catch (Exception e)
        {
            String msg = "Cannot destroy module " + moduleID;
            log.error(msg, e);
        }
    }
    
    
    /**
     * Save all modules current configuration to the repository
     */
    public void saveModulesConfiguration()
    {
        try
        {
            // update config of loaded modules
            for (IModule<?> module: loadedModules.values())
                configRepo.update(module.getConfiguration());    
            
            // remove configs that have been deleted 
            for (ModuleConfig moduleConf: configRepo.getAllModulesConfigurations())
            {
                if (!loadedModules.containsKey(moduleConf.id))
                    configRepo.remove(moduleConf.id);
            }
            
            configRepo.commit();
        }
        catch (Exception e)
        {
            log.error("Error while saving SensorHub configuration", e);
        }
    }
    
    
    /**
     * Saves the given module configurations in the repository
     * @param configList 
     */
    public synchronized void saveConfiguration(ModuleConfig... configList)
    {
        for (ModuleConfig config: configList)
            configRepo.update(config);
        
        configRepo.commit();
    }
    
    
    /**
     * @param moduleID local ID of desired module
     * @return module with given ID or null if not found
     */
    public IModule<?> getLoadedModuleById(String moduleID)
    {
        return loadedModules.get(moduleID);
    }
    
    
    @Override
    public Collection<IModule<?>> getLoadedModules()
    {
        return Collections.unmodifiableCollection(loadedModules.values());
    }
    
    
    /**
     * Retrieves list of all loaded modules that are sub-types
     * of the specified class
     * @param moduleType parent class of modules to search for
     * @return list of module instances of the specified type
     */
    @SuppressWarnings("unchecked")
    public <T> Collection<T> getLoadedModules(Class<T> moduleType)
    {
        ArrayList<T> matchingModules = new ArrayList<>();
        
        for (IModule<?> module: getLoadedModules())
        {
            if (moduleType.isAssignableFrom(module.getClass()))
                matchingModules.add((T)module);
        }
        
        return matchingModules;
    }
    
    
    /**
     * Find first module with the given type
     * @param moduleType parent class of modules to search for
     * @return The matching module or null if none were found
     */
    @SuppressWarnings("unchecked")
    public <T> T getModuleByType(Class<T> moduleType)
    {
        for (IModule<?> module: getLoadedModules())
        {
            if (moduleType.isAssignableFrom(module.getClass()))
                return (T)module;
        }
        
        return null;
    }
    
    
    @Override
    public IModule<?> getModuleById(String moduleID) throws SensorHubException
    {
        // load module if necessary
        if (!loadedModules.containsKey(moduleID))
        {
            if (configRepo.contains(moduleID))
                loadModuleAsync(configRepo.get(moduleID), null);
            else
                throw new SensorHubException("Unknown module " + moduleID);
        }
        
        return loadedModules.get(moduleID);
    }
    
    
    @SuppressWarnings("unchecked")
    public <T extends IModule<?>> WeakReference<T> getModuleRef(String moduleID) throws SensorHubException
    {
        IModule<?> module = getModuleById(moduleID);
        return new WeakReference<>((T)module);
    }
    
    
    /**
     * Retrieves list of all installed module types
     * @return list of module providers (not the module themselves)
     */
    public Collection<IModuleProvider> getInstalledModuleTypes()
    {
        return configRepo.getModuleClassFinder().getInstalledModuleTypes(Object.class);
    }
    
    
    /**
     * Retrieves list of all installed module types that are sub-types
     * of the specified class
     * @param moduleClass parent class of modules to search for
     * @return list of module providers (not the module themselves)
     */
    public Collection<IModuleProvider> getInstalledModuleTypes(Class<?> moduleClass)
    {
        return configRepo.getModuleClassFinder().getInstalledModuleTypes(moduleClass);
    }
    
    
    /**
     * Shuts down all modules and the config repository
     * @param saveConfig If true, save current modules config
     * @param saveState If true, save current module state
     * @throws SensorHubException 
     */
    public synchronized void shutdown(boolean saveConfig, boolean saveState) throws SensorHubException
    {
        shutdownCalled = true;
        
        // do nothing if no modules have been loaded
        if (loadedModules.isEmpty())
            return;
        
        log.info("Module registry shutdown initiated");
        log.info("Stopping all modules (saving config = {}, saving state = {})", saveConfig, saveState);
        
        // separate datastore/database modules from other modules
        var dataStores = new ArrayList<IModule<?>>();
        var otherModules = new ArrayList<IModule<?>>();
        for (IModule<?> module: getLoadedModules())
        {
            if (module instanceof IDatabase || module instanceof IDataStore)
                dataStores.add(module);
            else
                otherModules.add(module);
        }
        
        // stop all non-datastore modules
        stopModules(otherModules, saveConfig, saveState);
        
        // then stop all datastores
        stopModules(dataStores, saveConfig, saveState);
        
        // shutdown executor once all tasks have been run
        asyncExec.shutdown();
        
        // unregister from all modules and warn if some could not stop
        boolean firstWarning = true;
        for (IModule<?> module: getLoadedModules())
        {
            module.unregisterListener(this);
            
            ModuleState state = module.getCurrentState();
            if (state != ModuleState.STOPPED && state != ModuleState.LOADED)
            {
                if (firstWarning)
                {
                    log.warn("The following modules could not be stopped");
                    firstWarning = false;
                }
                
                if (log.isWarnEnabled())
                    log.warn(MsgUtils.moduleString(module));
            }
        } 
        
        // clear loaded modules
        loadedModules.clear();
        
        // properly close config database
        configRepo.close();
    }
    
    
    private void stopModules(Collection<IModule<?>> moduleList, boolean saveConfig, boolean saveState)
    {
        // call stop on all modules
        for (IModule<?> module: moduleList)
        {
            try
            {
                // save config if requested
                if (saveConfig)
                    configRepo.update(module.getConfiguration());
                
                // save state if requested
                if (saveState)
                {
                    try
                    {
                        IModuleStateManager stateManager = getStateManager(module.getLocalID());
                        if (stateManager != null)
                            module.saveState(stateManager);
                    }
                    catch (Exception ex)
                    {
                        log.error("State could not be saved for module " + MsgUtils.moduleString(module), ex);
                    }
                }
                
                // request to stop module
                stopModuleAsync(module);
            }
            catch (Exception e)
            {
                log.error("Error during shutdown", e);
            }
        }
        
        // wait for all modules to actually stop
        try
        {
            Async.waitForCondition(() -> {
                boolean allStopped = true;
                for (IModule<?> module: moduleList)
                {
                    ModuleState state = module.getCurrentState();
                    if (state != ModuleState.STOPPED && state != ModuleState.LOADED)
                    {
                        allStopped = false;
                        break;
                    }
                }
                return allStopped;
            }, SHUTDOWN_TIMEOUT_MS);
        }
        catch (TimeoutException e)
        {
            log.error("Could not stop all modules before timeout");
        }
    }
    
    
    /**
     * Returns the default state manager for the given module
     * @param moduleID
     * @return the state manager or null if no module data folder is specified in config
     */
    public IModuleStateManager getStateManager(String moduleID)
    {
        String moduleDataPath = hub.getConfig().getModuleDataPath();
        if (moduleDataPath != null)
            return new DefaultModuleStateManager(moduleDataPath, moduleID);
        else
            return null;
    }
    
    
    /**
     * @return the state manager for the 'core' module
     */
    public IModuleStateManager getCoreStateManager()
    {
        return getStateManager("00_core");
    }
    
    
    /**
     * Retrieves the folder where the module data should be stored 
     * @param moduleID Local ID of module
     * @return File object representing the folder or null if none was specified
     */
    public File getModuleDataFolder(String moduleID)
    {
        ISensorHubConfig oshConfig = hub.getConfig();
        if (oshConfig == null)
            return null;
        
        String moduleDataRoot = hub.getConfig().getModuleDataPath();
        if (moduleDataRoot == null)
            return null;
        
        var folder = new File(moduleDataRoot, FileUtils.safeFileName(moduleID));
        if (!folder.exists())
            folder.mkdirs();
        
        return folder;
    }


    public void handleEvent(Event e)
    {        
        if (e instanceof ModuleEvent)
        {
            IModule<?> module = ((ModuleEvent) e).getModule();
            String moduleString = MsgUtils.moduleString(module);
            
            switch (((ModuleEvent)e).getType())
            {
                case STATE_CHANGED:
                    switch (((ModuleEvent) e).getNewState())
                    {
                        case INITIALIZING:
                            log.info("Initializing module {}", moduleString);
                            break;
                            
                        case INITIALIZED:
                            log.info("Module {} initialized", moduleString);
                            postInit(module);
                            break;
                            
                        case STARTING:
                            log.info("Starting module {}", moduleString);
                            break;
                            
                        case STARTED:
                            log.info("Module {} started", moduleString);
                            break;
                            
                        case STOPPING:
                            log.info("Stopping module {}", moduleString);
                            break;
                            
                        case STOPPED:
                            log.info("Module {} stopped", moduleString);
                            break;
                            
                        default:
                            break;
                    }
                    break;
                    
                case ERROR:
                    log.error("Error in module {}", moduleString);
                    break;
                    
                default:
                    break;
            }
            
            // notify other modules waiting
            notifyWaitFutures((ModuleEvent)e);
            
            // forward events to event bus
            // events from all modules are published in the same group
            IEventPublisher modulePublisher = hub.getEventBus().getPublisher(EVENT_GROUP_ID, e.getSourceID());
            modulePublisher.publish(e);
        }
    }
    
    
    @SuppressWarnings("unchecked")
    protected void notifyWaitFutures(ModuleEvent e)
    {
        synchronized (waitForModuleFutures)
        {
            var it = waitForModuleFutures.iterator();
            while (it.hasNext())
            {
                var f = it.next();
                if (f.predicate.test(e.getModule()))
                {
                    it.remove();
                    ((WaitFuture<IModule<?>>)f).complete(e.getModule());
                }
            }
        }
    }
    
    
    public <T> CompletableFuture<T> waitForModuleType(Class<T> moduleType, ModuleState requiredState)
    {
        Asserts.checkNotNull(moduleType, "moduleClass");
        Asserts.checkNotNull(requiredState, ModuleState.class);
        
        return waitForModule(m -> {
            return moduleType.isAssignableFrom(m.getClass())
                && m.getCurrentState() == requiredState;
        });
    }
    
    
    public <T extends IModule<?>> CompletableFuture<T> waitForModule(String id, ModuleState requiredState)
    {
        Asserts.checkNotNull(id, "moduleID");
        Asserts.checkNotNull(requiredState, ModuleState.class);
        
        return waitForModule(m -> {
            return m.getLocalID().equals(id)
                && m.getCurrentState() == requiredState;
        });
    }
    
    
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> waitForModule(final Predicate<IModule<?>> predicate)
    {
        Asserts.checkNotNull(predicate, Predicate.class);
        
        synchronized (waitForModuleFutures)
        {
            var future = new WaitFuture<T>(predicate);
            
            var modules = getLoadedModules();
            for (var m: modules)
            {
                if (future.predicate.test(m))
                {
                    future.complete((T)m);
                    return future;
                }
            }
                
            waitForModuleFutures.add(future);
            return future;
        }
    }
    
    
    protected void postInit(IModule<?> module)
    {
        String moduleString = MsgUtils.moduleString(module);
        
        // load module state
        try
        {
            IModuleStateManager stateManager = getStateManager(module.getLocalID());
            if (stateManager != null)
                module.loadState(stateManager);
        }
        catch (SensorHubException e)
        {
            log.error("Cannot load state of module {}", moduleString, e);
        }
    }
    
    
    public ISensorHub getParentHub()
    {
        return hub;
    }
}
