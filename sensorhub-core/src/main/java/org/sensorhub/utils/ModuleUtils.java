/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2016 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.utils;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import org.sensorhub.api.module.IModule;
import org.sensorhub.api.module.IModuleProvider;
import org.sensorhub.api.module.ModuleConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.impl.StaticLoggerBinder;
import org.vast.util.Asserts;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.ContextInitializer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ModuleUtils
{
    private static final Logger log = LoggerFactory.getLogger(ModuleUtils.class);
    
    public static final String MODULE_NAME = "Bundle-Name";
    public static final String MODULE_DESC = "Bundle-Description";
    public static final String MODULE_VERSION = "Bundle-Version";
    public static final String MODULE_VENDOR = "Bundle-Vendor";
    public static final String MODULE_BUILD = "Bundle-BuildNumber";
    public static final String MODULE_DEPS = "OSH-Dependencies";
    
    public static final String LOG_MODULE_ID = "MODULE_ID";

    private static Pattern ENV_VAR_REGEX = Pattern.compile("\\$?\\$\\{(.+?)(:(.+?))?\\}");

    public static Manifest getManifest(Class<?> clazz)
    {
        try
        {
            URL srcUrl = clazz.getProtectionDomain().getCodeSource().getLocation();
            
            if (srcUrl != null && srcUrl.getFile().toLowerCase().endsWith(".jar"))
            {
                String jarName = URLDecoder.decode(srcUrl.getFile(), StandardCharsets.UTF_8.name());
                log.debug("Loading manifest from JAR file: {}", jarName);
                
                try ( JarFile jar = new JarFile(new File(jarName)) ) {
                    return jar.getManifest();
                }
            }
            
            return null;            
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Cannot access JAR manifest", e);
        }
    }
    
    
    public static IModuleProvider getModuleInfo(Class<?> clazz)
    {
        Manifest manifest = getManifest(clazz);
        final String name, desc, version, vendor;
        
        if (manifest == null)
        {
            name = MODULE_NAME;
            desc = MODULE_DESC;
            version = MODULE_VERSION;
            vendor = MODULE_VENDOR;
        }
        else
        {
            Attributes attributes = manifest.getMainAttributes();
            name = attributes.getValue(MODULE_NAME);
            desc = attributes.getValue(MODULE_DESC);
            version = attributes.getValue(MODULE_VERSION);
            vendor = attributes.getValue(MODULE_VENDOR);
        }
        
        return new IModuleProvider()
        {
            @Override
            public String getModuleName()
            {
                return name;
            }

            @Override
            public String getModuleDescription()
            {
                return desc;
            }

            @Override
            public String getModuleVersion()
            {
                return version;
            }

            @Override
            public String getProviderName()
            {
                return vendor;
            }

            @Override
            public Class<? extends IModule<?>> getModuleClass()
            {
                return null;
            }

            @Override
            public Class<? extends ModuleConfig> getModuleConfigClass()
            {
                return null;
            }    
        };
    }
    
    
    public static String[] getBundleDependencies(Class<?> clazz)
    {
        Manifest manifest = getManifest(clazz);
        if (manifest == null)
            return new String[0];
            
        String packages = manifest.getMainAttributes().getValue(MODULE_DEPS);
        if (packages == null)
            return new String[0];
        else
            return packages.split(",");
    }
    
    
    public static String getBuildNumber(Class<?> clazz)
    {
        Manifest manifest = getManifest(clazz);
        if (manifest == null)
            return null;
        return manifest.getMainAttributes().getValue(MODULE_BUILD);
    }
    
    
    /**
     * Creates or retrieves logger dedicated to the specified module
     * @param module Module to get logger for
     * @return Logger instance in a separate logging context
     */
    public static Logger createModuleLogger(IModule<?> module)
    {
        Asserts.checkNotNull(module, IModule.class);        
        String moduleID = module.getLocalID();

        // if module config wasn't initialized or logback not available, use class logger
        StaticLoggerBinder binder = StaticLoggerBinder.getSingleton();
        if (moduleID == null || !binder.getLoggerFactoryClassStr().contains("logback"))
            return LoggerFactory.getLogger(module.getClass());
        
        // generate instance ID
        String instanceID = Integer.toHexString(moduleID.hashCode());
        instanceID.replaceAll("-", ""); // remove minus sign if any
        
        // create logger in new context
        try
        {
            LoggerContext logContext = new LoggerContext();
            logContext.setName(FileUtils.safeFileName(moduleID));
            logContext.putProperty(LOG_MODULE_ID, FileUtils.safeFileName(moduleID));
            new ContextInitializer(logContext).autoConfig();
            return logContext.getLogger(module.getClass().getCanonicalName() + ":" + instanceID);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Could not configure module logger", e);
        }
    }

    /**
     * Performs variable expansion on a configuration string property.<br/>
     * Variables are resolved by searching first the JVM system properties (i.e. set with -D)
     * and then the environment variables.
     * @param str The string to expand
     * @return the string with all variable expanded
     */
    public static String expand(String str)
    {
        return expand(str, false);
    }


    public static String expand(String str, boolean honorLazyFlag)
    {
        String expandedStr = str;
        Matcher matcher = ENV_VAR_REGEX.matcher(str);
        while (matcher.find())
        {
            String varStr = matcher.group();
            String replaceStr;

            if (varStr.startsWith("$$") && honorLazyFlag)
            {
                // case of variable expanded lazily at run time
                // this only works with String config params and allows the variable name to show
                // up in the admin console. (e.g. paths and URLs)
                // expand() must then be called again by the module itself.
                continue;
            }
            else
            {
                // case of variable expanded at config load time (i.e. during json parsing)
                String varName = matcher.group(1);
                String defaultVal = matcher.group(3);
                String envVal = getEnvVar(varName);
                replaceStr = (envVal != null) ? envVal :
                        (defaultVal != null) ? defaultVal : "";

                expandedStr = expandedStr.replace(varStr, replaceStr);
            }
        }

        return expandedStr;
    }


    private static String getEnvVar(String varName)
    {
        String val = System.getProperty(varName);
        if (val == null)
            val = System.getenv(varName);
        return val;
    }
}
