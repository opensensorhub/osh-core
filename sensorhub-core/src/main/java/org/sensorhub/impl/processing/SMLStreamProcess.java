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

import java.io.InputStream;
import java.net.URL;
import java.util.Map;
import net.opengis.OgcPropertyList;
import net.opengis.swe.v20.AbstractSWEIdentifiable;
import net.opengis.swe.v20.DataComponent;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.api.processing.DataSourceConfig;
import org.sensorhub.api.processing.ProcessException;
import org.sensorhub.utils.MsgUtils;
import org.vast.process.DataQueue;
import org.vast.process.SMLException;
import org.vast.sensorML.AbstractProcessImpl;
import org.vast.sensorML.AggregateProcessImpl;
import org.vast.sensorML.SMLHelper;
import org.vast.sensorML.SMLUtils;


/**
 * <p>
 * Implementation of process module fully configured using a SensorML process
 * chain description. The SensorML execution engine is used to execute the
 * different processing components used in the chain.
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since Feb 20, 2015
 */
public class SMLStreamProcess extends AbstractStreamProcess<SMLStreamProcessConfig>
{
    AbstractProcessImpl smlProcess;
    

    @Override
    public void init(SMLStreamProcessConfig config) throws SensorHubException
    {
        super.init(config);
        
        // only go further if sensorML file was provided
        // otherwise we'll do it at the next update
        if (config.sensorML != null)
        {
            SMLUtils utils = new SMLUtils(SMLUtils.V2_0);
            
            // parse SensorML file
            try
            {
                InputStream is = new URL(config.sensorML).openStream();
                processDescription = utils.readProcess(is);
                smlProcess = (AbstractProcessImpl)processDescription;
                
                // execute the whole chain in a single thread
                if (smlProcess instanceof AggregateProcessImpl)
                    ((AggregateProcessImpl)smlProcess).setChildrenThreadsOn(false);
            }
            catch (Exception e)
            {
                throw new ProcessException("Error while parsing static SensorML description for process " +
                        MsgUtils.moduleString(this), e);
            }
            
            // make process executable
            try
            {
                SMLHelper.makeProcessExecutable(smlProcess);
                smlProcess.createNewInputBlocks();
                smlProcess.createNewOutputBlocks();
            }
            catch (SMLException e)
            {
                throw new ProcessException("Error while preparing SensorML process for execution in " +
                        MsgUtils.moduleString(this), e);
            }
            
            // advertise process inputs and outputs
            scanIOList(smlProcess.getInputList(), inputs, false);
            scanIOList(smlProcess.getParameterList(), parameters, false);
            scanIOList(smlProcess.getOutputList(), outputs, true);
        }
    }
    
    
    @Override
    public void updateConfig(SMLStreamProcessConfig config) throws SensorHubException
    {
        super.updateConfig(config);
        
        if (smlProcess == null)
            init(config);
        else
        {
            stop();
            start();
        }
    }


    @Override
    protected void connectInput(String inputName, String dataPath, DataQueue inputQueue) throws ProcessException
    {        
        try
        {
            smlProcess.connectInput(inputName, dataPath, inputQueue);
        }
        catch (SMLException e)
        {
            throw new ProcessException("Cannot connect input", e);
        }
    }
    
    
    protected void scanIOList(OgcPropertyList<AbstractSWEIdentifiable> ioList, Map<String, DataComponent> ioMap, boolean isOutput) throws ProcessException
    {
        int numSignals = ioList.size();
        for (int i=0; i<numSignals; i++)
        {
            String ioName = ioList.getProperty(i).getName();
            AbstractSWEIdentifiable ioDesc = ioList.get(i);
            DataComponent ioComponent = SMLHelper.getIOComponent(ioDesc);
            ioComponent.setName(ioName);
            ioMap.put(ioName, isOutput ? ioComponent.copy() : ioComponent);
            
            if (isOutput)
                outputInterfaces.put(ioName, new SMLOutputInterface(this, ioComponent));
        }
    }
    
    
    @Override
    public void start() throws SensorHubException
    {
        if (smlProcess == null)
            throw new SensorHubException("No valid SensorML processing chain provided");
        
        try
        {
            // start process thread
            smlProcess.start();
            
            // call super to register to input events
            super.start();
        }
        catch (org.vast.process.SMLException e)
        {
            throw new ProcessException("Error when starting SensorML process thread", e);
        }
    }
    
    
    @Override
    public void stop()
    {
        // stop processing thread
        smlProcess.stop();
        
        // call super to unregister from input events
        super.stop();        
    }
    

    @Override
    public boolean isPauseSupported()
    {
        return true;
    }


    @Override
    public boolean isCompatibleDataSource(DataSourceConfig dataSource)
    {
        // TODO implement isCompatibleDataSource
        return true;
    }


    @Override
    protected void process(DataEvent lastEvent) throws ProcessException
    {
        // nothing to do
        // the process thread takes care of getting data from input queues
        // and sending events whenever data is available on output queues        
    }
}
