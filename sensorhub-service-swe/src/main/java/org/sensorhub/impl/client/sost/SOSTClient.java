/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.client.sost;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.opengis.gml.v32.AbstractFeature;
import net.opengis.swe.v20.DataBlock;
import org.sensorhub.api.client.ClientException;
import org.sensorhub.api.client.IClientModule;
import org.sensorhub.api.common.Event;
import org.sensorhub.api.common.IEventListener;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.api.data.IDataProducerModule;
import org.sensorhub.api.data.IStreamingDataInterface;
import org.sensorhub.api.module.ModuleEvent;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.sensorhub.api.sensor.ISensorDataInterface;
import org.sensorhub.api.sensor.SensorDataEvent;
import org.sensorhub.api.sensor.SensorEvent;
import org.sensorhub.impl.SensorHub;
import org.sensorhub.impl.comm.RobustIPConnection;
import org.sensorhub.impl.module.AbstractModule;
import org.sensorhub.impl.module.RobustConnection;
import org.sensorhub.impl.security.ClientAuth;
import org.sensorhub.utils.MsgUtils;
import org.vast.cdm.common.DataStreamWriter;
import org.vast.ogc.om.IObservation;
import org.vast.ogc.om.ObservationImpl;
import org.vast.ows.GetCapabilitiesRequest;
import org.vast.ows.OWSException;
import org.vast.ows.sos.InsertResultRequest;
import org.vast.ows.sos.InsertResultTemplateRequest;
import org.vast.ows.sos.InsertResultTemplateResponse;
import org.vast.ows.sos.InsertSensorRequest;
import org.vast.ows.sos.SOSInsertionCapabilities;
import org.vast.ows.sos.SOSServiceCapabilities;
import org.vast.ows.sos.SOSUtils;
import org.vast.ows.swe.InsertSensorResponse;
import org.vast.ows.swe.SWESUtils;
import org.vast.ows.swe.UpdateSensorRequest;
import org.vast.swe.Base64Encoder;
import org.vast.swe.SWEData;


/**
 * <p>
 * Implementation of an SOS-T client that listens to sensor events and 
 * forwards them to an SOS via InsertSensor/UpdateSensor, InsertResultTemplate
 * and InsertResult requests.<br/>
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since Feb 6, 2015
 */
public class SOSTClient extends AbstractModule<SOSTClientConfig> implements IClientModule<SOSTClientConfig>, IEventListener
{
    RobustConnection connection;
    IDataProducerModule<?> dataSource;
    SOSUtils sosUtils = new SOSUtils();  
    String sosEndpointUrl;
    String offering;
    Map<IStreamingDataInterface, StreamInfo> dataStreams;
    
    
    public class StreamInfo
    {
        String templateID;
        public long lastEventTime = Long.MIN_VALUE;
        public int measPeriodMs = 1000;
        public int errorCount = 0;
        private int minRecordsPerRequest = 10;
        private SWEData resultData = new SWEData();
        private ThreadPoolExecutor threadPool;
        private HttpURLConnection connection;
        private DataStreamWriter persistentWriter;
        private volatile boolean connecting = false;
        private volatile boolean stopping = false;
    }
    
    
    public SOSTClient()
    {
        this.dataStreams = new LinkedHashMap<>();
    }
    
    
    private void setAuth()
    {
        ClientAuth.getInstance().setUser(config.sos.user);
        if (config.sos.password != null)
            ClientAuth.getInstance().setPassword(config.sos.password.toCharArray());
    }


