/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2021 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.system;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.sensorhub.api.command.CommandStatusEvent;
import org.sensorhub.api.command.CommandData;
import org.sensorhub.api.command.CommandEvent;
import org.sensorhub.api.command.CommandStatus;
import org.sensorhub.api.command.CommandStreamChangedEvent;
import org.sensorhub.api.command.CommandStreamDisabledEvent;
import org.sensorhub.api.command.CommandStreamEnabledEvent;
import org.sensorhub.api.command.CommandStreamEvent;
import org.sensorhub.api.command.CommandStreamInfo;
import org.sensorhub.api.command.CommandStreamRemovedEvent;
import org.sensorhub.api.command.ICommandStatus;
import org.sensorhub.api.command.ICommandData;
import org.sensorhub.api.command.ICommandStreamInfo;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.command.CommandStreamKey;
import org.sensorhub.api.datastore.command.ICommandStreamStore;
import org.sensorhub.api.event.Event;
import org.sensorhub.api.event.EventUtils;
import org.sensorhub.api.event.IEventListener;
import org.sensorhub.api.event.IEventPublisher;
import org.sensorhub.impl.event.DelegatingSubscriber;
import org.sensorhub.impl.event.DelegatingSubscriberAdapter;
import org.sensorhub.impl.event.DelegatingSubscription;
import org.sensorhub.utils.DataComponentChecks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.util.Asserts;


/**
 * <p>
 * Helper class for creating/updating/deleting command streams and persisting 
 * command messages in the associated datastore, as well as publishing the
 * corresponding events.
 * </p>
 *
 * @author Alex Robin
 * @date Mar 21, 2021
 */
public class CommandStreamTransactionHandler implements IEventListener
{
    static Logger log = LoggerFactory.getLogger(CommandStreamTransactionHandler.class);
    protected final SystemDatabaseTransactionHandler rootHandler;
    protected final CommandStreamKey csKey; // local DB key
    protected ICommandStreamInfo csInfo;
    protected IEventPublisher commandDataEventPublisher;
    protected IEventPublisher cmdStatusEventPublisher;
    
