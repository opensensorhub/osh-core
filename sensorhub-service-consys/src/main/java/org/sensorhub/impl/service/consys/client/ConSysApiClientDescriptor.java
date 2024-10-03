package org.sensorhub.impl.service.consys.client;

import org.sensorhub.api.module.IModule;
import org.sensorhub.api.module.IModuleProvider;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.impl.module.JarModuleProvider;

public class ConSysApiClientDescriptor extends JarModuleProvider implements IModuleProvider {

    @Override
    public String getModuleName()
    {
        return "Connected Systems Client";
    }


    @Override
    public String getModuleDescription()
    {
        return "Connected Systems client for pushing observations to a remote SensorHub";
    }


    @Override
    public Class<? extends IModule<?>> getModuleClass()
    {
        return ConSysApiClientModule.class;
    }


    @Override
    public Class<? extends ModuleConfig> getModuleConfigClass()
    {
        return ConSysApiClientConfig.class;
    }

}
