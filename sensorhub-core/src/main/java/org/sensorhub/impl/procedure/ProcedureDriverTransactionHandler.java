/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.procedure;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import org.sensorhub.api.command.CommandEvent;
import org.sensorhub.api.command.ICommandData;
import org.sensorhub.api.command.ICommandReceiver;
import org.sensorhub.api.command.IStreamingControlInterface;
import org.sensorhub.api.data.FoiEvent;
import org.sensorhub.api.data.IDataProducer;
import org.sensorhub.api.data.IStreamingDataInterface;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.feature.FeatureKey;
import org.sensorhub.api.event.Event;
import org.sensorhub.api.event.IEventListener;
import org.sensorhub.api.obs.DataStreamAddedEvent;
import org.sensorhub.api.obs.DataStreamChangedEvent;
import org.sensorhub.api.procedure.IProcedureDriver;
import org.sensorhub.api.procedure.IProcedureGroupDriver;
import org.sensorhub.api.procedure.ProcedureEvent;
import org.sensorhub.api.utils.OshAsserts;
import org.sensorhub.impl.procedure.wrapper.ProcedureWrapper;
import org.vast.data.TextEncodingImpl;
import org.vast.ogc.gml.IGeoFeature;
import org.vast.util.Asserts;
import org.vast.util.TimeExtent;


/* 
 * Extension of procedure transaction handler with utility methods for
 * registering/unregistering procedure drivers recursively.
 * This class is used internally by the procedure registry.
 */
