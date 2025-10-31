/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.command;

import java.time.Instant;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.utils.OshAsserts;
import org.sensorhub.utils.ObjectUtils;
import org.vast.util.Asserts;
import org.vast.util.BaseBuilder;
import org.vast.util.TimeExtent;


/**
 * <p>
 * Immutable class used as command status report
 * </p>
 *
 * @author Alex Robin
 * @date Mar 10, 2021
 */
public class CommandStatus implements ICommandStatus
{
    protected BigId commandID;
    protected Instant reportTime;
    protected TimeExtent executionTime;
    protected CommandStatusCode statusCode;
    protected int progress = -1;
    protected String message;
    protected ICommandResult result;
    
    
    protected CommandStatus()
    {
        // can only instantiate with builder or static methods
    }
    
    
    protected CommandStatus(BigId commandID, CommandStatusCode statusCode, TimeExtent executionTime)
    {
        this.commandID = commandID;
        this.reportTime = Instant.now();
        this.executionTime = executionTime;
        this.statusCode = Asserts.checkNotNull(statusCode, CommandStatusCode.class);
    }
    
    
    /**
     * Generate a status report for a command that was immediately executed
     * and completed successfully
     * @param commandID The ID of the command triggering the report
     * @return The status report
     */
    public static ICommandStatus completed(BigId commandID)
    {
        return new CommandStatus(commandID, CommandStatusCode.COMPLETED, TimeExtent.currentTime());
    }
    
    
    /**
     * Generate a status report for a command that was immediately rejected
     * @param commandID The ID of the command triggering the report
     * @param errorMsg The error message
     * @return The status report
     */
    public static ICommandStatus rejected(BigId commandID, String errorMsg)
    {
        var status = new CommandStatus(commandID, CommandStatusCode.REJECTED, null);
        status.message = errorMsg;
        return status;
    }
    
    
    /**
     * Generate a status report for a command that failed during execution
     * @param commandID The ID of the command triggering the report
     * @param errorMsg The error message
     * @return The status report
     */
    public static ICommandStatus failed(BigId commandID, String errorMsg)
    {
        var status = new CommandStatus(commandID, CommandStatusCode.FAILED, null);
        status.message = errorMsg;
        return status;
    }
    
    
    /**
     * Generate a status report for a command that is pending and will be
     * processed later on
     * @param commandID The ID of the command triggering the report
     * @return The status report
     */
    public static ICommandStatus pending(BigId commandID)
    {
        return new CommandStatus(commandID, CommandStatusCode.PENDING, null);
    }
    
    
    /**
     * Generate a status report for a command that was immediately accepted
     * but will be executed later on
     * @param commandID The ID of the command triggering the report
     * @return The status report
     */
    public static ICommandStatus accepted(BigId commandID)
    {
        return new CommandStatus(commandID, CommandStatusCode.ACCEPTED, null);
    }
    
    
    /**
     * Generate a status report for a command that is scheduled to be 
     * executed later on
     * @param commandID The ID of the command triggering the report
     * @param execTime The time (instant or period) at which the command
     * is scheduled to execute
     * @return The status report
     */
    public static ICommandStatus scheduled(BigId commandID, TimeExtent execTime)
    {
        return new CommandStatus(commandID, CommandStatusCode.SCHEDULED, execTime);
    }
    
    
    /**
     * Generate a status report for a command that is in-progress
     * @param commandID The ID of the command triggering the report
     * @param progress The estimated progress expressed in percent
     * @param message A message describing the current progress (can be null)
     * @param execTime The new estimate of the execution time
     * @return The status report
     */
    public static ICommandStatus progress(BigId commandID, int progress, String message, TimeExtent execTime)
    {
        var status = new CommandStatus(commandID, CommandStatusCode.EXECUTING, execTime);
        status.progress = progress;
        status.message = message;
        return status;
    }
    
    
    /**
     * Generate a status report for a command that was asynchronously executed
     * and completed successfully
     * @param commandID The ID of the command triggering the report
     * @param execTime The actual time (or time period) the command was executed
     * @return The status report
     */
    public static ICommandStatus completed(BigId commandID, TimeExtent execTime)
    {
        return new CommandStatus(commandID, CommandStatusCode.COMPLETED, execTime);
    }
    
    
    /**
     * Generate a status report for a command that was asynchronously executed
     * and completed successfully with a result
     * @param commandID The ID of the command triggering the report
     * @param execTime The actual time (or time period) the command was executed
     * @param result The result (observations) produced during execution of the command
     * @return The status report
     */
    public static ICommandStatus completed(BigId commandID, TimeExtent execTime, ICommandResult result)
    {
        var status = new CommandStatus(commandID, CommandStatusCode.COMPLETED, execTime);
        status.result = Asserts.checkNotNull(result, ICommandResult.class);
        return status;
    }

