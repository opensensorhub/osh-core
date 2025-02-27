/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2021 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.datastore.command;

import org.sensorhub.api.common.BigId;
import org.sensorhub.api.resource.ResourceKey;


/**
 * <p>
 * Immutable key object used to index command streams in data stores.
 * </p>
 *
 * @author Alex Robin
 * @since Nov 3, 2020
 */
public class CommandStreamKey extends ResourceKey<CommandStreamKey>
{
    
    public CommandStreamKey(BigId id)
    {
        super(id);
    }
    
    
    public CommandStreamKey(int scope, long internalID)
    {
        super(BigId.fromLong(scope, internalID));
    }
}
