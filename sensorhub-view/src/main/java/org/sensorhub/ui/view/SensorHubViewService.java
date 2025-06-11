package org.sensorhub.ui.view;

import com.vaadin.server.VaadinServlet;
import com.vaadin.ui.UI;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.event.IEventListener;
import org.sensorhub.api.module.ModuleEvent;
import org.sensorhub.impl.service.AbstractHttpServiceModule;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.LogManager;

public class SensorHubViewService extends AbstractHttpServiceModule<SensorHubViewConfig> implements IEventListener {


    protected static final String SERVLET_PARAM_MODULE = "view_instance";
    protected static final String SERVLET_PARAM_UI_CLASS = "SensorHubViewUI";
    protected static final String WIDGETSET = "widgetset";
    protected static final int HEARTBEAT_INTERVAL = 10;
    VaadinServlet servlet;

    public SensorHubViewService(){}


    @Override
    protected void doStart() throws SensorHubException {
        logger.debug("***** SensorHub View Starting *****");

        LogManager.getLogManager().reset();

        //deploy servlet
        servlet = new VaadinServlet();

        Map<String, String> initParams = new HashMap<>();
        initParams.put(SERVLET_PARAM_UI_CLASS, UI.class.getCanonicalName());
        if(config.widgetSet != null)
            initParams.put(WIDGETSET, config.widgetSet);
        initParams.put("productionMode", "true");
        initParams.put("heartbeatInterval", Integer.toString(HEARTBEAT_INTERVAL));


        PrintStream oldStdErr = System.err;
        System.setErr(new PrintStream(new OutputStream() {
            @Override
            public void write(int b) { }
        }));

        // deploy servlet
        httpServer.deployServlet(servlet, initParams, "/VAADIN/*");
        System.setErr(oldStdErr);
        servlet.getServletContext().setAttribute(SERVLET_PARAM_MODULE, this);

        setState(ModuleEvent.ModuleState.STARTED);
    }

    @Override
    protected void doStop() throws SensorHubException {
        //stop servlet
        undeploy();

        setState(ModuleEvent.ModuleState.STOPPED);
    }

    protected void undeploy() {
        if (servlet != null)
        {
            httpServer.undeployServlet(servlet);
            servlet.destroy();
            servlet = null;
        }
    }
}