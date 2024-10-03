package org.sensorhub.impl.service.consys.client;

import com.google.common.base.Strings;
import org.sensorhub.api.client.ClientException;
import org.sensorhub.api.client.IClientModule;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.data.*;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.obs.DataStreamFilter;
import org.sensorhub.api.datastore.obs.ObsFilter;
import org.sensorhub.api.datastore.system.SystemFilter;
import org.sensorhub.api.event.EventUtils;
import org.sensorhub.api.system.ISystemWithDesc;
import org.sensorhub.api.system.SystemAddedEvent;
import org.sensorhub.api.system.SystemChangedEvent;
import org.sensorhub.api.system.SystemEnabledEvent;
import org.sensorhub.api.system.SystemRemovedEvent;
import org.sensorhub.api.system.SystemDisabledEvent;
import org.sensorhub.api.system.SystemEvent;
import org.sensorhub.impl.module.AbstractModule;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.vast.util.Asserts;

import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.PasswordAuthentication;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.CompletableFuture;

public class ConSysApiClientModule extends AbstractModule<ConSysApiClientConfig> implements IClientModule<ConSysApiClientConfig> {

    IObsSystemDatabase dataBaseView;
    String apiEndpointUrl;
    ConSysApiClient client;
    Map<String, SystemRegInfo> registeredSystems;
    NavigableMap<String, StreamInfo> dataStreams;
    Flow.Subscription registrySubscription;

    public static class SystemRegInfo
    {
        private String systemID;
        private BigId internalID;
        private Flow.Subscription subscription;
        private ISystemWithDesc system;
    }

    public static class StreamInfo
    {
        private IDataStreamInfo dataStream;
        private String dataStreamID;
        private String topicID;
        private BigId internalID;
        private String sysUID;
        private Flow.Subscription subscription;
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

    @Override
    protected void doInit() throws SensorHubException
    {
        this.dataBaseView = config.dataSourceSelector.getFilteredView(getParentHub());

        this.client = ConSysApiClient.
                newBuilder(apiEndpointUrl)
                .simpleAuth(config.conSys.user, !config.conSys.password.isEmpty() ? config.conSys.password.toCharArray() : null)
                .build();

        // TODO: Other initialization
    }

    @Override
    protected void doStart() throws SensorHubException {
        // Check if endpoint is available
        try{
            HttpURLConnection urlConnection = (HttpURLConnection) client.endpoint.toURL().openConnection();
            if (!Strings.isNullOrEmpty(config.conSys.user)) {
                urlConnection.setAuthenticator(new Authenticator() {
                    @Override
                    public PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(config.conSys.user, config.conSys.password != null ? config.conSys.password.toCharArray() : new char[0]);
                    }
                });
            }
            urlConnection.connect();
            Asserts.checkArgument(urlConnection.getResponseCode() == HttpURLConnection.HTTP_OK);
        } catch (Exception e) {
            throw new SensorHubException("Unable to establish connection to Connected Systems endpoint");
        }

        reportStatus("Connection to " + apiEndpointUrl + " was made successfully");

        dataBaseView.getSystemDescStore().selectEntries(
                new SystemFilter.Builder()
                        .withNoParent()
                        .build())
                .forEach((entry) -> {
                    var systemRegInfo = registerSystem(entry.getKey().getInternalID(), entry.getValue());
                    checkSubSystems(systemRegInfo);
                    registerSystemDataStreams(systemRegInfo);
                });

        subscribeToRegistryEvents();

        for (var stream : dataStreams.values())
            startStream(stream);
    }

    @Override
    protected void doStop() throws SensorHubException {
        super.doStop();

        for(var stream : dataStreams.values())
            stopStream(stream);
    }

