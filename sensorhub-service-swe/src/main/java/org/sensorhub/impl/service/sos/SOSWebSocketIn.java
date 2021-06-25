/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.sos;

import java.io.ByteArrayInputStream;
import java.util.function.Consumer;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.sensorhub.impl.service.WebSocketUtils;
import org.slf4j.Logger;
import org.vast.cdm.common.DataStreamParser;
import net.opengis.swe.v20.DataBlock;


/**
 * <p>
 * Input only websocket for receiving live record streams (via InsertResult)
 * </p>
 *
 * @author Alex Robin
 * @since Dec 13, 2016
 */
public class SOSWebSocketIn implements WebSocketListener
{
    Logger log;
    Session session;
    DataStreamParser parser;
    Consumer<DataBlock> consumer;
    
    
    public SOSWebSocketIn(DataStreamParser parser, Consumer<DataBlock> consumer, Logger log)
    {
        this.parser = parser;
        this.consumer = consumer;
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
            consumer.accept(data);
        }
        catch (Exception e)
        {
            log.error("Error while parsing websocket packet", e);
            if (session != null)
                session.close(StatusCode.BAD_DATA, e.getMessage());
        }
    }


    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        WebSocketUtils.logClose(session, statusCode, reason, log);
        session = null;
    }
    
    
    @Override
    public void onWebSocketError(Throwable e)
    {
        log.error(WebSocketUtils.PROTOCOL_ERROR_MSG, e);
    }


    @Override
    public void onWebSocketText(String msg)
    {
        WebSocketUtils.closeSession(session, StatusCode.BAD_DATA, WebSocketUtils.INPUT_NOT_SUPPORTED, log);
    }
    
    
    public void close()
    {
        WebSocketUtils.closeSession(session, StatusCode.NORMAL, "End of streaming session", log);
    }
}
