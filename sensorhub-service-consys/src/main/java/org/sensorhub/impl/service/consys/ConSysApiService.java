/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys;

import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.procedure.ProcedureFilter;
import org.sensorhub.api.event.IEventListener;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.sensorhub.api.service.IServiceModule;
import org.sensorhub.impl.database.registry.FilteredFederatedDatabase;
import org.sensorhub.impl.module.ModuleRegistry;
import org.sensorhub.impl.service.AbstractHttpServiceModule;
import org.sensorhub.impl.service.consys.deployment.DeploymentHandler;
import org.sensorhub.impl.service.consys.deployment.DeploymentMembersHandler;
import org.sensorhub.impl.service.consys.feature.FoiHandler;
import org.sensorhub.impl.service.consys.feature.FoiHistoryHandler;
import org.sensorhub.impl.service.consys.home.CollectionHandler;
import org.sensorhub.impl.service.consys.home.ConformanceHandler;
import org.sensorhub.impl.service.consys.home.HomePageHandler;
import org.sensorhub.impl.service.consys.obs.CustomObsFormat;
import org.sensorhub.impl.service.consys.obs.DataStreamHandler;
import org.sensorhub.impl.service.consys.obs.DataStreamSchemaHandler;
import org.sensorhub.impl.service.consys.obs.ObsHandler;
import org.sensorhub.impl.service.consys.obs.ObsStatsHandler;
import org.sensorhub.impl.service.consys.procedure.ProcedureHandler;
import org.sensorhub.impl.service.consys.property.PropertyHandler;
import org.sensorhub.impl.service.consys.resource.JarStaticResourceHandler;
import org.sensorhub.impl.service.consys.system.SystemHandler;
import org.sensorhub.impl.service.consys.system.SystemHistoryHandler;
import org.sensorhub.impl.service.consys.system.SystemMembersHandler;
import org.sensorhub.impl.service.consys.task.CommandHandler;
import org.sensorhub.impl.service.consys.task.CommandResultHandler;
import org.sensorhub.impl.service.consys.task.CommandStatusHandler;
import org.sensorhub.impl.service.consys.task.CommandStreamHandler;
import org.sensorhub.impl.service.consys.task.CommandStreamSchemaHandler;
import org.sensorhub.utils.NamedThreadFactory;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;


/**
 * <p>
 * Implementation of SensorHub SWE API service.<br/>
 * The service can be configured to expose some or all of the systems and
 * observations available on the hub.
 * </p>
 *
 * @author Alex Robin
 * @since Oct 12, 2020
 */
public class ConSysApiService extends AbstractHttpServiceModule<ConSysApiServiceConfig> implements RestApiService, IServiceModule<ConSysApiServiceConfig>, IEventListener
{
    protected ConSysApiServlet servlet;
    ScheduledExecutorService threadPool;
    
