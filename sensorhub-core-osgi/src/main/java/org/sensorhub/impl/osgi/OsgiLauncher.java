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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.util.*;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;


public class OsgiLauncher
{
    static final String BUNDLE_FOLDER = "bundles";
    BundleContext systemCtx;
    
    
    public OsgiLauncher() throws Exception
    {
        var frameworkFactory = ServiceLoader.load(FrameworkFactory.class).iterator().next();
        var config = new HashMap<String,String>();
        
        config.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        config.put(Constants.FRAMEWORK_SYSTEMPACKAGES,
            "org.osgi.framework; version=1.10.0," +
            //"org.osgi.framework.dto; version=1.8.0," +
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
        
        var framework = frameworkFactory.newFramework(config);
        
        // register shutdown hook for a clean stop 
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try
            {
                framework.stop();
            }
            catch (BundleException e)
            {
                e.printStackTrace();
            }
        }));        
        
        framework.start();
        systemCtx = framework.getBundleContext();
        Bundle bundle;
        
        // autostart everything in bundles folder
        File dir = new File(BUNDLE_FOLDER);

        File[] bundleJarFiles = dir.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.endsWith(".jar");
            }
        });
        for (var f: bundleJarFiles)
            newBundle(f.toPath());
        
        // install all bundles
        var coreBundle = bundle = systemCtx.installBundle("reference:file:./sensorhub-core/build/libs/sensorhub-core-2.0.0-bundle.jar");
        bundle.start();
