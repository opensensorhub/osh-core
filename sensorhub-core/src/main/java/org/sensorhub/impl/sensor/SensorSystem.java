/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2016 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.sensor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import net.opengis.sensorml.v20.PhysicalSystem;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.module.IModule;
import org.sensorhub.api.module.IModuleStateManager;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.api.module.ModuleEvent;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.sensorhub.api.data.IDataProducerModule;
import org.sensorhub.api.event.Event;
import org.sensorhub.api.system.ISystemDriver;
import org.sensorhub.api.system.ISystemGroupDriver;
import org.sensorhub.impl.module.ModuleRegistry;
import org.sensorhub.impl.processing.AbstractProcessModule;
import org.sensorhub.impl.sensor.SensorSystemConfig.SystemMember;
import org.sensorhub.utils.MsgUtils;
import org.vast.swe.SWEConstants;
import org.vast.util.Asserts;
import com.google.common.collect.ImmutableMap;


/**
 * <p>
 * Class allowing to group several sensors drivers and processes into a single
 * system.<br/>
 * The system's outputs consist of the ones from the individual sensors and
 * processes included in the group.<br/>
 * Relative location and orientation of components can also be set
 * </p>
 *
 * @author Alex Robin
 * @since Mar 19, 2016
 */
public class SensorSystem extends AbstractSensorModule<SensorSystemConfig> implements ISystemGroupDriver<IDataProducerModule<?>>
{
    public static final String DEFAULT_XMLID_PREFIX = "SYSTEM_";
    public static final String AUTO_ID = "auto";
    private static final String URN_PREFIX = "urn:";
    
    Collection<IDataProducerModule<?>> subsystems = new ArrayList<>();


    @Override
    protected void doInit() throws SensorHubException
    {
        super.doInit();
        
        // generate unique ID
        if (config.uniqueID != null && !config.uniqueID.equals(AUTO_ID))
        {
            if (config.uniqueID.startsWith(URN_PREFIX))
            {
                this.uniqueID = config.uniqueID;
                String suffix = config.uniqueID.replace(URN_PREFIX, "");
                generateXmlID(DEFAULT_XMLID_PREFIX, suffix);
            }
            else
            {
                this.uniqueID = URN_PREFIX + "osh:system:" + config.uniqueID;
                generateXmlID(DEFAULT_XMLID_PREFIX, config.uniqueID);
            }
        }
        
        // Init all subsystem modules
        for (var module: subsystems)
        {
            if (module != null)
            {
                try
                {
                    module.init();
                }
                catch (Exception e)
                {
                    getLogger().error("Cannot initialize system component {}", MsgUtils.moduleString(config), e);
                }
            }
        }
    }
    
    
    public IModule<?> addSubsystem(SystemMember member) throws SensorHubException
    {
        var module = (IDataProducerModule<?>)loadModule(member.config);
        if (module == null)
            throw new SensorHubException("Error loading module");
        
        config.subsystems.add(member);
        subsystems.add(module);
        return module;
    }
    
    
    public void removeSubSystem(String id) throws SensorHubException
    {
        Asserts.checkNotNull(id, "id");
        
        // remove from config
        var it2 = config.subsystems.iterator();
        while (it2.hasNext())
        {
            var memberCfg = it2.next();
            if (id.equals(memberCfg.config.id))
                it2.remove();
        }
        
        // remove and stop module
        var it = subsystems.iterator();
        while (it.hasNext())
        {
            var member = it.next();
            if (id.equals(member.getLocalID()))
            {
                it.remove();
                member.stop();
            }
        }
    }
    
    
    private IModule<?> loadModule(ModuleConfig config)
    {
        try
        {
            if (config.id == null)
                config.id = UUID.randomUUID().toString();
            
            var module = getParentHub().getModuleRegistry().loadSubModule(config, false);
            module.setParentHub(getParentHub());
            
            if (module instanceof AbstractSensorModule)
                ((AbstractSensorModule<?>)module).attachToParent(this);
            
            if (module instanceof AbstractProcessModule)
                ((AbstractProcessModule<?>)module).attachToParent(this);
            
            // register to receive module events
            module.registerListener(this::handleEvent);
            
            return module;
        }
        catch (Exception e)
        {
            getLogger().error("Cannot load system component {}", MsgUtils.moduleString(config), e);
            return null;
        }
    }
    
    
    protected void handleEvent(Event e)
    {
        if (e instanceof ModuleEvent)
        {
            eventHandler.publish(e);
            if(((ModuleEvent) e).getType() == ModuleEvent.Type.CONFIG_CHANGED)
            {
                var moduleConfig = ((ModuleEvent)e).getModule().getConfiguration();
                for(SystemMember member : config.subsystems)
                {
                    if(moduleConfig.id.equals(member.config.id))
                    {
                        member.config = moduleConfig;
                        break;
                    }
                }
            }

            // If we receive a new submodule and parent is already started, we need to register that submodule manually.
            if(((ModuleEvent) e).getNewState() != null && ((ModuleEvent) e).getNewState().equals(ModuleState.STARTING) /* Register before starting module */
            && e.getSource() instanceof ISystemDriver && e.getSource() != this /* ModuleEvent is from system member */
            && ((IDataProducerModule<?>) e.getSource()).getLocalID() != null /* Module has valid id */
            && ((IDataProducerModule<?>) e.getSource()).getUniqueIdentifier() != null /* Module has UID */)
            {
                // Get driver of new submodule
                var memberProc = this.getMembers().get(((IDataProducerModule<?>) e.getSource()).getLocalID());
                // Only register submodule if not already registered
                if(!getParentHub().getSystemDriverRegistry().isRegistered(memberProc.getUniqueIdentifier()))
                    getParentHub().getSystemDriverRegistry().register(memberProc).thenRun(() -> System.out.println("Registered member " + memberProc.getName() + "!"));
            }
        }
    }

