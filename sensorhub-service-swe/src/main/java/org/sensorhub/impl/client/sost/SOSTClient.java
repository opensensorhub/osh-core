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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import org.sensorhub.api.client.ClientException;
import org.sensorhub.api.client.IClientModule;
import org.sensorhub.api.event.EventUtils;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.data.DataStreamAddedEvent;
import org.sensorhub.api.data.DataStreamDisabledEvent;
import org.sensorhub.api.data.DataStreamEnabledEvent;
import org.sensorhub.api.data.DataStreamRemovedEvent;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.data.IObsData;
import org.sensorhub.api.data.ObsEvent;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.obs.DataStreamFilter;
import org.sensorhub.api.datastore.obs.ObsFilter;
import org.sensorhub.api.datastore.system.SystemFilter;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.sensorhub.api.system.ISystemWithDesc;
import org.sensorhub.api.system.SystemAddedEvent;
import org.sensorhub.api.system.SystemChangedEvent;
import org.sensorhub.api.system.SystemEvent;
import org.sensorhub.api.system.SystemRemovedEvent;
import org.sensorhub.api.system.SystemDisabledEvent;
import org.sensorhub.api.system.SystemEnabledEvent;
import org.sensorhub.impl.comm.RobustIPConnection;
import org.sensorhub.impl.module.AbstractModule;
import org.sensorhub.impl.module.RobustConnection;
import org.sensorhub.impl.security.ClientAuth;
import org.sensorhub.utils.SerialExecutor;
import org.sensorhub.utils.CallbackException;
import org.sensorhub.utils.Lambdas;
import org.vast.cdm.common.DataStreamWriter;
import org.vast.ogc.gml.IFeature;
import org.vast.ogc.om.IObservation;
import org.vast.ogc.om.ObservationImpl;
import org.vast.ows.GetCapabilitiesRequest;
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
 * Implementation of an SOS-T client that listens to system events and 
 * forwards them to a remote SOS via InsertSensor/UpdateSensor,
 * InsertResultTemplate and InsertResult requests.<br/>
 * </p>
 *
 * @author Alex Robin
 * @since Feb 6, 2015
 */
public class SOSTClient extends AbstractModule<SOSTClientConfig> implements IClientModule<SOSTClientConfig>
{
    RobustConnection connection;
    IObsSystemDatabase dataBaseView;
    SOSUtils sosUtils = new SOSUtils();  
    String sosEndpointUrl;
    Subscription registrySub;
    Map<String, SystemRegInfo> registeredSystems;
    NavigableMap<String, StreamInfo> dataStreams;
    ExecutorService threadPool = ForkJoinPool.commonPool();
    
    
    public class SystemRegInfo
    {
        //private long internalID;
        private String offeringId;
        private Subscription sub;
        private Instant startValidTime;
    }
    
    
    public class StreamInfo
    {
        public long lastEventTime = Long.MIN_VALUE;
        public int measPeriodMs = 1000;
        public int errorCount = 0;
        private int minRecordsPerRequest = 10;
        private BigId internalID;
        private String sysUID;
        private String outputName;
        private String topicId;
        private Subscription sub;
        private String templateId;
        private SWEData resultData = new SWEData();
        private SerialExecutor executor = new SerialExecutor(threadPool, config.connection.maxQueueSize);
        private HttpURLConnection connection;
        private DataStreamWriter persistentWriter;
        private volatile boolean connecting = false;
        private volatile boolean stopping = false;
    }
    
    
    public SOSTClient()
    {
        this.startAsync = true;
        this.registeredSystems = new ConcurrentHashMap<>();
        this.dataStreams = new ConcurrentSkipListMap<>();
    }
    
    
    private void setAuth()
    {
        ClientAuth.getInstance().setUser(config.sos.user);
        if (config.sos.password != null)
            ClientAuth.getInstance().setPassword(config.sos.password.toCharArray());
    }


