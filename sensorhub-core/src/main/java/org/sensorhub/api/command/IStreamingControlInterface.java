/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2017 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.command;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.sensorhub.api.command.ICommandStatus.CommandStatusCode;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.event.IEventProducer;
import org.vast.data.TextEncodingImpl;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;


/**
 * <p>
 * Interface for all taskable components using SWE Common model to describe
 * structure and encoding of commands they accept (e.g. actuators, processes...)
 * <p></p>
 * It is the responsibility of the implementation to report task status when
 * commands are processed asynchronously. In particular, special care must be
 * taken to avoid missing status updates in case of disconnections from the
 * actual taskable system.
 * </p>
 *
 * @author Alex Robin
 * @since Mar 23, 2017
 */
public interface IStreamingControlInterface extends IEventProducer
{
    public static final String ERROR_NO_UPDATE = "Command updates are not supported by driver";
    public static final String ERROR_NO_CANCEL = "Command cancellation is not supported by driver";
    
    
    /**
     * Allows by-reference access to parent module
     * @return parent module instance
     */
    public ICommandReceiver getParentProducer();
    
    
    /**
     * Gets the interface name.
     * @return name of this control interface
     */
    public String getName();


    /**
     * Checks if this interface is enabled
     * @return true if interface is enabled, false otherwise
     */
    public boolean isEnabled();
    
    
    /**
     * Retrieves description of command message
     * Note that this can be a choice of multiple messages
     * @return Data component containing message structure
     */
    public DataComponent getCommandDescription();
    
    
    /**
     * @return The recommended encoding for the command parameters
     */
    public default DataEncoding getCommandEncoding()
    {
        return new TextEncodingImpl();
    }
    
    
    /**
     * Gets the control stream valid time. If null is returned, the valid start time of the
     * parent system will be used, or if an older version of the same control stream already 
     * exists, current time will be used instead.
     * @return the timestamp to use for the valid start time of the control stream
     */
    public default Instant getValidStartTime()
    {
        return null;
    }
    
    
    /**
     * Validates the command parameters synchronously. This is called before
     * the command is submitted for execution (it is used to avoid persisting invalid
     * commands on the sensor hub).
     * @param command
     * @throws CommandException if the command is invalid
     */
    public void validateCommand(ICommandData command) throws CommandException;
    
    
    /**
     * Submit the provided command to the receiving system.
     * <p>
     * If a command is executed or rejected immediately, the status report
     * returned by the future will have a final status code such as
     * {@link CommandStatusCode#COMPLETED COMPLETED},
     * {@link CommandStatusCode#REJECTED REJECTED}, or
     * {@link CommandStatusCode#FAILED FAILED},
     * and no further status reports are to be expected from the event channel.
     * </p><p>
     * If a command is scheduled to be executed later or takes longer to
     * execute, the initial status report returned by the future will have a
     * non-final status code and must include a task ID. Subsequent reports
     * are then sent via the dedicated event channel, using the same task ID,
     * to provide status updates to the caller.
     * </p><p>
     * The future should only complete exceptionally if there is an unexpected error
     * or internal error that should not be reported to the caller. Otherwise, all
     * errors associated to the processing of the command by the receiver system
     * should be reported in the status object instead.
     * </p>
     * @param command Command data (with ID set)
     * @return A future that will be completed normally when the system is ready to
     * receive the next command. Note that the command may not be fully executed
     * or may not even be accepted at this point. In all cases, a valid status
     * object must be returned by the future.
     */
    public CompletableFuture<ICommandStatus> submitCommand(ICommandData command);
    
    
    /**
     * Update an existing command/task.
     * @param command New command (with ID set)
     * @return A future that will complete normally if the update is accepted or
     * exceptionally if the update is rejected early.
     */
    default public CompletableFuture<Void> updateCommand(ICommandData command)
    {
        return CompletableFuture.failedFuture(new CommandException(ERROR_NO_UPDATE));
    }
    
    
    /**
     * Cancels an existing command/task.
     * @param cmdID The ID of the command to be canceled (as provided in the initial command submit)
     * @return A future that will complete normally if the cancellation is accepted or
     * exceptionally if the task cannot be canceled.
     */
    default public CompletableFuture<Void> cancelCommand(BigId cmdID)
    {
        return CompletableFuture.failedFuture(new CommandException(ERROR_NO_CANCEL));
    }
}
