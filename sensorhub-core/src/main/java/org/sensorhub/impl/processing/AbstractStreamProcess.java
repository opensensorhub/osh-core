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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.WeakHashMap;
import net.opengis.gml.v32.AbstractFeature;
import net.opengis.sensorml.v20.AbstractProcess;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import org.sensorhub.api.common.Event;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.api.data.IDataProducerModule;
import org.sensorhub.api.data.IStreamingDataInterface;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.api.processing.DataSourceConfig;
import org.sensorhub.api.processing.DataSourceConfig.InputLinkConfig;
import org.sensorhub.api.processing.IStreamProcessModule;
import org.sensorhub.api.processing.ProcessException;
import org.sensorhub.api.processing.StorageDataSourceConfig;
import org.sensorhub.api.processing.StreamProcessConfig;
import org.sensorhub.api.processing.StreamingDataSourceConfig;
import org.sensorhub.impl.SensorHub;
import org.sensorhub.impl.module.AbstractModule;
import org.sensorhub.impl.module.ModuleRegistry;
import org.sensorhub.utils.MsgUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.process.DataQueue;
import org.vast.sensorML.SMLFactory;
import org.vast.swe.SWEHelper;


/**
 * <p>
 * Class providing default implementation of common stream processing API methods.
 * This can be used as the base for most stream process implementations.<br/>
 * Concrete process implementations generally need to override either
 * {@link #init(ModuleConfig)} or {@link #start()} in order to provide
 * actual process inputs, outputs and parameters.
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @param <ConfigType> Type of process configuration
 * @since Feb 22, 2015
 */
public abstract class AbstractStreamProcess<ConfigType extends StreamProcessConfig> extends AbstractModule<ConfigType> implements IStreamProcessModule<ConfigType>
{
    public final static String DEFAULT_ID = "PROCESS_DESC";
    private static final Logger log = LoggerFactory.getLogger(AbstractStreamProcess.class);
    protected static final int MAX_ERRORS = 10;
        
    protected Map<String, DataComponent> inputs = new LinkedHashMap<String, DataComponent>();
    protected Map<String, DataComponent> outputs = new LinkedHashMap<String, DataComponent>();
    protected Map<String, DataComponent> parameters = new LinkedHashMap<String, DataComponent>();
    protected Map<String, IStreamingDataInterface> outputInterfaces = new LinkedHashMap<String, IStreamingDataInterface>();  
    
    protected Map<IStreamingDataInterface, InputData> streamSources;
    protected AbstractProcess processDescription;
    protected long lastUpdatedSensorDescription = Long.MIN_VALUE;
    protected Object sensorDescLock = new Object();
    protected boolean paused = false;
    protected int errorCount = 0;
    
    
    protected class InputData
    {
        private DataComponent srcDataBuffer;
        private List<DataQueue> dataQueues = new ArrayList<DataQueue>();
        
        public List<DataQueue> getDataQueues() {
            return dataQueues;
        }
    }
    
    
    public AbstractStreamProcess()
    {
    }
    
    
    /**
     * Method called everytime a new data event has been received on any input.<br/>
     * Concrete implementation must override this method to launch processing when appropriate
     * (e.g. when enough data has been received).<br/>
     * Since data is accumulated in input queues, process should properly remove any data that
     * needs to be discarded from the queues, as early as possible, even when no processing is done.
     * @param lastEvent data event triggering this call
     * @throws ProcessException
     */
    protected abstract void process(DataEvent lastEvent) throws ProcessException;
    
    
    /**
     * Helper method to make sure derived classes add outputs consistently in the different maps
     * @param outputInterface
     */
    protected void addOutput(IStreamingDataInterface outputInterface)
    {
        String outputName = outputInterface.getName();
        outputs.put(outputName, outputInterface.getRecordDescription());
        outputInterfaces.put(outputName, outputInterface);
    }


