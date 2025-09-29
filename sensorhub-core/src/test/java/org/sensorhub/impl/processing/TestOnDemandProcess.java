/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.processing;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sensorhub.api.event.Event;
import org.sensorhub.api.command.CommandData;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.api.event.IEventListener;
import org.sensorhub.api.processing.IProcessModule;
import org.sensorhub.api.processing.ProcessConfig;
import org.sensorhub.impl.SensorHub;
import org.sensorhub.impl.module.ModuleRegistry;
import org.sensorhub.impl.system.SystemDatabaseTransactionHandler;
import org.vast.cdm.common.DataStreamWriter;


public class TestOnDemandProcess implements IEventListener
{
    static String TEST_PROCESS_ID = "TEST_PROCESS1";
    static String NAME_OUTPUT1 = "y";
    static final double SAMPLING_PERIOD = 0.1;
    static final int SAMPLE_COUNT = 10;

    SensorHub hub;
    ModuleRegistry registry;
    DataStreamWriter writer;
    volatile int eventCount = 0;
        
    
    @Before
    public void setupFramework() throws Exception
    {
        // init sensorhub with in-memory config
        hub = new SensorHub();
        hub.start();
        registry = hub.getModuleRegistry();
    }
    
    
    protected IProcessModule<?> createOnDemandProcess1() throws Exception
    {
        // create test process
        var processCfg = new ProcessConfig();
        processCfg.autoStart = false;
        processCfg.moduleClass = TestLinearEquationProcessModule.class.getCanonicalName();
        processCfg.id = TEST_PROCESS_ID;
        processCfg.name = "Process1";
        var process = (IProcessModule<?>)registry.loadModule(processCfg);
        process.init();
        process.start();
        return process;
    }
    
    
    protected void runProcessDirect(IProcessModule<?> process) throws Exception
    {
        // set param data
        var params = process.getParameterDescriptors().get("params");
        params.getData().setDoubleValue(0, 2);
        params.getData().setDoubleValue(1, -3);
        
        // send input data
        var input = process.getCommandInputs().get("input");
        var inputData = input.getCommandDescription().createDataBlock();
        inputData.setDoubleValue(2.0);
        var cmd = new CommandData(1L, inputData);
        
        input.submitCommand(cmd)
            .thenAccept(status -> {
                System.out.println(status);
                assertNotNull(status.getResult());
                assertNotNull(status.getResult().getInlineRecords());
                assertEquals(1, status.getResult().getInlineRecords().size());
                var rec = status.getResult().getInlineRecords().iterator().next();
                assertEquals(2*2-3, rec.getDoubleValue(0), 1e-8);
            }).get();
    }
    
    
    protected void runProcessViaCommandQueue(IProcessModule<?> process) throws Exception
    {
        var dbHandler = new SystemDatabaseTransactionHandler(hub.getEventBus(), hub.getDatabaseRegistry().getFederatedDatabase());
        var cmdHandler = dbHandler.getCommandStreamHandler(process.getUniqueIdentifier(), "input");
        
        // set param data
        var params = process.getParameterDescriptors().get("params");
        params.getData().setDoubleValue(0, 5);
        params.getData().setDoubleValue(1, -1.5);
        
        // send input data
        var input = process.getCommandInputs().get("input");
        var inputData = input.getCommandDescription().createDataBlock();
        inputData.setDoubleValue(20.0);
        var cmd = new CommandData.Builder()
            .withCommandStream(cmdHandler.getCommandStreamKey().getInternalID())
            .withParams(inputData)
            .withSender("test")
            .build();
        
        cmdHandler.submitCommand(new Random().nextInt(), cmd, 1, TimeUnit.SECONDS)
            .thenAccept(status -> {
                System.out.println(status);
                assertNotNull(status.getResult());
                assertEquals(1, status.getResult().getInlineRecords().size());
                var rec = status.getResult().getInlineRecords().iterator().next();
                assertEquals(5*20.-1.5, rec.getDoubleValue(0), 1e-8);
            }).get();
    }
    
    
    @Test
    public void testSimpleProcessDirect() throws Throwable
    {
        var process = createOnDemandProcess1();
        if (process.getCurrentError() != null)
            throw process.getCurrentError();
        runProcessDirect(process);
    }
    
    
    @Test
    public void testSimpleProcessViaCommandQueue() throws Throwable
    {
        var process = createOnDemandProcess1();
        if (process.getCurrentError() != null)
            throw process.getCurrentError();
        runProcessViaCommandQueue(process);
    }
    
    
    @Override
    public void handleEvent(Event e)
    {
        if (e instanceof DataEvent)
        {
            try
            {
                System.out.print(((DataEvent)e).getSource().getName() + ": ");
                
                writer.setDataComponents(((DataEvent)e).getSource().getRecordDescription());
                writer.reset();
                writer.write(((DataEvent)e).getRecords()[0]);
                writer.flush();
                System.out.println();
                
                eventCount++;
                System.out.println(eventCount);
            }
            catch (IOException ex)
            {
                ex.printStackTrace();
            }
            
            synchronized (this) { this.notify(); }
        }
    }
    
        
    @After
    public void cleanup()
    {
        try
        {
            registry.shutdown(false, false);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }
}
