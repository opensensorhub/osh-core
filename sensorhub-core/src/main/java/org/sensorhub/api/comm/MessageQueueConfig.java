package org.sensorhub.api.comm;

import org.sensorhub.api.config.DisplayInfo;
import org.sensorhub.api.module.SubModuleConfig;


public abstract class MessageQueueConfig extends SubModuleConfig
{
    @DisplayInfo(desc="Name of topic/queue to use")
    public String topicName;
    
    @DisplayInfo(desc="Enable/disable writing to queue")
    public boolean enablePublish;
    
    @DisplayInfo(desc="Enable/disable reading from queue")
    public boolean enableSubscribe;
}