    static final Set<String> CONF_CLASSES = ImmutableSet.of(
        "http://www.opengis.net/spec/ogcapi-common-1/1.0/conf/core",
        "http://www.opengis.net/spec/ogcapi-common-1/1.0/conf/html",
        "http://www.opengis.net/spec/ogcapi-common-1/1.0/conf/json",
        "http://www.opengis.net/spec/ogcapi-common-1/1.0/conf/oas30",
        "http://www.opengis.net/spec/ogcapi-common-2/0.0/conf/collections",
        "http://www.opengis.net/spec/ogcapi-common-2/0.0/conf/html",
        "http://www.opengis.net/spec/ogcapi-common-2/0.0/conf/json",
        
        "http://www.opengis.net/spec/ogcapi-features-1/1.0/conf/core",
        "http://www.opengis.net/spec/ogcapi-features-1/1.0/conf/geojson",
        "http://www.opengis.net/spec/ogcapi-features-1/1.0/conf/html",
        "http://www.opengis.net/spec/ogcapi-features-4/1.0/conf/create-replace-delete",
        
        "http://www.opengis.net/spec/ogcapi-connectedsystems-1/1.0/conf/core",
        "http://www.opengis.net/spec/ogcapi-connectedsystems-1/1.0/conf/system",
        "http://www.opengis.net/spec/ogcapi-connectedsystems-1/1.0/conf/subsystem",
        "http://www.opengis.net/spec/ogcapi-connectedsystems-1/1.0/conf/deployment",
        "http://www.opengis.net/spec/ogcapi-connectedsystems-1/1.0/conf/subdeployment",
        "http://www.opengis.net/spec/ogcapi-connectedsystems-1/1.0/conf/procedure",
        "http://www.opengis.net/spec/ogcapi-connectedsystems-1/1.0/conf/sf",
        "http://www.opengis.net/spec/ogcapi-connectedsystems-1/1.0/conf/property",
        "http://www.opengis.net/spec/ogcapi-connectedsystems-1/1.0/conf/create-replace-delete",
        "http://www.opengis.net/spec/ogcapi-connectedsystems-1/1.0/conf/geojson",
        "http://www.opengis.net/spec/ogcapi-connectedsystems-1/1.0/conf/sensorml",
        
        "http://www.opengis.net/spec/ogcapi-connectedsystems-2/1.0/conf/datastream",
        "http://www.opengis.net/spec/ogcapi-connectedsystems-2/1.0/conf/controlstream",
        "http://www.opengis.net/spec/ogcapi-connectedsystems-2/1.0/conf/system-history",
        "http://www.opengis.net/spec/ogcapi-connectedsystems-2/1.0/conf/system-event",
        "http://www.opengis.net/spec/ogcapi-connectedsystems-2/1.0/conf/create-replace-delete",
        "http://www.opengis.net/spec/ogcapi-connectedsystems-2/1.0/conf/json",
        "http://www.opengis.net/spec/ogcapi-connectedsystems-2/1.0/conf/swecommon-json",
        "http://www.opengis.net/spec/ogcapi-connectedsystems-2/1.0/conf/swecommon-text",
        "http://www.opengis.net/spec/ogcapi-connectedsystems-2/1.0/conf/swecommon-binary",
        
        "http://www.opengis.net/spec/ogcapi-connectedsystems-3/1.0/conf/websocket",
        "http://www.opengis.net/spec/ogcapi-connectedsystems-3/1.0/conf/mqtt"
    );


    @Override
    public void setConfiguration(ConSysApiServiceConfig config)
    {
        super.setConfiguration(config);
    }


