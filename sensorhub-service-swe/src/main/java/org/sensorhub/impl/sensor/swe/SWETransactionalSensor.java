/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.sensor.swe;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import net.opengis.gml.v32.AbstractFeature;
import net.opengis.gml.v32.TimePeriod;
import net.opengis.sensorml.v20.AbstractPhysicalProcess;
import net.opengis.sensorml.v20.AbstractProcess;
import net.opengis.sensorml.v20.DataInterface;
import net.opengis.sensorml.v20.IOPropertyList;
import net.opengis.swe.v20.AbstractSWEIdentifiable;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import net.opengis.swe.v20.DataStream;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.module.IModuleStateManager;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.sensorhub.api.sensor.ISensorControlInterface;
import org.sensorhub.api.sensor.ISensorDataInterface;
import org.sensorhub.api.sensor.SensorEvent;
import org.sensorhub.api.sensor.SensorException;
import org.sensorhub.impl.sensor.AbstractSensorModule;
import org.sensorhub.impl.sensor.swe.DataStructureHash;
import org.sensorhub.utils.MsgUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.data.SWEFactory;
import org.vast.ogc.om.IObservation;
import org.vast.sensorML.SMLUtils;
import org.vast.util.Asserts;


/**
 * <p>
 * Virtual sensor interface automatically created by SOS and SPS services
 * when a new sensor is registered through these interfaces.
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since Dec 16, 2016
 */
public class SWETransactionalSensor extends AbstractSensorModule<SWETransactionalSensorConfig>
{
    protected final static String STATE_SML_DESC = "SensorDescription";
    protected static final Logger log = LoggerFactory.getLogger(SWETransactionalSensor.class);
        
    Map<DataStructureHash, String> structureToOutputMap = new HashMap<DataStructureHash, String>();
    Map<DataStructureHash, String> structureToTaskableParamMap = new HashMap<DataStructureHash, String>();
    AbstractFeature currentFoi;
    
    
    public SWETransactionalSensor()
    {
    }
    
    
    @Override
    public String getName()
    {
        if (sensorDescription != null && sensorDescription.getName() != null)
            return sensorDescription.getName();
        
        return config.name;
    }
    
    
    @Override
    public AbstractFeature getCurrentFeatureOfInterest()
    {
        return currentFoi;
    }


    public void newObservation(IObservation... observations) throws SensorException
    {
        // don't do anything if sensor is not started
        if (state != ModuleState.STARTED)
            return;

        // TODO implement insert observation
        // also register template
    }


