/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.client.sos;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import net.opengis.sensorml.v20.AbstractProcess;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.sensorhub.api.common.SensorHubException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.cdm.common.DataStreamParser;
import org.vast.ows.OWSException;
import org.vast.ows.OWSExceptionReader;
import org.vast.ows.sos.GetResultRequest;
import org.vast.ows.sos.GetResultTemplateRequest;
import org.vast.ows.sos.GetResultTemplateResponse;
import org.vast.ows.sos.SOSUtils;
import org.vast.ows.swe.DescribeSensorRequest;
import org.vast.sensorML.SMLUtils;
import org.vast.swe.SWEHelper;
import org.vast.xml.DOMHelper;
import org.w3c.dom.Element;


/**
 * <p>
 * Implementation of an SOS client that connects to a remote SOS to download
 * real-time observations and make them available on the local node as data
 * events.<br/>
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since Aug 25, 2015
 */
public class SOSClient
{
    protected static final Logger log = LoggerFactory.getLogger(SOSClient.class);

    SOSUtils sosUtils = new SOSUtils();
    GetResultRequest grRequest;
    WebSocketClient wsClient;
    DataComponent dataDescription;
    DataEncoding dataEncoding;
    boolean useWebsockets;
    volatile boolean started;
    
    
    public interface SOSRecordListener
    {
        public void newRecord(DataBlock data);
    }


    public SOSClient(GetResultRequest request, boolean useWebsockets)
    {
        this.grRequest = request;
        this.useWebsockets = useWebsockets;
    }
    
    
    public void retrieveStreamDescription() throws SensorHubException
    {
        // create output definition
        try
        {
            GetResultTemplateRequest req = new GetResultTemplateRequest();
            req.setGetServer(grRequest.getGetServer());
            req.setVersion(grRequest.getVersion());
            req.setOffering(grRequest.getOffering());
            req.getObservables().addAll(grRequest.getObservables());
            GetResultTemplateResponse resp = sosUtils.<GetResultTemplateResponse> sendRequest(req, false);
            this.dataDescription = resp.getResultStructure();
            this.dataEncoding = resp.getResultEncoding();
            log.debug("Retrieved observation result template from {}", sosUtils.buildURLQuery(req));
        }
        catch (Exception e)
        {
            throw new SensorHubException("Error while getting observation result template", e);
        }
    }
    
    
    public AbstractProcess getSensorDescription(String sensorUID) throws SensorHubException
    {

        try
        {
            DescribeSensorRequest req = new DescribeSensorRequest();
            req.setGetServer(grRequest.getGetServer());
            req.setVersion(grRequest.getVersion());
            req.setProcedureID(sensorUID);            
            
            InputStream is = sosUtils.sendGetRequest(req).getInputStream();
            DOMHelper dom = new DOMHelper(new BufferedInputStream(is), false);
            OWSExceptionReader.checkException(dom, dom.getBaseElement());
            Element smlElt = dom.getElement("description/SensorDescription/data/*");
            AbstractProcess smlDesc = new SMLUtils(SMLUtils.V2_0).readProcess(dom, smlElt);
            log.debug("Retrieved sensor description for sensor {}", sensorUID);
            
            return smlDesc;
        }
        catch (Exception e)
        {
            throw new SensorHubException("Cannot fetch SensorML description for sensor " + sensorUID, e);
        }
    }


    public void startStream(SOSRecordListener listener) throws SensorHubException
    {
        if (started)
            return;
        
        // prepare parser
        DataStreamParser parser = SWEHelper.createDataParser(dataEncoding);
        parser.setDataComponents(dataDescription);
        parser.setRenewDataBlock(true);

        if (useWebsockets)
            connectWithWebsockets(parser, listener);
        else
            connectWithPersistentHttp(parser, listener);
    }


    protected void connectWithPersistentHttp(final DataStreamParser parser, final SOSRecordListener listener) throws SensorHubException
    {
        // connect to data stream
        try
        {
            log.debug("Connecting to {}", sosUtils.buildURLQuery(grRequest));
            HttpURLConnection conn = sosUtils.sendGetRequest(grRequest);
            InputStream is = new BufferedInputStream(conn.getInputStream());
            parser.setInput(is);
        }
        catch (Exception e)
        {
            throw new SensorHubException("Error while connecting to SOS data stream", e);
        }

        // start parsing data
        Thread parseThread = new Thread()
        {
            public void run()
            {
                started = true;
                DataBlock data;

                try
                {
                    while (started && (data = parser.parseNextBlock()) != null)
                        listener.newRecord(data);
                }
                catch (IOException e)
                {
                    if (started)
                        log.error("Error while parsing SOS data stream", e);
                }
                finally
                {
                    try
                    {
                        parser.close();
                    }
                    catch (IOException e)
                    {
                        log.trace("Cannot close SOS connection", e);
                    }
                    
                    started = false;
                }
            }
        };

        parseThread.start();
    }


    protected void connectWithWebsockets(final DataStreamParser parser, final SOSRecordListener listener) throws SensorHubException
    {
        String destUri = null;
        try
        {
            destUri = sosUtils.buildURLQuery(grRequest);
            destUri = destUri.replace("http://", "ws://")
                             .replace("https://", "wss://");
        }
        catch (OWSException e)
        {
            throw new SensorHubException("Error while generating websocket SOS request", e);
        }

        WebSocketListener socket = new WebSocketAdapter() {            
            
            @Override
            public void onWebSocketBinary(byte[] payload, int offset, int len)
            {
                try
                {
                    // skip if no payload
                    if (payload == null || payload.length == 0)
                        return;
                    
                    ByteArrayInputStream is = new ByteArrayInputStream(payload, offset, len);
                    parser.setInput(is);
                    DataBlock data = parser.parseNextBlock();
                    listener.newRecord(data);
                }
                catch (IOException e)
                {
                    log.error("Error while parsing websocket packet", e);
                }
            }

            @Override
            public void onWebSocketClose(int statusCode, String reason)
            {
                
            }

            @Override
            public void onWebSocketError(Throwable cause)
            {
                
            }            
        };
        
        try
        {
            wsClient = new WebSocketClient();
            started = true;
            wsClient.start();
            URI wsUri = new URI(destUri);
            wsClient.connect(socket, wsUri);
            log.debug("Connecting to {}", destUri);
        }
        catch (Exception e)
        {
            throw new SensorHubException("Error while connecting to SOS data stream", e);
        }
    }


    public void stopStream()
    {
        started = false;
        
        if (wsClient != null)
        {
            try
            {
                wsClient.stop();
            }
            catch (Exception e)
            {
                log.trace("Cannot close websocket client", e);
            }
        }
    }


    public DataComponent getRecordDescription()
    {
        return dataDescription;
    }


    public DataEncoding getRecommendedEncoding()
    {
        return dataEncoding;
    }
}