    protected String getSosEndpointUrl()
    {
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
    
    
    protected void checkConfiguration() throws SensorHubException
    {
        // TODO check config
    }
    
    
    @Override
    protected void doInit() throws SensorHubException
    {
        // check configuration
        checkConfiguration();
        
        // create database view using filter provided in configuration
        this.dataBaseView = config.dataSourceSelector.getFilteredView(getParentHub());
        
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
                    setAuth();
                    caps = sosUtils.sendRequest(request, false);
                }
                catch (Exception e)
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
    protected void doStart() throws SensorHubException
    {
        connection.updateConfig(config.connection);
        
        connection.waitForConnectionAsync()
            .thenRun(() -> {
                reportStatus("Connected to SOS endpoint " + getSosEndpointUrl());
                
                // subscribe to get notified when new systems are added
                subscribeToRegistryEvents();
                
                // register all selected systems and their datastreams
                dataBaseView.getSystemDescStore().selectEntries(new SystemFilter.Builder().build())
                    //.parallel()
                    .forEach(entry -> {
                        var id = entry.getKey().getInternalID();
                        var sys = entry.getValue();
                        addProcedure(id, sys);
                    });
            })
            .thenRun(() -> {                
                setState(ModuleState.STARTED);
            })
            .exceptionally(err -> {
                if (err != null)
                    reportError(null, err.getCause());
                try { stop(); }
                catch (SensorHubException e) {}
                return null;
            });
    }
    
    
    @Override
    protected void doStop()
    {
        // cancel reconnection loop
        if (connection != null)
            connection.cancel();
        
        // stop all streams
        for (var streamInfo: dataStreams.values())
            stopStream(streamInfo);
        
        // unsubscribe from system events
        if (registrySub != null)
            registrySub.cancel();
        for (var sysInfo: registeredSystems.values())
        {
            if (sysInfo.sub != null)
                sysInfo.sub.cancel();
        }
        
        // shutdown thread pool
        // will not do anything if common pool was used
        try
        {
            if (threadPool != null && !threadPool.isShutdown())
            {
                threadPool.shutdownNow();
                threadPool.awaitTermination(3, TimeUnit.SECONDS);
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }
    
    
    /*
     * Subscribe to this hub events to get notified when
     * systems are added or removed
     */
    protected void subscribeToRegistryEvents()
    {
        getParentHub().getEventBus().newSubscription(SystemEvent.class)
            .withTopicID(EventUtils.getSystemRegistryTopicID())
            .withEventType(SystemEvent.class)
            .subscribe(e -> {
                handleEvent(e);
            })
            .thenAccept(sub -> {
                registrySub = sub;
                sub.request(Long.MAX_VALUE);
            });
    }
    
    
    /*
     * Register system, subscribe to its events and start all selected datastreams
     */
    protected void addProcedure(BigId sysID, ISystemWithDesc sys)
    {
        var sysUID = sys.getUniqueIdentifier();

        // register or update system atomically
        // this makes sure we skip if already registered
        registeredSystems.compute(sysUID, (uid, sysInfo) -> {
            try {
                // if sensor hasn't been registered yet
                if (sysInfo == null)
                {
                    // register system
                    var newsysInfo = registerSensor(sysID, sys);
                    sysInfo = newsysInfo;
                    
                    // subscribe to system events
                    getParentHub().getEventBus().newSubscription(SystemEvent.class)
                        .withTopicID(EventUtils.getSystemStatusTopicID(sysUID))
                        .withEventType(SystemEvent.class)
                        .subscribe(e -> {
                            handleEvent(e);
                        })
                        .thenAccept(sub -> {
                            newsysInfo.sub = sub;
                            sub.request(Long.MAX_VALUE);
                        });
                    
                    // register all selected datastreams
                    var dsFilter = new DataStreamFilter.Builder()
                        .withSystems(sysID)
                        .withCurrentVersion()
                        .build();
                    
                    var addedStreams = new ArrayList<StreamInfo>();
                    dataBaseView.getDataStreamStore().selectEntries(dsFilter)
                       .forEach(Lambdas.checked(e -> {
                           var dsId = e.getKey().getInternalID();
                           var dsInfo = e.getValue();
                           var streamInfo = registerDataStream(dsId, dsInfo, newsysInfo);
                           addedStreams.add(streamInfo);
                       }));
                    
                    // start all streams
                    for (var streamInfo: addedStreams)
                        startStream(streamInfo);
                }
                
                // if description is newer, call updateSensor
                else if (sys.getValidTime().begin().isAfter(sysInfo.startValidTime))
                {
                    updateSensor(sysUID);
                    sysInfo.startValidTime = sys.getValidTime().begin();
                }
                
                return sysInfo;
            }
            catch (ClientException | CallbackException e)
            {
                reportError(e.getMessage(), e.getCause());
                return null;
            }
        });
    }
    
    
    /*
     * Helper to register new sensor when a ProcedureAdded event is received
     */
    protected void addProcedure(String sysUID)
    {
        var sysEntry = dataBaseView.getSystemDescStore().getCurrentVersionEntry(sysUID);
        if (sysEntry != null)
        {
            var id = sysEntry.getKey().getInternalID();
            var sys = sysEntry.getValue();
            addProcedure(id, sys);
        }
    }
    
    
    /*
     * Register sensor at remote SOS
     */
    protected SystemRegInfo registerSensor(BigId sysID, ISystemWithDesc sys) throws ClientException
    {
        var sysUID = sys.getUniqueIdentifier();
        
        try
        {
            // build insert sensor request
            InsertSensorRequest req = new InsertSensorRequest();
            req.setConnectTimeOut(config.connection.connectTimeout);
            req.setPostServer(getSosEndpointUrl());
            req.setVersion("2.0");
            req.setProcedureDescription(sys.getFullDescription());
            req.setProcedureDescriptionFormat(SWESUtils.DEFAULT_PROCEDURE_FORMAT);
            req.getObservationTypes().add(IObservation.OBS_TYPE_RECORD);
            req.getFoiTypes().add("gml:Feature");
            
            // send request and get assigned ID
            setAuth();
            InsertSensorResponse resp = sosUtils.sendRequest(req, false);

            // create sysedure info from server response
            var sysInfo = new SystemRegInfo();
            sysInfo.offeringId = resp.getAssignedOffering();
            sysInfo.startValidTime = sys.getValidTime().begin();
            
            getLogger().info("System {} registered at SOS endpoint {}", sysUID, getSosEndpointUrl());
            return sysInfo;
        }
        catch (Exception e)
        {
            throw new ClientException("Error registering system " + sysUID + " at remote SOS", e);
        }
    }
    
    
    /*
     * Update sensor description at remote SOS
     */
    protected void updateSensor(String sysUID)
    {
        try
        {
            var sys = dataBaseView.getSystemDescStore().getCurrentVersion(sysUID);
            if (sys != null)
            {
                // build update sensor request
                UpdateSensorRequest req = new UpdateSensorRequest(SOSUtils.SOS);
                req.setConnectTimeOut(config.connection.connectTimeout);
                req.setPostServer(getSosEndpointUrl());
                req.setVersion("2.0");
                req.setProcedureId(sys.getUniqueIdentifier());
                req.setProcedureDescription(sys.getFullDescription());
                req.setProcedureDescriptionFormat(SWESUtils.DEFAULT_PROCEDURE_FORMAT);
                
                // send request
                setAuth();
                sosUtils.sendRequest(req, false);
                
                getLogger().info("Procedure {} updated at SOS endpoint {}", sysUID, getSosEndpointUrl());
            }
        }
        catch (Exception e)
        {
            reportError("Error updating system " + sysUID + " at remote SOS", e);
        }
    }
    
    
    /*
     * Disable and optionally remove previously managed system
     */
    protected void disableSystem(String sysUID, boolean remove)
    {
        var sysInfo = remove ? registeredSystems.remove(sysUID) : registeredSystems.get(sysUID);
        if (sysInfo != null)
        {
            if (sysInfo.sub != null)
            {
                sysInfo.sub.cancel();
                sysInfo.sub = null;
                getLogger().debug("Unsubscribed from system {}", sysUID);
            }
            
            var sysDataStreams = dataStreams.subMap(sysUID, sysUID + "\uffff");
            for (var streamInfo: sysDataStreams.values())
                stopStream(streamInfo);
            
            if (remove)
                getLogger().info("Removed system {}", sysUID);
        }
    }
    
    
    /*
     * Register datastream at remote SOS
     */
    protected StreamInfo registerDataStream(BigId dsID, IDataStreamInfo dsInfo, SystemRegInfo sysInfo) throws ClientException
    {
        var dsTopicId = EventUtils.getDataStreamDataTopicID(dsInfo);
        
        try
        {
            // skip if already registered
            var streamInfo = dataStreams.get(dsTopicId);
            if (streamInfo != null)
                return streamInfo;
            
            // assign ID to record struct so we can use it to retain the output name
            var dsInfoWithId = dsInfo.getRecordStructure().copy();
            dsInfoWithId.setId(dsInfo.getOutputName());
            
            // otherwise register result template
            // create request object
            InsertResultTemplateRequest req = new InsertResultTemplateRequest();
            req.setConnectTimeOut(config.connection.connectTimeout);
            req.setPostServer(getSosEndpointUrl());
            req.setVersion("2.0");
            req.setOffering(sysInfo.offeringId);
            req.setResultStructure(dsInfoWithId);
            req.setResultEncoding(dsInfo.getRecordEncoding());
            ObservationImpl obsTemplate = new ObservationImpl();
            req.setObservationTemplate(obsTemplate);
            
            // set FOI if known
            dataBaseView.getObservationStore().selectObservedFois(
                new ObsFilter.Builder()
                    .withDataStreams(dsID)
                    .withPhenomenonTime().withCurrentTime().done()
                    .build())
                .findFirst().ifPresent(id -> {
                    IFeature foi = dataBaseView.getFoiStore().getCurrentVersion(id);
                    if (foi != null)
                        obsTemplate.setFeatureOfInterest(foi);
                });
            
            // send request
            setAuth();
            InsertResultTemplateResponse resp = sosUtils.sendRequest(req, false);
            
            // add stream info to map
            streamInfo = new StreamInfo();
            streamInfo.internalID = dsID;
            streamInfo.sysUID = dsInfo.getSystemID().getUniqueID();
            streamInfo.outputName = dsInfo.getOutputName();
            streamInfo.topicId = dsTopicId;
            streamInfo.templateId = resp.getAcceptedTemplateId();
            streamInfo.resultData.setElementType(dsInfo.getRecordStructure());
            streamInfo.resultData.setEncoding(dsInfo.getRecordEncoding());
            //streamInfo.measPeriodMs = (int)(output.getAverageSamplingPeriod()*1000);
            streamInfo.minRecordsPerRequest = 1;//(int)(1.0 / sensorOutput.getAverageSamplingPeriod());
            dataStreams.put(streamInfo.topicId, streamInfo);
            
            getLogger().info("Datastream {} registered at SOS endpoint {}", dsTopicId, getSosEndpointUrl());
                        
            return streamInfo;
        }
        catch (Exception e)
        {
            throw new ClientException("Error registering datastream " + dsTopicId + " at remote SOS", e);
        }
    }
    
    
    /*
     * Subscribe to eventbus and start sending data data to remote SOS
     */
    protected void startStream(StreamInfo streamInfo) throws ClientException
    {
        try
        {
            // skip if stream was already started
            if (streamInfo.sub != null)
                return;
            
            // subscribe to data events
            getParentHub().getEventBus().newSubscription(ObsEvent.class)
                .withTopicID(streamInfo.topicId)
                .withEventType(ObsEvent.class)
                .subscribe(e -> {
                    handleEvent(e, streamInfo);
                })
                .thenAccept(sub -> {
                    streamInfo.sub = sub;
                    sub.request(Long.MAX_VALUE);
                    
                    // send latest record(s)
                    dataBaseView.getObservationStore().select(new ObsFilter.Builder()
                            .withDataStreams(streamInfo.internalID)
                            .withLatestResult()
                            .build())
                        .forEach(obs -> {
                            sendObs(obs, streamInfo);
                        });
                    
                    getLogger().info("Starting data push for stream {} to SOS endpoint {}", streamInfo.topicId, getSosEndpointUrl());
                });
        }
        catch(Exception e)
        {
            throw new ClientException("Error starting data push for stream " + streamInfo.topicId, e);
        }
    }
    
    
    /*
     * Helper to register and start new datastream when a DataStreamAdded event is received
     */
    protected void addAndStartStream(String sysUID, String outputName)
    {
        try
        {
            var sysInfo = registeredSystems.get(sysUID);
            var dsEntry = dataBaseView.getDataStreamStore().getLatestVersionEntry(sysUID, outputName);
            if (sysInfo != null && dsEntry != null)
            {
                var dsId = dsEntry.getKey().getInternalID();
                var dsInfo = dsEntry.getValue();
                
                var streamInfo = registerDataStream(dsId, dsInfo, sysInfo);
                startStream(streamInfo);
            }
        }
        catch (ClientException e)
        {
            reportError(e.getMessage(), e.getCause());
        }
    }
    
    
    /*
     * Disable and optionally remove previously managed datastream
     */
    protected void disableDataStream(String sysUID, String outputName, boolean remove)
    {
        var dsSourceId = EventUtils.getDataStreamDataTopicID(sysUID, outputName);
        var streamInfo = remove ? dataStreams.remove(dsSourceId) : dataStreams.get(dsSourceId);
        if (streamInfo != null)
        {
            stopStream(streamInfo);
            if (remove)
                getLogger().info("Removed datastream {}", dsSourceId);
        }
    }
    
    
    /*
     * Stop listening and pushing data for the given stream
     */
    protected void stopStream(StreamInfo streamInfo)
    {
        // unsubscribe from eventbus
        if (streamInfo.sub != null)
        {
            streamInfo.sub.cancel();
            streamInfo.sub = null;
        }
        
        // close persistent HTTP connection
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
            if (getLogger().isDebugEnabled())
                getLogger().error("Cannot close persistent connection", e);
        }
        finally
        {
            streamInfo.persistentWriter = null;
            streamInfo.connection = null;
        }
        
        // clear executor queue
        if (streamInfo.executor != null)
            streamInfo.executor.clear();
        
        getLogger().info("Stopping data push for stream {}", streamInfo.topicId);
    }
    
    
    protected void handleEvent(final ObsEvent e, final StreamInfo streamInfo)
    {
        // we stop here if we had too many errors
        if (streamInfo.errorCount >= config.connection.maxConnectErrors)
        {
            String outputName = ((ObsEvent)e).getOutputName();
            reportError("Too many errors sending '" + outputName + "' data to SOS-T. Stopping Stream.", null);
            stopStream(streamInfo);
            checkDisconnected();
            return;
        }
        
        // skip if we cannot handle more requests
        if (streamInfo.executor.getQueue().size() == config.connection.maxQueueSize)
        {
            String outputName = ((ObsEvent)e).getOutputName();
            getLogger().warn("Too many '{}' records to send to SOS-T. Bandwidth cannot keep up.", outputName);
            getLogger().info("Skipping records by purging record queue");
            streamInfo.executor.clear();
            return;
        }
        
        // record last event time
        streamInfo.lastEventTime = e.getTimeStamp();
        
        // send obs to remote SOS
        sendObs(e, streamInfo);
    }
    
    
    protected void handleEvent(final SystemEvent e)
    {
        // sensor description updated
        if (e instanceof SystemChangedEvent)
        {
            CompletableFuture.runAsync(() -> {
                var sysUID = e.getSystemUID();
                updateSensor(sysUID);
            });
        }
        
        // system events
        else if (e instanceof SystemAddedEvent || e instanceof SystemEnabledEvent)
        {
            CompletableFuture.runAsync(() -> {
                var sysUID = e.getSystemUID();
                addProcedure(sysUID);
            });
        }
        
        else if (e instanceof SystemDisabledEvent)
        {
            CompletableFuture.runAsync(() -> {
                var sysUID = e.getSystemUID();
                disableSystem(sysUID, false);
            });
        }
        
        else if (e instanceof SystemRemovedEvent)
        {
            CompletableFuture.runAsync(() -> {
                var sysUID = e.getSystemUID();
                disableSystem(sysUID, true);
            });
        }
        
        // datastream events
        else if (e instanceof DataStreamAddedEvent || e instanceof DataStreamEnabledEvent)
        {
            CompletableFuture.runAsync(() -> {
                var sysUID = e.getSystemUID();
                var outputName = ((DataStreamAddedEvent) e).getOutputName();
                addAndStartStream(sysUID, outputName);
            });
        }
        
        else if (e instanceof DataStreamDisabledEvent)
        {
            CompletableFuture.runAsync(() -> {
                var sysUID = e.getSystemUID();
                var outputName = ((DataStreamAddedEvent) e).getOutputName();
                disableDataStream(sysUID, outputName, false);
            });
        }
        
        else if (e instanceof DataStreamRemovedEvent)
        {
            CompletableFuture.runAsync(() -> {
                var sysUID = e.getSystemUID();
                var outputName = ((DataStreamAddedEvent) e).getOutputName();
                disableDataStream(sysUID, outputName, true);
            });
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
    
    
    private void sendObs(final IObsData obs, final StreamInfo streamInfo)
    {
        sendObs(new ObsEvent(
            obs.getResultTime().toEpochMilli(),
            streamInfo.sysUID,
            streamInfo.outputName,
            obs), streamInfo);
    }
    
    
    private void sendObs(final ObsEvent e, final StreamInfo streamInfo)
    {
        // send record using one of 2 methods
        if (config.connection.usePersistentConnection)
            sendInPersistentRequest(e, streamInfo);
        else
            sendAsNewRequest(e, streamInfo);
    }
    
    
    /*
     * Sends each new record using an XML InsertResult POST request
     */
    private void sendAsNewRequest(final ObsEvent e, final StreamInfo streamInfo)
    {
        // append records to buffer
        for (var obs: e.getObservations())
            streamInfo.resultData.pushNextDataBlock(obs.getResult());
        
        // send request if min record count is reached
        if (streamInfo.resultData.getNumElements() >= streamInfo.minRecordsPerRequest)
        {
            final InsertResultRequest req = new InsertResultRequest();
            req.setPostServer(getSosEndpointUrl());
            req.setVersion("2.0");
            req.setTemplateId(streamInfo.templateId);
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
                            String outputName = e.getOutputName();
                            int numRecords = req.getResultData().getComponentCount();
                            getLogger().trace("Sending " + numRecords + " '" + outputName + "' record(s) to SOS-T");
                            getLogger().trace("Queue size is " + streamInfo.executor.getQueue().size());
                        }
                        
                        sosUtils.sendRequest(req, false);
                    }
                    catch (Exception ex)
                    {
                        String outputName = e.getOutputName();
                        reportError("Error when sending '" + outputName + "' data to SOS-T", ex, true);
                        streamInfo.errorCount++;
                    }
                }
            };
            
            // run task in async thread pool
            streamInfo.executor.execute(sendTask);
        }
    }
    
    
    /*
     * Sends all records in the same persistent HTTP connection.
     * The connection is created when the first record is received
     */
    private void sendInPersistentRequest(final ObsEvent e, final StreamInfo streamInfo)
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
                            getLogger().debug("Initiating persistent HTTP request");
                        
                        final InsertResultRequest req = new InsertResultRequest();
                        req.setPostServer(getSosEndpointUrl());
                        req.setVersion("2.0");
                        req.setTemplateId(streamInfo.templateId);
                        
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
                        String outputName = e.getOutputName();
                        int numRecords = e.getObservations().length;
                        getLogger().trace("Sending " + numRecords + " '" + outputName + "' record(s) to SOS-T");
                        getLogger().trace("Queue size is " + streamInfo.executor.getQueue().size());
                    }
                    
                    // write records to output stream
                    for (var obs: e.getObservations())
                        streamInfo.persistentWriter.write(obs.getResult());
                    streamInfo.persistentWriter.flush();
                }
                catch (Exception ex)
                {
                    // ignore exception if stream was purposely stopped
                    if (streamInfo.stopping)
                        return;
                    
                    String outputName = e.getOutputName();
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
        streamInfo.executor.execute(sendTask);
    }


    @Override
    public boolean isConnected()
    {
        return connection.isConnected();
    }
    
    
    public Map<String, StreamInfo> getDataStreams()
    {
        return dataStreams;
    }


    @Override
    public void cleanup() throws SensorHubException
    {
        // nothing to clean
    }
}