    /**
     * Generate a status report for a command that was asynchronously executed
     * and completed successfully with a result
     * @param commandID The ID of the command triggering the report
     * @param execTime The actual time (or time period) the command was executed
     * @param result The result (observations) produced during execution of the command
     * @param message The message
     * @return The status report
     */
    public static ICommandStatus completed(BigId commandID, TimeExtent execTime, ICommandResult result, String message)
    {
        var status = new CommandStatus(commandID, CommandStatusCode.COMPLETED, execTime);
        status.result = Asserts.checkNotNull(result, ICommandResult.class);
        status.message = message;
        return status;
    }

    @Override
    public BigId getCommandID()
    {
        return commandID;
    }


    @Override
    public Instant getReportTime()
    {
        return reportTime;
    }


    @Override
    public CommandStatusCode getStatusCode()
    {
        return statusCode;
    }


    @Override
    public TimeExtent getExecutionTime()
    {
        return executionTime;
    }


    @Override
    public int getProgress()
    {
        return progress;
    }


    @Override
    public String getMessage()
    {
        return message;
    }


    @Override
    public ICommandResult getResult()
    {
        return result;
    }


    @Override
    public String toString()
    {
        return ObjectUtils.toString(this, true);
    }
    
    
    /*
     * Builder
     */
    public static class Builder extends CommandStatusBuilder<Builder, CommandStatus>
    {
        public Builder()
        {
            this.instance = new CommandStatus();
        }
        
        public static Builder from(ICommandStatus base)
        {
            return new Builder().copyFrom(base);
        }
    }
    
    
    @SuppressWarnings("unchecked")
    public abstract static class CommandStatusBuilder<
            B extends CommandStatusBuilder<B, T>,
            T extends CommandStatus>
        extends BaseBuilder<T>
    {       
        protected CommandStatusBuilder()
        {
        }
        
        
        protected B copyFrom(ICommandStatus base)
        {
            instance.commandID = base.getCommandID();
            instance.reportTime = base.getReportTime();
            instance.executionTime = base.getExecutionTime();
            instance.statusCode = base.getStatusCode();
            instance.progress = base.getProgress();
            instance.message = base.getMessage();
            instance.result = base.getResult();
            return (B)this;
        }


        public B withCommand(BigId id)
        {
            instance.commandID = id;
            return (B)this;
        }
        
        
        public B withReportTime(Instant reportTime)
        {
            instance.reportTime = reportTime;
            return (B)this;
        }
        
        
        public B withExecutionTime(TimeExtent execTime)
        {
            instance.executionTime = execTime;
            return (B)this;
        }


        public B withStatusCode(CommandStatusCode statusCode)
        {
            instance.statusCode = statusCode;
            return (B)this;
        }


        public B withProgress(int progress)
        {
            instance.progress = progress;
            return (B)this;
        }


        public B withMessage(String message)
        {
            instance.message = message;
            return (B)this;
        }


        public B withResult(ICommandResult result)
        {
            instance.result = result;
            return (B)this;
        }
        
        
        @Override
        public T build()
        {
            OshAsserts.checkValidInternalID(instance.commandID, "commandID");
            if (instance.reportTime == null)
                instance.reportTime = Instant.now();
            return super.build();
        }
    }
}
