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
import java.io.FileInputStream;
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
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.AllowSymLinkAliasChecker;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.sensorhub.api.security.ISecurityManager;
import org.sensorhub.impl.SensorHub;
import org.sensorhub.impl.module.AbstractModule;
import org.sensorhub.impl.service.HttpServerConfig.AuthMethod;
import org.vast.util.Asserts;


/**
 * <p>
 * Wrapper module for the HTTP server engine (Jetty for now)
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since Sep 6, 2013
 */
public class HttpServer extends AbstractModule<HttpServerConfig>
{
    private static final String OSH_SERVER_ID = "osh-server";
    private static final String OSH_HANDLERS = "osh-handlers";
    private static final String OSH_HTTP_CONNECTOR_ID = "osh-http";
    private static final String OSH_HTTPS_CONNECTOR_ID = "osh-https";
    private static final String OSH_STATIC_CONTENT_ID = "osh-static";
    private static final String OSH_SERVLET_HANDLER_ID = "osh-servlets";

    private static final String[] SECURITY_EXCLUDED_METHODS = {"OPTIONS"};
    private static final String CORS_ALLOWED_METHODS = "GET, POST, PUT, DELETE, OPTIONS";
    private static final String CORS_ALLOWED_HEADERS = "origin, content-type, accept, authorization";
    
    private static final String CERT_ALIAS = "jetty";
    public static final String TEST_MSG = "SensorHub web server is up";
    private static HttpServer instance;
        
    Server server;
    ServletContextHandler servletHandler;
    ConstraintSecurityHandler jettySecurityHandler;
    
    
    public HttpServer()
    {
        if (instance != null)
            throw new IllegalStateException("Cannot start several HTTP server instances");
        
        instance = this;
    }
    
    
    public static HttpServer getInstance()
    {
        return instance;
    }

    
    @Override
    public synchronized void updateConfig(HttpServerConfig config) throws SensorHubException
    {
        boolean accessControlEnabled = SensorHub.getInstance().getSecurityManager().isAccessControlEnabled();
        if (!accessControlEnabled && config.authMethod != null && config.authMethod != AuthMethod.NONE)
        {
            reportError("Cannot enable authentication if no user registry is setup", null);
            return;
        }
        
        super.updateConfig(config);
    }


    @Override
    public synchronized void start() throws SensorHubException
    {
        try
        {
            server = new Server();
            ServerConnector http = null;
            ServerConnector https = null;
            HandlerList handlers = new HandlerList();
            
            // HTTP connector
            HttpConfiguration httpConfig = new HttpConfiguration();
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
                Asserts.checkNotNull(config.keyStorePath, "The keystore path must be set when HTTPS is used");
                Asserts.checkNotNull(config.trustStorePath, "The trust store path must be set when HTTPS is used");
                Asserts.checkNotNull(config.keyStorePassword, "The keystore password must be set when HTTPS is used");
                
                SslContextFactory sslContextFactory = new SslContextFactory();
                sslContextFactory.setKeyStorePath(new File(config.keyStorePath).getAbsolutePath());
                sslContextFactory.setKeyStorePassword(config.keyStorePassword);
                sslContextFactory.setKeyManagerPassword(config.keyStorePassword);
                sslContextFactory.setCertAlias(CERT_ALIAS);
                sslContextFactory.setTrustStorePath(new File(config.trustStorePath).getAbsolutePath());
                sslContextFactory.setTrustStorePassword(config.keyStorePassword);
                sslContextFactory.setWantClientAuth(true);
                HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
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
            if (config.staticDocRootUrl != null)
            {
                ResourceHandler fileResourceHandler = new ResourceHandler();
                fileResourceHandler.setEtags(true);
                
                fileResourceContext = new ContextHandler();
                fileResourceContext.setContextPath("/");
                //fileResourceContext.setAllowNullPathInfo(true);
                fileResourceContext.setHandler(fileResourceHandler);
                fileResourceContext.setResourceBase(config.staticDocRootUrl);

                //fileResourceContext.clearAliasChecks();
                fileResourceContext.addAliasCheck(new AllowSymLinkAliasChecker());
                
                handlers.addHandler(fileResourceContext);
                getLogger().info("Static resources root is " + config.staticDocRootUrl);
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
                    
                    // load user list
                    ISecurityManager securityManager = SensorHub.getInstance().getSecurityManager();
                    OshLoginService loginService = new OshLoginService(securityManager);
                    
                    if (config.authMethod == AuthMethod.BASIC)
                        jettySecurityHandler.setAuthenticator(new BasicAuthenticator());
                    else if (config.authMethod == AuthMethod.DIGEST)
                        jettySecurityHandler.setAuthenticator(new DigestAuthenticator());
                    else if (config.authMethod == AuthMethod.CERT)
                        jettySecurityHandler.setAuthenticator(new ClientCertAuthenticator());
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
                    FileInputStream is = new FileInputStream(config.xmlConfigFile);
                    XmlConfiguration xmlConfig = new XmlConfiguration(is);
                    
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
            
            setState(ModuleState.STARTED);
        }
        catch (Exception e)
        {
            throw new SensorHubException("Cannot start embedded HTTP server", e);
        }
    }
    
    
    @Override
    public synchronized void stop() throws SensorHubException
    {
        try
        {
            if (server != null)
            {
                server.stop();
                servletHandler = null;
                jettySecurityHandler = null;
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


    @Override
    public synchronized void cleanup() throws SensorHubException
    {
        server = null;
        instance = null;
    }
    
    
    public Server getJettyServer()
    {
        return server;
    }
}
