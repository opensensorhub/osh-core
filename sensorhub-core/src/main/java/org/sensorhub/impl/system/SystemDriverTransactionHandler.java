/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.system;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import org.sensorhub.api.command.CommandEvent;
import org.sensorhub.api.command.ICommandReceiver;
import org.sensorhub.api.command.IStreamingControlInterface;
import org.sensorhub.api.command.IStreamingControlInterfaceWithResult;
import org.sensorhub.api.data.IDataProducer;
import org.sensorhub.api.data.IStreamingDataInterface;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.feature.FeatureKey;
import org.sensorhub.api.event.Event;
import org.sensorhub.api.event.IEventListener;
import org.sensorhub.api.system.ISystemDriver;
import org.sensorhub.api.system.ISystemGroupDriver;
import org.sensorhub.api.system.SystemChangedEvent;
import org.sensorhub.api.system.SystemEvent;
import org.sensorhub.api.utils.OshAsserts;
import org.sensorhub.impl.system.wrapper.SystemWrapper;
import org.sensorhub.utils.SerialExecutor;
import org.vast.ogc.gml.IFeature;
import org.vast.util.Asserts;
import org.vast.util.TimeExtent;


/** 
 * Extension of {@link SystemTransactionHandler} with utility methods for
 * registering/unregistering system drivers recursively.<br/>
 * This class is used internally by the system registry.
 */
class SystemDriverTransactionHandler extends SystemTransactionHandler implements IEventListener
{
    ISystemDriver driver; // reference to live system driver
    Map<String, DataStreamTransactionHandler> dataStreamHandlers = new HashMap<>();
    Map<String, CommandStreamTransactionHandler> commandStreamHandlers = new HashMap<>();
    Map<String, SystemDriverTransactionHandler> memberHandlers = new HashMap<>();
    Map<String, Subscription> commandSubscriptions = new HashMap<>();
    Executor executor;
    
    
    protected SystemDriverTransactionHandler(FeatureKey sysKey, String sysUID, String parentGroupUID, SystemDatabaseTransactionHandler rootHandler, Executor parentExecutor)
    {
        super(sysKey, sysUID, parentGroupUID, rootHandler);
        this.executor = new SerialExecutor(parentExecutor);
    }
    
    
    protected void doFinishRegister(ISystemDriver driver) throws DataStoreException
    {
        Asserts.checkNotNull(driver, ISystemDriver.class);
        this.driver = driver;
        
        // enable and start listening to driver events
        enable();
        driver.registerListener(this);
        
        // if data producer, register fois and datastreams
        if (driver instanceof IDataProducer)
        {
            var dataSource = (IDataProducer)driver;
            
            for (var foi: dataSource.getCurrentFeaturesOfInterest().values())
                doRegister(foi);
            
            for (var dataStream: dataSource.getOutputs().values())
                doRegister(dataStream);
        }
        
        // if command sink, register command streams
        if (driver instanceof ICommandReceiver)
        {
            var taskableSource = (ICommandReceiver)driver;
            for (var commandStream: taskableSource.getCommandInputs().values())
                doRegister(commandStream);
        }
        
        // if group, also register members recursively
        if (driver instanceof ISystemGroupDriver)
        {
            for (var member: ((ISystemGroupDriver<?>)driver).getMembers().values())
            {
                doRegisterMember(member, driver.getCurrentDescription().getValidTime());
            }
        }

        if (DefaultSystemRegistry.log.isInfoEnabled())
        {
            var msg = String.format("System registered: %s", sysUID);
            DefaultSystemRegistry.log.info("{} ({} FOIs, {} datastreams, {} command inputs, {} members)",
                    msg,
                    driver instanceof IDataProducer ? ((IDataProducer)driver).getCurrentFeaturesOfInterest().size() : 0,
                    driver instanceof IDataProducer ? ((IDataProducer)driver).getOutputs().size() : 0,
                    driver instanceof ICommandReceiver ? ((ICommandReceiver)driver).getCommandInputs().size() : 0,
                    driver instanceof ISystemGroupDriver ? ((ISystemGroupDriver<?>)driver).getMembers().size() : 0);
        }
    }
    
    
    protected void doUnregister(boolean sendEvents)
    {
        driver.unregisterListener(this);
        
        // unregister members recursively
        for (var memberHandler: memberHandlers.values())
            memberHandler.doUnregister(sendEvents);
        memberHandlers.clear();
        
        // if data producer, unregister datastreams
        if (driver instanceof IDataProducer)
        {
            for (var output: ((IDataProducer)driver).getOutputs().values())
                doUnregister(output, sendEvents);
        }
        dataStreamHandlers.clear();
        
        // if taskable system, unregister command streams
        if (driver instanceof ICommandReceiver)
        {
            for (var input: ((ICommandReceiver)driver).getCommandInputs().values())
                doUnregister(input, sendEvents);
        }
        commandStreamHandlers.clear();
        
        if (sendEvents)
            disable();
        driver = null;
    }
    
    
    public CompletableFuture<Boolean> update(ISystemDriver proc)
    {
        Asserts.checkNotNull(proc, ISystemDriver.class);
        
        return CompletableFuture.supplyAsync(() -> {
            try { return doUpdate(proc); }
            catch (DataStoreException e) { throw new CompletionException(e); }
        }, executor);
    }
    
    
    protected boolean doUpdate(ISystemDriver proc) throws DataStoreException
    {
        Asserts.checkNotNull(proc, ISystemDriver.class);
        
        var procWrapper = new SystemWrapper(proc.getCurrentDescription())
            .hideOutputs()
            .hideTaskableParams()
            .defaultToValidFromNow();
        
        return update(procWrapper);
    }
    
    
    public CompletableFuture<Boolean> registerMember(ISystemDriver proc)
    {
        Asserts.checkNotNull(proc, ISystemDriver.class);
        
        return CompletableFuture.supplyAsync(() -> {
            try { return doRegisterMember(proc, null); }
            catch (DataStoreException e) { throw new CompletionException(e); }
        }, executor);
    }
    
    
    protected synchronized boolean doRegisterMember(ISystemDriver driver, TimeExtent validTime) throws DataStoreException
    {
        Asserts.checkNotNull(driver, ISystemDriver.class);
        var uid = OshAsserts.checkValidUID(driver.getUniqueIdentifier());
        
        var procWrapper = new SystemWrapper(driver.getCurrentDescription())
            .hideOutputs()
            .hideTaskableParams();
        
        // also default to proper valid time
        if (validTime != null)
            procWrapper.defaultToValidTime(validTime);
        else
            procWrapper.defaultToValidFromNow();

        // add or update existing system entry
        var newMemberHandler = (SystemDriverTransactionHandler)addOrUpdateMember(procWrapper);

        // replace and cleanup old handler
        var oldMemberHandler = memberHandlers.get(uid);
        boolean isNew = oldMemberHandler == null;
        if (!isNew)
        {
            // only allow same member driver to re-register with same UID
            ISystemDriver registeredDriver = oldMemberHandler.driver;
            if (registeredDriver != null && registeredDriver != driver)
                throw new IllegalArgumentException("A subsystem with UID " + uid + " is already registered");

            driver.unregisterListener(memberHandlers.get(uid));
        }

        memberHandlers.put(uid, newMemberHandler);

        // register/update driver sub-components
        newMemberHandler.doFinishRegister(driver);
        return isNew;
    }
    
    
    public CompletableFuture<Boolean> unregisterMember(ISystemDriver proc)
    {
        Asserts.checkNotNull(driver, ISystemDriver.class);
        
        return CompletableFuture.supplyAsync(() -> {
            try { return doUnregisterMember(proc); }
            catch (DataStoreException e) { throw new CompletionException(e); }
        }, executor);
    }
    
    
    protected synchronized boolean doUnregisterMember(ISystemDriver driver) throws DataStoreException
    {
        Asserts.checkNotNull(driver, ISystemDriver.class);
        var uid = OshAsserts.checkValidUID(driver.getUniqueIdentifier());
        
        var memberHandler = memberHandlers.remove(uid);
        if (memberHandler != null)
            memberHandler.doUnregister(true);
        
        return true;
    }