public class ProcedureDriverTransactionHandler extends ProcedureTransactionHandler implements IEventListener
{
    protected IProcedureDriver driver; // reference to live procedure
    protected Map<String, DataStreamTransactionHandler> dataStreamHandlers = new HashMap<>();
    protected Map<String, CommandStreamTransactionHandler> commandStreamHandlers = new HashMap<>();
    protected Map<String, ProcedureDriverTransactionHandler> memberHandlers = new HashMap<>();
    protected Map<String, Subscription> commandSubscriptions = new HashMap<>();
    
    
    protected ProcedureDriverTransactionHandler(FeatureKey procKey, String procUID, String parentGroupUID, ProcedureObsTransactionHandler rootHandler)
    {
        super(procKey, procUID, parentGroupUID, rootHandler);
    }
    
    
    protected void doFinishRegister(IProcedureDriver driver) throws DataStoreException
    {
        Asserts.checkNotNull(driver, IProcedureDriver.class);
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
        if (driver instanceof IProcedureGroupDriver)
        {
            for (var member: ((IProcedureGroupDriver<?>)driver).getMembers().values())
                doRegisterMember(member, driver.getCurrentDescription().getValidTime());
        }

        if (DefaultProcedureRegistry.log.isInfoEnabled())
        {
            var msg = String.format("Procedure registered: %s", procUID);            
            DefaultProcedureRegistry.log.info("{} ({} FOIs, {} datastreams, {} command inputs, {} members)",
                    msg,
                    driver instanceof IDataProducer ? ((IDataProducer)driver).getCurrentFeaturesOfInterest().size() : 0,
                    driver instanceof IDataProducer ? ((IDataProducer)driver).getOutputs().size() : 0,
                    driver instanceof ICommandReceiver ? ((ICommandReceiver)driver).getCommandInputs().size() : 0,
                    driver instanceof IProcedureGroupDriver ? ((IProcedureGroupDriver<?>)driver).getMembers().size() : 0);
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
            for (var dsHandler: dataStreamHandlers.values())
            {
                var outputName = dsHandler.getDataStreamInfo().getOutputName();
                var output = ((IDataProducer)driver).getOutputs().get(outputName);
                if (output != null)
                    output.unregisterListener(dsHandler);
                
                if (sendEvents)
                    dsHandler.disable();
            }
        }
        dataStreamHandlers.clear();
        
        // if taskable procedure, unregister command streams
        if (driver instanceof ICommandReceiver)
        {
            // TODO cleanup command inputs          
        }
        commandStreamHandlers.clear();
        
        if (sendEvents)
            disable();
        driver = null;
    }
    
    
    protected CompletableFuture<Boolean> registerMember(IProcedureDriver proc)
    {
        Asserts.checkNotNull(proc, IProcedureDriver.class);
        
        return CompletableFuture.supplyAsync(() -> {
            try { return doRegisterMember(proc, null); }
            catch (DataStoreException e) { throw new CompletionException(e); }
        });
    }
    
    
    protected synchronized boolean doRegisterMember(IProcedureDriver driver, TimeExtent validTime) throws DataStoreException
    {
        Asserts.checkNotNull(driver, IProcedureDriver.class);
        var uid = OshAsserts.checkValidUID(driver.getUniqueIdentifier());
        boolean isNew = false;
        
        var procWrapper = new ProcedureWrapper(driver.getCurrentDescription())
            .hideOutputs()
            .hideTaskableParams();
        
        // also default to proper valid time
        if (validTime != null)
            procWrapper.defaultToValidTime(validTime);
        else
            procWrapper.defaultToValidFromNow();
        
        // add or update existing procedure entry
        var newMemberHandler = (ProcedureDriverTransactionHandler)addOrUpdateMember(procWrapper);
        
        // replace and cleanup old handler
        var oldMemberHandler = memberHandlers.get(uid);
        if (oldMemberHandler != null)
        {
            driver.unregisterListener(oldMemberHandler);
            isNew = false;
        }
        memberHandlers.put(uid, newMemberHandler);

        // register/update driver sub-components
        newMemberHandler.doFinishRegister(driver);
        return isNew;
    }
    
    
    protected CompletableFuture<Boolean> unregisterMember(IProcedureDriver proc)
    {
        Asserts.checkNotNull(driver, IProcedureDriver.class);
        
        return CompletableFuture.supplyAsync(() -> {
            try { return doUnregisterMember(proc); }
            catch (DataStoreException e) { throw new CompletionException(e); }
        });
    }
    
    
    protected synchronized boolean doUnregisterMember(IProcedureDriver driver) throws DataStoreException
    {
        Asserts.checkNotNull(driver, IProcedureDriver.class);
        var uid = OshAsserts.checkValidUID(driver.getUniqueIdentifier());
        
        var memberHandler = memberHandlers.remove(uid);
        if (memberHandler != null)
            memberHandler.doUnregister(true);
        
        return true;
    }


    protected CompletableFuture<Boolean> register(IStreamingDataInterface output)
    {
        Asserts.checkNotNull(output, IStreamingDataInterface.class);
        
        return CompletableFuture.supplyAsync(() -> {
            try { return doRegister(output); }
            catch (DataStoreException e) { throw new CompletionException(e); }
        });
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
        newDsHandler.parentGroupUID = this.parentGroupUID;
            
        // replace and cleanup old handler
        var oldDsHandler = dataStreamHandlers.get(output.getName());
        if (oldDsHandler != null)
        {
            output.unregisterListener(oldDsHandler);
            isNew = false;
        }
        dataStreamHandlers.put(output.getName(), newDsHandler);
        
        // add latest record if any
        if (output.getLatestRecord() != null)
            newDsHandler.addObs(output.getLatestRecord());
        
        // enable and start forwarding events
        newDsHandler.enable();
        output.registerListener(newDsHandler);
        
        return isNew;
    }


    protected CompletableFuture<Void> unregister(IStreamingDataInterface output)
    {
        doUnregister(output);
        return CompletableFuture.completedFuture(null);
    }
    
    
    protected synchronized void doUnregister(IStreamingDataInterface output)
    {
        Asserts.checkNotNull(output, IStreamingDataInterface.class);
        
        var dsHandler = dataStreamHandlers.remove(output.getName());
        if (dsHandler != null)
        {
            output.unregisterListener(dsHandler);
            dsHandler.disable();
        }
    }


