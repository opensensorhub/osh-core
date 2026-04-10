/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.osgi;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.util.HashMap;
import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.FrameworkFactory;


public class SensorHubOsgi
{
    static final Logger LOGGER = Logger.getLogger(SensorHubOsgi.class.getName());
    static final String DEFAULT_AUTODEPLOY_DIR = "bundles";
    static final String DEFAULT_BUNDLECACHE_DIR = ".bundle-cache";
    static final String REF_PREFIX = "reference:file:";
    static BundleContext systemCtx;
    
    
    public static void main(String[] args) throws Exception
    {
        // parse args
        var argSet = new HashMap<String,String>();
        for (int i = 1; i < args.length; i++)
        {
            var kvp = args[i].split("=");
            argSet.put(kvp[0], kvp.length > 1 ? kvp[1] : null);
        }
        var oshConfig = args.length > 0 ? args[0] : null;
        var clearCache = argSet.containsKey("-clearCache");
        var autoDeployDir = argSet.computeIfAbsent("-autoDeployDir", k -> DEFAULT_AUTODEPLOY_DIR);
        var bundleCacheDir = argSet.computeIfAbsent("-bundleCacheDir", k -> DEFAULT_BUNDLECACHE_DIR);
        
        // check args
        if (oshConfig == null)
        {
            // print usage
            System.out.println("Usage: SensorHubOsgi CONFIG_FILE [OPTION]\n");
            System.out.println("Options:");
            System.out.println("\\t-clearCache        Clear the OSGi bundle cache");
            System.out.println("\\t-autoDeployDir     Path of directory where bundles will be auto-deployed");
            System.out.println("\\t-bundleCacheDir    Path of bundle cache directory");
            System.exit(1);
        }
        
        // create bundles directory
        new File(autoDeployDir).mkdir();
        
        // create osgi config
        var config = new HashMap<String,String>();
        if (clearCache)
            config.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        config.put(Constants.FRAMEWORK_STORAGE, bundleCacheDir);
        config.put(Constants.FRAMEWORK_SYSTEMPACKAGES,
            "org.osgi.framework; version=1.10.0," +
            "org.osgi.framework.dto; version=1.8.0," +
            "org.osgi.framework.wiring; version=1.2.0," +
            //"org.osgi.dto; version=1.1.0," +
            "org.osgi.resource; version=1.0.0," +
            "org.osgi.util.tracker; version=1.5.2," +
            "org.osgi.service.packageadmin; version=1.2.0," +
            "org.osgi.service.startlevel; version=1.1.0," +
            "org.osgi.service.url; version=1.0.0");
        
        config.put(Constants.FRAMEWORK_BOOTDELEGATION,
            "javax.*,sun.*,com.sun.*," +
            "org.xml.*,org.w3c.*");
        
        config.put("org.sensorhub.config", oshConfig);
        
        // load framework
        var frameworkFactory = ServiceLoader.load(FrameworkFactory.class).iterator().next();
        var framework = frameworkFactory.newFramework(config);
        
        // register shutdown hook for a clean stop 
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run()
            {
                try
                {
                    framework.stop();
                    framework.waitForStop(20000);
                }
                catch (InterruptedException e)
                {
                    LOGGER.log(Level.SEVERE, "Thread was interrupted during OSGi shutdown");
                    Thread.currentThread().interrupt();
                }
                catch (Exception e)
                {
                    LOGGER.log(Level.SEVERE, "Error while shutting down OSGi framework", e);
                }
            }
        });
        
        // start framework
        framework.init();
        systemCtx = framework.getBundleContext();
        
        // install everything in auto-deploy folder
        File dir = new File(autoDeployDir);
        File[] bundleJarFiles = dir.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.endsWith(".jar");
            }
        });
        for (var f: bundleJarFiles) {
            LOGGER.info("Installing bundle " + f);
            var bundle = systemCtx.installBundle(REF_PREFIX + f.toPath().toString());
            if (bundle.getHeaders().get("Fragment-Host") == null) {
                bundle.start();
            }
        }
        
        // start all installed bundles
        framework.start();
        
        // watch bundle folder
        try
        {
            var watcher = new DirectoryWatcher(Paths.get(autoDeployDir), StandardWatchEventKinds.ENTRY_CREATE);
            var watcherThread = new Thread(watcher);
            watcher.addListener(path -> {
                try
                {
                    var bundle = systemCtx.installBundle(REF_PREFIX + path.toString());
                    if (bundle.getHeaders().get("Fragment-Host") == null) {
                        bundle.start();
                    }
                }
                catch (BundleException e)
                {
                    LOGGER.log(Level.SEVERE, "Error installing bundle", e);
                }
            });
            watcherThread.start();
        }
        catch (IOException e)
        {
            LOGGER.log(Level.SEVERE, "Error creating watcher on bundle folder", e);
        }
    }
}
