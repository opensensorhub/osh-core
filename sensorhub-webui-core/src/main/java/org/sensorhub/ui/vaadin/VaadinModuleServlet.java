package org.sensorhub.ui.vaadin;

import com.vaadin.server.VaadinServlet;
import org.slf4j.Logger;
import org.vast.ows.OWSUtils;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;


/**
 *
 * @author Kalyn Stricklin
 * @since June 2025
 */
@WebServlet(urlPatterns = "/VAADIN/*", name = "VaadinServlet", asyncSupported = true)
public class VaadinModuleServlet extends VaadinServlet{

    final transient Logger log;

    VaadinServiceModule vaadinServiceModule;

    VaadinModuleServlet(VaadinServiceModule vaadinServiceModule, Logger log){
        this.log = log;
        this.vaadinServiceModule = vaadinServiceModule;
    }


    @Override
    protected void servletInitialized() throws ServletException {
        super.servletInitialized();

        getServletContext().setAttribute("vaadin_instance", new VaadinServiceModule());

        getService().addSessionInitListener(event ->
                log.debug("Vaadin Servlet Initialized")
        );
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        try
        {
            this.getService().setClassLoader(this.getClass().getClassLoader());
            super.service(request, response);
        }
        catch (SecurityException e)
        {
            log.info("Access Forbidden: {}", e.getMessage());
            sendError(response, HttpServletResponse.SC_FORBIDDEN, e.getMessage());
        }
    }


    protected void sendError(HttpServletResponse resp, int errorCode, String errorMsg)
    {
        try
        {
            resp.sendError(errorCode, errorMsg);
        }
        catch (IOException e)
        {
            if (!OWSUtils.isClientDisconnectError(e) && log.isDebugEnabled())
                log.error("Cannot send error", e);
        }
    }
}


