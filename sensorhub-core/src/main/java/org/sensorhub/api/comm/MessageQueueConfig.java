package org.sensorhub.api.comm;

import org.sensorhub.api.config.DisplayInfo;
import org.sensorhub.api.module.SubModuleConfig;


public abstract class MessageQueueConfig extends SubModuleConfig
{
    @DisplayInfo(desc="Name of topic/queue to use for publish and subscribe operations")
    public String topicName;
    
    @DisplayInfo(desc="Name of subscription to use for publish and subscribe operations")
    public String subscriptionName;
    
    @DisplayInfo(desc="Enable/disable publisher")
    public boolean enablePublish;
    
    @DisplayInfo(desc="Enable/disable subscriber")
    public boolean enableSubscribe;
    
    @DisplayInfo(desc="Set to always use a new subscription (i.e. with no message history)")
    public boolean deleteOldSubscription;
}