    protected CompletableFuture<Boolean> register(IStreamingControlInterface controlInput)
    {
        Asserts.checkNotNull(controlInput, IStreamingControlInterface.class);
        
        return CompletableFuture.supplyAsync(() -> {
            try { return doRegister(controlInput); }
            catch (DataStoreException e) { throw new CompletionException(e); }
        });
    }
    
    
    protected synchronized boolean doRegister(IStreamingControlInterface controlInput) throws DataStoreException
    {
        Asserts.checkNotNull(controlInput, IStreamingControlInterface.class);
        boolean isNew = true;
        
        // add or update existing command stream entry
        var newCsHandler = addOrUpdateCommandStream(
            controlInput.getName(),
            controlInput.getCommandDescription(),
            new TextEncodingImpl());
        newCsHandler.parentGroupUID = this.parentGroupUID;
            
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
        
        // enable
        newCsHandler.enable();
        
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
            public void onNext(CommandEvent item)
            {
                CompletableFuture.runAsync(() -> {
                    sendCommand(item.getCommands().iterator());
                });                
            }
            
            protected void sendCommand(Iterator<ICommandData> commands)
            {
                if (commands.hasNext())
                {
                    var cmd = commands.next();
                    controlInput.executeCommand(cmd, csHandler::sendAck)
                        .thenRun(() -> sendCommand(commands));
                }
                else
                {
                    sub.request(1);
                }
            }

            @Override
            public void onError(Throwable throwable)
            {
                
            }

            @Override
            public void onComplete()
            {
            }            
        });
    }


    protected CompletableFuture<Void> unregister(IStreamingControlInterface commandStream)
    {
        doUnregister(commandStream);
        return CompletableFuture.completedFuture(null);
    }
    
    
    protected synchronized void doUnregister(IStreamingControlInterface commandStream)
    {
        Asserts.checkNotNull(commandStream, IStreamingControlInterface.class);
        
        // remove handler
        var csHandler = commandStreamHandlers.remove(commandStream.getName());
        if (csHandler != null)
            csHandler.disable();
        
        // cancel subcriptions to received commands
        var sub = commandSubscriptions.remove(commandStream.getName());
        if (sub != null)
            sub.cancel();
    }


    protected CompletableFuture<Boolean> register(IGeoFeature foi)
    {
        Asserts.checkNotNull(foi, IGeoFeature.class);
        OshAsserts.checkValidUID(foi.getUniqueIdentifier());
        
        return CompletableFuture.supplyAsync(() -> {
            try { return doRegister(foi); }
            catch (DataStoreException e) { throw new CompletionException(e); }
        });
    }
    
    
    protected synchronized boolean doRegister(IGeoFeature foi) throws DataStoreException
    {
        addOrUpdateFoi(foi);
        return false;
    }


    @Override
    public void handleEvent(Event e)
    {
        if (e instanceof FoiEvent)
        {
            if (((FoiEvent) e).getFoi() != null)
                register(((FoiEvent) e).getFoi());
        }    
        
        else if (e instanceof ProcedureEvent)
        {
            // register item if needed
            if (driver != null && driver.isEnabled())
            {
                if (driver instanceof IDataProducer)
                {
                    var outputs = ((IDataProducer)driver).getOutputs();
                    
                    if (e instanceof DataStreamAddedEvent || e instanceof DataStreamChangedEvent)
                        register(outputs.get(((DataStreamAddedEvent)e).getOutputName()));
                    else if (e instanceof DataStreamAddedEvent)
                        register(outputs.get(((DataStreamAddedEvent)e).getOutputName()));
                    // TODO handle all event types and redirect to register methods when appropriate
                }
            }
        }
    }
    
    
    protected ProcedureDriverTransactionHandler createMemberProcedureHandler(FeatureKey memberKey, String memberUID)
    {
        return new ProcedureDriverTransactionHandler(memberKey, memberUID, procUID, rootHandler);
    }

}
