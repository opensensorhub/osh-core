/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.sos;

import java.util.Iterator;
import org.sensorhub.api.common.Event;
import org.sensorhub.api.common.IEventListener;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.module.ModuleEvent;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.sensorhub.api.service.IServiceModule;
import org.sensorhub.impl.module.AbstractModule;
import org.sensorhub.impl.service.HttpServer;
import org.vast.ows.sos.SOSServiceCapabilities;


/**
 * <p>
 * Implementation of SensorHub generic SOS service.
 * This service is automatically configured (mostly) from information obtained
 * from the selected data sources (sensors, storages, processes, etc).
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since Sep 7, 2013
 */
public class SOSService extends AbstractModule<SOSServiceConfig> implements IServiceModule<SOSServiceConfig>, IEventListener
{
    protected SOSServlet servlet;
    
    
    @Override
    public void requestStart() throws SensorHubException
    {
        if (canStart())
        {
            HttpServer httpServer = HttpServer.getInstance();
            if (httpServer == null)
                throw new SensorHubException("HTTP server module is not loaded");
            
            // subscribe to server lifecycle events
            httpServer.registerListener(this);
            
            // we actually start in the handleEvent() method when
            // a STARTED event is received from HTTP server
        }
    }
    
    
    @Override
    public void setConfiguration(SOSServiceConfig config)
    {
        // HACK: remove orphan consumers still there after deleting offering from UI
        Iterator<SOSConsumerConfig> it = config.dataConsumers.iterator();
        while (it.hasNext())
        {
            String uri = it.next().offeringID;
            boolean found = false;
            
            if (uri != null)
            {
                for (SOSProviderConfig providerConf: config.dataProviders)
                {
                    if (uri.equals(providerConf.offeringID))
                    {
                        found = true;
                        break;
                    }
                }
            }
            
            if (!found)
                it.remove();
        }
        
        super.setConfiguration(config);
        this.securityHandler = new SOSSecurity(this, config.security.enableAccessControl);
    }
    
    
    @Override
    public void start() throws SensorHubException
    {
        // deploy servlet
        servlet = new SOSServlet(config, (SOSSecurity)this.securityHandler, getLogger());
        deploy();
        
        setState(ModuleState.STARTED);
    }
    
    
    @Override
    public void stop()
    {
        // undeploy servlet
        undeploy();
        if (servlet != null)
            servlet.stop();
        servlet = null;
        
        setState(ModuleState.STOPPED);
    }
   
    
    protected void deploy() throws SensorHubException
    {
        HttpServer httpServer = HttpServer.getInstance();
        if (httpServer == null || !httpServer.isStarted())
            throw new SensorHubException("An HTTP server instance must be started");
        
        // deploy ourself to HTTP server
        httpServer.deployServlet(servlet, config.endPoint);
        httpServer.addServletSecurity(config.endPoint, config.security.requireAuth);
    }
    
    
    protected void undeploy()
    {
        HttpServer httpServer = HttpServer.getInstance();
        
        // return silently if HTTP server missing on stop
        if (httpServer == null || !httpServer.isStarted())
            return;
        
        httpServer.undeployServlet(servlet);
    }
    
    
    @Override
    public void cleanup() throws SensorHubException
    {
        // stop listening to http server events
        HttpServer httpServer = HttpServer.getInstance();
        if (httpServer != null)
            httpServer.unregisterListener(this);
        
        // TODO destroy all virtual sensors?
        //for (SOSConsumerConfig consumerConf: config.dataConsumers)
        //    SensorHub.getInstance().getModuleRegistry().destroyModule(consumerConf.sensorID);
        
        // unregister security handler
        if (securityHandler != null)
            securityHandler.unregister();
    }
    
    
    @Override
    public void handleEvent(Event<?> e)
    {
        // catch HTTP server lifecycle events
        if (e instanceof ModuleEvent && e.getSource() == HttpServer.getInstance())
        {
            ModuleState newState = ((ModuleEvent) e).getNewState();
            
            // start when HTTP server is enabled
            if (newState == ModuleState.STARTED)
            {
                try
                {
                    start();
                }
                catch (Exception ex)
                {
                    reportError("SOS Service could not start", ex);
                }
            }
            
            // stop when HTTP server is disabled
            else if (newState == ModuleState.STOPPED)
                stop();
        }
    }
    
    
    public SOSServiceCapabilities getCapabilities()
    {
        if (isStarted())
            return servlet.capabilities;
        else
            return null;
    }
}
