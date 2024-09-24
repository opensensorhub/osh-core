package org.sensorhub.impl.service.consys.client;

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

    class SystemRegInfo
    {
        private Flow.Subscription subscription;
        private ISystemWithDesc system;
    }

    class StreamInfo
    {
        private String dataStreamID;
        private String topicID;
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

        dataBaseView.getSystemDescStore().select(dataBaseView.getSystemDescStore().selectAllFilter())
                .forEach((system) -> {
                    String newSys = null;
                    try {
                        newSys = client.addSystem(system).get();
                        String finalNewSys = newSys;

                        dataBaseView.getDataStreamStore().select(new DataStreamFilter.Builder().withSystems(new SystemFilter.Builder().withUniqueIDs(system.getUniqueIdentifier()).build()).build())
                                .forEach((datastream) -> {
                                    try {
                                        String newDs = client.addDataStream(finalNewSys, datastream).get();
                                        System.out.println("Added datastream " + datastream.getOutputName() + ". Now pushing observations...");
                                        if(dataBaseView.getObservationStore() == null) {
                                            System.out.println("Observation store does not exist, continuing");
                                            return;
                                        }
                                        dataBaseView.getObservationStore().select(
                                                new ObsFilter.Builder().withDataStreams(
                                                        new DataStreamFilter.Builder().withOutputNames(datastream.getOutputName())
                                                                .build())
                                                        .build())
                                                .forEach((obs) -> {
                                                    client.pushObs(newDs, obs, dataBaseView.getObservationStore());
                                                });
                                    } catch (InterruptedException | ExecutionException e) {
                                        throw new RuntimeException(e);
                                    }
                                });
                    } catch (InterruptedException | ExecutionException e) {
                        throw new RuntimeException(e);
                    }
                    System.out.println(newSys);
                });

        // TODO: Check if system exists
        // TODO: Ensure connection can be made

        // TODO: Subscribe to system registry

        // TODO: Register systems/subsystems to destination SensorHub
        // TODO: Register datastreams to destination
        // TODO: Push observations from datastreams
    }

    protected void registerSystemDataStreams(SystemRegInfo system)
    {
        dataBaseView.getDataStreamStore().select(
                new DataStreamFilter.Builder()
                        .withSystems(new SystemFilter.Builder()
                            .withUniqueIDs(system.system.getUniqueIdentifier())
                                .build())
                        .build())
                .forEach((dataStream) -> {
//                    var streamInfo = registerDataStream(dataStream);
//                    dataStreams.put("", streamInfo);
                });
    }

    protected void registerDataStream(IDataStreamInfo dataStream)
    {

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