//
        bundle  = systemCtx.installBundle("reference:file:./sensorhub-service-swe/build/libs/sensorhub-service-swe-2.0.0-bundle.jar");
        bundle.start();

        bundle = systemCtx.installBundle("reference:file:./sensorhub-webui-core/build/libs/sensorhub-webui-core-2.0.0-bundle.jar");
        bundle.start();
        

        String[] bundles  = {
            "./sensors/weather/sensorhub-utils-grid/build/libs/sensorhub-utils-grid-0.3.3-bundle.jar",
//            "./comm/sensorhub-comm-dio/build/libs/sensorhub-comm-dio-2.0.0-bundle.jar",
//            "./comm/sensorhub-comm-ip-zeroconf/build/libs/sensorhub-comm-ip-zeroconf-2.0.0-bundle.jar",
//            "./services/sensorhub-service-commrelay/build/libs/sensorhub-service-commrelay-1.0.0-bundle.jar",
//            "./processing/sensorhub-process-basicmath/build/libs/sensorhub-process-basicmath-1.0.0-bundle.jar",
//            "./processing/sensorhub-process-vecmath/build/libs/sensorhub-process-vecmath-1.0.0-bundle.jar",
//            "./processing/sensorhub-process-geoloc/build/libs/sensorhub-process-geoloc-1.0.0-bundle.jar",
//            "./sensors/weather/sensorhub-driver-metar/build/libs/sensorhub-driver-metar-1.0.0-bundle.jar",
//            "./sensors/weather/sensorhub-driver-meteobridge/build/libs/sensorhub-driver-meteobridge-0.1-SNAPSHOT-bundle.jar",
//            "./sensors/weather/sensorhub-driver-httpweather/build/libs/sensorhub-driver-httpweather-0.1-SNAPSHOT-bundle.jar",
//            "./sensors/weather/sensorhub-driver-uahweather/build/libs/sensorhub-driver-uahweather-0.1.0-SNAPSHOT-bundle.jar",
//            "./sensors/weather/sensorhub-driver-vaisala/build/libs/sensorhub-driver-vaisala-1.0.0-SNAPSHOT-bundle.jar",
//            "./sensors/hydro/sensorhub-driver-intellisense/build/libs/sensorhub-driver-intellisense-0.1.0-bundle.jar",
//            "./sensors/hydro/sensorhub-storage-usgswater/build/libs/sensorhub-storage-usgswater-0.1.0-bundle.jar",
//            "./sensors/simulated/sensorhub-driver-simulatedcbrn/build/libs/sensorhub-driver-simulatedcbrn-1.0.0-bundle.jar",
//            "./sensors/simulated/sensorhub-driver-plume/build/libs/sensorhub-driver-plume-1.0-SNAPSHOT-bundle.jar",
//            "./sensors/simulated/sensorhub-driver-fakeweather/build/libs/sensorhub-driver-fakeweather-1.0.0-bundle.jar",
//            "./sensors/simulated/sensorhub-driver-simweatherstation/build/libs/sensorhub-driver-simweatherstation-0.1-SNAPSHOT-bundle.jar",
//            "./sensors/simulated/sensorhub-driver-fakecam/build/libs/sensorhub-driver-fakecam-0.1-bundle.jar",
//            "./sensors/simulated/sensorhub-driver-fakegps/build/libs/sensorhub-driver-fakegps-1.0.1-bundle.jar",
//            "./sensors/aviation/sensorhub-driver-navdb/build/libs/sensorhub-driver-navdb-0.5-bundle.jar",
//            "./sensors/health/sensorhub-driver-angelsensor/build/libs/sensorhub-driver-angelsensor-0.1-SNAPSHOT-bundle.jar",
            "./sensors/video/sensorhub-driver-videocam/build/libs/sensorhub-driver-videocam-1.0.0-bundle.jar",
            "./sensors/video/sensorhub-driver-rtpcam/build/libs/sensorhub-driver-rtpcam-1.0.0-bundle.jar",
            "./sensors/video/sensorhub-driver-dahua/build/libs/sensorhub-driver-dahua-1.0.0-bundle.jar",
            "./sensors/video/sensorhub-driver-foscam/build/libs/sensorhub-driver-foscam-1.0.0-bundle.jar",
            "./sensors/video/sensorhub-driver-virb-xe/build/libs/sensorhub-driver-virb-xe-1.0.0-bundle.jar",
            "./sensors/video/sensorhub-driver-axis/build/libs/sensorhub-driver-axis-1.0.0-bundle.jar",
            "./sensors/video/sensorhub-driver-v4l/build/libs/sensorhub-driver-v4l-1.0.0-bundle.jar",
//            "./comm/sensorhub-comm-ble/build/libs/sensorhub-comm-ble-2.0.0-bundle.jar",
//            "./comm/sensorhub-comm-ble-dbus/build/libs/sensorhub-comm-ble-dbus-2.0.0-bundle.jar",

//            "./sensors/avl/sensorhub-driver-avl-911/build/libs/sensorhub-driver-avl-911-1.0.0-bundle.jar",
//            "./sensors/positioning/sensorhub-driver-ahrs/build/libs/sensorhub-driver-ahrs-0.1-SNAPSHOT-bundle.jar",
//            "./sensors/positioning/sensorhub-driver-bno055/build/libs/sensorhub-driver-bno055-1.0.0-bundle.jar",
//            "./sensors/positioning/sensorhub-driver-mti/build/libs/sensorhub-driver-mti-1.0.0-bundle.jar",
//            "./sensors/positioning/sensorhub-driver-gps-nmea/build/libs/sensorhub-driver-gps-nmea-1.0.0-bundle.jar",
//            "./sensors/positioning/sensorhub-driver-vectornav/build/libs/sensorhub-driver-vectornav-0.1-SNAPSHOT-bundle.jar",
//            "./sensors/positioning/sensorhub-driver-trek1000/build/libs/sensorhub-driver-trek1000-1.0.0-bundle.jar",
//            "./sensors/cbrne/sensorhub-driver-gamma/build/libs/sensorhub-driver-gamma-0.1-SNAPSHOT-bundle.jar",
//            "./sensors/robotics/sensorhub-driver-mavlink/build/libs/sensorhub-driver-mavlink-1.0.1-bundle.jar",
//            "./sensors/robotics/sensorhub-driver-pwm-servos/build/libs/sensorhub-driver-pwm-servos-0.1-SNAPSHOT-bundle.jar",
//            "./sensors/smarthome/sensorhub-driver-domoticz/build/libs/sensorhub-driver-domoticz-0.1.0-SNAPSHOT-bundle.jar",
//            "./sensors/others/sensorhub-driver-intelipod/build/libs/sensorhub-driver-intelipod-1.0.0-bundle.jar",
//            "./sensors/health/sensorhub-driver-angelsensor/build/libs/sensorhub-driver-angelsensor-0.1-SNAPSHOT-bundle.jar",
//            "./sensors/weather/sensorhub-driver-nexrad/build/libs/sensorhub-driver-nexrad-1.0.0-SNAPSHOT-bundle.jar",
//            "./sensors/video/sensorhub-driver-onvif/build/libs/sensorhub-driver-onvif-0.0.1-bundle.jar",
//            "./security/sensorhub-security-gxoauth/build/libs/sensorhub-security-gxoauth-0.9.0-bundle.jar",
//            "./security/sensorhub-security-oauth/build/libs/sensorhub-security-oauth-0.9.0-bundle.jar",
//            "./persistence/sensorhub-storage-es/build/libs/sensorhub-storage-es-1.0.0-SNAPSHOT-bundle.jar",
//            "./persistence/sensorhub-storage-h2/build/libs/sensorhub-storage-h2-2.0.0-bundle.jar",
            "./sensors/social/sensorhub-driver-twitter/build/libs/sensorhub-driver-twitter-0.0.1-bundle.jar",
            "./sensors/weather/sensorhub-driver-storm/build/libs/sensorhub-driver-storm-0.4.0-bundle.jar"
        };
        List<Bundle> bundleList = new ArrayList<>();
        for(String b : bundles) {
            try {
            bundleList.add(systemCtx.installBundle("reference:file:../osh-addons/"+b));
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
        for(Bundle b: bundleList) {
            try {
                b.start();
                System.out.println("[SUCESS] "+b.getLocation());
            }catch (Exception ex) {
                System.err.println("[ERROR] "+b.getLocation());
                ex.printStackTrace();
            }
        }
        // watch bundle folder
        try
        {
            var watcher = new DirectoryWatcher(Paths.get(BUNDLE_FOLDER), StandardWatchEventKinds.ENTRY_CREATE);
            var watcherThread = new Thread(watcher);
            watcher.addListener(this::newBundle);
            watcherThread.start();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }

        // start sensor hub
//        var ref = coreBundle.getBundleContext().getServiceReferences(Runnable.class, "(type=ISensorHub)").stream().findFirst().get();
        var ref = systemCtx.getBundle().getBundleContext().getServiceReferences(Runnable.class, "(type=ISensorHub)").stream().findFirst().get();
        //var ref = coreBundle.getBundleContext().getServiceReference(Runnable.class);
        var hub = systemCtx.getService(ref);
        hub.run();
        
    }
    
    
    public void newBundle(Path path)
    {
        try
        {
            var bundle = systemCtx.installBundle("reference:file:" + path.toString());
            bundle.start();
        }
        catch (BundleException e)
        {
            e.printStackTrace();
        }
    }
    
    
    public static void main(String[] args) throws Exception
    {
        // create bundles directory
        new File(BUNDLE_FOLDER).mkdir();
        
        System.getProperties().setProperty("osh.config", "/run/media/nevro/9014351c-ebf9-45fb-8661-65ce5562215f/OSH/github/osh-core/sensorhub-core-test/src/main/resources/config_empty_sost.json");
        new OsgiLauncher();
    }
}