    /*
     * csKey must always be the local DB key
     */
    CommandStreamTransactionHandler(CommandStreamKey csKey, ICommandStreamInfo csInfo, SystemDatabaseTransactionHandler rootHandler)
    {
        this.csKey = csKey;
        this.csInfo = csInfo;
        this.rootHandler = rootHandler;
    }
    
    
    public boolean update(ICommandStreamInfo csInfo) throws DataStoreException
    {
        var oldCsInfo = this.csInfo;
        if (oldCsInfo == null)
            return false;
        
        // check control input name wasn't changed
        if (!csInfo.getControlInputName().equals(oldCsInfo.getControlInputName()))
            throw new DataStoreException("The system control input (controlInputName) associated to a command stream cannot be changed");
        
        // check if command stream already has commands
        var hasCmd = oldCsInfo.getIssueTimeRange() != null;
        if (hasCmd &&
            (!DataComponentChecks.checkStructCompatible(oldCsInfo.getRecordStructure(), csInfo.getRecordStructure()) ||
             !DataComponentChecks.checkEncodingEquals(oldCsInfo.getRecordEncoding(), csInfo.getRecordEncoding()) ||
             !DataComponentChecks.checkStructCompatibleNullAllowed(oldCsInfo.getResultStructure(), csInfo.getResultStructure()) ||
             !DataComponentChecks.checkEncodingEqualsNullAllowed(oldCsInfo.getResultEncoding(), csInfo.getResultEncoding())))
            throw new DataStoreException("Cannot update the structure or encoding of a command interface if it already has received commands");
        
        // update command stream info
        var newCsInfo = new CommandStreamInfo.Builder()
            .withName(csInfo.getName())
            .withDescription(csInfo.getDescription())
            .withSystem(oldCsInfo.getSystemID())
            .withRecordDescription(csInfo.getRecordStructure())
            .withRecordEncoding(csInfo.getRecordEncoding())
            .withResultDescription(csInfo.getResultStructure())
            .withResultEncoding(csInfo.getResultEncoding())
            .withValidTime(oldCsInfo.getValidTime())
            .build();
        getCommandStreamStore().replace(csKey, newCsInfo);
        this.csInfo = newCsInfo;
        
        // send event
        var event = new CommandStreamChangedEvent(csInfo);
        publishCommandStreamEvent(event);
        
        return true;
    }
    
    
    public boolean delete() throws DataStoreException
    {
        var oldCsKey = getCommandStreamStore().remove(csKey);
        if (oldCsKey == null)
            return false;
        
        // if command stream was currently valid, disable it first
        if (csInfo.getValidTime().endsNow())
            disable();
        
        // send event
        var event = new CommandStreamRemovedEvent(csInfo);
        publishCommandStreamEvent(event);
        
        return true;
    }
    
    
    public void enable()
    {
        var event = new CommandStreamEnabledEvent(csInfo);
        publishCommandStreamEvent(event);
    }
    
    
    public void disable()
    {
        var event = new CommandStreamDisabledEvent(csInfo);
        publishCommandStreamEvent(event);
    }
    
    
    public void connectCommandReceiver(Subscriber<CommandEvent> subscriber)
    {
        connectCommandReceiver(subscriber, 5000);
    }
    
    
    public void connectCommandReceiver(Subscriber<CommandEvent> subscriber, long ackTimeout)
    {
        Asserts.checkNotNull(subscriber, Subscriber.class);
        
        var dataTopic = EventUtils.getCommandDataTopicID(csInfo);
        if (rootHandler.eventBus.getNumberOfSubscribers(dataTopic) > 0)
            throw new IllegalStateException("A command receiver is already connected to " + dataTopic);
        
        // monitor status messages received from receiver to detect timeouts
        var pendingCommands = new ConcurrentHashMap<Long, ICommandData>();
        Subscription statusSub;
        try
        {
            var statusTopic = EventUtils.getCommandStatusTopicID(csInfo);
            statusSub = rootHandler.eventBus.newSubscription(CommandStatusEvent.class)
                .withTopicID(statusTopic)
                .consume(e -> {
                    pendingCommands.remove(e.getCorrelationID());
                })
                .get();
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Unable to subscribe to command ACK", e);
        }
        
        // subscribe to receive command on this channel
        rootHandler.eventBus.newSubscription(CommandEvent.class)
            .withTopicID(dataTopic)
            .subscribe(new DelegatingSubscriber<CommandEvent>(subscriber) {
                @Override
                public void onSubscribe(Subscription sub)
                {
                    super.onSubscribe(new DelegatingSubscription(sub) {
                        public void cancel() {
                            // also cancel status subscription
                            statusSub.cancel();
                            super.cancel();
                        }
                    });
                }
                
                @Override
                public void onNext(CommandEvent e)
                {
                    log.debug("Command {}: Received\n{}", e.getCorrelationID(), e.getCommand());
                    
                    // need to use internal stream ID
                    var cmd = CommandData.Builder.from(e.getCommand())
                        .withCommandStream(csKey.getInternalID())
                        .build();
                    
                    // add command to DB
                    var cmdKey = rootHandler.db.getCommandStore().add(cmd);
                    
                    // assign ID and mark as pending
                    e.getCommand().assignID(cmdKey);
                    pendingCommands.put(e.getCorrelationID(), e.getCommand());
                    
                    // forward to command receiver
                    super.onNext(e);
                    
                    // send pending status on timeout
                    var delayer = CompletableFuture.delayedExecutor(ackTimeout, TimeUnit.MILLISECONDS);
                    delayer.execute(() -> {
                        var pendingCmd = pendingCommands.remove(e.getCorrelationID());
                        if (pendingCmd != null)
                        {
                            log.info("Command {}: ACK timeout", e.getCorrelationID());
                            sendStatus(
                                e.getCorrelationID(),
                                CommandStatus.pending(pendingCmd.getID()));
                        }
                    });
                }
            });
    }


    public void connectStatusReceiver(Subscriber<CommandStatusEvent> subscriber)
    {
        Asserts.checkNotNull(subscriber, Subscriber.class);
        
        var statusTopic = EventUtils.getCommandStatusTopicID(csInfo);
        rootHandler.eventBus.newSubscription(CommandStatusEvent.class)
            .withTopicID(statusTopic)
            .subscribe(subscriber);
    }


