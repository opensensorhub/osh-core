/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.database.system;

import org.sensorhub.api.module.IModule;
import org.sensorhub.api.module.IModuleProvider;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.impl.module.JarModuleProvider;


public class SystemDriverDatabaseDescriptor extends JarModuleProvider implements IModuleProvider
{
    @Override
    public String getModuleName()
    {
        return "System Driver Database";
    }
    

    @Override
    public String getModuleDescription()
    {
        return "Generic database wrapper to record all data produced by one or more system drivers";
    }
    

    @Override
    public Class<? extends IModule<?>> getModuleClass()
    {
        return SystemDriverDatabase.class;
    }
    

    @Override
    public Class<? extends ModuleConfig> getModuleConfigClass()
    {
        return SystemDriverDatabaseConfig.class;
    }
}
