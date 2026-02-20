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

import org.sensorhub.api.client.ClientConfig;
import org.sensorhub.api.config.DisplayInfo;
import org.sensorhub.impl.comm.HTTPConfig;
import org.sensorhub.impl.comm.RobustIPConnectionConfig;
import org.sensorhub.impl.datastore.view.ObsSystemDatabaseViewConfig;
import org.sensorhub.impl.service.consys.client.http.JavaHttpClient;

public class ConSysApiClientConfig extends ClientConfig {

    @DisplayInfo(desc="Filtered view to select systems/datastreams to register with Connected Systems")
    @DisplayInfo.Required
    public ObsSystemDatabaseViewConfig dataSourceSelector;


    @DisplayInfo(label="Connected Systems Endpoint", desc="Connected Systems endpoint where the requests are sent")
    public HTTPConfig conSys = new HTTPConfig();


    @DisplayInfo(label="Connection Options")
    public RobustIPConnectionConfig connection = new RobustIPConnectionConfig();

    @DisplayInfo(label="OAuth Options", desc="Allows for the usage of OAuth Client Credentials (\"bearer\") tokens for instead of basic authentication")
    public ConSysOAuthConfig conSysOAuth = new ConSysOAuthConfig();

    @DisplayInfo(label="Http Client Implementation", desc="Fully qualified class name of the HTTP client implementation to use")
    public String httpClientImplClass;

    public ConSysApiClientConfig()
    {
        this.moduleClass = ConSysApiClientModule.class.getCanonicalName();
        this.conSys.resourcePath = "/sensorhub/api";
        this.httpClientImplClass = JavaHttpClient.class.getCanonicalName();
    }

}
