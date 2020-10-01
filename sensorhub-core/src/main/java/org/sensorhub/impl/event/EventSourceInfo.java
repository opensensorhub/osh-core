/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.event;

import org.sensorhub.api.event.IEventSourceInfo;
import org.vast.util.Asserts;


public class EventSourceInfo implements IEventSourceInfo
{
    String groupID;
    String sourceID;
    
    
    public EventSourceInfo(String sourceID)
    {
        this(null, sourceID);
    }
    
    
    public EventSourceInfo(String groupID, String sourceID)
    {
        Asserts.checkNotNull(sourceID, "sourceID");
        this.groupID = groupID;
        this.sourceID = sourceID;
    }


    @Override
    public String getGroupID()
    {
        return groupID;
    }


    @Override
    public String getSourceID()
    {
        return sourceID;
    }
    
    
    @Override
    public String toString()
    {
        return (groupID == null ? "" : groupID) + " | " + sourceID;        
    }
    

}