    /**
     * Connect status receiver for a specific command
     * @param correlationID
     * @param subscriber
     */
    public void connectStatusReceiver(long correlationID, Subscriber<ICommandStatus> subscriber)
    {
        Asserts.checkNotNull(subscriber, Subscriber.class);
        
        // create filtered subscriber specific for this command
        // subscription will be automatically canceled at the end
        var cmdSubscriber = new DelegatingSubscriberAdapter<CommandStatusEvent, ICommandStatus>(subscriber) {
            Subscription subscription;
            BigId cmdID = null;

            @Override
            public void onSubscribe(Subscription subscription)
            {
                this.subscription = subscription;
                subscriber.onSubscribe(subscription);
            }
            
            @Override
            public void onNext(CommandStatusEvent event)
            {
                boolean isMyCommand = false;
                
                // initially use correlation ID to get command ID
                // then compare command IDs because correlation ID may not be set afterwards
                if (event.getStatus().getCommandID().equals(cmdID))
                {
                    isMyCommand = true;
                }
                else if (event.getCorrelationID() == correlationID)
                {
                    isMyCommand = true;
                    cmdID = event.getStatus().getCommandID();
                }
                
                if (isMyCommand)
                {
                    log.debug("Command {}: Status received\n{}", event.getCorrelationID(), event.getStatus());
                    subscriber.onNext(event.getStatus());
                    
                    // cancel subscription if this status is final
                    if (event.getStatus().getStatusCode().isFinal())
                    {
                        subscriber.onComplete();
                        subscription.cancel();
                    }
                }
                else
                    subscription.request(1);
            }
        };
        
        // register subscriber specific for this command
        try
        {
            var statusTopic = EventUtils.getCommandStatusTopicID(csInfo);
            rootHandler.eventBus.newSubscription(CommandStatusEvent.class)
                .withTopicID(statusTopic)
                .subscribe(cmdSubscriber)
                .get();
        }
        catch (Exception e)
        {
            log.error("Error subscribing to status events", e);
            throw new IllegalStateException(e);
        }
    }
    
    
    /**
     * Submit a command and receive status reports asynchronously
     * @param correlationID Correlation ID to attach to the command
     * @param cmd The command to submit
     * @param subscriber Subscriber that will be notified every time a new
     * status report is available for the command.
     */
    public void submitCommand(long correlationID, ICommandData cmd, Subscriber<ICommandStatus> subscriber)
    {
        // check command stream is correct
        Asserts.checkArgument(cmd.getCommandStreamID() == csKey.getInternalID(), "Invalid command stream ID");
        
        // TODO validate command
        
        // register status callback if provided
        if (subscriber != null)
            connectStatusReceiver(correlationID, subscriber);
        
        // if this is connected to a local driver, reject command if no command receiver is listening
        var cmdPublisher = getCommandDataEventPublisher();
        if (this.rootHandler instanceof SystemRegistryTransactionHandler) {
            if (cmdPublisher.getNumberOfSubscribers() == 0)
            {
                publishStatusEvent(
                    correlationID,
                    CommandStatus.rejected(BigId.NONE, "Receiving system is disabled"));
                return;
            }
        }
        
        // send command to bus
        cmdPublisher.publish(new CommandEvent(
            System.currentTimeMillis(),
            csInfo.getSystemID().getUniqueID(),
            csInfo.getControlInputName(),
            cmd, correlationID));
    }
    
    
    /**
     * Submit a command w/o registering for status updates
     * @param correlationID Correlation ID to attach to the command
     * @param cmd The command to submit
     */
    public void submitCommandNoStatus(long correlationID, ICommandData cmd)
    {
        submitCommand(correlationID, cmd, null);
    }
    
    
    /**
     * Submit a command and receive only the first status report via a future
     * @param correlationID Correlation ID to attach to the command
     * @param cmd The command to submit
     * @return A future that will complete when the initial status report is received.
     * The future will wait forever for a status message to be received unless its
     * {@link CompletableFuture#cancel cancel} method is called.
     * (this initial status report contains the ID assigned to the command)
     */
    public CompletableFuture<ICommandStatus> submitCommand(long correlationID, ICommandData cmd)
    {
        return submitCommand(correlationID, cmd, Long.MAX_VALUE, TimeUnit.MILLISECONDS);
    }
    
    
    /**
     * Submit a command and receive only the first status report via a future
     * @param correlationID Correlation ID to attach to the command
     * @param cmd The command to submit
     * @param timeOut How long to wait for the first ack/status message before
     * completing the future exceptionally with a TimeOutException
     * @param unit A TimeUnit determining how to interpret the timeout parameter
     * @return A future that will complete when the initial status report is received
     * (this initial status report contains the ID assigned to the command)
     */
    public CompletableFuture<ICommandStatus> submitCommand(long correlationID, ICommandData cmd, long timeOut, TimeUnit unit)
    {
        // create a future that will complete when status subscriber
        // receives the first status message
        var future = new CompletableFuture<ICommandStatus>() {
            Subscription subscription;
            public boolean cancel(boolean interrupt) {
                subscription.cancel();
                return super.cancel(interrupt);
            }
        };
        
        var subscriber = new Subscriber<ICommandStatus>() {
            Subscription subscription;
            
            @Override
            public void onSubscribe(Subscription subscription)
            {
                this.subscription = subscription;
                future.subscription = subscription;
                subscription.request(1);
            }

            @Override
            public void onNext(ICommandStatus item)
            {
                subscription.cancel();
                future.complete(item);
            }

            @Override
            public void onError(Throwable e)
            {
                log.error("Error while listening for status event", e);
                future.completeExceptionally(e);
            }

            @Override
            public void onComplete()
            {
            }
        };
        
        if (timeOut != Long.MAX_VALUE)
        {
            // cancel future and subscription on timeout
            var delayer = CompletableFuture.delayedExecutor(timeOut, unit);
            delayer.execute(() -> {
                if (!future.isDone())
                {
                    if (subscriber.subscription != null)
                        subscriber.subscription.cancel();
                    future.completeExceptionally(new TimeoutException());
                }
            });
        }
        
        submitCommand(correlationID, cmd, subscriber);
        
        return future;
    }
    
    
    public BigId sendStatus(long correlationID, ICommandStatus status)
    {
        log.debug("Command {}: Sending status\n{}", correlationID, status);
        
        // forward to event bus
        publishStatusEvent(correlationID, status);
        
        // store command status in DB
        return rootHandler.db.getCommandStatusStore().add(status);
    }
    
    
    protected void publishStatusEvent(long correlationID, ICommandStatus status)
    {
        getCommandStatusEventPublisher().publish(new CommandStatusEvent(
            System.currentTimeMillis(),
            csInfo.getSystemID().getUniqueID(),
            csInfo.getControlInputName(),
            correlationID,
            status));
    }


