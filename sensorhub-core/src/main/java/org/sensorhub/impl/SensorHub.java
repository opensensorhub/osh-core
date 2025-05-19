/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl;

import java.util.HashMap;
import java.util.concurrent.ForkJoinPool;
import org.osgi.framework.BundleContext;
import org.sensorhub.api.ISensorHub;
import org.sensorhub.api.ISensorHubConfig;
import org.sensorhub.api.comm.INetworkManager;
import org.sensorhub.api.common.IdEncoders;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.database.IDatabaseRegistry;
import org.sensorhub.api.event.IEventBus;
import org.sensorhub.api.module.IModuleConfigRepository;
import org.sensorhub.api.processing.IProcessingManager;
import org.sensorhub.api.security.ISecurityManager;
import org.sensorhub.api.system.ISystemDriverRegistry;
import org.sensorhub.impl.comm.NetworkManagerImpl;
import org.sensorhub.impl.common.IdEncodersBase32;
import org.sensorhub.impl.common.IdEncodersDES;
import org.sensorhub.impl.database.registry.DefaultDatabaseRegistry;
import org.sensorhub.impl.datastore.mem.InMemorySystemStateDbConfig;
import org.sensorhub.impl.event.EventBus;
import org.sensorhub.impl.module.InMemoryConfigDb;
import org.sensorhub.impl.module.ModuleClassFinder;
import org.sensorhub.impl.module.ModuleConfigJsonFile;
import org.sensorhub.impl.module.ModuleRegistry;
import org.sensorhub.impl.processing.ProcessingManagerImpl;
import org.sensorhub.impl.security.ClientAuth;
import org.sensorhub.impl.security.SecurityManagerImpl;
import org.sensorhub.impl.system.DefaultSystemRegistry;
import org.sensorhub.utils.ModuleUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * <p>
 * Main class reponsible for starting/stopping all modules
 * </p>
 *
 * @author Alex Robin
 * @since Sep 4, 2013
 */
public class SensorHub implements ISensorHub
{
    private static final Logger log = LoggerFactory.getLogger(SensorHub.class);
    private static final String ERROR_MSG = "Fatal error during sensorhub execution";
    
    protected ISensorHubConfig config;
    protected IModuleConfigRepository moduleConfigs;
    protected BundleContext osgiContext;
    protected ModuleRegistry moduleRegistry;
    protected IEventBus eventBus;
    protected ISystemDriverRegistry driverRegistry;
    protected IDatabaseRegistry databaseRegistry;
    protected INetworkManager networkManager;
    protected ISecurityManager securityManager;
    protected IProcessingManager processingManager;
    protected IdEncoders idEncoders;
    protected boolean useSecureIds;
    protected volatile boolean started = false;
    
    
    public SensorHub()
    {
        this.config = new SensorHubConfig();
    }
    
    
    public SensorHub(ISensorHubConfig config)
    {
        this.config = config;
    }
    
    
    public SensorHub(ISensorHubConfig config, BundleContext osgiContext)
    {
        this.config = config;
        this.osgiContext = osgiContext;
    }
    
    
    public SensorHub(ISensorHubConfig config, IModuleConfigRepository moduleConfigs)
    {
        this.config = config;
        this.moduleConfigs = moduleConfigs;
    }
    
    
    @Override
    public synchronized void start() throws SensorHubException
    {
        if (!started)
        {
            log.info("*****************************************");
            log.info("Starting SensorHub...");
            log.info("Version: {}", ModuleUtils.getModuleInfo(SensorHub.class).getModuleVersion());
            log.info("Build number: {}", ModuleUtils.getBuildNumber(SensorHub.class));
            log.info("JDK version: {}, {}, {}",
                System.getProperty("java.vm.name"),
                System.getProperty("java.runtime.version"),
                System.getProperty("java.vm.vendor"));
            log.info("OS type: {}, {}",
                System.getProperty("os.name"),
                System.getProperty("os.arch"));
            log.info("CPU cores: {}", Runtime.getRuntime().availableProcessors());
            log.info("CommonPool parallelism: {}", ForkJoinPool.commonPool().getParallelism());
            
            // use provided module configs, read from JSON or create an in-memory one
            if (moduleConfigs == null)
            {
                var classFinder = new ModuleClassFinder(osgiContext);
                moduleConfigs = config.getModuleConfigPath() != null ?
                    new ModuleConfigJsonFile(config.getModuleConfigPath(), true, classFinder) :
                    new InMemoryConfigDb(classFinder);
            }
            
            // init hub core components
            this.moduleRegistry = new ModuleRegistry(this, moduleConfigs);
            this.eventBus = new EventBus();
            this.databaseRegistry = new DefaultDatabaseRegistry(this);
            this.driverRegistry = new DefaultSystemRegistry(this, new InMemorySystemStateDbConfig());
            
            // init service managers
            this.securityManager = new SecurityManagerImpl(this);
            this.networkManager = new NetworkManagerImpl(this);
            this.processingManager = new ProcessingManagerImpl(this);
            this.idEncoders = useSecureIds ? new IdEncodersDES(this) : new IdEncodersBase32();
            
            // prepare client authenticator (e.g. for HTTP connections, etc...)
            ClientAuth.createInstance("keystore");
            
            // load all modules in the order implied by dependency constraints
            moduleRegistry.loadAllModules();
            started = true;
        }
    }
    
    
    @Override
    public void saveAndStop()
    {
        stop(true, true);
    }
    
    
    @Override
    public void stop()
    {
        stop(false, true);
    }
    
    
    @Override
    public synchronized void stop(boolean saveConfig, boolean saveState)
    {
        try
        {
            if (started)
            {
                moduleRegistry.shutdown(saveConfig, saveState);
                eventBus.shutdown();
                started = false;
                log.info("SensorHub was cleanly stopped");
            }
        }
        catch (Exception e)
        {
            log.error("Error while stopping SensorHub", e);
        }
    }
    
    
    @Override
    public ISensorHubConfig getConfig()
    {
        return config;
    }


