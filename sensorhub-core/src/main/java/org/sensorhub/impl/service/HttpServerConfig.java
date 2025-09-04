/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service;

import org.sensorhub.api.config.DisplayInfo;
import org.sensorhub.api.config.DisplayInfo.FieldType.Type;
import org.sensorhub.api.module.ModuleConfig;

import java.util.ArrayList;
import java.util.List;


/**
 * <p>
 * Configuration class for the HTTP server module
 * </p>
 *
 * @author Alex Robin
 * @since Sep 6, 2013
 */
public class HttpServerConfig extends ModuleConfig
{
    public enum AuthMethod
    {
        NONE, BASIC, DIGEST, CERT,
        EXTERNAL // provided by external module
    }
    
    @DisplayInfo(label="HTTP Port", desc="TCP port where server will listen for unsecure HTTP connections (use 0 to disable HTTP).")
    public int httpPort = 8080;
    
    
    @DisplayInfo(label="HTTPS Port", desc="TCP port where server will listen for secure HTTP (HTTPS) connections (use 0 to disable HTTPS).")
    public int httpsPort = 0;
    

    @DisplayInfo(label = "Static File Servers", desc = "Static web content configurations")
    public List<FileServerConfig> fileServerConfigs = new ArrayList<>(List.of(new FileServerConfig(
                            "/",
                            "web",
                            false)));

    
    @DisplayInfo(desc="Root URL where the server will accept requests. This will be the prefix to all servlet URLs.")
    public String servletsRootUrl = "/sensorhub";
    
    
    @DisplayInfo(label="Proxy Base URL", desc="Public URL as viewed from the outside when requests transit through a proxy server.")
    public String proxyBaseUrl = null;
    
    
    @DisplayInfo(label="Authentication Method", desc="Method used to authenticate users on this server")
    public AuthMethod authMethod = AuthMethod.NONE;
    
    
    @DisplayInfo(desc="Path to a key store containing the certificate and keypair that this server will present to clients when accessed over HTTPS. " +
    		"If this value is blank, will default to using the value of the \"javax.net.ssl.keyStore\" system property. " +
    		"This value can use variable expansion expressions of the form \"$${name}\" (for environment variables and system properties) or \"$${file;/path/to/file}\" (for secret file contents).")
    public String keyStorePath;
    
    
    @DisplayInfo(desc="Password for the key store (and for the keypair within the keystore). " +
    		"If this value is blank, will default to using the value of the \"javax.net.ssl.keyStorePassword\" system property. " +
    		"This value can use variable expansion expressions of the form \"$${name}\" (for environment variables and system properties) or \"$${file;/path/to/file}\" (for secret file contents).")
    @DisplayInfo.FieldType(Type.PASSWORD)
    public String keyStorePassword;
    
    
    @DisplayInfo(desc="Alias for the public/private keypair within the key store that will be used to identify this server.")
    public String keyAlias = "jetty";

    
    @DisplayInfo(desc="Path to the TLS trust store that is used when client authentication is required. " +
    		"Ignored if client certificate authentication is not used. " +
    		"Certificates in this file designate the signing authorities for client certificates that will be trusted. " +
    		"If this value is blank, will default to using the value of the \"javax.net.ssl.trustStore\" system property. " +
			"This value can use variable expansion expressions of the form \"$${name}\" (for environment variables and system properties) or \"$${file;/path/to/file}\" (for secret file contents).")
    public String trustStorePath;
    
    
    @DisplayInfo(desc="Password for the trust store. " +
    		"Ignored if client certificate authentication is not used. " +
    		"If this value is blank, will default to using the value of the \"javax.net.ssl.trustStorePassword\" system property. " +
    		"This value can use variable expansion expressions of the form \"$${name}\" (for environment variables and system properties) or \"$${file;/path/to/file}\" (for secret file contents).")
    @DisplayInfo.FieldType(Type.PASSWORD)
    public String trustStorePassword;
    
    
    @DisplayInfo(desc="Path to external config file (in Jetty IOC XML format)")
    public String xmlConfigFile;
    
    
    @DisplayInfo(label="Enable CORS", desc="Enable generation of CORS headers to allow cross-domain requests from browsers")
    public boolean enableCORS = true;
    

    public HttpServerConfig()
    {
        this.id = "HTTP_SERVER_0";
        this.name = "HTTP Server";
        this.moduleClass = HttpServer.class.getCanonicalName();
        this.autoStart = true;
    }
    
    
    public int getHttpPort()
    {
        return httpPort;
    }


    public void setHttpPort(int httpPort)
    {
        this.httpPort = httpPort;
    }


    public String getServletsRootUrl()
    {
        return servletsRootUrl;
    }


    public void setServletsRootUrl(String rootURL)
    {
        this.servletsRootUrl = rootURL;
    }
    
}
