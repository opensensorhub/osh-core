/***************************** BEGIN LICENSE BLOCK ***************************
 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2023 Botts Innovative Research Inc. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.comm;

import org.sensorhub.api.module.IModule;
import org.sensorhub.impl.comm.ConnectionEventArgs;

import java.util.function.Consumer;

/**
 * Interface for all server communication providers giving access to input and output streams
 * via a connection event handler.
 *
 * @param <ConfigType> Comm module config type
 * @author Michael Elmore
 * @since November 2023
 */
public interface IServerCommProvider<ConfigType extends CommProviderConfig<?>> extends IModule<ConfigType> {
    /**
     * Adds a connection event handler to this comm provider.
     * The handler will be called when a connection is established.
     * Only one handler can be registered at a time.
     * Calling this method again will replace the previous handler.
     *
     * @param eventHandler The event handler to add
     */
    void onConnection(Consumer<ConnectionEventArgs> eventHandler);
}