    @Override
    public Map<String, DataComponent> getInputDescriptors()
    {
        return Collections.unmodifiableMap(inputs);
    }
    
    
    @Override
    public Map<String, DataComponent> getOutputDescriptors()
    {
        return Collections.unmodifiableMap(outputs);
    }
    
    
    @Override
    public Map<String, DataComponent> getParameters()
    {
        return Collections.unmodifiableMap(parameters);
    }
    
    
    @Override
    public Map<String, IStreamingDataInterface> getAllOutputs()
    {
        return Collections.unmodifiableMap(outputInterfaces);
    }
    
    
    @Override
    public AbstractFeature getCurrentFeatureOfInterest()
    {
        return null;
    }
    
    
    @Override
    public String getUniqueIdentifier()
    {
        return getCurrentDescription().getUniqueIdentifier();
    }


    @Override
    public AbstractProcess getCurrentDescription()
    {
        synchronized (sensorDescLock)
        {
            if (processDescription == null)
            {
                processDescription = new SMLFactory().newSimpleProcess();
            
                // default IDs
                processDescription.setId(DEFAULT_ID);
                processDescription.setUniqueIdentifier(getLocalID());
            
                // name
                processDescription.setName(getName());
            
                // inputs
                for (Entry<String, DataComponent> input: getInputDescriptors().entrySet())
                {
                    DataComponent inputDesc = input.getValue();
                    processDescription.addInput(input.getKey(), inputDesc);
                }
            
                // outputs
                for (Entry<String, DataComponent> output: getOutputDescriptors().entrySet())
                {
                    DataComponent outputDesc = output.getValue();
                    processDescription.addOutput(output.getKey(), outputDesc);
                }
            
                // parameters
                for (Entry<String, DataComponent> param: getParameters().entrySet())
                {
                    DataComponent paramDesc = param.getValue();
                    processDescription.addParameter(param.getKey(), paramDesc);
                }
            }
        }
        
        return processDescription;
    }


    @Override
    public long getLastDescriptionUpdate()
    {
        return lastUpdatedSensorDescription;
    }
    
    
    protected void connectInput(String inputName, String dataPath, DataQueue inputQueue) throws ProcessException
    {        
        DataComponent destData = inputs.get(inputName);
        if (destData == null)
            throw new ProcessException("Input " + inputName + " doesn't exist");
        
        try
        {
            DataComponent dest = SWEHelper.findComponentByPath(destData, dataPath);
            inputQueue.setDestinationComponent(dest);
        }
        catch (Exception e)
        {
            throw new ProcessException("Cannot connect input", e);
        }        
    }