    protected String getSosEndpointUrl()
    {
        setAuth();
        return sosEndpointUrl;
    }
    
    
    @Override
    public void setConfiguration(SOSTClientConfig config)
    {
        super.setConfiguration(config);
         
        // compute full host URL
        String scheme = "http";
        if (config.sos.enableTLS)
            scheme = "https";
        sosEndpointUrl = scheme + "://" + config.sos.remoteHost + ":" + config.sos.remotePort;
        if (config.sos.resourcePath != null)
        {
            if (config.sos.resourcePath.charAt(0) != '/')
                sosEndpointUrl += '/';
            sosEndpointUrl += config.sos.resourcePath;
        }
    };
    
    
    @Override
    public void init() throws SensorHubException
    {
        // get handle to sensor data source
        try
        {
            dataSource = (IDataProducerModule<?>)SensorHub.getInstance().getModuleRegistry().getModuleById(config.dataSourceID);
        }
        catch (Exception e)
        {
            throw new ClientException("Cannot find data source with local ID " + config.dataSourceID, e);
        }
        
        // create connection handler
        this.connection = new RobustIPConnection(this, config.connection, "SOS server")
        {
            public boolean tryConnect() throws IOException
            {
                // first check if we can reach remote host on specified port
                if (!tryConnectTCP(config.sos.remoteHost, config.sos.remotePort))
                    return false;
                
                // check connection to SOS by fetching capabilities
                SOSServiceCapabilities caps = null;
                try
                {
                    GetCapabilitiesRequest request = new GetCapabilitiesRequest();
                    request.setConnectTimeOut(connectConfig.connectTimeout);
                    request.setService(SOSUtils.SOS);
                    request.setGetServer(getSosEndpointUrl());
                    caps = sosUtils.sendRequest(request, false);
                }
                catch (OWSException e)
                {
                    reportError("Cannot fetch SOS capabilities", e, true);
                    return false;
                }
                
                // check insert operations are supported
                if (!caps.getPostServers().isEmpty())
                {
                    String[] neededOps = new String[] {"InsertSensor", "InsertResultTemplate", "InsertResult"};
                    for (String opName: neededOps)
                    {
                        if (!caps.getPostServers().containsKey(opName))
                            throw new IOException(opName + " operation not supported by this SOS endpoint");
                    }
                }
                
                // check SML2 is supported
                SOSInsertionCapabilities insertCaps = caps.getInsertionCapabilities();
                if (insertCaps != null)
                {
                    if (!insertCaps.getProcedureFormats().contains(SWESUtils.DEFAULT_PROCEDURE_FORMAT))
                        throw new IOException("SensorML v2.0 format not supported by this SOS endpoint");
                    
                    if (!insertCaps.getObservationTypes().contains(IObservation.OBS_TYPE_RECORD))
                        throw new IOException("DataRecord observation type not supported by this SOS endpoint");
                }
                
                return true;
            }
        };
    }
    
    
    @Override
    public void requestStart() throws SensorHubException
    {
        if (canStart())
        {
            try
            {
                // register to sensor events            
                reportStatus("Waiting for data source " + MsgUtils.moduleString(dataSource));
                dataSource.registerListener(this);                
                
                // we'll actually start when we receive sensor STARTED event
            }
            catch (Exception e)
            {
                reportError(CANNOT_START_MSG, e);
                requestStop();
            }
        }
    }
    
    
    @Override
    public void start() throws SensorHubException
    {
        connection.updateConfig(config.connection);
        connection.waitForConnection();
        reportStatus("Connected to " + getSosEndpointUrl());
        
        try
        {   
            // register sensor
            registerSensor(dataSource);
            getLogger().info("Data source {} registered with SOS", MsgUtils.moduleString(dataSource));
        }
        catch (Exception e)
        {
            throw new ClientException("Error while registering sensor with remote SOS", e);
        }
        
        
        // register all stream templates
        for (IStreamingDataInterface o: dataSource.getAllOutputs().values())
        {
            // skip excluded outputs
            if (config.excludedOutputs != null && config.excludedOutputs.contains(o.getName()))
                continue;
            
            try
            {
                registerDataStream(o);
            }
            catch (Exception e)
            {
                throw new ClientException("Error while registering " + o.getName() + " data stream with remote SOS", e);
            }
        }
        
        getLogger().info("Result templates registered with SOS");            
        setState(ModuleState.STARTED);        
    }
    
    
    @Override
    public void stop() throws SensorHubException
    {
        // cancel reconnection loop
        if (connection != null)
            connection.cancel();
        
        // unregister from sensor
        if (dataSource != null)
            dataSource.unregisterListener(this);
        
        // stop all streams
        for (Entry<IStreamingDataInterface, StreamInfo> entry: dataStreams.entrySet())
            stopStream(entry.getKey(), entry.getValue());
    }
    
    
    /*
     * Stop listening and pushing data for the given stream
     */
    protected void stopStream(IStreamingDataInterface output, StreamInfo streamInfo)
    {
        // unregister listeners
        output.unregisterListener(this);
        
        // close open HTTP streams
        try
        {
            streamInfo.stopping = true;
            if (streamInfo.persistentWriter != null)
                streamInfo.persistentWriter.close();
            if (streamInfo.connection != null)
                streamInfo.connection.disconnect();
        }
        catch (IOException e)
        {
            getLogger().trace("Cannot close persistent connection", e);
        }
        
        // stop thread pool
        try
        {
            if (streamInfo.threadPool != null && !streamInfo.threadPool.isShutdown())
            {
                streamInfo.threadPool.shutdownNow();
                streamInfo.threadPool.awaitTermination(3, TimeUnit.SECONDS);
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }
    
    
    /*
     * Registers sensor with remote SOS
     */
    protected void registerSensor(IDataProducerModule<?> sensor) throws OWSException
    {
        // build insert sensor request
        InsertSensorRequest req = new InsertSensorRequest();
        req.setConnectTimeOut(config.connection.connectTimeout);
        req.setPostServer(getSosEndpointUrl());
        req.setVersion("2.0");
        req.setProcedureDescription(sensor.getCurrentDescription());
        req.setProcedureDescriptionFormat(SWESUtils.DEFAULT_PROCEDURE_FORMAT);
        req.getObservationTypes().add(IObservation.OBS_TYPE_RECORD);
        req.getFoiTypes().add("gml:Feature");
        
        // send request and get assigned ID
        InsertSensorResponse resp = sosUtils.sendRequest(req, false);
        this.offering = resp.getAssignedOffering();
    }
    
    
    /*
     * Update sensor description at remote SOS
     */
    protected void updateSensor(IDataProducerModule<?> sensor) throws OWSException
    {
        // build update sensor request
        UpdateSensorRequest req = new UpdateSensorRequest(SOSUtils.SOS);
        req.setConnectTimeOut(config.connection.connectTimeout);
        req.setPostServer(getSosEndpointUrl());
        req.setVersion("2.0");
        req.setProcedureId(sensor.getUniqueIdentifier());
        req.setProcedureDescription(sensor.getCurrentDescription());
        req.setProcedureDescriptionFormat(SWESUtils.DEFAULT_PROCEDURE_FORMAT);
        
        // send request
        sosUtils.sendRequest(req, false);
    }
    
    
    /*
     * Prepare to send the given sensor output data to the remote SOS server
     */
    protected void registerDataStream(IStreamingDataInterface sensorOutput) throws OWSException
    {
        // generate insert result template
        InsertResultTemplateRequest req = new InsertResultTemplateRequest();
        req.setConnectTimeOut(config.connection.connectTimeout);
        req.setPostServer(getSosEndpointUrl());
        req.setVersion("2.0");
        req.setOffering(offering);
        req.setResultStructure(sensorOutput.getRecordDescription());
        req.setResultEncoding(sensorOutput.getRecommendedEncoding());
        ObservationImpl obsTemplate = new ObservationImpl();
        
        // set FOI if known
        AbstractFeature foi = sensorOutput.getParentModule().getCurrentFeatureOfInterest();
        if (foi != null)
            obsTemplate.setFeatureOfInterest(foi);
        req.setObservationTemplate(obsTemplate);
        
        // send request
        InsertResultTemplateResponse resp = sosUtils.sendRequest(req, false);
        
        // add stream info to map
        StreamInfo streamInfo = new StreamInfo();
        streamInfo.templateID = resp.getAcceptedTemplateId();
        streamInfo.resultData.setElementType(sensorOutput.getRecordDescription());
        streamInfo.resultData.setEncoding(sensorOutput.getRecommendedEncoding());
        streamInfo.measPeriodMs = (int)(sensorOutput.getAverageSamplingPeriod()*1000);
        streamInfo.minRecordsPerRequest = 1;//(int)(1.0 / sensorOutput.getAverageSamplingPeriod());
        dataStreams.put(sensorOutput, streamInfo);
        
        // start thread pool
        BlockingQueue<Runnable> workQueue = new ArrayBlockingQueue<>(config.connection.maxQueueSize);
        streamInfo.threadPool = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, workQueue);
        
        // send last record
        if (sensorOutput.getLatestRecord() != null)
            sendAsNewRequest(new DataEvent(
                    sensorOutput.getLatestRecordTime(), 
                    sensorOutput, sensorOutput.getLatestRecord()),
                    streamInfo);
        
        // register to data events
        sensorOutput.registerListener(this);
    }
    
    
    @Override
    public void handleEvent(final Event<?> e)
    {
        // sensor module lifecycle event
        if (e instanceof ModuleEvent)
        {
            ModuleState newState = ((ModuleEvent) e).getNewState();
            
            // start when sensor is started
            if (newState == ModuleState.STARTED)
            {
                try
                {
                    start();
                }
                catch (SensorHubException ex)
                {
                    reportError("Could not start SOS-T Client", ex);
                    setState(ModuleState.STOPPED);
                }
            }
        }
                
        // sensor description updated
        else if (e instanceof SensorEvent)
        {
            if (((SensorEvent) e).getType() == SensorEvent.Type.SENSOR_CHANGED)
            {
                try
                {
                    updateSensor(dataSource);
                }
                catch (OWSException ex)
                {
                    getLogger().error("Error when sending updates sensor description to SOS-T", ex);
                }
            }
        }
        
        // sensor data received
        else if (e instanceof DataEvent)
        {
            // retrieve stream info
            StreamInfo streamInfo = dataStreams.get(e.getSource());
            if (streamInfo == null)
                return;
            
            // we stop here if we had too many errors
            if (streamInfo.errorCount >= config.connection.maxConnectErrors)
            {
                String outputName = ((SensorDataEvent)e).getSource().getName();
                reportError("Too many errors sending '" + outputName + "' data to SOS-T. Stopping Stream.", null);
                stopStream((ISensorDataInterface)e.getSource(), streamInfo);
                checkDisconnected();                
                return;
            }
            
            // skip if we cannot handle more requests
            if (streamInfo.threadPool.getQueue().remainingCapacity() == 0)
            {
                String outputName = ((SensorDataEvent)e).getSource().getName();
                getLogger().warn("Too many '{}' records to send to SOS-T. Bandwidth cannot keep up.", outputName);
                getLogger().info("Skipping records by purging record queue");
                streamInfo.threadPool.getQueue().clear();
                return;
            }
            
            // record last event time
            streamInfo.lastEventTime = e.getTimeStamp();
            
            // send record using one of 2 methods
            if (config.connection.usePersistentConnection)
                sendInPersistentRequest((DataEvent)e, streamInfo);
            else
                sendAsNewRequest((DataEvent)e, streamInfo);
        }
    }
    
    
    private void checkDisconnected()
    {
        // if all streams have been stopped, initiate reconnection
        boolean allStopped = true;
        for (StreamInfo streamInfo: dataStreams.values())
        {
            if (!streamInfo.stopping)
            {
                allStopped = false;
                break;
            }
        }
        
        if (allStopped)
        {
            reportStatus("All streams stopped on error. Trying to reconnect...");
            connection.reconnect();
        }
    }
    
    
    /*
     * Sends each new record using an XML InsertResult POST request
     */
    private void sendAsNewRequest(final DataEvent e, final StreamInfo streamInfo)
    {
        // append records to buffer
        for (DataBlock record: e.getRecords())
            streamInfo.resultData.pushNextDataBlock(record);
        
        // send request if min record count is reached
        if (streamInfo.resultData.getNumElements() >= streamInfo.minRecordsPerRequest)
        {
            final InsertResultRequest req = new InsertResultRequest();
            req.setPostServer(getSosEndpointUrl());
            req.setVersion("2.0");
            req.setTemplateId(streamInfo.templateID);
            req.setResultData(streamInfo.resultData);
            
            // create new container for future data
            streamInfo.resultData = streamInfo.resultData.copy();
            
            // create send request task
            Runnable sendTask = new Runnable() {
                @Override
                public void run()
                {
                    try
                    {
                        if (getLogger().isTraceEnabled())
                        {
                            String outputName = e.getSource().getName();
                            int numRecords = req.getResultData().getComponentCount();
                            getLogger().trace("Sending " + numRecords + " '" + outputName + "' record(s) to SOS-T");
                            getLogger().trace("Queue size is " + streamInfo.threadPool.getQueue().size());
                        }
                        
                        sosUtils.sendRequest(req, false);
                    }
                    catch (Exception ex)
                    {
                        String outputName = e.getSource().getName();
                        reportError("Error when sending '" + outputName + "' data to SOS-T", ex, true);
                        streamInfo.errorCount++;
                    }
                }           
            };
            
            // run task in async thread pool
            streamInfo.threadPool.execute(sendTask);
        }
    }
    
    
    /*
     * Sends all records in the same persistent HTTP connection.
     * The connection is created when the first record is received
     */
    private void sendInPersistentRequest(final DataEvent e, final StreamInfo streamInfo)
    {
        // skip records while we are connecting to remote SOS
        if (streamInfo.connecting)
            return;
        
        // create send request task
        Runnable sendTask = new Runnable() {
            @Override
            public void run()
            {
                try
                {
                    // connect if not already connected
                    if (streamInfo.persistentWriter == null)
                    {                        
                        streamInfo.connecting = true;
                        if (getLogger().isDebugEnabled())
                            getLogger().debug("Initiating streaming request");
                        
                        final InsertResultRequest req = new InsertResultRequest();
                        req.setPostServer(getSosEndpointUrl());
                        req.setVersion("2.0");
                        req.setTemplateId(streamInfo.templateID);
                        
                        // connect to server                        
                        HttpURLConnection conn = sosUtils.sendPostRequestWithQuery(req);                        
                        conn.setRequestProperty("Content-type", "text/plain");
                        conn.setChunkedStreamingMode(32);
                        if (config.sos.user != null && config.sos.password != null)
                        {
                            // need to configure auth manually when using streaming
                            byte[] data = (config.sos.user+":"+config.sos.password).getBytes("UTF-8");
                            String encoded = new String(Base64Encoder.encode(data));
                            conn.setRequestProperty("Authorization", "Basic "+encoded);
                        }
                        conn.connect();
                        streamInfo.connection = conn;
                        
                        // prepare writer
                        streamInfo.persistentWriter = streamInfo.resultData.getDataWriter();
                        streamInfo.persistentWriter.setOutput(new BufferedOutputStream(conn.getOutputStream()));
                        streamInfo.connecting = false;
                    }
                    
                    if (getLogger().isTraceEnabled())
                    {
                        String outputName = e.getSource().getName();
                        int numRecords = e.getRecords().length;
                        getLogger().trace("Sending " + numRecords + " '" + outputName + "' record(s) to SOS-T");
                        getLogger().trace("Queue size is " + streamInfo.threadPool.getQueue().size());
                    }
                    
                    // write records to output stream
                    for (DataBlock record: e.getRecords())
                        streamInfo.persistentWriter.write(record);
                    streamInfo.persistentWriter.flush();
                }
                catch (Exception ex)
                {
                    // ignore exception if stream was purposely stopped
                    if (streamInfo.stopping)
                        return;
                    
                    String outputName = e.getSource().getName();
                    reportError("Error when sending '" + outputName + "' data to SOS-T", ex, true);
                    streamInfo.errorCount++;
                    
                    try
                    {
                        if (streamInfo.persistentWriter != null)
                            streamInfo.persistentWriter.close();
                    }
                    catch (IOException e1)
                    {
                        getLogger().trace("Cannot close persistent connection", e1);
                    }
                    
                    // clean writer so we reconnect
                    streamInfo.persistentWriter = null;
                    streamInfo.connecting = false;
                }
            }           
        };
        
        // run task in async thread pool
        streamInfo.threadPool.execute(sendTask);
    }


    @Override
    public boolean isConnected()
    {
        return connection.isConnected();
    }
    
    
    public Map<IStreamingDataInterface, StreamInfo> getDataStreams()
    {
        return dataStreams;
    }


    @Override
    public void cleanup() throws SensorHubException
    {
        // nothing to clean
    }
}
