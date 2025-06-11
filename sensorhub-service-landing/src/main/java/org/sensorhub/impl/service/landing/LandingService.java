/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2012-2016 Sensia Software LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.landing;

import com.vaadin.server.VaadinServlet;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.event.IEventListener;
import org.sensorhub.api.module.ModuleEvent;
import org.sensorhub.impl.service.AbstractHttpServiceModule;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.LogManager;


/**
 * @author Kalyn Stricklin
 * @since Feb 2025
 */
public class LandingService extends AbstractHttpServiceModule<LandingConfig> implements IEventListener
{

    protected static final String SERVLET_PARAM_MODULE = "landing_instance";
    protected static final String SERVLET_PARAM_UI_CLASS = "LandingUI";
    protected static final String WIDGETSET = "widgetset";
    protected static final int HEARTBEAT_INTERVAL = 10;
    VaadinServlet landingServlet;
    LandingUISecurity securityHandler;


    public LandingService(){
    }

    @Override
    public void setConfiguration(LandingConfig config){
        super.setConfiguration(config);

        //set security handler
        this.securityHandler = new LandingUISecurity(this, true);
    }

    @Override
    protected void doStart() throws SensorHubException {
       logger.debug("***** Landing Service starting *****");

        LogManager.getLogManager().reset();

        //deploy servlet
        landingServlet = new LandingServlet(this, getSecurityHandler(), getLogger());

        Map<String, String> initParams = new HashMap<>();
        initParams.put(SERVLET_PARAM_UI_CLASS, LandingUI.class.getCanonicalName());
        if(config.widgetSet != null)
            initParams.put(WIDGETSET, config.widgetSet);
        initParams.put("productionMode", "true");
        initParams.put("heartbeatInterval", Integer.toString(HEARTBEAT_INTERVAL));


        // deploy servlet
        deploy(initParams);

    }


    @Override
    protected void doStop() throws SensorHubException {
        //stop servlet
        undeploy();

        setState(ModuleEvent.ModuleState.STOPPED);
    }

    protected void deploy(Map<String, String> initParams){

        PrintStream oldStdErr = System.err;
        System.setErr(new PrintStream(new OutputStream() {
            @Override
            public void write(int b) { }
        }));

        logger.debug("***** Landing Service deploying *****");


        // deploy landing ui to HTTP server
        httpServer.deployServlet(landingServlet, initParams, "/*");
        System.setErr(oldStdErr);
        landingServlet.getServletContext().setAttribute(SERVLET_PARAM_MODULE, this);

        //set up security
        httpServer.addServletSecurity("/*", config.security.requireAuth);
        
        setState(ModuleEvent.ModuleState.STARTED);
    }


    protected void undeploy() {
        if (landingServlet != null)
        {
            httpServer.undeployServlet(landingServlet);
            landingServlet.destroy();
            landingServlet = null;
        }

    }

    @Override
    public void cleanup() throws SensorHubException {
        // unregister security handler
        if (securityHandler != null)
            securityHandler.unregister();
    }

    protected LandingUISecurity getSecurityHandler() { return this.securityHandler;}

    public String getPublicEndpointUrl()
    {
        return getHttpServer().getPublicEndpointUrl(config.endPoint);
    }

}
