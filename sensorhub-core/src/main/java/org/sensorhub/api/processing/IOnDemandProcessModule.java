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


/**
 * <p>
 * Base interface for on-demand processing modules.<br/>
 * This type of module is configured to fetch data from fixed streaming or
 * archive sources, but can be further parameterized for each execution.<br/>
 * The caller is responsible for getting new process instances when appropriate:
 * A single process instance can be reused several times sequentially, but
 * separate instances are needed to launch in parallel.
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @param <ConfigType> Type of configuration class
 * @since Feb 20, 2015
 */
public interface IOnDemandProcessModule<ConfigType extends ProcessConfig> extends IProcessModule<ConfigType>
{

    
    /**
     * Retrieves a new instance of this processor configured with default data sources
     * @return new process instance
     */
    public IOnDemandProcessInstance getNewProcessInstance();
    
}
