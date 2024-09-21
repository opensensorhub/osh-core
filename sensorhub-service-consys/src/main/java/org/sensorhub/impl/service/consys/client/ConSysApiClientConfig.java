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
    public ConSysConnectionConfig connection = new ConSysConnectionConfig();


    public static class ConSysConnectionConfig extends RobustIPConnectionConfig
    {
        @DisplayInfo(desc="Enable to use a persistent HTTP connection for InsertResult")
        public boolean usePersistentConnection;


        @DisplayInfo(desc="Maximum number of records in upload queue (used to compensate for variable bandwidth)")
        public int maxQueueSize = 10;


        @DisplayInfo(desc="Maximum number of stream errors before we try to reconnect to remote server")
        public int maxConnectErrors = 10;
    }


    public ConSysApiClientConfig()
    {
        this.moduleClass = ConSysApiClientModule.class.getCanonicalName();
        this.conSys.resourcePath = "/sensorhub/api";
    }

}
