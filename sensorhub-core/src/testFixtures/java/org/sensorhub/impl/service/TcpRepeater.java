/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;


public class TcpRepeater
{
    List<Socket> clientSockets = new ArrayList<Socket>();
    boolean started;
    ServerSocket server;
    
    Object sync = new Object();
    volatile boolean sending = false;
    byte[] msgBytes;
    
    
    TcpRepeater(int port) throws IOException
    {
        // create connection receive thread
        server = new ServerSocket(port);
        started = true;
        
        Thread listenThread = new Thread() {
            @Override
            public void run() 
            {
                while (started)
                {
                    try
                    {
                        Socket s = server.accept();
                        clientSockets.add(s);
                    }
                    catch (IOException e)
                    {
                        System.err.println("Cannot accept client connection");
                        break;
                    }
                }
            }
        };
        listenThread.start();
        
        Thread sendThread = new Thread() {
            @Override
            public void run() 
            {
                while (started)
                {
                    synchronized(sync)
                    {
                        try { sync.wait(); }
                        catch (InterruptedException e) { return; }
                        sending = true;
                    }
                    
                    Iterator<Socket> it = clientSockets.iterator();
                    while (it.hasNext())
                    {
                        try
                        {
                            Socket s = it.next();
                            if (s.isClosed())
                            {
                                it.remove();
                            }
                            else if (s.isConnected())
                            {
                                s.getOutputStream().write(msgBytes);
                                s.getOutputStream().flush();
                            }
                        }
                        catch (IOException e)
                        {
                        }
                    }
                    
                    sending = false;
                }
            }
        };
        sendThread.start();
    }
    
    
    public void sendMessage(byte[] msgBytes) throws IOException
    {
        synchronized(sync)
        {
            if (!sending)
            {
                this.msgBytes = msgBytes.clone();
                sync.notify();
            }
        }
    }
    
    
    public void stop()
    {
        try
        {
            started = false;
            server.close();
            for (Socket s: clientSockets)
                s.close();
            clientSockets.clear();
        }
        catch (IOException e)
        {            
        }
    }
}
