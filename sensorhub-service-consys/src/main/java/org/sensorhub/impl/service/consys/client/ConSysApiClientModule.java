package org.sensorhub.impl.service.consys.client;

import org.checkerframework.checker.units.qual.C;
import org.sensorhub.api.client.ClientException;
import org.sensorhub.api.client.IClientModule;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.data.DataStreamInfo;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.data.ObsEvent;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.obs.DataStreamFilter;
import org.sensorhub.api.datastore.obs.ObsFilter;
import org.sensorhub.api.datastore.system.SystemFilter;
import org.sensorhub.api.event.EventUtils;
import org.sensorhub.api.system.ISystemWithDesc;
import org.sensorhub.impl.module.AbstractModule;
import org.sensorhub.impl.module.RobustConnection;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.vast.cdm.common.DataStreamWriter;
import org.vast.swe.SWEData;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;

public class ConSysApiClientModule extends AbstractModule<ConSysApiClientConfig> implements IClientModule<ConSysApiClientConfig> {

    RobustConnection connection;
    IObsSystemDatabase dataBaseView;
    String apiEndpointUrl;
    ConSysApiClient client;
    Map<String, SystemRegInfo> registeredSystems;
    NavigableMap<String, StreamInfo> dataStreams;

    static class SystemRegInfo
    {
        private String systemID;
        private BigId internalID;
        private Flow.Subscription subscription;
        private ISystemWithDesc system;
    }

    static class StreamInfo
    {
        private String dataStreamID;
        private String topicID;
        private BigId internalID;
        private String sysUID;
        private String outputName;
        private Flow.Subscription subscription;
        private HttpURLConnection connection;
        private DataStreamWriter persistentWriter;
        private volatile boolean connecting = false;
        private volatile boolean stopping = false;
    }

    public ConSysApiClientModule()
    {
//        this.startAsync = true;
        this.registeredSystems = new ConcurrentHashMap<>();
        this.dataStreams = new ConcurrentSkipListMap<>();
    }

    @Override
    public void setConfiguration(ConSysApiClientConfig config)
    {
        super.setConfiguration(config);

        String scheme = "http";
        if (config.conSys.enableTLS)
            scheme += "s";
        apiEndpointUrl = scheme + "://" + config.conSys.remoteHost + ":" + config.conSys.remotePort;
        if (config.conSys.resourcePath != null)
        {
            if (config.conSys.resourcePath.charAt(0) != '/')
                apiEndpointUrl += '/';
            apiEndpointUrl += config.conSys.resourcePath;
        }
    }

    protected void checkConfiguration() throws SensorHubException
    {
        // TODO check config
    }

    @Override
    protected void doInit() throws SensorHubException
    {
        checkConfiguration();

        this.dataBaseView = config.dataSourceSelector.getFilteredView(getParentHub());

        this.client = ConSysApiClient.
                newBuilder(apiEndpointUrl)
                .simpleAuth(config.conSys.user, config.conSys.password.toCharArray())
                .build();

        // TODO: Other initialization
    }

    @Override
    protected void doStart() throws SensorHubException {
        // Check if endpoint is available
        try{
            client.sendGetRequest(URI.create(apiEndpointUrl), ResourceFormat.JSON, null);
        } catch (Exception e) {
            reportError("Unable to establish connection to Connected Systems endpoint", e);
        }

        dataBaseView.getSystemDescStore().selectEntries(
                new SystemFilter.Builder()
                        .withNoParent()
                        .build())
                .forEach((entry) -> {
                    var systemRegInfo = registerSystem(entry.getKey().getInternalID(), entry.getValue());
                    checkSubSystems(systemRegInfo);
                    registerSystemDataStreams(systemRegInfo);
                });

        for (var stream : dataStreams.values())
            startStream(stream);

        // TODO: Check if system exists
        // TODO: Ensure connection can be made

        // TODO: Subscribe to system registry

        // TODO: Register systems/subsystems to destination SensorHub
        // TODO: Register datastreams to destination
        // TODO: Push observations from datastreams
    }

    @Override
    protected void doStop() throws SensorHubException {
        super.doStop();

        for(var stream : dataStreams.values())
            stopStream(stream);
    }

