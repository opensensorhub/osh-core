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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.security.authentication.ClientCertAuthenticator;
import org.eclipse.jetty.security.authentication.DigestAuthenticator;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.servlet.*;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.sensorhub.api.security.ISecurityManager;
import org.sensorhub.api.service.IHttpServer;
import org.sensorhub.impl.module.AbstractModule;
import org.sensorhub.impl.service.HttpServerConfig.AuthMethod;
import org.sensorhub.utils.ModuleUtils;
import org.vast.util.Asserts;

import com.google.common.base.Strings;


/**
 * <p>
 * Wrapper module for the HTTP server engine (Jetty for now)
 * </p>
 *
 * @author Alex Robin
 * @since Sep 6, 2013
 */
public class HttpServer extends AbstractModule<HttpServerConfig> implements IHttpServer<HttpServerConfig>
{
    private static final String OSH_SERVER_ID = "osh-server";
    private static final String OSH_HANDLERS = "osh-handlers";
    private static final String OSH_HTTP_CONNECTOR_ID = "osh-http";
    private static final String OSH_HTTPS_CONNECTOR_ID = "osh-https";
    private static final String OSH_STATIC_CONTENT_ID = "osh-static";
    private static final String OSH_SERVLET_HANDLER_ID = "osh-servlets";
    
    private static final String[] SECURITY_EXCLUDED_METHODS = {"OPTIONS"};
    private static final String CORS_ALLOWED_METHODS = "GET, POST, PUT, DELETE, PATCH, OPTIONS";
    private static final String CORS_ALLOWED_HEADERS = "origin, content-type, accept, authorization";
    private static final String CORS_EXPOSE_HEADERS = "location, link";
    
    public static final String TEST_MSG = "SensorHub web server is up";
        
    Server server;
    ServletContextHandler servletHandler;
    ConstraintSecurityHandler jettySecurityHandler;
    
    
    public HttpServer()
    {
    }

    
    @Override
    public synchronized void updateConfig(HttpServerConfig config) throws SensorHubException
    {
        boolean accessControlEnabled = getParentHub().getSecurityManager().isAccessControlEnabled();
        if (!accessControlEnabled && config.authMethod != null && config.authMethod != AuthMethod.NONE)
        {
            reportError("Cannot enable authentication if no user registry is setup", null);
            return;
        }
        
        super.updateConfig(config);
    }