    public CompletableFuture<Boolean> register(IStreamingDataInterface output)
    {
        Asserts.checkNotNull(output, IStreamingDataInterface.class);
        
        return CompletableFuture.supplyAsync(() -> {
            try { return doRegister(output); }
            catch (DataStoreException e) { throw new CompletionException(e); }
        }, executor);
    }
    
    
    protected synchronized boolean doRegister(IStreamingDataInterface output) throws DataStoreException
    {
        Asserts.checkNotNull(output, IStreamingDataInterface.class);
        boolean isNew = true;
        
        // add or update existing datastream entry
        var newDsHandler = addOrUpdateDataStream(
            output.getName(),
            output.getRecordDescription(),
            output.getRecommendedEncoding());
            
        // replace and cleanup old handler
        var oldDsHandler = dataStreamHandlers.get(output.getName());
        if (oldDsHandler != null)
        {
            output.unregisterListener(oldDsHandler);
            isNew = false;
        }
        dataStreamHandlers.put(output.getName(), newDsHandler);
        
        // enable and register to receive data events from driver
        newDsHandler.enable();
        output.registerListener(newDsHandler);
        
        return isNew;
    }


    public CompletableFuture<Void> unregister(IStreamingDataInterface output)
    {
        doUnregister(output, true);
        return CompletableFuture.completedFuture(null);
    }
    
    
    protected synchronized void doUnregister(IStreamingDataInterface output, boolean sendEvents)
    {
        Asserts.checkNotNull(output, IStreamingDataInterface.class);
        
        var dsHandler = dataStreamHandlers.remove(output.getName());
        if (dsHandler != null)
        {
            output.unregisterListener(dsHandler);
            if (sendEvents)
                dsHandler.disable();
        }
    }


