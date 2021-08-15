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
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.felix.bundlerepository.*;
import org.apache.felix.bundlerepository.impl.RepositoryAdminImpl;
import org.osgi.framework.*;
import org.osgi.framework.launch.FrameworkFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class OsgiLauncher
{
    protected static final Logger LOGGER = LoggerFactory.getLogger(OsgiLauncher.class);

    static final String BUNDLE_FOLDER = "bundles";
    BundleContext systemCtx;
    private Map<String, Bundle> osgiBundles = new ConcurrentHashMap<>();

    public OsgiLauncher() throws Exception {
        this(BUNDLE_FOLDER);
    }
    public OsgiLauncher(String baseBundleFolder) throws Exception {
        String baseBundlePath = new File(baseBundleFolder).getAbsolutePath();

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
            try {
                framework.stop();
            } catch (BundleException e) {
                e.printStackTrace();
            }
        }));

        framework.start();
        systemCtx = framework.getBundleContext();

        // autostart everything in bundles folder
        File dir = new File(baseBundlePath);

        // list files from the base directory and install bundle
        File[] bundleJarFiles = dir.listFiles((dir1, name) -> name.endsWith(".jar"));

        // we have to install all bundles before starting them
        for (var f: bundleJarFiles) {
            osgiBundles.put(f.getPath(), systemCtx.installBundle("reference:file:" + f.toPath()));
        }

        for (var entry : osgiBundles.keySet()) {
            osgiBundles.get(entry).start();
        }

        // watch bundle folder
        try {
            var watcher = new DirectoryWatcher(Paths.get(baseBundleFolder),
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE
                    );
            var watcherThread = new Thread(watcher);
            watcher.addListener(this::updateBundle);
            watcherThread.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void startHub() throws InvalidSyntaxException {
        // start sensor hub
//        var ref = coreBundle.getBundleContext().getServiceReferences(Runnable.class, "(type=ISensorHub)").stream().findFirst().get();
        var ref = systemCtx.getBundle().getBundleContext().getServiceReferences(Runnable.class, "(type=ISensorHub)").stream().findFirst().get();
        //var ref = coreBundle.getBundleContext().getServiceReference(Runnable.class);
        var hub = systemCtx.getService(ref);
        hub.run();
        LOGGER.info("Hub have been started successfully");
    }

    protected void installFromRepository(String remoteLocation)  {
        try {
            RepositoryAdmin repositoryAdmin = new RepositoryAdminImpl(systemCtx, null);
            repositoryAdmin.addRepository(new URL(remoteLocation));

            Resolver resolver = repositoryAdmin.resolver();
            Resource[] resources = repositoryAdmin.discoverResources("(|(presentationname=*)(symbolicname=*))");

            for (Resource r : resources) {
                resolver.add(r);
            }
            if (resolver.resolve()) {
                resolver.deploy(Resolver.START);
            } else {
                Reason[] reqs = resolver.getUnsatisfiedRequirements();
                for (int i = 0; i < reqs.length; i++) {
                    LOGGER.warn("Unable to resolve {}",reqs[i].getResource().toString());
                }
            }
        }catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public void installBundles(List<Path> paths) {
        final List<Bundle> bundles = new ArrayList<>();

        for(Path path : paths) {
            try {
                bundles.add(systemCtx.installBundle("reference:file:"+path.toFile().getAbsolutePath()));
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        for(Bundle b: bundles) {
            try {
                b.start();
                LOGGER.info("[SUCESS] "+b.getLocation());
            }catch (Exception ex) {
                LOGGER.error("[ERROR] "+b.getLocation());
                ex.printStackTrace();
            }
        }
    }

    public void updateBundle(DirectoryEvent directoryEvent) {
        try {
            String key = directoryEvent.path.toString();
            if(directoryEvent.kind == StandardWatchEventKinds.ENTRY_CREATE){
                var bundle = systemCtx.installBundle("reference:file:" + key);
                bundle.start();
                osgiBundles.put(key,bundle);
            } else if(directoryEvent.kind == StandardWatchEventKinds.ENTRY_DELETE) {
                if(!osgiBundles.containsKey(key)) {
                    LOGGER.warn("The osgi context does not contain {}",key);
                } else {
                    Bundle bundle = osgiBundles.remove(key);
                    bundle.uninstall();
                    LOGGER.info("Uninstall bundle {}",bundle);
                }
            }
        } catch (BundleException e) {
            e.printStackTrace();
        }
    }


    public static void main(String[] args) throws Exception
    {
        // create bundles directory
        new File(BUNDLE_FOLDER).mkdir();
        URL url = OsgiLauncher.class.getClassLoader().getResource("config_empty_sost.json");
        System.getProperties().setProperty("osh.config", url.getFile());
        OsgiLauncher osgiLauncher = new OsgiLauncher();

        String baseDir = "..";

        String[] bundles  = {
                baseDir   + "/osh-addons/services/sensorhub-service-video/build/libs/sensorhub-service-video-1.2.0-bundle.jar",
//                baseDir + "/osh-addons/comm/sensorhub-comm-mqtt/build/libs/sensorhub-comm-mqtt-2.0.0-bundle.jar",
//                baseDir + "/osh-addons/sensors/weather/sensorhub-utils-grid/build/libs/sensorhub-utils-grid-0.3.3-bundle.jar",
//                baseDir + "/osh-addons/comm/sensorhub-comm-dio/build/libs/sensorhub-comm-dio-2.0.0-bundle.jar",
//                baseDir + "/osh-addons/comm/sensorhub-comm-ip-zeroconf/build/libs/sensorhub-comm-ip-zeroconf-2.0.0-bundle.jar",
//                baseDir + "/osh-addons/comm/sensorhub-comm-rxtx/build/libs/sensorhub-comm-rxtx-2.0.0-bundle.jar",
//                baseDir + "/osh-addons/services/sensorhub-service-commrelay/build/libs/sensorhub-service-commrelay-1.0.0-bundle.jar",
//                baseDir + "/osh-addons/processing/sensorhub-process-basicmath/build/libs/sensorhub-process-basicmath-1.0.0-bundle.jar",
//                baseDir + "/osh-addons/processing/sensorhub-process-vecmath/build/libs/sensorhub-process-vecmath-1.0.0-bundle.jar",
//                baseDir + "/osh-addons/processing/sensorhub-process-geoloc/build/libs/sensorhub-process-geoloc-1.0.0-bundle.jar",
//                baseDir + "/osh-addons/sensors/weather/sensorhub-driver-metar/build/libs/sensorhub-driver-metar-1.0.0-bundle.jar",
//                baseDir + "/osh-addons/sensors/weather/sensorhub-driver-meteobridge/build/libs/sensorhub-driver-meteobridge-0.1-SNAPSHOT-bundle.jar",
//                baseDir + "/osh-addons/sensors/weather/sensorhub-driver-httpweather/build/libs/sensorhub-driver-httpweather-0.1-SNAPSHOT-bundle.jar",
//                baseDir + "/osh-addons/sensors/weather/sensorhub-driver-uahweather/build/libs/sensorhub-driver-uahweather-0.1.0-SNAPSHOT-bundle.jar",
//                baseDir + "/osh-addons/sensors/weather/sensorhub-driver-vaisala/build/libs/sensorhub-driver-vaisala-1.0.0-SNAPSHOT-bundle.jar",
//                baseDir + "/osh-addons/sensors/hydro/sensorhub-driver-intellisense/build/libs/sensorhub-driver-intellisense-0.1.0-bundle.jar",
//                baseDir + "/osh-addons/sensors/hydro/sensorhub-storage-usgswater/build/libs/sensorhub-storage-usgswater-0.1.0-bundle.jar",
//                baseDir + "/osh-addons/sensors/simulated/sensorhub-driver-simulatedcbrn/build/libs/sensorhub-driver-simulatedcbrn-1.0.0-bundle.jar",
//                baseDir + "/osh-addons/sensors/simulated/sensorhub-driver-plume/build/libs/sensorhub-driver-plume-1.0-SNAPSHOT-bundle.jar",
//                baseDir + "/osh-addons/sensors/simulated/sensorhub-driver-fakeweather/build/libs/sensorhub-driver-fakeweather-1.0.0-bundle.jar",
//                baseDir + "/osh-addons/sensors/simulated/sensorhub-driver-simweatherstation/build/libs/sensorhub-driver-simweatherstation-0.1-SNAPSHOT-bundle.jar",
//                baseDir + "/osh-addons/sensors/simulated/sensorhub-driver-fakecam/build/libs/sensorhub-driver-fakecam-0.1-bundle.jar",
//                baseDir + "/osh-addons/sensors/simulated/sensorhub-driver-fakegps/build/libs/sensorhub-driver-fakegps-1.0.1-bundle.jar",
//                baseDir + "/osh-addons/sensors/aviation/sensorhub-driver-navdb/build/libs/sensorhub-driver-navdb-0.5-bundle.jar",
//                baseDir + "/osh-addons/sensors/health/sensorhub-driver-angelsensor/build/libs/sensorhub-driver-angelsensor-0.1-SNAPSHOT-bundle.jar",
                baseDir + "/osh-addons/sensors/video/sensorhub-driver-videocam/build/libs/sensorhub-driver-videocam-1.0.0-bundle.jar",
                baseDir + "/osh-addons/sensors/video/sensorhub-driver-rtpcam/build/libs/sensorhub-driver-rtpcam-1.0.0-bundle.jar",
//                baseDir + "/osh-addons/sensors/video/sensorhub-driver-dahua/build/libs/sensorhub-driver-dahua-1.0.0-bundle.jar",
                baseDir + "/osh-addons/sensors/video/sensorhub-driver-foscam/build/libs/sensorhub-driver-foscam-1.0.0-bundle.jar",
//                baseDir + "/osh-addons/sensors/video/sensorhub-driver-virb-xe/build/libs/sensorhub-driver-virb-xe-1.0.0-bundle.jar",
                baseDir + "/osh-addons/sensors/video/sensorhub-driver-axis/build/libs/sensorhub-driver-axis-1.0.0-bundle.jar",
//                baseDir + "/osh-addons/sensors/video/sensorhub-driver-v4l/build/libs/sensorhub-driver-v4l-1.0.0-bundle.jar",
//                baseDir + "/osh-addons/comm/sensorhub-comm-ble/build/libs/sensorhub-comm-ble-2.0.0-bundle.jar",
//                baseDir + "/osh-addons/comm/sensorhub-comm-ble-dbus/build/libs/sensorhub-comm-ble-dbus-2.0.0-bundle.jar",
//                baseDir + "/osh-addons/sensors/positioning/sensorhub-driver-ahrs/build/libs/sensorhub-driver-ahrs-0.1-SNAPSHOT-bundle.jar",
//                baseDir + "/osh-addons/sensors/positioning/sensorhub-driver-bno055/build/libs/sensorhub-driver-bno055-1.0.0-bundle.jar",
//                baseDir + "/osh-addons/sensors/positioning/sensorhub-driver-mti/build/libs/sensorhub-driver-mti-1.0.0-bundle.jar",
//                baseDir + "/osh-addons/sensors/positioning/sensorhub-driver-gps-nmea/build/libs/sensorhub-driver-gps-nmea-1.0.0-bundle.jar",
//                baseDir + "/osh-addons/sensors/positioning/sensorhub-driver-vectornav/build/libs/sensorhub-driver-vectornav-0.1-SNAPSHOT-bundle.jar",
//                baseDir + "/osh-addons/sensors/positioning/sensorhub-driver-trek1000/build/libs/sensorhub-driver-trek1000-1.0.0-bundle.jar",
//                baseDir + "/osh-addons/sensors/avl/sensorhub-driver-avl-911/build/libs/sensorhub-driver-avl-911-1.0.0-bundle.jar",
//                baseDir + "/osh-addons/sensors/cbrne/sensorhub-driver-gamma/build/libs/sensorhub-driver-gamma-0.1-SNAPSHOT-bundle.jar",
//                baseDir + "/osh-addons/sensors/robotics/sensorhub-driver-mavlink/build/libs/sensorhub-driver-mavlink-1.0.1-bundle.jar",
//                baseDir + "/osh-addons/sensors/robotics/sensorhub-driver-pwm-servos/build/libs/sensorhub-driver-pwm-servos-0.1-SNAPSHOT-bundle.jar",
//                baseDir + "/osh-addons/sensors/smarthome/sensorhub-driver-domoticz/build/libs/sensorhub-driver-domoticz-0.1.0-SNAPSHOT-bundle.jar",
//                baseDir + "/osh-addons/sensors/others/sensorhub-driver-intelipod/build/libs/sensorhub-driver-intelipod-1.0.0-bundle.jar",
//                baseDir + "/osh-addons/sensors/health/sensorhub-driver-angelsensor/build/libs/sensorhub-driver-angelsensor-0.1-SNAPSHOT-bundle.jar",
//                baseDir + "/osh-addons/sensors/weather/sensorhub-driver-nexrad/build/libs/sensorhub-driver-nexrad-1.0.0-SNAPSHOT-bundle.jar",
//                baseDir + "/osh-addons/sensors/video/sensorhub-driver-onvif/build/libs/sensorhub-driver-onvif-0.0.1-bundle.jar",
//                baseDir + "/osh-addons/security/sensorhub-security-gxoauth/build/libs/sensorhub-security-gxoauth-0.9.0-bundle.jar",
//                baseDir + "/osh-addons/security/sensorhub-security-oauth/build/libs/sensorhub-security-oauth-0.9.0-bundle.jar",
//                baseDir + "/osh-addons/sensors/video/sensorhub-driver-kinect/build/libs/sensorhub-driver-kinect-1.0.0-bundle.jar",
//                baseDir + "/osh-addons/persistence/sensorhub-storage-compat/build/libs/sensorhub-storage-compat-2.0.0-bundle.jar",
//                baseDir + "/osh-addons/persistence/sensorhub-storage-es/build/libs/sensorhub-storage-es-1.0.0-SNAPSHOT-bundle.jar",
//                baseDir + "/osh-addons/persistence/sensorhub-storage-h2/build/libs/sensorhub-storage-h2-2.0.0-bundle.jar",
//                baseDir + "/osh-addons/sensors/social/sensorhub-driver-twitter/build/libs/sensorhub-driver-twitter-0.0.1-bundle.jar",
//                baseDir + "/osh-addons/sensors/weather/sensorhub-driver-storm/build/libs/sensorhub-driver-storm-0.4.0-bundle.jar",
//                baseDir + "/osh-addons/processing/sensorhub-process-utils/build/libs/sensorhub-process-utils-1.0.0-bundle.jar",
//                baseDir + "/osh-addons/processing/sensorhub-process-ffmpeg/build/libs/sensorhub-process-ffmpeg-4.2.2-bundle.jar",
//                baseDir + "/osh-addons/processing/sensorhub-process-opencv/build/libs/sensorhub-process-opencv-4.5.1-bundle.jar",
//                baseDir + "/osh-addons/persistence/sensorhub-storage-perst/build/libs/sensorhub-storage-perst-2.0.0-bundle.jar"
        };


        osgiLauncher.installBundles(Arrays.stream(bundles).sequential().map(Paths::get).collect(Collectors.toList()));
//        osgiLauncher.installFromRepository("http://localhost:3333/osgi.xml");
        osgiLauncher.startHub();
    }
}
