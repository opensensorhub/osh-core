/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2012-2023 Sensia Software LLC. and Botts Innovative Research, Inc. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.comm;

import org.sensorhub.api.comm.ICommProvider;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.impl.module.AbstractModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.DatagramChannel;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;


/**
 * <p>
 * Communication provider for UDP links
 * </p>
 *
 * @author Alex Robin
 * @since Dec 12, 2015
 */
public class UDPCommProvider extends AbstractModule<UDPCommProviderConfig> implements ICommProvider<UDPCommProviderConfig> {
    static final Logger log = LoggerFactory.getLogger(UDPCommProvider.class.getSimpleName());
    private final Set<Consumer<ConnectionEventArgs>> listeners = new HashSet<>();

    DatagramChannel channel;
    InputStream is;
    OutputStream os;


    @Override
    public InputStream getInputStream() throws IOException {
        return is;
    }


    @Override
    public OutputStream getOutputStream() throws IOException {
        return os;
    }


    @Override
    protected void doStart() throws SensorHubException {
        UDPConfig config = this.config.protocol;

        try {
            SocketAddress localAddr = new InetSocketAddress(config.localPort);

            // if remote port is set to AUTO, use port the first UDP packet was sent from
            // to access this info we need to create a plain socket first
            int remotePort = config.remotePort;
            if (remotePort <= 0) {
                try (DatagramSocket socket = new DatagramSocket(config.localPort)) {
                    DatagramPacket udpPacket = new DatagramPacket(new byte[1], 1);
                    socket.receive(udpPacket);
                    remotePort = udpPacket.getPort();
                }
            }

            SocketAddress remoteAddr = new InetSocketAddress(config.remoteHost, remotePort);
            channel = DatagramChannel.open();
            channel.bind(localAddr);
            channel.connect(remoteAddr);
            is = Channels.newInputStream(channel);
            os = Channels.newOutputStream(channel);
            broadcast(is, os);
        } catch (Exception e) {
            throw new SensorHubException("Cannot setup UDP socket between local address " + config.localPort +
                    " and remote host " + config.remoteHost + ":" + config.remotePort, e);
        }
    }


    @Override
    protected void doStop() throws SensorHubException {
        try {
            channel.close();
        } catch (IOException e) {
            log.error("Cannot close datagram channel", e);
        }
    }


    @Override
    public void cleanup() throws SensorHubException {
        // nothing to clean
    }

    @Override
    public void onConnection(Consumer<ConnectionEventArgs> eventHandler) {
        listeners.add(eventHandler);
    }

    private void broadcast(InputStream input, OutputStream output) {
        var args = new ConnectionEventArgs(input, output);
        listeners.forEach(listener -> listener.accept(args));
    }
}
