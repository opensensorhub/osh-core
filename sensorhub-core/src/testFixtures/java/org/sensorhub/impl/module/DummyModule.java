/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.module;

import org.sensorhub.api.ISensorHub;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.event.IEventHandler;
import org.sensorhub.api.event.IEventListener;
import org.sensorhub.api.module.IModule;
import org.sensorhub.api.module.IModuleStateManager;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.api.module.ModuleEvent;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.sensorhub.impl.event.BasicEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class DummyModule implements IModule<ModuleConfig>
{
    static Logger logger = LoggerFactory.getLogger(DummyModule.class);
    ISensorHub hub;
    ModuleConfig config;
    ModuleState state = ModuleState.LOADED;
    IEventHandler eventHandler;


    @Override
    public boolean isInitialized()
    {
        return true;
    }


    @Override
    public boolean isStarted()
    {
        return (state == ModuleState.STARTED);
    }
    
    
    @Override
    public void init(ModuleConfig config) throws SensorHubException
    {
        this.config = config;
        init();
    }


    @Override
    public void updateConfig(ModuleConfig config)
    {
    }


    @Override
    public void setConfiguration(ModuleConfig config)
    {
        this.config = config;
        this.eventHandler = new BasicEventHandler();
    }


    @Override
    public ModuleConfig getConfiguration()
    {
        return config;
    }


    @Override
    public String getName()
    {
        return config.name;
    }


    @Override
    public String getDescription()
    {
        return config.description;
    }


    @Override
    public String getLocalID()
    {
        return config.id;
    }


    @Override
    public void saveState(IModuleStateManager saver)
    {
    }


    @Override
    public void loadState(IModuleStateManager loader)
    {
    }


    @Override
    public void cleanup()
    {
    }


    @Override
    public void registerListener(IEventListener listener)
    {
        eventHandler.registerListener(listener);
    }


    @Override
    public void unregisterListener(IEventListener listener)
    {
        eventHandler.registerListener(listener);
    }


    @Override
    public ModuleState getCurrentState()
    {
        return this.state;
    }


    @Override
    public String getStatusMessage()
    {
        return null;
    }


    @Override
    public Throwable getCurrentError()
    {
        return null;
    }


    @Override
    public void init() throws SensorHubException
    {
        setState(ModuleState.INITIALIZED);
    }


    @Override
    public void start() throws SensorHubException
    {
        setState(ModuleState.STARTED);
    }


    @Override
    public void stop() throws SensorHubException
    {
        setState(ModuleState.STOPPED);
    }
    
    
    protected void setState(ModuleState newState)
    {
        this.state = newState;
        eventHandler.publish(new ModuleEvent(this, newState));
    }


    @Override
    public boolean waitForState(ModuleState state, long timeout)
    {
        return true;
    }


    @Override
    public void setParentHub(ISensorHub hub)
    {
        this.hub = hub;        
    }


    @Override
    public ISensorHub getParentHub()
    {
        return this.hub;
    }


    @Override
    public Logger getLogger()
    {
        return logger;
    }
    
}