package org.sensorhub.ui.view;

import com.vaadin.server.VaadinServlet;
import org.slf4j.Logger;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;


/**
 *
 * @author Kalyn Stricklin
 * @since June 2025
 */
@WebServlet(urlPatterns = "/VAADIN/*", name = "SensorHubViewServlet", asyncSupported = true)
public class SensorhubViewServlet extends VaadinServlet{

    final transient Logger log;

    SensorhubViewService parentService;

    SensorhubViewServlet(SensorhubViewService parentService, Logger log){
        this.log = log;
        this.parentService = parentService;
    }


    @Override
    protected void servletInitialized() throws ServletException {
        super.servletInitialized();

        getServletContext().setAttribute("view_instance", new SensorhubViewService());

        getService().addSessionInitListener(event ->
                log.debug("SensorHub View Servlet Initialized")
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
            response.sendError(HttpServletResponse.SC_FORBIDDEN, e.getMessage());
        }
    }

}


