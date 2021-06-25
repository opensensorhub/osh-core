/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2016-2017 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.sps;

import java.io.ByteArrayInputStream;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.sensorhub.impl.service.WebSocketUtils;
import org.slf4j.Logger;
import org.vast.cdm.common.DataStreamParser;
import net.opengis.swe.v20.DataBlock;


/**
 * <p>
 * Input only websocket for receiving live command streams (for direct tasking)
 * </p>
 *
 * @author Alex Robin
 * @since Jan 24, 2017
 */
public class SPSWebSocketIn implements WebSocketListener
{
    SPSServlet servlet;
    ISPSConnector connector;
    DataStreamParser parser;
    String taskID;
    Logger log;
    Session session;
    
    
    public SPSWebSocketIn(SPSServlet servlet, String taskID, ISPSConnector conn, DataStreamParser parser, Logger log)
    {
        this.servlet = servlet;
        this.taskID = taskID;
        this.connector = conn;
        this.parser = parser;
        this.log = log;
    }
    
    
    @Override
    public void onWebSocketConnect(Session session)
    {
        this.session = session;
    }
    
    
    @Override
    public void onWebSocketBinary(byte payload[], int offset, int len)
    {
        try
        {
            // skip if no payload
            if (payload == null || payload.length == 0)
                return;
            
            ByteArrayInputStream is = new ByteArrayInputStream(payload, offset, len);
            parser.setInput(is);
            DataBlock data = parser.parseNextBlock();
            connector.sendCommand(data, null);
        }
        catch (Exception e)
        {
            log.error(WebSocketUtils.PARSE_ERROR_MSG, e);
            if (session != null)
                WebSocketUtils.closeSession(session, StatusCode.BAD_DATA, e.getMessage(), log);
        }
    }


    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        WebSocketUtils.logClose(session, statusCode, reason, log);
        servlet.receiveSessionsMap.remove(taskID);
        session = null;
    }
    
    
    @Override
    public void onWebSocketError(Throwable e)
    {
        log.error(WebSocketUtils.PROTOCOL_ERROR_MSG, e);
        close();
    }


    @Override
    public void onWebSocketText(String msg)
    {
        WebSocketUtils.closeSession(session, StatusCode.BAD_DATA, WebSocketUtils.TEXT_NOT_SUPPORTED, log);
    }
    
    
    public void close()
    {
        WebSocketUtils.closeSession(session, StatusCode.NORMAL, "End of tasking session", log);
        servlet.receiveSessionsMap.remove(taskID);
        session = null;
    }
}
