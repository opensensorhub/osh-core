/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2025 GeoRobotix. All Rights Reserved.

 ******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.client;

import org.sensorhub.api.module.IModule;
import org.sensorhub.api.module.IModuleProvider;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.impl.module.JarModuleProvider;

public class ConSysApiClientDescriptor extends JarModuleProvider implements IModuleProvider {

    @Override
    public String getModuleName()
    {
        return "Connected Systems Client";
    }


    @Override
    public String getModuleDescription()
    {
        return "Connected Systems client for pushing observations to a remote SensorHub";
    }


    @Override
    public Class<? extends IModule<?>> getModuleClass()
    {
        return ConSysApiClientModule.class;
    }


    @Override
    public Class<? extends ModuleConfig> getModuleConfigClass()
    {
        return ConSysApiClientConfig.class;
    }

}
