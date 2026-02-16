package org.sensorhub.impl.service.consys.client;

import org.sensorhub.api.client.ClientConfig;
import org.sensorhub.api.config.DisplayInfo;
import org.sensorhub.impl.comm.HTTPConfig;
import org.sensorhub.impl.comm.RobustIPConnectionConfig;
import org.sensorhub.impl.datastore.view.ObsSystemDatabaseViewConfig;

public class ConSysApiClientConfig extends ClientConfig {

    @DisplayInfo(desc="Filtered view to select systems/datastreams to register with Connected Systems")
    @DisplayInfo.Required
    public ObsSystemDatabaseViewConfig dataSourceSelector;


    @DisplayInfo(label="Connected Systems Endpoint", desc="Connected Systems endpoint where the requests are sent")
    public HTTPConfig conSys = new HTTPConfig();


    @DisplayInfo(label="Connection Options")
    public RobustIPConnectionConfig connection = new RobustIPConnectionConfig();

    @DisplayInfo(label="OAuth Options", desc="Allows for the usage of OAuth Client Credentials (\"bearer\") tokens for instead of basic authentication")
    public ConSysOAuthConfig conSysOAuth = new ConSysOAuthConfig();

    public ConSysApiClientConfig()
    {
        this.moduleClass = ConSysApiClientModule.class.getCanonicalName();
        this.conSys.resourcePath = "/sensorhub/api";
    }

}
