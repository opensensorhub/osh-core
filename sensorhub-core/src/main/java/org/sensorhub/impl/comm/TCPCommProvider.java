/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.comm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.sensorhub.api.comm.ICommProvider;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.impl.module.AbstractModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * <p>
 * Communication provider for TCP/IP links
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since July 2, 2015
 */
public class TCPCommProvider extends AbstractModule<TCPCommProviderConfig> implements ICommProvider<TCPCommProviderConfig>
{
    static final Logger log = LoggerFactory.getLogger(TCPCommProvider.class.getSimpleName());
    
    Socket socket;
    InputStream is;
    OutputStream os;
    
    
    public TCPCommProvider() 
    {
    }
    
    
    @Override
    public InputStream getInputStream() throws IOException
    {
        return is;
    }


    @Override
    public OutputStream getOutputStream() throws IOException
    {
        return os;
    }


    @Override
    public void start() throws SensorHubException
    {        
        TCPConfig config = this.config.protocol;
        
        try
        {
            InetAddress addr = InetAddress.getByName(config.remoteHost);
            
            if (config.enableTLS)
            {
                SSLSocketFactory factory = (SSLSocketFactory)SSLSocketFactory.getDefault();
                socket = factory.createSocket(addr, config.remotePort);
                ((SSLSocket)socket).startHandshake();
                is = socket.getInputStream();
                os = socket.getOutputStream();
            }
            else
            {
                SocketAddress endpoint = new InetSocketAddress(addr, config.remotePort);
                socket = new Socket();
                socket.connect(endpoint, 1000);
                is = socket.getInputStream();
                os = socket.getOutputStream();
            }
        }
        catch (IOException e)
        {
            throw new SensorHubException("Cannot connect to remote host "
                                         + config.remoteHost + ":" + config.remotePort + " via TCP", e);
        }
    }


    @Override
    public void stop() throws SensorHubException
    {
        try
        {
            socket.close();
        }
        catch (IOException e)
        {
            log.trace("Cannot close socket", e);
        }        
    }


    @Override
    public void cleanup() throws SensorHubException
    {
    }
}
