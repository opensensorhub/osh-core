/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.task;

import org.sensorhub.api.system.SystemEvent;


/**
 * <p>
 * Event generated when a new task is added
 * </p>
 *
 * @author Alex Robin
 * @date Mar 10, 2021
 */
public class TaskEvent extends SystemEvent
{

    public TaskEvent(long timeStamp, String sysUID)
    {
        super(timeStamp, sysUID);
        // TODO Auto-generated constructor stub
    }

}
