/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2021 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.module;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.sensorhub.api.module.IModuleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * <p>
 * The module class finder can find classes using the ServiceLoader API or
 * OSGI service discovery.
 * </p>
 *
 * @author Alex Robin
 * @since Apr 20, 2021
 */
public class ModuleClassFinder
{
    private static final Logger log = LoggerFactory.getLogger(ModuleRegistry.class);
    BundleContext osgiContext;
    
    
    public ModuleClassFinder()
    {        
    }
    
    
    public ModuleClassFinder(BundleContext osgiContext)
    {
        this.osgiContext = osgiContext;
    }

    public Class<?> findClass(String className) throws ClassNotFoundException
    {

        if (osgiContext != null)
        {
            try {
                var moduleRefs = osgiContext.getServiceReferences(IModuleProvider.class, null);
                for (var ref: moduleRefs) {
                    var m = osgiContext.getService(ref);
                    
                    var configClass = m.getModuleConfigClass();
                    if (className.equals(configClass.getCanonicalName()))
                        return configClass;
                    
                    var implClass = m.getModuleClass();
                    if (className.equals(implClass.getCanonicalName()))
                        return implClass;

                    // try to find if the current module class loader can load this class/subclass
                    try {
                        return Class.forName(className, true, implClass.getClassLoader());
                    } catch (Exception ex) {
                        continue;
                    }
                }
            }
            catch (InvalidSyntaxException e)
            {
            }
        }

        return Class.forName(className);
    }

    public Collection<IModuleProvider> getInstalledModuleTypes(Class<?> moduleClass)
    {
        var availableModules = new ArrayList<IModuleProvider>();
        
        if (osgiContext != null)
        {
            try
            {
                var moduleRefs = osgiContext.getServiceReferences(IModuleProvider.class, null);
                for (var ref: moduleRefs)
                {
                    var m = osgiContext.getService(ref);                    
                    if (moduleClass.isAssignableFrom(m.getModuleClass()))
                        availableModules.add(m);
                }
            }
            catch (InvalidSyntaxException e)
            {
            }
        }
        else
        {
            ServiceLoader<IModuleProvider> sl = ServiceLoader.load(IModuleProvider.class);
            try
            {
                for (IModuleProvider provider: sl)
                {
                    if (moduleClass.isAssignableFrom(provider.getModuleClass()))
                        availableModules.add(provider);
                }
            }
            catch (ServiceConfigurationError e)
            {
                log.error("{}: {}", ServiceConfigurationError.class.getName(), e.getMessage());
            }
        }
        
        return availableModules;
    }
}