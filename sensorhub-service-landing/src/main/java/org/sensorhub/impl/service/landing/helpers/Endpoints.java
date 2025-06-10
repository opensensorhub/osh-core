package org.sensorhub.impl.service.landing.helpers;

/**
 *
 * @author Kalyn Stricklin
 * @since February 2025
 */
public enum Endpoints {
    ADMIN("/admin"),
    SOS("/sos?service=SOS&version=2.0&request=GetCapabilities"),
    API("/api"),
    DISCOVERY("/discovery/rules");


    private final String path;

    Endpoints(String path){
        this.path = path;
    }

    public String getPath(){
        return path;
    }

}
