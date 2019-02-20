/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.sensor;

import org.sensorhub.api.common.ProcedureEvent;
import org.sensorhub.api.common.ProcedureEvent.Type;


/**
 * <p>
 * Event generated when sensor state/configuration changes.
 * </p>
 *
 * @author Alex Robin
 * @since Nov 5, 2010
 */
public class SensorEvent extends ProcedureEvent<Type>
{
	
	/**
	 * Constructs the event for an individual sensor
	 * @param timeStamp unix time of event generation
	 * @param sensorModule sensor module that generated the event
	 * @param type type of event
	 */
	public SensorEvent(long timeStamp, ISensor sensor, Type type)
	{
	    this.type = type;
        this.timeStamp = timeStamp;
        this.source = sensor;
        this.procedureID = sensor.getUniqueIdentifier();
	}
	

	/**
     * Gets the unique ID of the sensor related to this event.<br/>
     * For sensor networks, this can be either the ID of the network as a whole
     * (if the change is global) or the ID of one of the sensor in the network
     * (if the change applies only to that particular sensor, e.g. recalibration).
     * @return the ID of the sensor that this event refers to
     */
    public String getSensorID()
    {
        return procedureID;
    }
    
    
	@Override
    public ISensor getSource()
    {
        return (ISensor)this.source;
    }
}
