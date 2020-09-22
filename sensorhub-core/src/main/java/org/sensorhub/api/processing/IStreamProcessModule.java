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

import java.util.Map;
import org.sensorhub.api.common.IEventListener;
import org.sensorhub.api.data.IDataProducerModule;
import net.opengis.swe.v20.DataBlock;


/**
 * <p>
 * Interface for all event stream processors.<br/>
 * This type of process is started in a persistent manner and listens to
 * incoming events. The algorithm is triggered repeatedly each time enough
 * input data events have been received or some time has ellapsed.<br/>
 * Each process can listen to multiple event streams and produce a different
 * type of result (and corresponding event) on each of its output.
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @param <ConfigType> Type of configuration class
 * @since Feb 20, 2015
 */
public interface IStreamProcessModule<ConfigType extends StreamProcessConfig> extends IProcessModule<ConfigType>, IDataProducerModule<ConfigType>, IEventListener
{   
    
    /**
     * Checks if this particular processing module supports pausing
     * @return true if the process can be paused and resumed at any time
     */
    boolean isPauseSupported();
    
    
    /**
     * Pauses processing of the event stream.<br/>
     * Incoming events are simply discarded and won't be processed when the process is resumed.
     */
    public void pause();
    
    
    /**
     * Resumes normal processing of the event stream.<br/>
     * Processing may actually resume only when the next event is received.
     */
    public void resume();
    
    
    /**
     * Updates parameter values of a running process
     * @param newParamData map of parameter names to the corresponding parameter values.
     * New values for all or only some of the parameters can be provided.  
     */
    public void updateParameters(Map<String, DataBlock> newParamData);
    
}
