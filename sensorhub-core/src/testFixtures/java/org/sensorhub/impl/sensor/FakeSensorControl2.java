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

import net.opengis.swe.v20.Category;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.ValidationException;
import org.sensorhub.api.command.*;
import org.sensorhub.impl.sensor.AbstractSensorControl;
import org.sensorhub.impl.sensor.FakeSensor;
import org.vast.swe.SWEHelper;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;


/**
 * <p>
 * Fake control input implementation for testing sensor control API
 * </p>
 *
 * @author Alex Robin
 * @since Jan 29, 2015
 */
public class FakeSensorControl2 extends AbstractSensorControl<FakeSensor> implements IStreamingControlInterface
{
    int counter = 1;
    Category commandStruct;
    
    
    public FakeSensorControl2(FakeSensor parentSensor)
    {
        super("command2", parentSensor);
        
        var swe = new SWEHelper();
        this.commandStruct = swe.createCategory()
            .name(name)
            .definition("urn:test:def:trigger")
            .addAllowedValues("NOW", "REPEAT", "STOP")
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
        counter++;
        var status = CommandStatus.completed(command.getID());
        return CompletableFuture.completedFuture(status);
    }


    @Override
    public void validateCommand(ICommandData command) throws CommandException
    {
        var cmdStruct = commandStruct.copy();
        cmdStruct.setData(command.getParams());
        
        var errors = new ArrayList<ValidationException>();
        cmdStruct.validateData(errors);
        if (!errors.isEmpty())
            throw new CommandException(errors.get(0).getMessage());
    }

}
