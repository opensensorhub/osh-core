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

import org.sensorhub.api.config.DisplayInfo;

public class ConSysOAuthConfig {

    @DisplayInfo(label = "Use OAuth Authentication")
    public boolean oAuthEnabled = true;

    @DisplayInfo(label="Token Endpoint", desc="URL of OAuth provider's token endpoint")
    public String tokenEndpoint;

    @DisplayInfo(desc="Client ID as provided by your OAuth provider")
    public String clientID;


    @DisplayInfo(desc="Client Secret as provided by your OAuth provider")
    public String clientSecret;
}
