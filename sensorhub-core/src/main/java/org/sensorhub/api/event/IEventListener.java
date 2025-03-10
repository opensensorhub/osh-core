/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.event;

/**
 * <p>
 * Generic event listener.<br/>
 * Not that this class does not provide flow control methods. If back-pressure
 * is needed, use Java 9 {@link java.util.concurrent.Flow.Subscriber Subscriber}
 * instead
 * </p>
 *
 * @author Alex Robin
 * @since Nov 5, 2010
 */
@FunctionalInterface
public interface IEventListener
{
    public void handleEvent(Event e);
}
