/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.system;

import org.sensorhub.api.common.BigId;
import org.sensorhub.api.event.Event;
import org.sensorhub.api.event.EventUtils;
import org.sensorhub.api.utils.OshAsserts;
import org.vast.util.Asserts;


/**
 * <p>
 * Base class for all events that are associated to a particular system.<br/>
 * Events can be generated by top level systems and by subsystems.
 * </p>
 *
 * @author Alex Robin
 * @since Apr 23, 2015
 */
public abstract class SystemEvent extends Event
{
    protected String systemUID;
    protected String sourceID;
    protected BigId systemID;
    

    public SystemEvent(long timeStamp, String sysUID)
    {
        this.timeStamp = timeStamp;
        this.systemUID = OshAsserts.checkValidUID(sysUID);
    }
    
    
    public SystemEvent(String sysUID)
    {
        this(System.currentTimeMillis(), sysUID);
    }


    /**
     * Gets the unique ID of the system related to this event.<br/>
     * For system groups (e.g. sensor networks), it will be either the UID
     * of the group as a whole (if the event is global) or the UID of a single
     * subsystem within the group (if the event applies only to that member)
     * @return Unique ID of related system
     */
    public String getSystemUID()
    {
        return systemUID;
    }


    /**
     * @return Local ID of the system related to this event
     */
    public BigId getSystemID()
    {
        return systemID;
    }
    
    
    /**
     * Called by the framework to assign the system's local ID to this event.
     * This can only be called once and must be called before the event is
     * dispatched.
     * @param internalID Local ID of related system
     */
    public void assignSystemID(BigId internalID)
    {
        Asserts.checkState(systemID == null, "System ID is already assigned");
        this.systemID = internalID;
    }
    

    @Override
    public String getSourceID()
    {
        if (sourceID == null)
            sourceID = EventUtils.getSystemStatusTopicID(systemUID);
        return sourceID;
    }

}