    @Override
    protected void updateSensorDescription()
    {
        synchronized (sensorDescLock)
        {
            super.updateSensorDescription();
            PhysicalSystem system = (PhysicalSystem)sensorDescription;
            system.setDefinition(SWEConstants.DEF_SYSTEM);
        }
    }


    @Override
    protected void setState(ModuleState newState) {
        super.setState(newState);

        // Ensure that autoStart starts modules after Sensor System is enabled
        if (newState == ModuleState.STARTED) {
            for (var member: subsystems)
            {
                try
                {
                    if (member.getConfiguration().autoStart)
                    {
                        member.waitForState(ModuleState.INITIALIZED, 10000);
                        member.start();
                    }
                }
                catch (Exception e)
                {
                    getLogger().error("Cannot start subsystem " + MsgUtils.moduleString(member), e);
                }
            }
        }
    }


    @Override
    protected void doStop() throws SensorHubException
    {
        for (var member: subsystems)
        {
            try
            {
                member.stop();
            }
            catch (SensorHubException e)
            {
                getLogger().error("Error stopping subsystem {}", MsgUtils.moduleString(member), e);
            }
        }
    }


    @Override
    public void cleanup() throws SensorHubException
    {
        for (var member: subsystems)
            member.cleanup();
        
        super.cleanup();
    }


    @Override
    public boolean isConnected()
    {
        return true;
    }

    @Override
    public void setConfiguration(SensorSystemConfig config) {
        super.setConfiguration(config);

        // Load all subsystem modules from config
        subsystems.clear();
        for (SystemMember member : config.subsystems) {
            var module = (IDataProducerModule<?>) loadModule(member.config);
            if (module != null) {
                subsystems.add(module);
            }
        }
    }

    @Override
    public synchronized void loadState(IModuleStateManager loader) throws SensorHubException
    {
        super.loadState(loader);
        
        // also load sub modules state
        ModuleRegistry reg = getParentHub().getModuleRegistry();
        for (var member: subsystems)
        {
            loader = reg.getStateManager(member.getLocalID());
            if (loader != null)
                member.loadState(loader);
        }
    }


    @Override
    public synchronized void saveState(IModuleStateManager saver) throws SensorHubException
    {
        super.saveState(saver);
        
        // also save sub modules state
        ModuleRegistry reg = getParentHub().getModuleRegistry();
        for (var member: subsystems)
        {
            saver = reg.getStateManager(member.getLocalID());
            member.saveState(saver);
        }
    }


    @Override
    protected void generateXmlIDFromUUID(String uuid)
    {
        super.generateXmlIDFromUUID(uuid);
        this.xmlID = this.xmlID.replace(AbstractSensorModule.DEFAULT_XMLID_PREFIX, DEFAULT_XMLID_PREFIX);
    }


    @Override
    public Map<String, ? extends IDataProducerModule<?>> getMembers()
    {
        return subsystems != null ?
            subsystems.stream().collect(ImmutableMap.toImmutableMap(this::getMemberId, e -> e)) :
            Collections.emptyMap();
    }
    
    
    protected String getMemberId(IDataProducerModule<?> member)
    {
        return member.getLocalID();
    }

}
