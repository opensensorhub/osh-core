/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2017 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.comm;

import java.util.Map;
import org.sensorhub.api.module.ISubModule;


/**
 * <p>
 * Common interface to publish and receive messages from various message
 * queue implementations
 * </p>
 *
 * @author Alex Robin
 * @since Nov 29, 2017
 */
public interface IMessageQueuePush<T extends MessageQueueConfig> extends ISubModule<T>
{
    
    public interface MessageListener
    {
        public void receive(Map<String,String> attrs, byte[] payload);
    }
    
    
    public void publish(byte[] payload);
    
    
    public void publish(Map<String,String> attrs, byte[] payload);
    
    
    public void registerListener(MessageListener listener);
    
    
    public void unregisterListener(MessageListener listener);
}