    @Override
    protected void doStart() throws SensorHubException
    {
        IObsSystemDatabase readDb;
        IObsSystemDatabase writeDb;
        
        // get handle to obs system database
        if (!Strings.isNullOrEmpty(config.databaseID))
        {
            writeDb = (IObsSystemDatabase)getParentHub().getModuleRegistry()
                .getModuleById(config.databaseID);
            if (writeDb != null && !writeDb.isOpen())
                writeDb = null;
        }
        else
            writeDb = getParentHub().getSystemDriverRegistry().getSystemStateDatabase();
        
        // get existing or create new FilteredView from config
        if (config.exposedResources != null)
        {
            if (writeDb != null)
            {
                var obsFilter = config.exposedResources.getObsFilter();
                var cmdFilter = config.exposedResources.getCommandFilter();
                readDb = new FilteredFederatedDatabase(
                    getParentHub().getDatabaseRegistry(),
                    obsFilter, cmdFilter, new ProcedureFilter.Builder().build(), writeDb.getDatabaseNum());
            }
            else
                readDb = config.exposedResources.getFilteredView(getParentHub());
        }
        else
            readDb = getParentHub().getDatabaseRegistry().getFederatedDatabase();

        // init security
        this.securityHandler = new ConSysApiSecurity(this, readDb, config.security.enableAccessControl);
        
        // init thread pool
        threadPool = Executors.newScheduledThreadPool(
            Runtime.getRuntime().availableProcessors(),
            new NamedThreadFactory("SWAPool"));

        // init timeout monitor
        //timeOutMonitor = new TimeOutMonitor();
        
        // load custom formats
        Map<String, CustomObsFormat> customFormats = new HashMap<>();
        for (var formatConfig: config.customFormats)
        {
            try
            {
                // find impl for this mime type
                ModuleRegistry moduleReg = getParentHub().getModuleRegistry();
                var clazz = moduleReg.<CustomObsFormat>findClass(formatConfig.className);
                var formatImpl = clazz.getDeclaredConstructor().newInstance();
                customFormats.put(formatConfig.mimeType, formatImpl);
                getLogger().info("Loaded custom {} format implementation: {}", formatConfig.mimeType, formatConfig.className);
            }
            catch (Exception e)
            {
                reportError("Error while initializing custom format for " + formatConfig.mimeType, e);
            }
        }
        
        // init short URI resolver
        var curieResolver = new CurieResolver();
        for (var mapping: config.uriPrefixMap) {
            var parts = mapping.split(" |,");
            if (parts.length != 2)
                throw new SensorHubException("Invalid CURIE mapping: " + mapping);
            curieResolver.addPrefix(parts[0], parts[1]);
        }
        
        // create obs db read/write wrapper
        var idEncoders = getParentHub().getIdEncoders();
        var eventBus = getParentHub().getEventBus();
        var handlerCtx = new HandlerContext(readDb, writeDb, eventBus, idEncoders, curieResolver);
        var security = (ConSysApiSecurity)this.securityHandler;
        var readOnly = writeDb == null || writeDb.isReadOnly();
        
        // create resource handlers hierarchy
        var homePage = new HomePageHandler(config);
        var rootHandler = new RootHandler(homePage, readOnly);
        rootHandler.addSubResource(new ConformanceHandler(CONF_CLASSES));
        
        // procedures
        if (handlerCtx.getProcedureStore() != null)
        {
            var procHandler = new ProcedureHandler(handlerCtx, security.procedure_permissions);
            rootHandler.addSubResource(procHandler);
        }
        
        // properties
        if (handlerCtx.getPropertyStore() != null)
        {
            var propHandler = new PropertyHandler(handlerCtx, security.property_permissions);
            rootHandler.addSubResource(propHandler);
        }
        
        // systems and sub-resources
        var systemsHandler = new SystemHandler(handlerCtx, security.system_permissions);
        rootHandler.addSubResource(systemsHandler);
        
        var sysMembersHandler = new SystemMembersHandler(handlerCtx, security.system_permissions);
        systemsHandler.addSubResource(sysMembersHandler);
        sysMembersHandler.addSubResource(sysMembersHandler);
        
        var sysHistoryHandler = new SystemHistoryHandler(handlerCtx, security.system_permissions);
        systemsHandler.addSubResource(sysHistoryHandler);
        sysMembersHandler.addSubResource(sysHistoryHandler);
        
        // deployments
        if (handlerCtx.getDeploymentStore() != null)
        {
            var deplHandler = new DeploymentHandler(handlerCtx, security.deployment_permissions);
            rootHandler.addSubResource(deplHandler);
            
            var deplMembersHandler = new DeploymentMembersHandler(handlerCtx, security.deployment_permissions);
            deplHandler.addSubResource(deplMembersHandler);
            deplMembersHandler.addSubResource(deplMembersHandler);
        }
        
        // features of interest and sub-resources
        var foiHandler = new FoiHandler(handlerCtx, security.foi_permissions);
        rootHandler.addSubResource(foiHandler);
        systemsHandler.addSubResource(foiHandler);
        sysMembersHandler.addSubResource(foiHandler);
        
        var foiHistoryHandler = new FoiHistoryHandler(handlerCtx, security.foi_permissions);
        foiHandler.addSubResource(foiHistoryHandler);
        
        // datastreams
        var dataStreamHandler = new DataStreamHandler(handlerCtx, security.datastream_permissions, customFormats);
        rootHandler.addSubResource(dataStreamHandler);
        systemsHandler.addSubResource(dataStreamHandler);
        sysMembersHandler.addSubResource(dataStreamHandler);
        var dataSchemaHandler = new DataStreamSchemaHandler(handlerCtx, security.datastream_permissions);
        dataStreamHandler.addSubResource(dataSchemaHandler);
        
        // observations
        var obsHandler = new ObsHandler(handlerCtx, threadPool, security.obs_permissions, customFormats);
        rootHandler.addSubResource(obsHandler);
        dataStreamHandler.addSubResource(obsHandler);
        foiHandler.addSubResource(obsHandler);
        
        // obs statistics
        var obsStatsHandler = new ObsStatsHandler(handlerCtx, security.datastream_permissions);
        //rootHandler.addSubResource(obsStatsHandler);
        dataStreamHandler.addSubResource(obsStatsHandler);
        //foiHandler.addSubResource(obsStatsHandler);
        
        // command streams
        var cmdStreamHandler = new CommandStreamHandler(handlerCtx, security.commandstream_permissions);
        rootHandler.addSubResource(cmdStreamHandler);
        systemsHandler.addSubResource(cmdStreamHandler);
        sysMembersHandler.addSubResource(cmdStreamHandler);
        var cmdSchemaHandler = new CommandStreamSchemaHandler(handlerCtx, security.commandstream_permissions);
        cmdStreamHandler.addSubResource(cmdSchemaHandler);
        
        // commands
        var cmdHandler = new CommandHandler(handlerCtx, threadPool, security.command_permissions);
        cmdStreamHandler.addSubResource(cmdHandler);
        
        // command status
        var statusHandler = new CommandStatusHandler(handlerCtx, threadPool, security.command_permissions);
        cmdHandler.addSubResource(statusHandler);
        cmdStreamHandler.addSubResource(statusHandler);
        
        // command result
        var resultHandler = new CommandResultHandler(handlerCtx, threadPool, security.command_permissions);
        cmdHandler.addSubResource(resultHandler);
        
        // collections
        var collectionHandler = new CollectionHandler();
        rootHandler.addSubResource(collectionHandler);
        
        // static web resources (for HTML output)
        var jarStaticResourceHandler = new JarStaticResourceHandler();
        rootHandler.addSubResource(jarStaticResourceHandler);
        
        // deploy servlet
        servlet = new ConSysApiServlet(this, (ConSysApiSecurity)securityHandler, rootHandler, getLogger());
        deploy();

        setState(ModuleState.STARTED);
    }