    @Override
    public void handleEvent(Event e)
    {
        if (e instanceof CommandStatusEvent)
        {
            var status = ((CommandStatusEvent) e).getStatus();
            sendStatus(-1, status); // no correlation ID on subsequent events
        }
    }
    
    
    protected synchronized IEventPublisher getCommandDataEventPublisher()
    {
        // create event publisher if needed
        // cache it because we need it often
        if (commandDataEventPublisher == null)
        {
            var topic = EventUtils.getCommandDataTopicID(csInfo);
            commandDataEventPublisher = rootHandler.eventBus.getPublisher(topic);
        }
         
        return commandDataEventPublisher;
    }
    
    
    protected synchronized IEventPublisher getCommandStatusEventPublisher()
    {
        // create event publisher if needed
        // cache it because we need it often
        if (cmdStatusEventPublisher == null)
        {
            var topic = EventUtils.getCommandStatusTopicID(csInfo);
            cmdStatusEventPublisher = rootHandler.eventBus.getPublisher(topic);
        }
         
        return cmdStatusEventPublisher;
    }
    
    
    protected void publishCommandStreamEvent(CommandStreamEvent event)
    {
        String topic;
        
        // assign internal ID before event is dispatched
        event.assignSystemID(csInfo.getSystemID().getInternalID());
        event.assignCommandStreamID(csKey.getInternalID());
        
        // publish on this datastream status channel
        topic = EventUtils.getCommandStreamStatusTopicID(csInfo);
        rootHandler.eventBus.getPublisher(topic).publish(event);
        
        // publish on system status channel
        var sysUid = csInfo.getSystemID().getUniqueID();
        topic = EventUtils.getSystemStatusTopicID(sysUid);
        rootHandler.eventBus.getPublisher(topic).publish(event);
        
        // publish on parent systems status recursively
        //Long parentId = rootHandler.db.getSystemDescStore().getCurrentVersionKey(sysUid).getInternalID();
        var parentId = csInfo.getSystemID().getInternalID();
        while ((parentId = rootHandler.db.getSystemDescStore().getParent(parentId)) != null)
        {
            sysUid = rootHandler.db.getSystemDescStore().getCurrentVersion(parentId).getUniqueIdentifier();
            topic = EventUtils.getSystemStatusTopicID(sysUid);
            rootHandler.eventBus.getPublisher(topic).publish(event);
        }
        
        // publish on systems root
        topic = EventUtils.getSystemRegistryTopicID();
        rootHandler.eventBus.getPublisher(topic).publish(event);
    }
    
    
    protected ICommandStreamStore getCommandStreamStore()
    {
        return rootHandler.db.getCommandStreamStore();
    }
    
    
    public CommandStreamKey getCommandStreamKey()
    {
        return csKey;
    }
    
    
    public ICommandStreamInfo getCommandStreamInfo()
    {
        return csInfo;
    }
}
