/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2026 GeoRobotix Innovative Research, LLC. All Rights Reserved.

 ******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.sensor;

import org.sensorhub.api.config.DisplayInfo;
import org.vast.util.ResponsibleParty;

import java.util.Collection;

public class IdentificationInfo {

    @DisplayInfo.Required
    @DisplayInfo(label = "System UID", desc = "Overrides this resource's system UID")
    public String systemUID;

    @DisplayInfo(label = "System Kind UID", desc = "Optional field overrides this resource's system kind/type UID")
    public String systemKindUID;

    @DisplayInfo(label = "Responsible Parties", desc = "Contact/business information of system/device operators and technical support")
    public Collection<ResponsibleParty> responsibleParties;

}