    protected void deploy() throws SensorHubException
    {
        var wildcardEndpoint = config.endPoint + "/*";
        
        // deploy ourself to HTTP server
        httpServer.deployServlet(servlet, wildcardEndpoint);
        httpServer.addServletSecurity(wildcardEndpoint, config.security.requireAuth);
    }


    @Override
    protected void doStop()
    {
        // undeploy servlet
        undeploy();
        
        // stop thread pool
        if (threadPool != null)
            threadPool.shutdown();

        setState(ModuleState.STOPPED);
    }


    protected void undeploy()
    {
        // return silently if HTTP server missing on stop
        if (httpServer == null || !httpServer.isStarted())
            return;

        if (servlet != null)
        {
            httpServer.undeployServlet(servlet);
            servlet.destroy();
            servlet = null;
        }
    }


    @Override
    public void cleanup() throws SensorHubException
    {
        // unregister security handler
        if (securityHandler != null)
            securityHandler.unregister();
    }


    public ScheduledExecutorService getThreadPool()
    {
        return threadPool;
    }
    
    
    public ConSysApiServlet getServlet()
    {
        return servlet;
    }
    
    
    public String getPublicEndpointUrl()
    {
        return getHttpServer().getPublicEndpointUrl(config.endPoint);
    }


    /*public TimeOutMonitor getTimeOutMonitor()
    {
        return timeOutMonitor;
    }*/
}
