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

import org.sensorhub.api.event.Event;


public class TestEvent extends Event
{
    String sourceID;
    String text;
    int count;
    
    
    public TestEvent(String sourceID, String text, int count)
    {
        this.timeStamp = System.currentTimeMillis();
        this.sourceID = sourceID;
        this.text = text;
        this.count = count;
    }
    
    
    @Override
    public String getSourceID()
    {
        return sourceID;
    }
    
    
    public String getText()
    {
        return text;
    }
    
    
    public int getCount()
    {
        return count;
    }

}
