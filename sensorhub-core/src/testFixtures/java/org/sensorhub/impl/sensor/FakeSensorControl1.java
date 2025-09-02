/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.sensor;

import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataRecord;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import org.sensorhub.api.command.CommandStatus;
import org.sensorhub.api.command.CommandStatusEvent;
import org.sensorhub.api.command.CommandException;
import org.sensorhub.api.command.ICommandStatus;
import org.sensorhub.api.command.ICommandData;
import org.sensorhub.api.command.IStreamingControlInterface;
import org.vast.swe.SWEHelper;


/**
 * <p>
 * Fake control input implementation for testing sensor control API
 * </p>
 *
 * @author Alex Robin
 * @since Jan 29, 2015
 */
public class FakeSensorControl1 extends AbstractSensorControl<FakeSensor> implements IStreamingControlInterface
{
    int counter = 1;
    DataRecord commandStruct;
    ArrayList<DataBlock> receivedCommands = new ArrayList<DataBlock>();
    
    
    public FakeSensorControl1(FakeSensor parentSensor)
    {
        this(parentSensor, "command1");
    }
    
    
    public FakeSensorControl1(FakeSensor parentSensor, String name)
    {
        super(name, parentSensor);
        
        var swe = new SWEHelper();
        this.commandStruct = swe.createRecord()
            .name(name)
            .definition("urn:test:def:command")
            .addField("samplingPeriod", swe.createQuantity()
                .definition("urn:test:def:samplingPeriod")
                .uomCode("s"))
            .addField("sensitivity", swe.createCategory()
                .definition("urn:test:def:sensitivity")
                .addAllowedValues("LOW", "HIGH"))
            .build();
    }


    @Override
    public DataComponent getCommandDescription()
    {
        return commandStruct;
    }


    @Override
    public CompletableFuture<ICommandStatus> submitCommand(ICommandData command)
    {
        return CompletableFuture.supplyAsync(() -> {
            System.out.println("Received command: " + command);
            receivedCommands.add(command.getParams());
            var status = CommandStatus.completed(command.getID());
            eventHandler.publish(new CommandStatusEvent(this, 0, status));
            return status;
        });
    }


    @Override
    public void validateCommand(ICommandData command) throws CommandException
    {        
    }
    
    
    public ArrayList<DataBlock> getReceivedCommands()
    {
        return receivedCommands;
    }

}
