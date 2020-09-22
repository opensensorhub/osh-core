/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.sps;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import org.sensorhub.api.common.Event;
import org.sensorhub.api.common.IEventListener;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.data.IStreamingDataInterface;
import org.sensorhub.api.module.ModuleEvent;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.sensorhub.api.processing.IStreamProcessModule;
import org.sensorhub.api.service.ServiceException;
import org.sensorhub.impl.SensorHub;
import org.sensorhub.utils.MsgUtils;
import org.vast.data.DataRecordImpl;
import org.vast.data.ScalarIterator;
import org.vast.ows.sps.DescribeTaskingResponse;
import org.vast.ows.sps.SPSOfferingCapabilities;
import org.vast.ows.swe.SWESOfferingCapabilities;
import org.vast.swe.SWEConstants;
import net.opengis.sensorml.v20.AbstractProcess;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataRecord;


/**
 * <p>
 * SPS connector for directly update streaming process parameters.
 * This connector doesn't support scheduling or persistent task management
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since Aug 15, 2020
 */
public class DirectStreamProcessConnector implements ISPSConnector, IEventListener
{
    final SPSServlet service;
    final StreamProcessConnectorConfig config;
    final IStreamProcessModule<?> process;
    final String procedureID;
    boolean disableEvents;
    DataRecord allparams;
    
    
    public DirectStreamProcessConnector(SPSServlet service, StreamProcessConnectorConfig config) throws SensorHubException
    {
        this.service = service;
        this.config = config;
        
        // get handle to sensor instance using sensor manager
        this.process = (IStreamProcessModule<?>)SensorHub.getInstance().getModuleRegistry().getModuleById(config.processID);
        this.procedureID = process.getUniqueIdentifier();
        
        // listen to sensor lifecycle events
        disableEvents = true; // disable events on startup
        process.registerListener(this);
        disableEvents = false;
    }
    

    @Override
    public SPSOfferingCapabilities generateCapabilities() throws ServiceException
    {
        checkEnabled();
        
        try
        {
            SPSOfferingCapabilities caps = new SPSOfferingCapabilities();
            
            // identifier
            if (config.offeringID != null)
                caps.setIdentifier(config.offeringID);
            else
                caps.setIdentifier(process.getUniqueIdentifier());
            
            // name
            if (config.name != null)
                caps.setTitle(config.name);
            else
                caps.setTitle(process.getName());
            
            // description
            if (config.description != null)
                caps.setDescription(config.description);
            else
                caps.setDescription("Tasking interface for " + process.getName());
            
            // use sensor uniqueID as procedure ID
            caps.getProcedures().add(process.getCurrentDescription().getUniqueIdentifier());
            
            // supported formats
            caps.getProcedureFormats().add(SWESOfferingCapabilities.FORMAT_SML2);
            
            // observable properties
            List<String> sensorOutputDefs = getObservablePropertiesFromProcess();
            caps.getObservableProperties().addAll(sensorOutputDefs);
                        
            // tasking parameters description
            DescribeTaskingResponse descTaskingResp = new DescribeTaskingResponse();
            this.allparams = getCommandsForProcess();
            descTaskingResp.setTaskingParameters(allparams.copy());
            caps.setParametersDescription(descTaskingResp);
            
            return caps;
        }
        catch (Exception e)
        {
            throw new ServiceException("Cannot generate capabilities for process " + MsgUtils.moduleString(process), e);
        }
    }
    
    
    protected DataRecord getCommandsForProcess()
    {
        DataRecordImpl allParams = new DataRecordImpl();
        
        // collect process commands descriptions
        // just group all parameters in a single command
        for (DataComponent param: process.getParameterDescriptors().values())
        {
            // skip hidden commands
            if (config.hiddenParams != null && config.hiddenParams.contains(param.getName()))
                continue;
            
            allParams.addComponent(param.getName(), param);
        }
        
        allParams.combineDataBlocks();
        return allParams;
    }
    
    
    protected List<String> getObservablePropertiesFromProcess()
    {
        List<String> observableUris = new ArrayList<>();
        
        // process outputs descriptions
        for (Entry<String, ? extends IStreamingDataInterface> entry: process.getAllOutputs().entrySet())
        {
            // iterate through all SWE components and add all definition URIs as observables
            // this way only composites with URI will get added
            IStreamingDataInterface output = entry.getValue();
            ScalarIterator it = new ScalarIterator(output.getRecordDescription());
            while (it.hasNext())
            {
                String defUri = it.next().getDefinition();
                if (defUri != null && !defUri.equals(SWEConstants.DEF_SAMPLING_TIME))
                    observableUris.add(defUri);
            }
        }
        
        return observableUris;
    }
    
    
    @Override
    public void updateCapabilities()
    {
        // nothing to update
    }
    
    
    @Override
    public AbstractProcess generateSensorMLDescription(double time)
    {
        return process.getCurrentDescription();
    }


    @Override
    public synchronized void sendSubmitData(ITask task, DataBlock data) throws ServiceException
    {
        try
        {
            allparams.setData(data);
            
            HashMap<String, DataBlock> newParamData = new HashMap<>();
            for (DataComponent param: ((DataRecord)allparams).getFieldList())
                newParamData.put(param.getName(), param.getData());
            
            process.updateParameters(newParamData);
        }
        catch (Exception e)
        {
            String msg = "Error sending command to process " + MsgUtils.moduleString(process);
            throw new ServiceException(msg, e);
        }
    }
    
    
    /*
     * Checks if provider and underlying sensor are enabled
     */
    protected void checkEnabled() throws ServiceException
    {
        if (!config.enabled)
        {
            String providerName = (config.name != null) ? config.name : "for " + config.processID;
            throw new ServiceException("Connector " + providerName + " is disabled");
        }
        
        if (!process.isStarted())
            throw new ServiceException("Process " + MsgUtils.moduleString(process) + " is disabled");
    }


    @Override
    public void handleEvent(Event<?> e)
    {
        if (disableEvents)
            return;
        
        // producer events
        if (e instanceof ModuleEvent && e.getSource() == process)
        {
            switch (((ModuleEvent)e).getType())
            {
                // show/hide offering when sensor is enabled/disabled
                case STATE_CHANGED:
                    ModuleState state = ((ModuleEvent)e).getNewState();
                    if (state == ModuleState.STARTED || state == ModuleState.STOPPING)
                    {
                        if (isEnabled())
                            service.showConnectorCaps(this);
                        else
                            service.hideConnectorCaps(this);
                    }
                    break;
                
                // cleanly remove connector when sensor is deleted
                case DELETED:
                    service.removeConnector(procedureID);
                    break;
                    
                default:
                    return;
            }
        }      
    }
    
    
    @Override
    public boolean isEnabled()
    {
        return (config.enabled && process.isStarted());
    }


    @Override
    public SPSConnectorConfig getConfig()
    {
        return config;
    }
    
    
    @Override
    public void cleanup()
    {
        process.unregisterListener(this);
    }


    @Override
    public String getProcedureID()
    {
        return procedureID;
    }
}
