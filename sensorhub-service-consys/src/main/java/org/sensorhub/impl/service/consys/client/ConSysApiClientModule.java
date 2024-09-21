package org.sensorhub.impl.service.consys.client;

import org.sensorhub.api.client.IClientModule;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.impl.module.AbstractModule;
import org.sensorhub.impl.module.RobustConnection;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

public class ConSysApiClientModule extends AbstractModule<ConSysApiClientConfig> implements IClientModule<ConSysApiClientConfig> {

    RobustConnection connection;
    IObsSystemDatabase dataBaseView;
    String apiEndpointUrl;
    ConSysApiClient client;

    public ConSysApiClientModule()
    {
        this.startAsync = true;
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

        this.client = new ConSysApiClient.ConSysApiClientBuilder(apiEndpointUrl)
                .simpleAuth(config.conSys.user, config.conSys.password.toCharArray())
                .build();

        // TODO: Other initialization
    }

    @Override
    protected void doStart() throws SensorHubException
    {
        System.out.println(client);
        dataBaseView.getSystemDescStore().select(dataBaseView.getSystemDescStore().selectAllFilter())
                .forEach((system) -> {
                    System.out.println(system.getName());
                    var newSys = client.addSystem(system);
                    System.out.println(newSys);
                });

        // TODO: Ensure connection can be made

        // TODO: Subscribe to system registry

        // TODO: Register systems/subsystems to destination SensorHub
        // TODO: Register datastreams to destination
        // TODO: Push observations from datastreams
    }

    @Override
    public boolean isConnected()
    {
        return false;
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
}