    @Override
    protected synchronized void doStart() throws SensorHubException
    {
        try
        {
            server = new Server();
            ServerConnector http = null;
            ServerConnector https = null;
            HandlerList handlers = new HandlerList();
            
            // HTTP connector
            HttpConfiguration httpConfig = new HttpConfiguration();
            httpConfig.setSendServerVersion(false);
            httpConfig.setSecureScheme("https");
            httpConfig.setSecurePort(config.httpsPort);
            if (config.httpPort > 0)
            {
                http = new ServerConnector(server,
                        new HttpConnectionFactory(httpConfig));
                http.setPort(config.httpPort);
                http.setIdleTimeout(300000);
                server.addConnector(http);
            }
            
            // HTTPS connector
            if (config.httpsPort > 0)
            {
                KeyStoreInfo keyStoreInfo = getKeyStoreInfo(config);
                
                SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
                sslContextFactory.setKeyStorePath(new File(keyStoreInfo.getKeyStorePath()).getAbsolutePath());
                sslContextFactory.setKeyStorePassword(keyStoreInfo.getKeyStorePassword());
                sslContextFactory.setKeyManagerPassword(keyStoreInfo.getKeyStorePassword());
                sslContextFactory.setCertAlias(keyStoreInfo.getKeyAlias());
                if (config.authMethod == AuthMethod.CERT)
                {
                    TrustStoreInfo trustStoreInfo = getTrustStoreInfo(config);
                    sslContextFactory.setTrustStorePath(new File(trustStoreInfo.getTrustStorePath()).getAbsolutePath());
                    sslContextFactory.setTrustStorePassword(trustStoreInfo.getTrustStorePassword());
                    sslContextFactory.setWantClientAuth(true);
                }
                HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
                httpsConfig.setSendServerVersion(false);
                httpsConfig.addCustomizer(new SecureRequestCustomizer());
                https = new ServerConnector(server, 
                        new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()),
                        new HttpConnectionFactory(httpsConfig));
                https.setPort(config.httpsPort);
                https.setIdleTimeout(300000);
                server.addConnector(https);
            }
            
            // static content
            ContextHandler fileResourceContext = null;
            if (config.staticDocsRootUrl != null)
            {
                ResourceHandler fileResourceHandler = new ResourceHandler();
                fileResourceHandler.setEtags(true);
                
                fileResourceContext = new ContextHandler();
                fileResourceContext.setContextPath(config.staticDocsRootUrl);
                //fileResourceContext.setAllowNullPathInfo(true);
                fileResourceContext.setHandler(fileResourceHandler);
                fileResourceContext.setResourceBase(config.staticDocsRootDir);

                //fileResourceContext.clearAliasChecks();
                //fileResourceContext.addAliasCheck(new SymlinkAllowedResourceAliasChecker(fileResourceContext));
                
                handlers.addHandler(fileResourceContext);
                getLogger().info("Static resources root is " + config.staticDocsRootUrl);
            }
            
            // servlets
            if (config.servletsRootUrl != null)
            {
                // create servlet handler
                this.servletHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
                servletHandler.setContextPath(config.servletsRootUrl);
                handlers.addHandler(servletHandler);
                getLogger().info("Servlets root is " + config.servletsRootUrl);
                
                // security handler
                if (config.authMethod != null && config.authMethod != AuthMethod.NONE)
                {
                    jettySecurityHandler = new ConstraintSecurityHandler();
                    
                    // create login service connected to OSH security manager
                    ISecurityManager securityManager = getParentHub().getSecurityManager();
                    OshLoginService loginService = new OshLoginService(securityManager);
                    
                    if (config.authMethod == AuthMethod.BASIC)
                        jettySecurityHandler.setAuthenticator(new HttpLogoutWrapper(new BasicAuthenticator(), getLogger()));
                    else if (config.authMethod == AuthMethod.DIGEST)
                        jettySecurityHandler.setAuthenticator(new HttpLogoutWrapper(new DigestAuthenticator(), getLogger()));
                    else if (config.authMethod == AuthMethod.CERT)
                        jettySecurityHandler.setAuthenticator(new HttpLogoutWrapper(new ClientCertAuthenticator(), getLogger()));
                    else if (config.authMethod == AuthMethod.EXTERNAL)
                    {
                        Authenticator authenticator = securityManager.getAuthenticator();
                        if (authenticator == null)
                            throw new IllegalStateException("External authentication method was selected but no authenticator implementation is available");
                        jettySecurityHandler.setAuthenticator(authenticator);
                    }
                    
                    jettySecurityHandler.setLoginService(loginService);
                    servletHandler.setSecurityHandler(jettySecurityHandler);
                }
                
                // filter to add proper cross-origin headers
                if (config.enableCORS)
                {
                    FilterHolder holder = servletHandler.addFilter(CrossOriginFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
                    holder.setInitParameter("allowedMethods", CORS_ALLOWED_METHODS);
                    holder.setInitParameter("allowedHeaders", CORS_ALLOWED_HEADERS);
                    holder.setInitParameter("exposedHeaders", CORS_EXPOSE_HEADERS);
                }
                
                // add default test servlet
                servletHandler.addServlet(new ServletHolder(new HttpServlet() {
                    private static final long serialVersionUID = 1L;
                    @Override
                    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException
                    {
                        try
                        {
                            resp.getOutputStream().print(TEST_MSG);
                        }
                        catch (IOException e)
                        {
                            try
                            {
                                getLogger().error("Cannot send test message", e);
                                resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                            }
                            catch (IOException e1)
                            {
                                getLogger().trace("Cannot send HTTP error code", e1);
                            }
                        }
                    }
                }),"/test");
                addServletSecurity("/test", false);
            }
            
            server.setHandler(handlers);
            
            // also load external xml config file if any
            if (config.xmlConfigFile != null)
            {
                try
                {
                    Resource configFile = Resource.newResource(new File(config.xmlConfigFile));
                    XmlConfiguration xmlConfig = new XmlConfiguration(configFile);
                    
                    // assign IDs to existing beans so they can be reconfigured
                    xmlConfig.getIdMap().put(OSH_SERVER_ID, server);
                    xmlConfig.getIdMap().put(OSH_HANDLERS, handlers);
                    if (http != null)
                        xmlConfig.getIdMap().put(OSH_HTTP_CONNECTOR_ID, http);
                    if (https != null)
                        xmlConfig.getIdMap().put(OSH_HTTPS_CONNECTOR_ID, https);
                    if (fileResourceContext != null)
                        xmlConfig.getIdMap().put(OSH_STATIC_CONTENT_ID, fileResourceContext);
                    if (servletHandler != null)
                        xmlConfig.getIdMap().put(OSH_SERVLET_HANDLER_ID, servletHandler);
                    
                    // append xml config
                    xmlConfig.configure();
                }
                catch (Exception e)
                {
                    throw new IOException("Cannot configure Jetty using external XML file", e);
                }
            }            
            
            server.start();
            getLogger().info("HTTP server started on port " + config.httpPort);
            
            server.getErrorHandler().setShowServlet(false);
            setState(ModuleState.STARTED);
        }
        catch (Exception e)
        {
            throw new SensorHubException("Cannot start embedded HTTP server", e);
        }
    }

