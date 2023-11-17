/***************************** BEGIN LICENSE BLOCK ***************************
 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2023 Botts Innovative Research Inc. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.comm;

import org.sensorhub.api.module.IModule;
import org.sensorhub.api.module.IModuleProvider;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.impl.module.JarModuleProvider;

/**
 * Communication provider for TCP/IP server connections.
 *
 * @author Michael Elmore
 * @since September 2023
 */
public class TCPServerCommModuleDescriptor extends JarModuleProvider implements IModuleProvider {
    @Override
    public String getModuleName() {
        return "TCP Server Comm Driver";
    }

    @Override
    public String getModuleDescription() {
        return "Simple TCP/IP server communication provider using JDK TCP stack. Allows multiple client sensors to connect.";
    }

    @Override
    public Class<? extends IModule<?>> getModuleClass() {
        return TCPServerCommProvider.class;
    }

    @Override
    public Class<? extends ModuleConfig> getModuleConfigClass() {
        return TCPServerCommProviderConfig.class;
    }
}
