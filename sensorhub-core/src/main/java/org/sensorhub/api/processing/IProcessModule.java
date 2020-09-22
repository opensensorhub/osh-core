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
import org.sensorhub.api.module.IModule;
import net.opengis.swe.v20.DataComponent;


/**
 * <p>
 * Base interface for all SensorHub data processing modules
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @param <ConfigType> Type of configuration class
 * @since Feb 21, 2015
 */
public interface IProcessModule<ConfigType extends ProcessConfig> extends IModule<ConfigType>
{
    
    /**
     * Gets the list of inputs needed by this process.<br/>
     * Note that input data may contain more than the needed input.
     * To confirm that a given input is acceptable, call
     * {@link #isCompatibleDataSource(DataSourceConfig)}
     * @return map of descriptors of needed inputs
     */
    public Map<String, DataComponent> getInputDescriptors();
    
    
    /**
     * @return map of output descriptors
     */
    public Map<String, DataComponent> getOutputDescriptors();
    
    
    /**
     * Gets the list of parameter descriptors<br/>
     * Parameters that can be changed during processing must be marked as 'updatable'
     * but changing the values in the descriptor will have no effect on processing.
     * Instead, values must be changed using the updateParameters() method.
     * @return map of parameter descriptors
     */
    public Map<String, DataComponent> getParameterDescriptors();
    
    
    /**
     * Checks that a given data source is acceptable for this process
     * @param dataSource
     * @return true if data source is acceptable for this process
     */
    public boolean isCompatibleDataSource(DataSourceConfig dataSource);

}
