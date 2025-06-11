package org.sensorhub.ui.vaadin;

import com.vaadin.server.VaadinRequest;
import com.vaadin.server.VaadinServlet;
import com.vaadin.ui.UI;
import org.sensorhub.api.ISensorHub;
import org.sensorhub.impl.module.ModuleRegistry;
import org.sensorhub.ui.AdminUIModule;
import org.slf4j.Logger;

import javax.servlet.ServletContext;

public class VaadinUI extends UI {

    transient Logger log;
    transient ISensorHub hub;
    transient VaadinServiceModule vaadinServiceModule;
    transient ModuleRegistry moduleRegistry;


    @Override
    protected void init(VaadinRequest request) {
        try{
            ServletContext servletContext = VaadinServlet.getCurrent().getServletContext();
            this.vaadinServiceModule = (VaadinServiceModule) servletContext.getAttribute(VaadinServiceModule.SERVLET_PARAM_MODULE);
            this.hub = vaadinServiceModule.getParentHub();
            this.moduleRegistry = hub.getModuleRegistry();
        }catch (Exception e)
        {
            throw new IllegalStateException("Cannot get Vaadin UI module configuration", e);
        }
    }


    public ISensorHub getParentHub()
    {
        return hub;
    }

    public VaadinServiceModule getParentModule()
    {
        return vaadinServiceModule;
    }

    public Logger getOshLogger()
    {
        return vaadinServiceModule.getLogger();
    }
}
