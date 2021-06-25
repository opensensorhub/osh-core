/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2016 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service;

import java.security.Principal;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;


public class WebSocketUtils
{
    public static final String CONNECT_MSG = "Websocket session {} opened (from ip={}, user={})";
    public static final String CLOSE_BY_CLIENT_MSG = "Websocket session {} closed by client (status={}, reason={})";
    public static final String CLOSE_BY_SERVER_MSG = "Websocket session {} closed by server (status={}, reason={})";
    public static final String PROTOCOL_ERROR_MSG = "Websocket protocol error";
    public static final String SEND_ERROR_MSG = "Error while sending websocket stream";
    public static final String REQUEST_ERROR_MSG = "Invalid websocket request";
    public static final String INTERNAL_ERROR_MSG = "Internal error while processing websocket request";
    public static final String PARSE_ERROR_MSG = "Error while parsing websocket packet";
    public static final String INPUT_NOT_SUPPORTED = "Incoming data is not supported";
    public static final String TEXT_NOT_SUPPORTED = "Incoming text data is not supported";
    public static final String INIT_ERROR = "Error while initializing websocket connection";
    
    
    private WebSocketUtils()
    {        
    }
    
    
    public static void closeSession(Session session, int statusCode, String reason, Logger log)
    {
        if (session != null && session.isOpen())
        {
            if (log != null)
                log.debug(CLOSE_BY_SERVER_MSG, getSessionID(session), statusCode, reason);
            session.close(statusCode, reason);
        }
    }
    
    
    public static void logOpen(Session session, Logger log)
    {
        if (log.isDebugEnabled())
        {
            String remoteIp = session.getRemoteAddress().getAddress().getHostAddress();
            Principal user = session.getUpgradeRequest().getUserPrincipal();
            String userID = user != null ? user.getName() : "anonymous";
            log.debug(CONNECT_MSG, getSessionID(session), remoteIp, userID);
        }
    }
    
    
    public static void logClose(Session session, int statusCode, String reason, Logger log)
    {
        log.debug(CLOSE_BY_CLIENT_MSG, getSessionID(session), statusCode, reason);
    }
    
    
    private static String getSessionID(Session session)
    {
        int objId = System.identityHashCode(session);
        return Integer.toString(objId);
    }
}