    /*
     * This method either adds a new output or selects an existing output
     * if no output with the given name exists, it is created
     * if output already exists with same structure and encoding, its name is returned
     * if output already exists with different structure and/or encoding, an error is thrown
     */
    public String newOutput(DataComponent component, DataEncoding encoding) throws SensorException
    {
        Asserts.checkNotNull(component, DataComponent.class);
        Asserts.checkNotNull(encoding, DataEncoding.class);
        
        // use SensorML output name if structure matches one of the outputs
        DataStructureHash outputHashObj = new DataStructureHash(component, null);
        String outputName = structureToOutputMap.get(outputHashObj);
        
        // else use provided name
        if (outputName == null)
            outputName = component.getName();
        
        // else generate output name
        if (outputName == null)
            outputName = "output" + getAllOutputs().size();
        
        // create new sensor output interface if needed
        if (!getAllOutputs().containsKey(outputName))
        {
            component.setName(outputName);
            SWETransactionalSensorOutput newOutput = new SWETransactionalSensorOutput(this, component, encoding);
            addOutput(newOutput, false);
        }
        else
        {
            ISensorDataInterface output = getAllOutputs().get(outputName);
            
            // check that output definition is same as previously registered
            DataStructureHash oldOutputHashObj = new DataStructureHash(output.getRecordDescription(), output.getRecommendedEncoding());
            DataStructureHash newOutputHashObj = new DataStructureHash(component, encoding);
            if (!newOutputHashObj.equals(oldOutputHashObj))
                throw new SensorException("Output definition differs from previously registered output with the same name");
        }
        
        // update sensor description with data stream to keep encoding definition
        if (sensorDescription != null)
            wrapOutputWithDataStream(outputName, component, encoding);
        
        return outputName;
    }
    
    
    public String newControlInput(DataComponent component, DataEncoding encoding) throws SensorException
    {
        Asserts.checkNotNull(component, DataComponent.class);
        Asserts.checkNotNull(encoding, DataEncoding.class);
        
        // use SensorML param name if structure matches one of the taskable parameters
        DataStructureHash paramHashObj = new DataStructureHash(component, null);
        String paramName = structureToTaskableParamMap.get(paramHashObj);
        
        // else use provided name
        if (paramName == null)
            paramName = component.getName();
        
        // else generate output name
        if (paramName == null)
            paramName = "command" + getCommandInputs().size();
        
        // create new sensor control interface if needed
        if (!getCommandInputs().containsKey(paramName))
        {
            component.setName(paramName);
            SWETransactionalSensorControl newControl = new SWETransactionalSensorControl(this, component);
            addControlInput(newControl);
        }
        else
        {
            ISensorControlInterface controlInput = getCommandInputs().get(paramName);
            
            // check that control input definition is same as previously registered
            DataStructureHash oldInputHashObj = new DataStructureHash(controlInput.getCommandDescription(), null);
            DataStructureHash newInputHashObj = new DataStructureHash(component, null);
            if (!newInputHashObj.equals(oldInputHashObj))
                throw new SensorException("Control input definition differs from previously registered input with the same name");
        }
        
        // update sensor description with data stream to keep encoding definition
        if (sensorDescription != null)
            wrapParamWithDataStream(paramName, component, encoding);
        
        return paramName;
    }
    
    
    public void newFeatureOfInterest(String outputName, AbstractFeature foi) throws SensorException
    {
        // process feature of interest
        if (foi != null)
        {
            SWETransactionalSensorOutput output = (SWETransactionalSensorOutput)getAllOutputs().get(outputName);
            currentFoi = foi;
            output.publishNewFeatureOfInterest(currentFoi);
        }
    }    
    
    
    public void newResultRecord(String outputName, DataBlock... dataBlocks) throws SensorException
    {
        // don't do anything if sensor is not started
        if (state != ModuleState.STARTED)
            return;
        
        SWETransactionalSensorOutput output = (SWETransactionalSensorOutput)getAllOutputs().get(outputName);
        log.trace("New record received for output " + output.getName());
        
        for (DataBlock dataBlock: dataBlocks)
            output.publishNewRecord(dataBlock);
    }
    

    @Override
    public void start() throws SensorHubException
    {
        setState(ModuleState.STARTED);
    }


    @Override
    public void stop() throws SensorHubException
    {
        setState(ModuleState.STOPPED);
    }


    @Override
    public void cleanup() throws SensorHubException
    {
        File f = new File(this.getLocalID() + ".xml");
        if (f.exists())
            f.delete();
        super.cleanup();
    }


    @Override
    protected void updateSensorDescription()
    {
        sensorDescription.setUniqueIdentifier(config.id);
        
        // don't do anything more here.
        // we wait until description is set by SOS consumer
    }
    
    
    public void updateSensorDescription(AbstractProcess systemDesc)
    {
        sensorDescription = systemDesc;
        uniqueID = systemDesc.getUniqueIdentifier();
        
        // generate output hashcodes to compare with newly registered outputs
        IOPropertyList outputList = sensorDescription.getOutputList();
        for (int i = 0; i  < outputList.size(); i++)
        {
            DataStructureHash hashObj = new DataStructureHash(outputList.getComponent(i), null);
            structureToOutputMap.put(hashObj, outputList.getProperty(i).getName());
        }
        
        // generate control param hashcodes to compare with newly registered control inputs
        IOPropertyList paramList = sensorDescription.getParameterList();
        for (int i = 0; i  < paramList.size(); i++)
        {
            DataStructureHash hashObj = new DataStructureHash(paramList.getComponent(i), null);
            structureToTaskableParamMap.put(hashObj, paramList.getProperty(i).getName());
        }
        
        // record update time
        long unixTime = System.currentTimeMillis();
        lastUpdatedSensorDescription = unixTime;
        eventHandler.publishEvent(new SensorEvent(unixTime, this, SensorEvent.Type.SENSOR_CHANGED));
    }


    @Override
    public boolean isConnected()
    {
        // TODO use timeout value
        /*long now = System.currentTimeMillis();
        
        for (ISensorDataInterface output: this.getAllOutputs().values())
        {
            double samplingPeriod = output.getAverageSamplingPeriod();
            if (now - output.getLatestRecordTime() < 10*samplingPeriod)
                return true;
        }
        
        return false;*/
        return true;
    }


    @Override
    public void saveState(IModuleStateManager saver) throws SensorHubException
    {
        try (OutputStream os = saver.getOutputStream(STATE_SML_DESC))
        {            
            new SMLUtils(SMLUtils.V2_0).writeProcess(os, sensorDescription, true);
        }
        catch (Exception e)
        {
            throw new SensorHubException("Error while saving state for module " + MsgUtils.moduleString(this), e);
        }
    }