    protected void checkSubSystems(SystemRegInfo parentSystemRegInfo)
    {
        dataBaseView.getSystemDescStore().selectEntries(
                        new SystemFilter.Builder()
                                .withParents(parentSystemRegInfo.internalID)
                                .build())
                .forEach((entry) -> {
                    var systemRegInfo = registerSubSystem(entry.getKey().getInternalID(), parentSystemRegInfo, entry.getValue());
                    registerSystemDataStreams(systemRegInfo);
                });
    }

    protected SystemRegInfo registerSystem(BigId systemInternalID, ISystemWithDesc system)
    {
        try {
            String systemID = client.addSystem(system).get();

            SystemRegInfo systemRegInfo = new SystemRegInfo();
            systemRegInfo.systemID = systemID;
            systemRegInfo.internalID = systemInternalID;
            systemRegInfo.system = system;
            return systemRegInfo;
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    protected SystemRegInfo registerSubSystem(BigId systemInternalID, SystemRegInfo parentSystem, ISystemWithDesc system)
    {
        try {
            var getParent = client.getSystemById(parentSystem.systemID, ResourceFormat.JSON);
            if(getParent == null)
                throw new ClientException("Could not retrieve parent system " + parentSystem.systemID);
            String systemID = client.addSubSystem(parentSystem.systemID, system).get();

            SystemRegInfo systemRegInfo = new SystemRegInfo();
            systemRegInfo.systemID = systemID;
            systemRegInfo.internalID = systemInternalID;
            systemRegInfo.system = system;
            return systemRegInfo;
        } catch (InterruptedException | ExecutionException | ClientException e) {
            throw new RuntimeException(e);
        }
    }

    protected void registerSystemDataStreams(SystemRegInfo system)
    {
        dataBaseView.getDataStreamStore().selectEntries(
                new DataStreamFilter.Builder()
                        .withSystems(new SystemFilter.Builder()
                            .withUniqueIDs(system.system.getUniqueIdentifier())
                                .build())
                        .build())
                .forEach((entry) -> {
                    var streamInfo = registerDataStream(entry.getKey().getInternalID(), system.systemID, entry.getValue());
                    dataStreams.put(streamInfo.dataStreamID, streamInfo);
                });
    }

    protected StreamInfo registerDataStream(BigId dsId, String systemID, IDataStreamInfo dataStream)
    {
        var dsTopicId = EventUtils.getDataStreamDataTopicID(dataStream);

        StreamInfo streamInfo = new StreamInfo();
        try {
            streamInfo.dataStreamID = client.addDataStream(systemID, dataStream).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }

        streamInfo.topicID = dsTopicId;
        streamInfo.outputName = dataStream.getOutputName();
        streamInfo.sysUID = dataStream.getSystemID().getUniqueID();
        streamInfo.internalID = dsId;

        return streamInfo;
    }

    protected void startStream(StreamInfo streamInfo) throws ClientException
    {
        try
        {
            if(streamInfo.subscription != null)
                return;

            getParentHub().getEventBus().newSubscription(ObsEvent.class)
                    .withTopicID(streamInfo.topicID)
                    .withEventType(ObsEvent.class)
                    .subscribe(e -> handleEvent(e, streamInfo))
                    .thenAccept(subscription -> {
                        streamInfo.subscription = subscription;
                        subscription.request(Long.MAX_VALUE);

                        // Push latest observation
                        this.dataBaseView.getObservationStore().select(new ObsFilter.Builder()
                                .withDataStreams(streamInfo.internalID)
                                .withLatestResult()
                                .build())
                            .forEach(obs ->
                                client.pushObs(streamInfo.dataStreamID, obs, this.dataBaseView.getObservationStore()));

                        getLogger().info("Starting Connected Systems data push for stream {} with UID {} to Connected Systems endpoint {}",
                                streamInfo.dataStreamID, streamInfo.sysUID, apiEndpointUrl);
                    });
        } catch (Exception e)
        {
            throw new ClientException("Error starting data push for stream " + streamInfo.topicID, e);
        }
    }

    protected void stopStream(StreamInfo streamInfo) throws ClientException
    {
        if(streamInfo.subscription != null)
        {
            streamInfo.subscription.cancel();
            streamInfo.subscription = null;
        }


    }

    @Override
    public boolean isConnected()
    {
        return false;
    }

    protected void handleEvent(final ObsEvent e, StreamInfo streamInfo)
    {
        for(var obs : e.getObservations())
            client.pushObs(streamInfo.dataStreamID, obs, this.dataBaseView.getObservationStore());
    }

}