    public void setConfig(ISensorHubConfig config)
    {
        this.config = config;
    }
    
    
    public BundleContext getOsgiContext()
    {
        return osgiContext;
    }


    @Override
    public ModuleRegistry getModuleRegistry()
    {
        return moduleRegistry;
    }
    
    
    @Override
    public IEventBus getEventBus()
    {
        return eventBus;
    }
    
    
    @Override
    public ISystemDriverRegistry getSystemDriverRegistry()
    {
        return driverRegistry;
    }
    
    
    @Override
    public IDatabaseRegistry getDatabaseRegistry()
    {
        return databaseRegistry;
    }
    
    
    @Override
    public INetworkManager getNetworkManager()
    {
        return networkManager;
    }
    
    
    @Override
    public ISecurityManager getSecurityManager()
    {
        return securityManager;
    }
    
    
    @Override
    public IProcessingManager getProcessingManager()
    {
        return processingManager;
    }
    
    
    @Override
    public IdEncoders getIdEncoders()
    {
        return idEncoders; 
    }
    
    
    public static void main(String[] args)
    {
        // if no arg provided
        if (args.length < 1)
        {
            String version = ModuleUtils.getModuleInfo(SensorHub.class).getModuleVersion();
            String buildNumber = ModuleUtils.getBuildNumber(SensorHub.class);
            
            // print usage
            System.out.println("SensorHub " + version + " (build " + buildNumber + ")");
            System.out.println("Command syntax: sensorhub <module_config_path> [module_data_path]");
            System.out.println("""
                Options:
                
                -useSecureIds: use ID encryption to 
                """);
            System.exit(1);
        }
        
        // start sensorhub
        SensorHub instance = null;
        try
        {
            var moduleConfigPath = args[0];
            var moduleDataPath = args.length > 1 ? args[1] : null;
            
            // create argument map
            var argMap = new HashMap<String, String>();
            for (int i = 2; i < args.length; i++)
            {
                var arg = args[i];
                var parts = arg.split("=");
                var name = parts[0];
                var val = parts.length > 1 ? parts[1] : null;
                argMap.put(name, val);
            }
            
            SensorHubConfig config = new SensorHubConfig(moduleConfigPath, moduleDataPath);
            instance = new SensorHub(config);
            instance.useSecureIds = argMap.containsKey("useSecureIds");
            
            // register shutdown hook for a clean stop 
            final SensorHub sh = instance;
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run()
                {
                    sh.stop();
                }
            });
            
            instance.start();
        }
        catch (Exception e)
        {
            if (instance != null)
                instance.stop();
            
            System.err.println(ERROR_MSG);
            System.err.println(e.getLocalizedMessage());
            log.error(ERROR_MSG, e);
            
            System.exit(2);
        }
    }
}
