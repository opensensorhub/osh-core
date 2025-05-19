/***************************** BEGIN COPYRIGHT BLOCK **************************

Copyright (C) 2024 Delta Air Lines, Inc. All Rights Reserved.

Notice: All information contained herein is, and remains the property of
Delta Air Lines, Inc. The intellectual and technical concepts contained herein
are proprietary to Delta Air Lines, Inc. and may be covered by U.S. and Foreign
Patents, patents in process, and are protected by trade secret or copyright law.
Dissemination, reproduction or modification of this material is strictly
forbidden unless prior written permission is obtained from Delta Air Lines, Inc.

******************************* END COPYRIGHT BLOCK ***************************/

package org.sensorhub.impl.comm;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Flow.Subscription;
import org.sensorhub.api.comm.IMessageQueuePush;
import org.sensorhub.api.comm.MessageQueueConfig;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.event.Event;
import org.sensorhub.api.event.IEventPublisher;
import org.sensorhub.impl.module.AbstractSubModule;
import org.vast.util.Asserts;


/**
 * <p>
 * Local message queue implementation based on Java Publisher.
 * </p>
 *
 * @author Alex Robin
 * @since Jan 31, 2025
 */
public class LocalMessageQueue extends AbstractSubModule<MessageQueueConfig> implements IMessageQueuePush<MessageQueueConfig>
{
    Set<MessageListener> listeners = new CopyOnWriteArraySet<>();
    IEventPublisher publisher;
    Subscription sub;
    
    
    public static class LocalMqMessage extends Event
    {
        Map<String, String> attrs;
        byte[] payload;
        
        public LocalMqMessage(byte[] payload)
        {
            this.payload = payload;
        }
        
        public LocalMqMessage(Map<String, String> attrs, byte[] payload)
        {
            this.attrs = attrs;
            this.payload = payload;
        }
        
        @Override
        public String getSourceID()
        {
            return "LOCAL";
        }
    }
    
    
    @Override
    public void init(final MessageQueueConfig config) throws SensorHubException
    {
        super.init(config);
    }


    @Override
    public void publish(byte[] payload)
    {
        publish(null, payload);
    }


    @Override
    public void publish(Map<String, String> attrs, byte[] payload)
    {
        var msg = new LocalMqMessage(attrs, payload);
        
        if (publisher != null)
            publisher.publish(msg);
        else
            publishToListeners(msg);
    }


    @Override
    public void registerListener(MessageListener listener)
    {
        listeners.add(listener);
    }


    @Override
    public void unregisterListener(MessageListener listener)
    {
        listeners.remove(listener);
    }


    @Override
    public void start()
    {
        if (config.topicName != null)
        {
            Asserts.checkNotNull(parentModule, "parentModule");
            Asserts.checkNotNull(parentModule.getParentHub(), "parentHub");
            Asserts.checkNotNull(parentModule.getParentHub().getEventBus(), "eventBus");
            
            try
            {
                var eventBus = parentModule.getParentHub().getEventBus();
                this.publisher = eventBus.getPublisher(config.topicName);
                            
                this.sub = eventBus.newSubscription(LocalMqMessage.class)
                    .withTopicID(config.topicName)
                    .subscribe(e -> {
                        publishToListeners(e);
                        this.sub.request(1);
                    }).get();
                this.sub.request(1);
            }
            catch (Exception e)
            {
                parentModule.getLogger().error("Error connecting to event bus", e);
            }
        }
    }
    
    
    void publishToListeners(LocalMqMessage msg)
    {
        var attrs = ((LocalMqMessage)msg).attrs;
        if (attrs == null)
            attrs = Collections.emptyMap();
        var payload = ((LocalMqMessage)msg).payload;
        for (var l: listeners)
            l.receive(attrs, payload);
    }


    @Override
    public void stop()
    {
        if (this.sub != null)
            this.sub.cancel();
    }

}