    public CompletableFuture<Boolean> register(IStreamingControlInterface controlInput)
    {
        Asserts.checkNotNull(controlInput, IStreamingControlInterface.class);
        
        return CompletableFuture.supplyAsync(() -> {
            try { return doRegister(controlInput); }
            catch (DataStoreException e) { throw new CompletionException(e); }
        }, executor);
    }
    
    
    protected synchronized boolean doRegister(IStreamingControlInterface controlInput) throws DataStoreException
    {
        Asserts.checkNotNull(controlInput, IStreamingControlInterface.class);
        boolean isNew = true;
        
        // add or update existing command stream entry
        CommandStreamTransactionHandler newCsHandler;
        if (controlInput instanceof IStreamingControlInterfaceWithResult)
        {
            newCsHandler = addOrUpdateCommandStream(
                controlInput.getName(),
                controlInput.getCommandDescription(),
                controlInput.getCommandEncoding(),
                ((IStreamingControlInterfaceWithResult) controlInput).getResultDescription(),
                ((IStreamingControlInterfaceWithResult) controlInput).getResultEncoding());
        }
        else
        {
            newCsHandler = addOrUpdateCommandStream(
                controlInput.getName(),
                controlInput.getCommandDescription(),
                controlInput.getCommandEncoding());
        }
            
        // replace and cleanup old handler and subscription
        commandStreamHandlers.put(controlInput.getName(), newCsHandler);
        var oldSub = commandSubscriptions.get(controlInput.getName());
        if (oldSub != null)
        {
            oldSub.cancel();
            isNew = false;
        }
                
        // connect to receive commands from event bus
        connectControlInput(controlInput, newCsHandler);
        
        // enable and register to receive command status events from driver
        newCsHandler.enable();
        controlInput.registerListener(newCsHandler);
        
        return isNew;
    }
    
    
    protected void connectControlInput(IStreamingControlInterface controlInput, CommandStreamTransactionHandler csHandler)
    {
        csHandler.connectCommandReceiver(new Subscriber<CommandEvent>() {
            Subscription sub;
            
            @Override
            public void onSubscribe(Subscription sub)
            {
                this.sub = sub;
                commandSubscriptions.put(controlInput.getName(), sub);
                sub.request(1);
            }

            @Override
            public void onNext(CommandEvent event)
            {
                CompletableFuture.runAsync(() -> {
                    try
                    {
                        controlInput.submitCommand(event.getCommand())
                            .thenAccept(status -> {
                                csHandler.sendStatus(event.getCorrelationID(), status);
                                sub.request(1);
                            });
                    }
                    catch (Exception e)
                    {
                        onError(e);
                        sub.request(1);
                    }
                });
            }

            @Override
            public void onError(Throwable e)
            {
                DefaultSystemRegistry.log.error("Error dispatching commands to {} / {}", driver.getName(), controlInput.getName(), e);
            }

            @Override
            public void onComplete()
            {
            }
        });
    }


    public CompletableFuture<Void> unregister(IStreamingControlInterface controlInput)
    {
        doUnregister(controlInput, true);
        return CompletableFuture.completedFuture(null);
    }
    
    
    protected synchronized void doUnregister(IStreamingControlInterface controlInput, boolean sendEvents)
    {
        Asserts.checkNotNull(controlInput, IStreamingControlInterface.class);
        
        // remove handler
        var csHandler = commandStreamHandlers.remove(controlInput.getName());
        if (csHandler != null)
        {
            controlInput.unregisterListener(csHandler);
            if (sendEvents)
                csHandler.disable();
        }
        
        // cancel subscriptions to received commands
        var sub = commandSubscriptions.remove(controlInput.getName());
        if (sub != null)
            sub.cancel();
    }


    public CompletableFuture<Boolean> register(IFeature foi)
    {
        Asserts.checkNotNull(foi, IFeature.class);
        OshAsserts.checkValidUID(foi.getUniqueIdentifier());
        
        return CompletableFuture.supplyAsync(() -> {
            try { return doRegister(foi); }
            catch (DataStoreException e) { throw new CompletionException(e); }
        }, executor);
    }
    
    
    protected synchronized boolean doRegister(IFeature foi) throws DataStoreException
    {
        addOrUpdateFoi(foi);
        return false;
    }


    /*
     * Catch events from system or its members
     */
    @Override
    public void handleEvent(Event e)
    {
        if (e instanceof SystemEvent)
        {
            // register item if needed
            if (driver != null && driver.isEnabled())
            {
                var driverUid = driver.getUniqueIdentifier();
                var eventUid = ((SystemEvent) e).getSystemUID();

                // assign internal ID before event is dispatched
                ((SystemEvent)e).assignSystemID(sysKey.getInternalID());
                
                if (e instanceof SystemChangedEvent && driverUid.equals(eventUid))
                {
                    // update main system
                    update(driver);
                }
            }
        }
    }
    
    
    protected SystemDriverTransactionHandler createMemberHandler(FeatureKey memberKey, String memberUID)
    {
        return new SystemDriverTransactionHandler(memberKey, memberUID, sysUID, rootHandler, executor);
    }

}
