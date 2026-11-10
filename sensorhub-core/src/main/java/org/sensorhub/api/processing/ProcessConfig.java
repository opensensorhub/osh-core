/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.processing;

import org.sensorhub.api.config.DisplayInfo;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.api.sensor.IdentificationInfo;


/**
 * <p>
 * Configuration options for processing modules
 * </p>
 *
 * @author Alex Robin
 * @since Feb 20, 2015
 */
public class ProcessConfig extends ModuleConfig
{

    @DisplayInfo(label = "Identification Info", desc="Information used to uniquely identify this system and its responsible parties")
    public IdentificationInfo identificationInfo;

}
