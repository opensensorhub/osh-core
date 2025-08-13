/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2021 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service;

import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.event.Event;
import org.sensorhub.api.event.IEventListener;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.api.module.ModuleEvent;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.sensorhub.api.service.IHttpServer;
import org.sensorhub.impl.module.AbstractModule;


/**
 * <p>
 * Helper class to implement services that are deployed on the embedded HTTP
 * server. This class automatically handles deployments and re-deployments when
 * the embedded HTTP server stops and starts.
 * </p>
 * 
 * @param <ConfigType> Type of config
 *
 * @author Alex Robin
 * @since Apr 22, 2021
 */
public abstract class AbstractHttpServiceModule<ConfigType extends ModuleConfig> extends AbstractModule<ConfigType> implements IEventListener
{
    protected IHttpServer<?> httpServer;
    
    
    @Override
    public void start() throws SensorHubException
    {
        httpServer = getParentHub().getModuleRegistry().getModuleByType(IHttpServer.class);
        if (httpServer == null)
            throw new SensorHubException("HTTP server module is not loaded");

        // subscribe to server lifecycle events
        httpServer.registerListener(this);

        // we actually start in the handleEvent() method when
        // a STARTED event is received from HTTP server
    }
    
    
    @Override
    public void stop() throws SensorHubException
    {
        // stop listening to http server events
        if (httpServer != null)
            httpServer.unregisterListener(this);
        
        super.stop();        
        httpServer = null;
    }
    
    
    @Override
    public void handleEvent(Event e)
    {
        // catch HTTP server lifecycle events
        if (e instanceof ModuleEvent && e.getSource() == httpServer)
        {
            ModuleState newState = ((ModuleEvent) e).getNewState();

            // start when HTTP server is started
            if (newState == ModuleState.STARTED)
            {
                try
                {
                    super.start();
                }
                catch (Exception ex)
                {
                    reportError("Service could not start", ex);
                }
            }

            // stop when HTTP server is stopped
            else if (newState == ModuleState.STOPPED)
            {
                try
                {
                    stop();
                }
                catch (SensorHubException ex)
                {
                    reportError("Service could not stop", ex);
                }
            }

            // fully stop when HTTP server module is removed
            else if (((ModuleEvent) e).getType() == ModuleEvent.Type.UNLOADED)
            {
                try
                {
                    stop();
                }
                catch (SensorHubException ex)
                {
                    reportError("Service could not stop", ex);
                }
            }
        }
    }


    public IHttpServer<?> getHttpServer()
    {
        return httpServer;
    }    
}
