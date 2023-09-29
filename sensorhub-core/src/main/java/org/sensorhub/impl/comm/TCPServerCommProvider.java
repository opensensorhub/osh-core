/***************************** BEGIN LICENSE BLOCK ***************************
 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2023 Botts Innovative Research Inc. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.comm;

import org.sensorhub.api.comm.ICommProvider;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.impl.module.AbstractModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Communication provider for TCP/IP server connections.
 *
 * @author Michael Elmore
 * @since September 2023
 */
public class TCPServerCommProvider extends AbstractModule<TCPServerCommProviderConfig> implements ICommProvider<TCPServerCommProviderConfig> {
    static final Logger log = LoggerFactory.getLogger(TCPServerCommProvider.class.getSimpleName());
    private final Set<Consumer<ConnectionEventArgs>> eventHandlers = new HashSet<>();

    ServerSocket serverSocket;
    Thread serverThread;

    public TCPServerCommProvider() {
    }

    @Override
    protected void doStart() throws SensorHubException {
        var config = this.config.protocol;

        //Start a thread to listen on the configured port
        serverThread = new Thread(() -> {
            try {
                if (config.enableTLS) {
                    var sslServerSocketFactory = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
                    try (var sslServerSocket = (SSLServerSocket) sslServerSocketFactory.createServerSocket(config.localPort)) {
                        while (true) {
                            var socket = sslServerSocket.accept();
                            broadcast(socket.getInputStream(), socket.getOutputStream());
                        }
                    }
                } else {
                    serverSocket = new ServerSocket(config.localPort);
                    while (true) {
                        var socket = serverSocket.accept();
                        broadcast(socket.getInputStream(), socket.getOutputStream());
                    }
                }
            } catch (Exception e) {
                log.error("TCPServerCommProvider error: " + e.getMessage());
            }
        });
        serverThread.start();
    }

    @Override
    protected void doStop() throws SensorHubException {
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
            if (serverThread != null) {
                serverThread.interrupt();
            }
        } catch (IOException e) {
            log.error("TCPServerCommProvider error: " + e.getMessage());
        }
    }

    @Override
    public void cleanup() throws SensorHubException {
    }

    /**
     * TCPServerCommProvider does not support getInputStream() or getOutputStream().
     * Use onConnection() instead. This method will throw an UnsupportedOperationException.
     */
    @Override
    public InputStream getInputStream() {
        throw new UnsupportedOperationException("TCPServerCommProvider does not support getInputStream()");
    }

    /**
     * TCPServerCommProvider does not support getInputStream() or getOutputStream().
     * Use onConnection() instead. This method will throw an UnsupportedOperationException.
     */
    @Override
    public OutputStream getOutputStream() {
        throw new UnsupportedOperationException("TCPServerCommProvider does not support getOutputStream()");
    }

    /**
     * Register code to be called when a connection is established.
     * <p>
     * Usage: commProvider.onConnection(args -> { ... })
     * <p>
     * Or: commProvider.onConnection(this::handleConnection)
     *
     * @param eventHandler code to be called when a connection is established
     */
    @Override
    public void onConnection(Consumer<ConnectionEventArgs> eventHandler) {
        eventHandlers.add(eventHandler);
    }

    /**
     * Broadcast a connection to all registered event handlers.
     *
     * @param input  input stream
     * @param output output stream
     */
    private void broadcast(InputStream input, OutputStream output) {
        var args = new ConnectionEventArgs(input, output);
        eventHandlers.forEach(eventHandler -> eventHandler.accept(args));
    }
}