    @Override
    public void start() throws SensorHubException
    {
        errorCount = 0;
        streamSources = new WeakHashMap<IStreamingDataInterface, InputData>();
        
        // attemp to connect to all configured data sources
        // we keep sources in WeakHashMaps so that source modules can be properly GCed when unloaded
        for (DataSourceConfig dataSource: config.dataSources)
        {
            ModuleRegistry moduleReg = SensorHub.getInstance().getModuleRegistry();
            if (!isCompatibleDataSource(dataSource))
                throw new ProcessException("Data source is not supported");
            
            // case of streaming data source
            if (dataSource instanceof StreamingDataSourceConfig)
            {
                StreamingDataSourceConfig streamSrc = (StreamingDataSourceConfig) dataSource;
                IDataProducerModule<?> srcModule = (IDataProducerModule<?>)moduleReg.getModuleById(streamSrc.producerID);
                
                for (InputLinkConfig inputLink: streamSrc.inputConnections)
                {
                    int firstSepIndex;
                    String compPath;
                    DataQueue inputQueue = new DataQueue();
                    
                    // connect to source interface output and component
                    IStreamingDataInterface streamInterface = null;
                    InputData inputData = null;
                    try
                    {
                        firstSepIndex = inputLink.source.indexOf('/');
                        String outputName = (firstSepIndex < 0) ? inputLink.source : inputLink.source.substring(0, firstSepIndex);
                        compPath = (firstSepIndex < 0) ? "/" : inputLink.source.substring(firstSepIndex+1);
                        streamInterface = srcModule.getAllOutputs().get(outputName);
                        if (streamInterface == null)
                            throw new ProcessException("Output " + outputName + " doesn't exist");
                        
                        // if first time we get that input source add input data info to map
                        inputData = streamSources.get(streamInterface);
                        if (inputData == null)
                        {
                            inputData = new InputData();
                            inputData.srcDataBuffer = streamInterface.getRecordDescription().copy();;
                            streamSources.put(streamInterface, inputData);
                        }
                        
                        DataComponent src = SWEHelper.findComponentByPath(inputData.srcDataBuffer, compPath);
                        inputQueue.setSourceComponent(src);
                    }
                    catch (Exception e)
                    {
                        throw new ProcessException("Error while connecting to output signal " + inputLink.source +
                                                   " of " + MsgUtils.moduleString(srcModule), e);
                    }
                    
                    // connect to this process input component
                    try
                    {
                        // if destination is set to AUTO_CREATE
                        if (inputLink.destination.equals(DataSourceConfig.AUTO_CREATE))
                        {
                            DataComponent newInput = inputQueue.getSourceComponent().copy();
                            inputs.put(newInput.getName(), newInput);
                            inputQueue.setDestinationComponent(newInput);
                        }
                        else
                        {
                            firstSepIndex = inputLink.destination.indexOf('/');
                            String inputName = (firstSepIndex < 0) ? inputLink.destination : inputLink.destination.substring(0, firstSepIndex);
                            compPath = (firstSepIndex < 0) ? "/" : inputLink.destination.substring(firstSepIndex+1);
                            connectInput(inputName, compPath, inputQueue);
                        }
                    }
                    catch (Exception e)
                    {
                        throw new ProcessException("Error while connecting to input signal " + inputLink.destination +
                                                   " of process " + MsgUtils.moduleString(this), e);
                    }
                    
                    inputData.dataQueues.add(inputQueue);
                    streamInterface.registerListener(this);
                }
            }
            
            // case of storage data source
            else if (dataSource instanceof StorageDataSourceConfig)
            {
                // TODO what do we do with storage input?
                // should it be handled by the process impl directly?
            }
        }            
    }
    
    
    @Override
    public void stop()
    {
        if (streamSources != null)
        {
            // unregister listeners from all streaming data sources
            for (Entry<IStreamingDataInterface, InputData> streamSrc: streamSources.entrySet())
            {
                streamSrc.getKey().unregisterListener(this);
                
                // clear input queues
                for (DataQueue q: streamSrc.getValue().dataQueues)
                    q.clear();
            }
        }
    }


    @Override
    public void cleanup()
    {

    }


    @Override
    public void pause()
    {
        this.paused = true;
    }


    @Override
    public void resume()
    {
        this.paused = false;
    }
    
    
    @Override
    public void handleEvent(Event<?> e)
    {
        if (paused)
            return;
        
        if (e instanceof DataEvent)
        {
            // retrieve input data attached to the event source interface
            InputData inputData = streamSources.get(e.getSource());
            
            // check if streaming interface is still available
            // if source module was unloaded, it could have been GCed
            if (inputData != null)
            {
                // process each data block
                for (DataBlock dataBlk: ((DataEvent) e).getRecords())
                {
                    inputData.srcDataBuffer.setData(dataBlk);
                    
                    // send data to all connected queues
                    for (DataQueue queue: inputData.dataQueues)
                        queue.add(queue.getSourceComponent().getData());
                }
                
                try
                {
                    // launch processing
                    process((DataEvent)e);
                }
                catch (ProcessException ex)
                {
                    log.error("Error while processing data event with time stamp {}", e.getTimeStamp(), ex);
                    errorCount++;
                    if (errorCount > MAX_ERRORS)
                    {
                        log.error("Too many errors, stopping processing {}", getName());
                        stop();
                    }
                }
            }
        }
        
    }
}
