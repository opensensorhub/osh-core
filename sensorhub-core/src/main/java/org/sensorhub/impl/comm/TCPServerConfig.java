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

import org.sensorhub.api.comm.ICommConfig;
import org.sensorhub.api.config.DisplayInfo;
import org.sensorhub.api.config.DisplayInfo.FieldType;
import org.sensorhub.api.config.DisplayInfo.FieldType.Type;
import org.sensorhub.api.config.DisplayInfo.Required;
import org.sensorhub.api.config.DisplayInfo.ValueRange;


/**
 * Driver configuration options for the TCP/IP server network protocol
 *
 * @author Michael Elmore
 * @since September 2023
 */
public class TCPServerConfig implements ICommConfig {
    @DisplayInfo(desc = "Port number to listen on")
    @ValueRange(max = 65535)
    @Required
    public int localPort;

    @DisplayInfo(label = "User Name", desc = "Remote user name")
    public String user;

    @DisplayInfo(label = "Password", desc = "Remote password")
    @FieldType(Type.PASSWORD)
    public String password;

    @DisplayInfo(desc = "Secure communications with SSL/TLS")
    public boolean enableTLS;
}