    @Override
    protected void afterStart() throws SensorHubException {
        super.afterStart();


        if(servletHandler != null){
            ErrorPageErrorHandler errorHandler = new ErrorPageErrorHandler();
            errorHandler.addErrorPage(400, "/error/invalid");
            errorHandler.addErrorPage(403, "/error/forbidden");
            errorHandler.addErrorPage(404, "/error/notfound");

            servletHandler.setErrorHandler(errorHandler);

        }else{
            getLogger().warn("Servlet Handler is not initialized, cannot create error pages");
        }

    }

    @Override
    protected synchronized void doStop() throws SensorHubException
    {
        try
        {
            if (server != null)
            {
                server.stop();
                servletHandler = null;
                jettySecurityHandler = null;
                server = null;
            }
        }
        catch (Exception e)
        {
            throw new SensorHubException("Error while stopping SensorHub embedded HTTP server", e);
        }
    }
    
    
    protected void checkStarted()
    {
        if (!isStarted())
            throw new IllegalStateException("HTTP service must be started before servlets can be deployed");
    }
    
    
    public void deployServlet(HttpServlet servlet, String path)
    {
        deployServlet(servlet, null, path);
    }
    
    
    public synchronized void deployServlet(HttpServlet servlet, Map<String, String> initParams, String... paths)
    {
        checkStarted();
        
        ServletHolder holder = new ServletHolder(servlet);
        if (initParams != null)
            holder.setInitParameters(initParams);
        
        ServletMapping mapping = new ServletMapping();
        mapping.setServletName(holder.getName());
        mapping.setPathSpecs(paths);
        
        servletHandler.getServletHandler().addServlet(holder);
        servletHandler.getServletHandler().addServletMapping(mapping);
        getLogger().debug("Servlet deployed " + mapping.toString());
    }
    
    
    public synchronized void undeployServlet(HttpServlet servlet)
    {
        // silently do nothing if server has already been shutdown
        if (servletHandler == null)
            return;
        
        try
        {
            // there is no removeServlet method so we need to do it manually
            ServletHandler handler = servletHandler.getServletHandler();
            
            // first collect servlets we want to keep
            List<ServletHolder> servlets = new ArrayList<ServletHolder>();
            String nameToRemove = null;
            for( ServletHolder holder : handler.getServlets() )
            {
                if (holder.getServlet() != servlet)
                    servlets.add(holder);
                else
                    nameToRemove = holder.getName();
            }

            if (nameToRemove == null)
                return;
            
            // also update servlet path mappings
            List<ServletMapping> mappings = new ArrayList<ServletMapping>();
            for (ServletMapping mapping : handler.getServletMappings())
            {
                if (!nameToRemove.contains(mapping.getServletName()))
                    mappings.add(mapping);
            }

            // set the new configuration
            handler.setServletMappings( mappings.toArray(new ServletMapping[0]) );
            handler.setServlets( servlets.toArray(new ServletHolder[0]) );
        }
        catch (ServletException e)
        {
            getLogger().error("Error while undeploying servlet", e);
        }
    }
    
    
    public void addServletSecurity(String pathSpec, boolean requireAuth)
    {
        addServletSecurity(pathSpec, requireAuth, Constraint.ANY_AUTH);
    }
    
    
    public synchronized void addServletSecurity(String pathSpec, boolean requireAuth, String... roles)
    {
        if (jettySecurityHandler != null)
        {
            Constraint constraint = new Constraint();
            constraint.setRoles(roles);
            constraint.setAuthenticate(requireAuth);
            ConstraintMapping cm = new ConstraintMapping();
            cm.setConstraint(constraint);
            cm.setPathSpec(pathSpec);
            cm.setMethodOmissions(SECURITY_EXCLUDED_METHODS); // disable auth on OPTIONS requests (needed for CORS)
            jettySecurityHandler.addConstraintMapping(cm);
        }
    }


