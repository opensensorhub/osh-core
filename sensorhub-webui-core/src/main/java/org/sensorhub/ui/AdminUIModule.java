/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.ui;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.LogManager;
import org.sensorhub.api.comm.CommProviderConfig;
import org.sensorhub.api.comm.NetworkConfig;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.database.DatabaseConfig;
import org.sensorhub.api.datastore.command.CommandFilter;
import org.sensorhub.api.datastore.command.CommandStreamFilter;
import org.sensorhub.api.datastore.obs.DataStreamFilter;
import org.sensorhub.api.datastore.obs.ObsFilter;
import org.sensorhub.api.datastore.system.SystemFilter;
import org.sensorhub.api.event.IEventListener;
import org.sensorhub.api.module.IModule;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.sensorhub.api.processing.ProcessConfig;
import org.sensorhub.api.sensor.SensorConfig;
import org.sensorhub.impl.database.system.SystemDriverDatabaseConfig;
import org.sensorhub.impl.datastore.view.ObsSystemDatabaseViewConfig;
import org.sensorhub.impl.security.BasicSecurityRealmConfig;
import org.sensorhub.impl.service.AbstractHttpServiceModule;
import org.sensorhub.impl.service.HttpServerConfig;
import org.sensorhub.impl.service.sos.SOSServiceConfig;
import org.sensorhub.impl.service.sps.SPSServiceConfig;
import org.sensorhub.ui.api.IModuleAdminPanel;
import org.sensorhub.ui.api.IModuleConfigForm;
import org.sensorhub.ui.filter.DatabaseFilterConfigForm;
import org.sensorhub.ui.filter.DatabaseViewConfigForm;
import com.vaadin.server.VaadinServlet;


public class AdminUIModule extends AbstractHttpServiceModule<AdminUIConfig> implements IEventListener
{
    protected static final String SERVLET_PARAM_UI_CLASS = "UI";
    protected static final String SERVLET_PARAM_MODULE = "module_instance";
    protected static final String WIDGETSET = "widgetset";
    protected static final int HEARTBEAT_INTERVAL = 10; // in seconds
    
    VaadinServlet vaadinServlet;
    AdminUISecurity securityHandler;
    Map<String, Class<? extends IModuleConfigForm>> customForms = new HashMap<>();
    Map<String, Class<? extends IModuleAdminPanel<?>>> customPanels = new HashMap<>();
    
    
    public AdminUIModule()
    {
    }
    
    
    @Override
    public void setConfiguration(AdminUIConfig config)
    {
        super.setConfiguration(config);
        
        // set security handler
        this.securityHandler = new AdminUISecurity(this, true);
        String configClass = null;
        
        // load custom forms
        try
        {
            customForms.clear();
                    
            // default form builders
            customForms.put(HttpServerConfig.class.getCanonicalName(), HttpServerConfigForm.class);
            customForms.put(SystemDriverDatabaseConfig.class.getCanonicalName(), SystemDriverDatabaseConfigForm.class);
            customForms.put(CommProviderConfig.class.getCanonicalName(), CommProviderConfigForm.class);
            customForms.put(BasicSecurityRealmConfig.UserConfig.class.getCanonicalName(), BasicSecurityConfigForm.class);
            customForms.put(BasicSecurityRealmConfig.RoleConfig.class.getCanonicalName(), BasicSecurityConfigForm.class);
            customForms.put(SOSServiceConfig.class.getCanonicalName(), SOSConfigForm.class);
            customForms.put(SPSServiceConfig.class.getCanonicalName(), SPSConfigForm.class);
            customForms.put(ObsSystemDatabaseViewConfig.class.getCanonicalName(), DatabaseViewConfigForm.class);
            customForms.put(SystemFilter.class.getCanonicalName(), DatabaseFilterConfigForm.class);
            customForms.put(DataStreamFilter.class.getCanonicalName(), DatabaseFilterConfigForm.class);
            customForms.put(CommandStreamFilter.class.getCanonicalName(), DatabaseFilterConfigForm.class);
            customForms.put(ObsFilter.class.getCanonicalName(), DatabaseFilterConfigForm.class);
            customForms.put(CommandFilter.class.getCanonicalName(), DatabaseFilterConfigForm.class);
            
            // custom form builders defined in config
            for (CustomUIConfig customForm: config.customForms)
            {
                configClass = customForm.configClass;
                @SuppressWarnings("unchecked")
                var clazz = (Class<IModuleConfigForm>)Class.forName(customForm.uiClass);
                customForms.put(configClass, clazz);
                getLogger().debug("Loaded custom form for {}", configClass);
            }
        }
        catch (Exception e)
        {
            getLogger().error("Error while instantiating form builder for config class {}", configClass, e);
        }
        
        // load custom panels
        try
        {
            customPanels.clear();
            
            // load default panel builders
            customPanels.put(SensorConfig.class.getCanonicalName(), SensorAdminPanel.class);
            customPanels.put(ProcessConfig.class.getCanonicalName(), ProcessAdminPanel.class);
            customPanels.put(DatabaseConfig.class.getCanonicalName(), DatabaseAdminPanel.class);
            customPanels.put(NetworkConfig.class.getCanonicalName(), NetworkAdminPanel.class);
            customPanels.put(SOSServiceConfig.class.getCanonicalName(), SOSAdminPanel.class);
            customPanels.put(SPSServiceConfig.class.getCanonicalName(), SPSAdminPanel.class);
            
            // load custom panel builders defined in config
            for (CustomUIConfig customPanel: config.customPanels)
            {
                configClass = customPanel.configClass;
                @SuppressWarnings("unchecked")
                var clazz = (Class<IModuleAdminPanel<?>>)Class.forName(customPanel.uiClass);
                customPanels.put(configClass, clazz);
                getLogger().debug("Loaded custom panel for {}", configClass);
            } 
        }
        catch (Exception e)
        {
            getLogger().error("Error while instantiating panel builder for config class {}", configClass, e);
        }
    }
    
    
    @Override
    protected void doStart() throws SensorHubException
    {
        // reset java util logging config so we don't get annoying atmosphere logs
        LogManager.getLogManager().reset();//.getLogger("org.atmosphere").setLevel(Level.OFF);
        
        vaadinServlet = new AdminUIServlet(getSecurityHandler(), getLogger());
        Map<String, String> initParams = new HashMap<>();
        initParams.put(SERVLET_PARAM_UI_CLASS, AdminUI.class.getCanonicalName());
        if (config.widgetSet != null)
            initParams.put(WIDGETSET, config.widgetSet);
        initParams.put("productionMode", "true");  // set to false to compile theme on-the-fly
        initParams.put("heartbeatInterval", Integer.toString(HEARTBEAT_INTERVAL));
        
        // deploy servlet
        // HACK: we have to disable std err to hide message due to Vaadin duplicate implementation of SL4J
        // Note that this may hide error messages in other modules now that startup sequence is multithreaded
        PrintStream oldStdErr = System.err;
        System.setErr(new PrintStream(new OutputStream() {
            @Override
            public void write(int b) { }
        }));
        httpServer.deployServlet(vaadinServlet, initParams, "/admin/*");
        System.setErr(oldStdErr);
        vaadinServlet.getServletContext().setAttribute(SERVLET_PARAM_MODULE, this);
        
        // setup security
        httpServer.addServletSecurity("/admin/*", true);

        setState(ModuleState.STARTED);
    }
    

