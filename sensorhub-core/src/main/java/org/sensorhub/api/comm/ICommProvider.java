/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.comm;

import org.sensorhub.api.module.IModule;
import org.sensorhub.impl.comm.ConnectionEventArgs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Consumer;

/**
 * <p>
 * Interface for all communication providers giving access to an input stream
 * for reading incoming data and an output stream for sending outgoing data.
 * </p>
 *
 * @param <ConfigType> Comm module config type
 * @author Alex Robin
 * @since Jun 19, 2015
 */
public interface ICommProvider<ConfigType extends CommProviderConfig<?>> extends IModule<ConfigType> {

    InputStream getInputStream() throws IOException;


    OutputStream getOutputStream() throws IOException;

    /**
     * Adds a connection event handler to this comm provider.
     * The handler will be called when a connection is established.
     * <p>
     * For client comm providers, this will be called when the comm module starts.
     * Make sure to register the handler before starting the module or the event will be missed.
     * <p>
     * For server comm providers, this will be called every time a client connects.
     *
     * @param eventHandler The event handler to add
     */
    void onConnection(Consumer<ConnectionEventArgs> eventHandler);
}
