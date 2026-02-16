package org.sensorhub.impl.service.consys.client;

import org.sensorhub.api.config.DisplayInfo;

public class ConSysOAuthConfig {

    @DisplayInfo(label = "Use OAuth Authentication")
    public boolean oAuthEnabled = true;

    @DisplayInfo(label="Token Endpoint", desc="URL of OAuth provider's token endpoint")
    public String tokenEndpoint;

    @DisplayInfo(desc="Client ID as provided by your OAuth provider")
    public String clientID;


    @DisplayInfo(desc="Client Secret as provided by your OAuth provider")
    public String clientSecret;
}
