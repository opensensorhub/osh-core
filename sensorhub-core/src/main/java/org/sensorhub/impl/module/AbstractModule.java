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
import org.sensorhub.api.event.IEventSourceInfo;
import org.sensorhub.api.module.IModule;
import org.sensorhub.api.module.IModuleStateManager;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.api.module.ModuleEvent;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.sensorhub.impl.event.BasicEventHandler;
import org.sensorhub.impl.event.EventSourceInfo;
import org.sensorhub.utils.ModuleUtils;
import org.sensorhub.utils.MsgUtils;
import org.slf4j.Logger;
import org.vast.util.Asserts;


/**
 * <p>
 * Class providing default implementation of common module API methods 
 * </p>
 *
 * @author Alex Robin
 * @param <ConfigType> 
 * @since Oct 30, 2014
 */
public abstract class AbstractModule<ConfigType extends ModuleConfig> implements IModule<ConfigType>
{
    private ISensorHub hub;
    protected Logger logger;
    protected IEventHandler eventHandler = new BasicEventHandler();
    protected IEventSourceInfo eventSrcInfo;
    protected ConfigType config;
    protected ModuleState state = ModuleState.LOADED;
    protected ModuleSecurity securityHandler;
    protected final Object stateLock = new Object();
    protected boolean startRequested;
    protected boolean initAsync, startAsync, stopAsync;
    protected Throwable lastError;
    protected String statusMsg;
    

