/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2022 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.processing;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.sensorhub.api.command.IStreamingControlInterface;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.data.IStreamingDataInterface;
import org.sensorhub.api.processing.IDataProcess;
import org.sensorhub.api.processing.IProcessModule;
import org.sensorhub.api.processing.ProcessConfig;
import org.sensorhub.api.processing.ProcessingException;
import org.sensorhub.api.system.ISystemDriver;
import org.sensorhub.api.system.ISystemGroupDriver;
import org.sensorhub.api.utils.OshAsserts;
import org.sensorhub.impl.module.AbstractModule;
import org.vast.ogc.gml.IFeature;
import org.vast.util.Asserts;
import net.opengis.sensorml.v20.AbstractProcess;
import net.opengis.swe.v20.DataComponent;


/**
 * <p>
 * This class is used to wrap any instance of {@link IDataProcess} into a
 * full fledge module so it can be instantiated directly in the SensorHub.
 * </p>
 * 
 * @param <T> Type of process module config object
 *
 * @author Alex Robin
 * @since Sep 19, 2022
 */
public abstract class AbstractProcessWrapperModule<T extends ProcessConfig> extends AbstractModule<T> implements IProcessModule<T>
{
    IDataProcess process;
    
    
    public AbstractProcessWrapperModule()
    {
    }
    
    
    protected abstract IDataProcess initProcess();
    
    
    @Override
    protected void doInit()
    {
        this.process = Asserts.checkNotNull(initProcess(), "IDataProcess");
    }
    
    
    @Override
    protected void afterInit() throws SensorHubException
    {
        if (process.getCurrentDescription() == null)
            throw new IllegalStateException("No process description provided");
        OshAsserts.checkValidUID(process.getUniqueIdentifier());
        
        if (process.getInputDescriptors().isEmpty() && process.getOutputDescriptors().isEmpty())
            throw new IllegalStateException("At least one input or output must be defined");
    }
    
    
    @Override
    protected void beforeStart() throws SensorHubException
    {
        super.beforeStart();
        
        if(getParentSystem() != null && !getParentSystem().isEnabled())
            throw new ProcessingException("Parent system must be started");

        // register sensor with registry if attached to a hub and we have no parent
        try
        {
            if (hasParentHub() && getParentHub().getSystemDriverRegistry() != null && getParentSystem() == null)
                getParentHub().getSystemDriverRegistry().register(this).get(); // for now, block here until init is also async
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new ProcessingException("Interrupted while registering process", e);
        }
        catch (ExecutionException e)
        {
            throw new ProcessingException("Error registering process", e.getCause());
        }
    }
    
    
    @Override
    protected void afterStop() throws SensorHubException
    {
        // unregister process if attached to a hub
        try
        {
            if (hasParentHub() && getParentHub().getSystemDriverRegistry() != null && process.getParentSystem() == null)
                getParentHub().getSystemDriverRegistry().unregister(this).get();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new ProcessingException("Interrupted while unregistering process", e);
        }
        catch (ExecutionException e)
        {
            throw new ProcessingException("Error unregistering process", e.getCause());
        }
        
        super.afterStop();
    }


    @Override
    public Map<String, DataComponent> getInputDescriptors()
    {
        return process.getInputDescriptors();
    }


    @Override
    public Map<String, DataComponent> getOutputDescriptors()
    {
        return process.getOutputDescriptors();
    }
    
    
    @Override
    public Map<String, DataComponent> getParameterDescriptors()
    {
        return process.getParameterDescriptors();
    }
    
    
    @Override
    public Map<String, ? extends IStreamingDataInterface> getOutputs()
    {
        return process.getOutputs();
    }


    @Override
    public Map<String, ? extends IStreamingControlInterface> getCommandInputs()
    {
        return process.getCommandInputs();
    }
    
    
    @Override
    public String getUniqueIdentifier()
    {
        return process.getUniqueIdentifier();
    }


    @Override
    public AbstractProcess getCurrentDescription()
    {
        return process.getCurrentDescription();
    }


    @Override
    public long getLatestDescriptionUpdate()
    {
        return process.getLatestDescriptionUpdate();
    }


    @Override
    public String getParentSystemUID()
    {
        return process.getParentSystemUID();
    }


    @Override
    public ISystemGroupDriver<? extends ISystemDriver> getParentSystem()
    {
        return process.getParentSystem();
    }


    @Override
    public Map<String, ? extends IFeature> getCurrentFeaturesOfInterest()
    {
        return process.getCurrentFeaturesOfInterest();
    }


    @Override
    public boolean isEnabled()
    {
        return isStarted();
    }


    @Override
    public void cleanup()
    {
    }
}