    @Override
    public void loadState(IModuleStateManager loader) throws SensorHubException
    {
        try (InputStream is = loader.getAsInputStream(STATE_SML_DESC))
        {
            if (is != null)
            {
                // read saved description
                sensorDescription = (AbstractPhysicalProcess)new SMLUtils(SMLUtils.V2_0).readProcess(is);
                
                // set unique ID
                uniqueID = sensorDescription.getUniqueIdentifier();
                
                // set last description update time
                int timeListSize = sensorDescription.getValidTimeList().size();
                if (timeListSize > 0)
                {
                    double begin = ((TimePeriod)sensorDescription.getValidTimeList().get(0)).getBeginPosition().getDecimalValue();
                    lastUpdatedSensorDescription = (long)(begin*1000);
                }
                
                // generate output interfaces from description
                for (AbstractSWEIdentifiable output: sensorDescription.getOutputList())
                {
                    DataComponent dataStruct = null;
                    DataEncoding dataEnc = null;
                    
                    // handle cases for different types of outputs
                    if (output instanceof DataStream)
                    {
                        dataStruct = ((DataStream) output).getElementType();
                        dataEnc = ((DataStream) output).getEncoding();   
                    }
                    else if (output instanceof DataInterface)
                    {
                        dataStruct = ((DataInterface) output).getData().getElementType();
                        dataEnc = ((DataInterface) output).getData().getEncoding();                        
                    }
                    else
                    {
                        dataStruct = (DataComponent)output;
                    }
                    
                    // register output hashcode
                    DataStructureHash hashObj = new DataStructureHash(dataStruct, null);
                    structureToOutputMap.put(hashObj, dataStruct.getName());
                    
                    // register as output if encoding is specified
                    // if encoding is null, no template has been registered yet
                    if (dataEnc != null)
                        newOutput(dataStruct, dataEnc);
                }
                
                // generate control interfaces from description
                for (AbstractSWEIdentifiable param: sensorDescription.getParameterList())
                {
                    DataComponent dataStruct = null;
                    DataEncoding dataEnc = null;
                    
                    // handle cases for different types of outputs
                    if (param instanceof DataStream)
                    {
                        dataStruct = ((DataStream) param).getElementType();
                        dataEnc = ((DataStream) param).getEncoding();   
                    }
                    else if (param instanceof DataInterface)
                    {
                        dataStruct = ((DataInterface) param).getData().getElementType();
                        dataEnc = ((DataInterface) param).getData().getEncoding();                        
                    }
                    else
                    {
                        dataStruct = (DataComponent)param;
                    }
                    
                    // register output hashcode
                    DataStructureHash hashObj = new DataStructureHash(dataStruct, null);
                    structureToTaskableParamMap.put(hashObj, dataStruct.getName());
                    
                    // register as output if encoding is specified
                    // if encoding is null, no template has been registered yet
                    if (dataEnc != null)
                        newControlInput(dataStruct, dataEnc);
                }
            }
        }
        catch (Exception e)
        {
            throw new SensorHubException("Error while loading state for module " + MsgUtils.moduleString(this), e);
        }
    }
    
    
    /*
     * Used to wrap an output with a DataStream object to make sure we can recreate
     * the output interfaces after SensorHub is restarted
     */
    protected void wrapOutputWithDataStream(String outputName, DataComponent dataStruct, DataEncoding encoding)
    {
        DataStream ds = new SWEFactory().newDataStream();
        ds.setElementType(outputName, dataStruct);
        ds.setEncoding(encoding);
        
        if (!sensorDescription.getOutputList().hasProperty(outputName))
            sensorDescription.addOutput(outputName, ds);
        else
            sensorDescription.getOutputList().getProperty(outputName).setValue(ds);
    }
    
    
    /*
     * Used to wrap a control input with a DataStream object to make sure we can recreate
     * the control interfaces after SensorHub is restarted
     */
    protected void wrapParamWithDataStream(String paramName, DataComponent dataStruct, DataEncoding encoding)
    {
        DataStream ds = new SWEFactory().newDataStream();
        ds.setElementType(paramName, dataStruct);
        ds.setEncoding(encoding);
        
        if (!sensorDescription.getParameterList().hasProperty(paramName))
            sensorDescription.addParameter(paramName, ds);
        else
            sensorDescription.getParameterList().getProperty(paramName).setValue(ds);
    }

}