    @Override
    protected void doStop() throws SensorHubException
    {
        if (vaadinServlet != null)
        {
            httpServer.undeployServlet(vaadinServlet);
            vaadinServlet = null;
        }
        
        setState(ModuleState.STOPPED);
    }
    
    
    @SuppressWarnings("unchecked")
    protected IModuleAdminPanel<IModule<?>> generatePanel(Class<?> clazz)
    {
        IModuleAdminPanel<IModule<?>> panel = null;
        
        try
        {
            Class<IModuleAdminPanel<IModule<?>>> uiClass = null;
            
            // check if there is a custom panel registered, if not use default
            while (uiClass == null && clazz != null)
            {
                uiClass = (Class<IModuleAdminPanel<IModule<?>>>)customPanels.get(clazz.getCanonicalName());
                clazz = clazz.getSuperclass();
            }
            
            if (uiClass != null)
                panel = uiClass.getDeclaredConstructor().newInstance();
        }
        catch (Exception e)
        {
            getLogger().error("Cannot create custom panel", e);
        }
        
        if (panel == null)
            return new DefaultModulePanel<>();
        else
            return panel;
    }
    
    
    @SuppressWarnings("unchecked")
    protected IModuleConfigForm generateForm(Class<?> clazz)
    {
        IModuleConfigForm form = null;
        
        try
        {
            // check if there is a custom form registered, if not use default
            Class<IModuleConfigForm> uiClass = null;
            while (uiClass == null && clazz != null)
            {
                uiClass = (Class<IModuleConfigForm>)customForms.get(clazz.getCanonicalName());
                clazz = clazz.getSuperclass();
            }
            
            if (uiClass != null)
               form = uiClass.getDeclaredConstructor().newInstance();
        }
        catch (Exception e)
        {
            getLogger().error("Cannot create custom form", e);
        }
        
        if (form == null)
            return new GenericConfigForm();
        else
            return form;
    }
    

    @Override
    public void cleanup() throws SensorHubException
    {
        // unregister security handler
        if (securityHandler != null)
            securityHandler.unregister();
    }

    
    protected AdminUISecurity getSecurityHandler()
    {
        return (AdminUISecurity)this.securityHandler;
    }

}