    public AbstractModule() {}
    
    
    public void setParentHub(ISensorHub hub)
    {
        if (this.hub != null)
            throw new IllegalStateException("Parent hub has already been assigned");
        this.hub = hub;
    }
    
    
    public ISensorHub getParentHub()
    {
        Asserts.checkNotNull(hub, "Parent sensor hub hasn't been set");
        return this.hub;
    }
    
    
    public boolean hasParentHub()
    {
        return hub != null;
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
    public ConfigType getConfiguration()
    {
        return config;
    }
    
    
    @Override
    public void setConfiguration(ConfigType config)
    {
        if (this.config != config)
        {
            this.config = config;
            
            // set default security handler
            this.securityHandler = new ModuleSecurity(this, "all", false);
        }
    }
    
    
    /*
     * Default implementation is to re-init and restart the module when config was changed
     */
    @Override
    public synchronized void updateConfig(ConfigType config) throws SensorHubException
    {
        boolean wasStarted = isStarted();
        
        if (wasStarted)
            requestStop();
        
        try
        {
            setConfiguration(config);
            eventHandler.publish(new ModuleEvent(this, ModuleEvent.Type.CONFIG_CHANGED));
        }
        catch (Exception e)
        {
            reportError(CANNOT_UPDATE_MSG, e);
        }
        
        // force re-init
        requestInit(true);
        
        // also restart if it was started
        if (wasStarted)
            requestStart();
    }
    
    
    @Override
    public boolean isInitialized()
    {
        return state.ordinal() >= ModuleState.INITIALIZED.ordinal();
    }

    
    @Override
    public boolean isStarted()
    {
        return state == ModuleState.STARTED;
    }
    
    
    protected void checkStarted()
    {
        if (!isStarted())
            throw new IllegalStateException("Module is not started yet");
    }
    
    
    @Override
    public ModuleState getCurrentState()
    {
        return state;
    }
    
    
    /**
     * Sets the module state and sends the appropriate event if it has changed
     * @param newState
     */
    protected void setState(ModuleState newState)
    {
        synchronized (stateLock)
        {
            if (newState != state)
            {
                this.state = newState;
                stateLock.notifyAll();
                getLogger().info("Module {}", newState);
                
                if (eventHandler != null)
                {
                    ModuleEvent event = new ModuleEvent(this, newState);
                    eventHandler.publish(event);
                }
                
                // process delayed start request
                try
                {
                    if (startRequested && (newState == ModuleState.INITIALIZED || newState == ModuleState.STOPPED))
                        requestStart();
                }
                catch (SensorHubException e)
                {
                    getLogger().error("Error during delayed start", e);
                }
            }
        }
    }
    
    
    @Override
    public boolean waitForState(ModuleState state, long timeout)
    {
        synchronized (stateLock)
        {
            try
            {
                long stopWait = System.currentTimeMillis() + timeout;
                
                // special case if we wait for restart
                boolean waitForRestart = false;
                if (this.state.ordinal() > ModuleState.STARTED.ordinal() &&
                    (state == ModuleState.STARTED || state == ModuleState.STARTING))
                    waitForRestart = true;
                    
                while (this.state.ordinal() < state.ordinal() || (waitForRestart && this.state != state) )
                {
                    //Throwable error = getCurrentError();
                    //if (error != null)
                    //    return false;
                    
                    if (timeout > 0)
                    {
                        long waitTime = stopWait - System.currentTimeMillis();
                        if (waitTime <= 0)
                            return false;
                        stateLock.wait(waitTime);
                    }
                    else
                        stateLock.wait();
                }
            }
            catch (InterruptedException e)
            {
                return false;
            }
            
            return true;
        }
    }
    
    
    @Override
    public Throwable getCurrentError()
    {
        return lastError;
    }
    
    
    /**
     * Sets the module error state and sends corresponding event
     * @param msg
     * @param error
     * false to log only at debug level
     */
    public void reportError(String msg, Throwable error)
    {
        reportError(msg, error, false);
    }
    
    
    /**
     * Sets the module error state and sends corresponding event
     * @param msg
     * @param error 
     * @param logAsDebug set to true to log the exception only at debug level,
     * false to log at error level
     */
    public void reportError(String msg, Throwable error, boolean logAsDebug)
    {
        synchronized (stateLock)
        {
            if (msg != null)
                this.lastError = new SensorHubException(msg, error);
            else
                this.lastError = error;
            
            if (!logAsDebug || getLogger().isDebugEnabled())
            {
                if (msg != null)
                    getLogger().error(msg, error);
                else
                    getLogger().error("Error", error);
            }
            else if (msg != null)
            {
                getLogger().error(msg);
            }
            
            if (eventHandler != null)
            {
                ModuleEvent event = new ModuleEvent(this, this.lastError);               
                eventHandler.publish(event);
            }
        }
    }
    
    
    /**
     * Clears last error
     */
    public void clearError()
    {
        synchronized (stateLock)
        {
            this.lastError = null;
        }
    }
    
    
    @Override
    public String getStatusMessage()
    {
        return statusMsg;
    }
    
    
    /**
     * Sets the module status message and sends corresponding event
     * @param msg
     */
    public void reportStatus(String msg)
    {
        this.statusMsg = msg;
        getLogger().info(msg);
        
        if (eventHandler != null)
        {
            ModuleEvent event = new ModuleEvent(this, this.statusMsg);               
            eventHandler.publish(event);
        }
    }
    
    
    /**
     * Clears the current status message
     */
    public void clearStatus()
    {
        this.statusMsg = null;
    }
    
    
    /**
     * Helper method to send and log connection/disconnection events
     * @param connected
     */
    protected void notifyConnectionStatus(boolean connected, String remoteServiceName)
    {
        if (connected)
        {
            reportStatus("Connected to " + remoteServiceName);
            eventHandler.publish(new ModuleEvent(this, ModuleEvent.Type.CONNECTED));
        }
        else
        {
            reportStatus("Disconnected from " + remoteServiceName);
            eventHandler.publish(new ModuleEvent(this, ModuleEvent.Type.DISCONNECTED));
        }
    }
    
    
    protected boolean canInit(boolean force) throws SensorHubException
    {
        synchronized (stateLock)
        {
            // error if config hasn't been set
            if (this.config == null)
                throw new SensorHubException("Module configuration must be set");
            
            // do nothing if we are already intializing or initialized
            if (!force && state.ordinal() >= ModuleState.INITIALIZING.ordinal())
                return false;
            
            // otherwise actually init the module
            clearError();
            clearStatus();
            setState(ModuleState.INITIALIZING);            
            return true;
        }
    }
    
    
    @Override
    public void requestInit(boolean force) throws SensorHubException
    {
        if (canInit(force))
        {
            try
            {
                beforeInit();
                init();
                afterInit();
                if (!initAsync)
                    setState(ModuleState.INITIALIZED);
            }
            catch (Exception e)
            {
                reportError(CANNOT_INIT_MSG, e);
                setState(ModuleState.LOADED);
            }
        }
    }


    @Override
    public void init() throws SensorHubException
    {        
        Asserts.checkState(config != null, "Configuration must be set before calling init()");
    }
    
    
    @Override
    public void init(ConfigType config) throws SensorHubException
    {   
        setConfiguration(config);
        init();
    }


    /**
     * This method is called right before {@link #init()}.<br/>
     * It is typically implemented whenever a super class needs to run some logic
     * at the beginning of the initialization phase even when {@link #init()} is
     * overriden by its descendants
     * @throws SensorHubException
     */
    protected void beforeInit() throws SensorHubException
    {
        // to be implemented by derived classes 
    }


    /**
     * This method is called right after {@link #init()}.<br/>
     * It is typically implemented whenever a super class needs to run some logic
     * at the end of the initialization phase even when {@link #init()} is
     * overriden by its descendants
     * @throws SensorHubException
     */
    protected void afterInit() throws SensorHubException
    {
        // to be implemented by derived classes 
    }


    protected boolean canStart() throws SensorHubException
    {
        synchronized (stateLock)
        {
            // error if we were never initialized
            if (state == ModuleState.LOADED)
                throw new SensorHubException("Module must first be initialized");
            
            // do nothing if we're already started or starting
            if (state == ModuleState.STARTED || state == ModuleState.STARTING)
            {
                getLogger().warn("Module was already started");
                return false;
            }
            
            // set to start later if we're still initializing or stopping
            if (state == ModuleState.INITIALIZING || state == ModuleState.STOPPING)
            {
                startRequested = true;
                return false;
            }
            
            // actually start if we're either initialized or stopped
            clearError();
            setState(ModuleState.STARTING);
            return true;
        }
    }
    
    
    @Override
    public void requestStart() throws SensorHubException
    {
        if (canStart())
        {
            try
            {
                beforeStart();
                start();
                afterStart();
                if (!startAsync)
                    setState(ModuleState.STARTED);
            }
            catch (Exception e)
            {
                reportError(CANNOT_START_MSG, e);
                requestStop();
            }
        }
    }


    /**
     * This method is called right before {@link #start()}.<br/>
     * It is typically implemented whenever a super class needs to run some logic
     * at the beginning of the start phase even when {@link #start()} is
     * overriden by its descendants
     * @throws SensorHubException
     */
    protected void beforeStart() throws SensorHubException
    {
        // to be implemented by derived classes 
    }


    /**
     * This method is called right after {@link #start()}.<br/>
     * It is typically implemented whenever a super class needs to run some logic
     * at the end of the start phase even when {@link #start()} is overriden by
     * its descendants
     * @throws SensorHubException
     */
    protected void afterStart() throws SensorHubException
    {
        // to be implemented by derived classes 
    }
    
    
    protected boolean canStop()
    {
        synchronized (stateLock)
        {
            // do nothing if we're already stopping
            if (state == ModuleState.STOPPING)
                return false;
                        
            // otherwise we allow stop at any time
            // modules have to handle that properly
            setState(ModuleState.STOPPING);
            startRequested = false;
            return true;
        }
    }
    
    
    @Override
    public void requestStop() throws SensorHubException
    {
        ModuleState oldState = this.state;
        
        if (canStop())
        {
            try
            {
                beforeStop();
                stop();
                afterStop();
                clearStatus();
                
                // make sure we reset to LOADED if we didn't initialize correctly
                if (oldState == ModuleState.INITIALIZING)
                    setState(ModuleState.LOADED);
                else if (!stopAsync)
                    setState(ModuleState.STOPPED);
            }
            catch (Exception e)
            {
                reportError(CANNOT_STOP_MSG, e);
            }
        }
    }


    /**
     * This method is called right before {@link #stop()}.<br/>
     * It is typically implemented whenever a super class needs to run some logic
     * at the beginning of the stop phase even when {@link #stop()} is
     * overriden by its descendants
     * @throws SensorHubException
     */
    protected void beforeStop() throws SensorHubException
    {
        // to be implemented by derived classes 
    }


    /**
     * This method is called right after {@link #stop()}.<br/>
     * It is typically implemented whenever a super class needs to run some logic
     * at the end of the stop phase even when {@link #stop()} is overriden by
     * its descendants
     * @throws SensorHubException
     */
    protected void afterStop() throws SensorHubException
    {
        // to be implemented by derived classes 
    }


    @Override
    public void saveState(IModuleStateManager saver) throws SensorHubException
    {
        // does nothing in the default implementation        
    }


    @Override
    public void loadState(IModuleStateManager loader) throws SensorHubException
    {
        // does nothing in the default implementation
    }


    @Override
    public void cleanup() throws SensorHubException
    {
        if (eventHandler != null)
            eventHandler.publish(new ModuleEvent(this, ModuleEvent.Type.DELETED));
    }


    @Override
    public void registerListener(IEventListener listener)
    {
        synchronized (stateLock)
        {
            eventHandler.registerListener(listener);
            
            // notify current state synchronously while we're locked
            // so the listener can't miss it
            if (this.state != ModuleState.LOADED)
                listener.handleEvent(new ModuleEvent(this, this.state));
        }
    }


    @Override
    public void unregisterListener(IEventListener listener)
    {
        eventHandler.unregisterListener(listener);
    }
    
    
    @Override
    public IEventSourceInfo getEventSourceInfo()
    {
        if (eventSrcInfo == null)
            eventSrcInfo = new EventSourceInfo(getLocalID());
        
        return eventSrcInfo;
    }
    
    
    public Logger getLogger()
    {
        if (logger == null)
            logger = ModuleUtils.createModuleLogger(this);        
        return logger;
    }
    
    
    @Override
    public String toString()
    {
        return MsgUtils.moduleString(this);
    }
}
