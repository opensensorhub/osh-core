/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2021 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.command;

import java.time.Instant;
import org.sensorhub.api.common.BigId;
import org.vast.util.TimeExtent;


/**
 * <p>
 * Represents command status messages sent asynchronously by control interfaces.
 * </p><p>
 * The status report contains a status code identifying the current state of
 * the task/command in the state machine (see diagram in the documentation).
 * Depending of this status code, {@link #getExecutionTime()} can return the
 * estimated, scheduled or actual execution time of the command.
 * </p><p>
 * If a command is executed immediately, the instance of this class returned
 * after submitting the command will have a final status code such as
 * {@link CommandStatusCode#COMPLETED}, {@link CommandStatusCode#REJECTED}.
 * </p><p>
 * For long running tasks or commands that are scheduled for later execution,
 * the initial status report will carry a non-final status code.
 * </p>
 * @author Alex Robin
 * @date Mar 11, 2021
 */
public interface ICommandStatus
{
    /**
     * <p>
     * Status of the command provided by the receiver.
     * Command receivers must be able to report at least {@link #FAILED} and
     * {@link #COMPLETED} status, either synchronously or asynchronously.
     * All other codes are optional.
     * </p>
     */
    public enum CommandStatusCode
    {
        /**
         * The command is pending, meaning it has been received by the system
         * but no decision to accept or reject it has been taken.
         */
        PENDING(false),
        
        /**
         * The command was accepted by the receiving system. This usually means
         * that the command has passed the first validation steps but note that
         * it can still be rejected later or fail during execution.
         * An estimated execution time can optionally be provided with 
         * {@link #getExecutionTime()}
         */
        ACCEPTED(false),
        
        /**
         * The command was rejected by the receiving system. It won't be executed
         * at all and the error property provides the reason for the rejection.
         * This is a final state. No further status updates will be sent.
         */
        REJECTED(true),
        
        /**
         * The command was validated and effectively scheduled by the receiving system.
         * When this status code is used, the scheduled execution time must be provided
         * with {@link #getExecutionTime()}.<br/>
         * Note that systems are not required to notify the planned execution time
         * before execution.
         */
        SCHEDULED(false),
        
        /**
         * An update to the command was received and accepted. This code must be used
         * if the system supports task updates.
         * See the equivalent {@link #ACCEPTED} state for other details.
         */
        UPDATED(false),
        
        /**
         * The command was canceled by an authorized user.
         * This code must be used if the system supports user driven task cancellations.
         * The {@link #REJECTED} state should be used instead if the command
         * was canceled by the receiving system.
         * This is a final state. No further status updates will be sent.
         */
        CANCELED(true),
        
        /**
         * The command is currently being executed by the receiving system.
         * The status message can provide more information about the current
         * progress. A system can send several status updates with this code
         * but different time stamps to report progress incrementally
         * (e.g. for multi-stage executions). In particular, the progress
         * percentage and the end of the (estimated) execution time period
         * can be refined in each update.
         */
        EXECUTING(false),
        
        /**
         * The command has failed during execution. The error and/or status message
         * provides the reason for failure.
         * This is a final state. No further status updates will be sent.
         */
        FAILED(true),
        
        /**
         * The command has completed after a successful execution.
         * The actual execution time must be provided with {@link #getExecutionTime()}.
         * This is a final state. No further status updates will be sent.
         */
        COMPLETED(true);
        
        
        final boolean finalState;
        
        private CommandStatusCode(boolean finalState)
        {
            this.finalState = finalState;
        }
        
        /**
         * @return True if this state is final, false otherwise
         */
        public boolean isFinal()
        {
            return finalState;
        }
    }
    
    
    /**
     * @return The internal ID of the command that this status relates to. 
     * The initial status report must always include the command ID.
     */
    BigId getCommandID();
    
    
    /**
     * @return The time at which this status report was generated by the command
     * receiver
     */
    Instant getReportTime();
    
    
    /**
     * @return The command status code
     */
    CommandStatusCode getStatusCode();
    
    
    /**
     * @return The execution time of the command. This can be an estimated,
     * scheduled or actual execution time depending on the command status.
     * See {@link CommandStatusCode}
     */
    TimeExtent getExecutionTime();
    
    
    /**
     * @return The percentage of progress if it can be computed, -1 otherwise.
     */
    int getProgress();
    
    
    /**
     * @return A status message, which can be either an error message or an
     * informative message, depending on the status code
     */
    String getMessage();
    
    
    /**
     * @return The result(s) of the command (either inline of by reference)
     */
    ICommandResult getResult();
    
    
    default boolean isFinal()
    {
        var statusCode = getStatusCode();
        return statusCode != null && statusCode.isFinal();
    }
}
