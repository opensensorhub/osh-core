/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2016 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.module;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.sensorhub.api.event.Event;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.module.IModule;
import org.sensorhub.api.module.ModuleEvent;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.sensorhub.utils.MsgUtils;


public class AsyncModule extends AbstractModule<AsyncModuleConfig>
{
    ExecutorService exec = Executors.newSingleThreadExecutor();
    
    
    @Override
    public void init() throws SensorHubException
    {
        if (canInit())
        {
            Callable<Void> task = new Callable<Void>()
            {
                @Override
                public Void call() throws Exception
                {
                    try
                    {
                        if (config.moduleIDNeededForInit != null)
                        {
                            AbstractModule<?> module = (AbstractModule<?>)getParentHub().getModuleRegistry().getModuleById(config.moduleIDNeededForInit);
                            
                            if (!config.useWaitLoopForInit)
                            {
                                //getParentHub().getEventBus().registerListener(config.moduleIDNeededForInit, AsyncModule.this);
                                getParentHub().getEventBus().newSubscription()
                                    .withTopicID(config.moduleIDNeededForInit)
                                    .consume(AsyncModule.this::handleEvent);
                                return null;
                            }
                            else
                                module.waitForState(config.moduleStateNeededForInit, 0);                    
                        }
                        
                        try { Thread.sleep(config.initDelay); }
                        catch(InterruptedException e) {}
                        doInit();
                        
                        return null;
                    }
                    catch (Exception e)
                    {
                        reportError("Error during init", e);
                        throw e;
                    }
                }
            };
            
            try
            {
                if (config.useThreadForInit)
                    exec.submit(task);
                else
                    task.call();
            }
            catch (Exception e)
            {
                throw new SensorHubException(e.getMessage(), e.getCause());
            }
        }
    }


    @Override
    protected void doInit() throws SensorHubException
    {   
        System.out.println("Running init() of " + MsgUtils.moduleString(this));
        try { Thread.sleep(config.initExecTime); }
        catch(InterruptedException e) {}
        
        setState(ModuleState.INITIALIZED);
    }
    
    
    @Override
    public void start() throws SensorHubException
    {
        if (canStart())
        {
            Callable<Void> task = new Callable<Void>()
            {
                @Override
                public Void call() throws Exception
                {
                    try
                    {
                        if (config.moduleIDNeededForStart != null)
                        {
                            AbstractModule<?> module = (AbstractModule<?>)getParentHub().getModuleRegistry().getModuleById(config.moduleIDNeededForStart);
                            
                            if (!config.useWaitLoopForStart)
                            {
                                //getParentHub().getEventBus().registerListener(config.moduleIDNeededForStart, AsyncModule.this);
                                getParentHub().getEventBus().newSubscription()
                                    .withTopicID(config.moduleIDNeededForStart)
                                    .consume(AsyncModule.this::handleEvent);
                                return null;
                            }
                            else
                                module.waitForState(config.moduleStateNeededForStart, 0);                    
                        }
                        
                        try { Thread.sleep(config.startDelay); }
                        catch(InterruptedException e) {}
                        doStart();
                        
                        return null;
                    }
                    catch (Exception e)
                    {
                        reportError("Error during start", e);
                        throw e;
                    }
                }
            };
            
            try
            {
                if (config.useThreadForStart)
                    exec.submit(task);
                else
                    task.call();
            }
            catch (Exception e)
            {
                throw new SensorHubException(e.getMessage(), e.getCause());
            }     
        }
    }
    
    
    @Override
    protected void doStart() throws SensorHubException
    {
        System.out.println("Running start() of " + MsgUtils.moduleString(this));
        try { Thread.sleep(config.startExecTime); }
        catch(InterruptedException e) {}
        
        setState(ModuleState.STARTED);
    }
    
    
    @Override
    public void stop() throws SensorHubException
    {
        if (canStop())
        {
            Callable<Void> task = new Callable<Void>()
            {
                @Override
                public Void call() throws Exception
                {
                    try
                    {
                        try { Thread.sleep(config.stopDelay); }
                        catch(InterruptedException e) {}
                        doStop();
                        
                        return null;
                    }
                    catch (Exception e)
                    {
                        reportError("Error during stop", e);
                        throw e;
                    }
                }
            };
            
            try
            {
                if (config.useThreadForStop)
                    exec.submit(task);
                else
                    task.call();
            }
            catch (Exception e)
            {
                throw new SensorHubException(e.getMessage(), e.getCause());
            }     
        }
    }
    

    @Override
    protected void doStop() throws SensorHubException
    {
        System.out.println("Running stop() of " + MsgUtils.moduleString(this));
        try { Thread.sleep(config.stopExecTime); }
        catch(InterruptedException e) {}
        
        setState(ModuleState.STOPPED);        
    }


    public void handleEvent(Event e)
    {
        if (e instanceof ModuleEvent)
        {
            switch (((ModuleEvent)e).getType())
            {                
                case STATE_CHANGED:
                    IModule<?> module = (IModule<?>)e.getSource();
                    String moduleID = module.getLocalID();
                    ModuleState state = module.getCurrentState();
                    
                    if (moduleID.equals(config.moduleIDNeededForInit) && state == config.moduleStateNeededForInit)
                    {
                        try
                        {
                            setConfiguration(this.config);
                            doInit();
                        }
                        catch (SensorHubException e1)
                        {
                            reportError("Cannot init module", e1);
                        }
                    }
                    
                    else if (moduleID.equals(config.moduleIDNeededForStart) && state == config.moduleStateNeededForStart)
                    {
                        try
                        {
                            doStart();
                        }
                        catch (SensorHubException e1)
                        {
                            reportError("Cannot start module", e1);
                        }
                    }
                    
                    break;
                    
                default:
                    break;                
            }
        }        
    }

    
    @Override
    public void cleanup() throws SensorHubException
    {        
    }
}