    protected void subscribeToRegistryEvents()
    {
        getParentHub().getEventBus().newSubscription(SystemEvent.class)
                .withTopicID(EventUtils.getSystemRegistryTopicID())
                .withEventType(SystemEvent.class)
                .subscribe(this::handleEvent)
                .thenAccept(sub -> {
                    registrySubscription = sub;
                    sub.request(Long.MAX_VALUE);
                });
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

    private String tryUpdateSystem(ISystemWithDesc system)
    {
        try {
            var uidRequest = client.getSystemByUid(system.getUniqueIdentifier(), ResourceFormat.JSON);
            String systemID;
            if(uidRequest != null) {
                var oldSys = uidRequest.get();
                systemID = oldSys.getId();
                var responseCode = client.updateSystem(systemID, system).get();
                boolean successful = responseCode == 204;
                if(!successful)
                    throw new ClientException("Failed to update resource: " + apiEndpointUrl + ConSysApiClient.SYSTEMS_COLLECTION + "/" + systemID);
                return systemID;
            }
        } catch (ExecutionException | InterruptedException | ClientException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    private void disableSystem(String uid, boolean remove)
    {
        var sysInfo = remove ? registeredSystems.remove(uid) : registeredSystems.get(uid);
        if(sysInfo != null)
        {
            if(sysInfo.subscription != null)
            {
                sysInfo.subscription.cancel();
                sysInfo.subscription = null;
                getLogger().debug("Unsubscribed from system {}", uid);
            }

            var sysDataStreams = dataStreams.subMap(uid, uid + "\uffff");
            for(var streamInfo : sysDataStreams.values())
                stopStream(streamInfo);

            if(remove)
                getLogger().info("Removed system {}", uid);
        }
    }

    protected SystemRegInfo registerSystem(BigId systemInternalID, ISystemWithDesc system)
    {
        try {
            String systemID = tryUpdateSystem(system);
            if(systemID == null)
                systemID = client.addSystem(system).get();

            return registerSystemInfo(systemID, systemInternalID, system);
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    protected SystemRegInfo registerSubSystem(BigId systemInternalID, SystemRegInfo parentSystem, ISystemWithDesc system)
    {
        try {
            var getParent = client.getSystemById(parentSystem.systemID, ResourceFormat.JSON);
            var parent = getParent.get();
            if(parent == null)
                throw new ClientException("Could not retrieve parent system " + parentSystem.systemID);

            String systemID = tryUpdateSystem(system);
            if(systemID == null)
                systemID = client.addSubSystem(parentSystem.systemID, system).get();

            return registerSystemInfo(systemID, systemInternalID, system);
        } catch (InterruptedException | ExecutionException | ClientException e) {
            throw new RuntimeException(e);
        }
    }

    private SystemRegInfo registerSystemInfo(String systemID, BigId systemInternalID, ISystemWithDesc system)
    {
        SystemRegInfo systemRegInfo = new SystemRegInfo();
        systemRegInfo.systemID = systemID;
        systemRegInfo.internalID = systemInternalID;
        systemRegInfo.system = system;

        getParentHub().getEventBus().newSubscription(SystemEvent.class)
                .withTopicID(EventUtils.getSystemStatusTopicID(system.getUniqueIdentifier()))
                .withEventType(SystemEvent.class)
                .subscribe(this::handleEvent)
                .thenAccept(sub -> {
                    systemRegInfo.subscription = sub;
                    sub.request(Long.MAX_VALUE);
                });

        registeredSystems.put(system.getUniqueIdentifier(), systemRegInfo);
        return systemRegInfo;
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
                    if(Objects.equals(entry.getValue().getSystemID().getUniqueID(), system.system.getUniqueIdentifier()))
                        registerDataStream(entry.getKey().getInternalID(), system.systemID, entry.getValue());
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

        streamInfo.dataStream = dataStream;
        streamInfo.topicID = dsTopicId;
        streamInfo.sysUID = dataStream.getSystemID().getUniqueID();
        streamInfo.internalID = dsId;

        dataStreams.put(dsTopicId, streamInfo);
        return streamInfo;
    }

    protected void addAndStartStream(String uid, String outputName)
    {
        try
        {
            var sysInfo = registeredSystems.get(uid);
            var dsEntry = dataBaseView.getDataStreamStore().getLatestVersionEntry(uid, outputName);
            if(sysInfo != null && dsEntry != null)
            {
                var dsId = dsEntry.getKey().getInternalID();
                var dsInfo = dsEntry.getValue();

                var streamInfo = registerDataStream(dsId, sysInfo.systemID, dsInfo);
                startStream(streamInfo);
            }
        }
        catch (ClientException e)
        {
            reportError(e.getMessage(), e.getCause());
        }
    }

    protected void disableDataStream(String uid, String outputName, boolean remove)
    {
        var dsSourceId = EventUtils.getDataStreamDataTopicID(uid, outputName);
        var streamInfo = remove ? dataStreams.remove(dsSourceId) : dataStreams.get(dsSourceId);
        if(streamInfo != null)
        {
            stopStream(streamInfo);
            if(remove)
                getLogger().info("Removed datastream {}", dsSourceId);
        }
    }

    protected synchronized void startStream(StreamInfo streamInfo) throws ClientException
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
                                client.pushObs(streamInfo.dataStreamID, streamInfo.dataStream, obs, this.dataBaseView.getObservationStore()));

                        getLogger().info("Starting Connected Systems data push for stream {} with UID {} to Connected Systems endpoint {}",
                                streamInfo.dataStreamID, streamInfo.sysUID, apiEndpointUrl);
                    });
        } catch (Exception e)
        {
            throw new ClientException("Error starting data push for stream " + streamInfo.topicID, e);
        }
    }

    protected void stopStream(StreamInfo streamInfo)
    {
        if(streamInfo.subscription != null)
        {
            streamInfo.subscription.cancel();
            streamInfo.subscription = null;
        }

        // TODO Check other stuff
    }

    @Override
    public boolean isConnected()
    {
        return false;
    }

    protected void handleEvent(final ObsEvent e, StreamInfo streamInfo)
    {
        for(var obs : e.getObservations())
            client.pushObs(streamInfo.dataStreamID, streamInfo.dataStream, obs, this.dataBaseView.getObservationStore());
    }

    protected void handleEvent(final SystemEvent e)
    {
        // sensor description updated
        if (e instanceof SystemChangedEvent)
        {
            CompletableFuture.runAsync(() -> {
                var system = dataBaseView.getSystemDescStore().getCurrentVersion(e.getSystemUID());
                if(system != null)
                    tryUpdateSystem(system);
            });
        }

        // system events
        else if (e instanceof SystemAddedEvent || e instanceof SystemEnabledEvent)
        {
            CompletableFuture.runAsync(() -> {
                var system = dataBaseView.getSystemDescStore().getCurrentVersionEntry(e.getSystemUID());
                if(system != null)
                {
                    var systemRegInfo = registerSystem(system.getKey().getInternalID(), system.getValue());
                    checkSubSystems(systemRegInfo);
                    registerSystemDataStreams(systemRegInfo);
                }
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
                var outputName = ((DataStreamEvent) e).getOutputName();
                addAndStartStream(sysUID, outputName);
            });
        }

        else if (e instanceof DataStreamDisabledEvent)
        {
            CompletableFuture.runAsync(() -> {
                var sysUID = e.getSystemUID();
                var outputName = ((DataStreamEvent) e).getOutputName();
                disableDataStream(sysUID, outputName, false);
            });
        }

        else if (e instanceof DataStreamRemovedEvent)
        {
            CompletableFuture.runAsync(() -> {
                var sysUID = e.getSystemUID();
                var outputName = ((DataStreamEvent) e).getOutputName();
                disableDataStream(sysUID, outputName, true);
            });
        }
    }

}
