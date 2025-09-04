/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 The Initial Developer is Botts Innovative Research Inc. Portions created by the Initial
 Developer are Copyright (C) 2025 the Initial Developer. All Rights Reserved.

 ******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service;

import org.sensorhub.api.config.DisplayInfo;

public class FileServerConfig {

    @DisplayInfo(desc="Directory where static web content is located.")
    public String staticContentRootUrl;

    @DisplayInfo(desc="Root URL where static web content will be served.")
    public String staticContentRootDir;

    @DisplayInfo(label="Require Authentication", desc="Set to require remote users to be authentified before they can use this service")
    public boolean requireAuth;

    public FileServerConfig() {}

    public FileServerConfig(String contentUrl, String contentDir, boolean requireAuth) {
        this.requireAuth = requireAuth;
        staticContentRootDir = contentDir;
        staticContentRootUrl = contentUrl;
    }

}