    public String getServerBaseUrl()
    {
        String baseUrl = "";
        if (!Strings.isNullOrEmpty(config.proxyBaseUrl))
            baseUrl = config.proxyBaseUrl;
        else if (config.httpPort > 0)
            baseUrl = "http://localhost" + (config.httpPort != 80 ? ":" + config.httpPort : "");
        else if (config.httpsPort > 0)
            baseUrl = "https://localhost" + (config.httpsPort != 443 ? ":" + config.httpsPort : "");
        
        return baseUrl;
    }
    
    
    public String getServletsBaseUrl()
    {
        var baseUrl = getServerBaseUrl();
        
        if (config.servletsRootUrl != null)
            baseUrl = appendToUrlPath(baseUrl, config.servletsRootUrl);
        
        return appendToUrlPath(baseUrl, "");
    }
    
    
    public String getPublicEndpointUrl(String path)
    {
        return appendToUrlPath(getServletsBaseUrl(), path);
    }
    
    
    private String appendToUrlPath(String url, String nextPart)
    {
        if (url.endsWith("/"))
            url = url.substring(0, url.length()-1);
        
        return url + (nextPart.startsWith("/") ? nextPart : "/" + nextPart);
    }
    
    
    public Server getJettyServer()
    {
        return server;
    }
    
    private static KeyStoreInfo getKeyStoreInfo(HttpServerConfig config) {
    	String keyStorePath = ModuleUtils.expand(config.keyStorePath);
    	if ((keyStorePath == null) || (keyStorePath.length() == 0) || (keyStorePath.trim().length() == 0)) {
    		keyStorePath = System.getProperty("javax.net.ssl.keyStore");
    	}
  		Asserts.checkNotNullOrBlank(keyStorePath, "Either the key store path or the \"javax.net.ssl.keyStore\" system property must be specified.");
  		
  		String keyStorePassword = ModuleUtils.expand(config.keyStorePassword);
  		if ((keyStorePassword == null) || (keyStorePassword.length() == 0)) {
  			keyStorePassword = System.getProperty("javax.net.ssl.keyStorePassword");
  		}
  		Asserts.checkNotNullOrEmpty(keyStorePassword, "Key store password must be supplied.");
  		
  		String keyAlias = ModuleUtils.expand(config.keyAlias);
  		Asserts.checkNotNullOrEmpty(keyAlias, "Key alias must be supplied");
  		
  		return new KeyStoreInfo(keyStorePath, keyStorePassword, keyAlias);
    }
    
    private static TrustStoreInfo getTrustStoreInfo(HttpServerConfig config) {
    	String trustStorePath = ModuleUtils.expand(config.trustStorePath);
    	if ((trustStorePath == null) || (trustStorePath.length() == 0) || (trustStorePath.trim().length() == 0)) {
    		trustStorePath = System.getProperty("javax.net.ssl.trustStore");
    	}
  		Asserts.checkNotNullOrBlank(trustStorePath, "Either the trust store path or the \"javax.net.ssl.trustStore\" system property must be specified.");
  		
  		String trustStorePassword = ModuleUtils.expand(config.trustStorePassword);
  		if ((trustStorePassword == null) || (trustStorePassword.length() == 0)) {
  			trustStorePassword = System.getProperty("javax.net.ssl.trustStorePassword");
  		}
  		Asserts.checkNotNullOrEmpty(trustStorePassword, "Trust store password must be supplied.");
  		
  		return new TrustStoreInfo(trustStorePath, trustStorePassword);
    }
    
    private static class KeyStoreInfo {
    	private final String keyStorePath;
    	private final String keyStorePassword;
    	private final String keyAlias;
		public KeyStoreInfo(String keyStorePath, String keyStorePassword, String keyAlias) {
			this.keyStorePath = keyStorePath;
			this.keyStorePassword = keyStorePassword;
			this.keyAlias = keyAlias;
		}
		public String getKeyStorePath() {
			return keyStorePath;
		}
		public String getKeyStorePassword() {
			return keyStorePassword;
		}
		public String getKeyAlias() {
			return keyAlias;
		}
    }

    private static class TrustStoreInfo {
    	private final String trustStorePath;
    	private final String trustStorePassword;
		public TrustStoreInfo(String trustStorePath, String trustStorePassword) {
			this.trustStorePath = trustStorePath;
			this.trustStorePassword = trustStorePassword;
		}
		public String getTrustStorePath() {
			return trustStorePath;
		}
		public String getTrustStorePassword() {
			return trustStorePassword;
		}
    }

    @Override
    public boolean isAuthEnabled()
    {
        return config.authMethod != AuthMethod.NONE;
    }
}
