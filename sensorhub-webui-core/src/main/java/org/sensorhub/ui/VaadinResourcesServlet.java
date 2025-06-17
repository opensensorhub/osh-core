package org.sensorhub.ui;

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
@WebServlet(urlPatterns = "/VAADIN/*", name = "VaadinResourcesServlet", asyncSupported = true)
public class VaadinResourcesServlet extends VaadinServlet{

    final transient Logger log;

    AdminUIModule parentService;

    VaadinResourcesServlet(AdminUIModule parentService, Logger log){
        this.log = log;
        this.parentService = parentService;
    }


    @Override
    protected void servletInitialized() throws ServletException {
        super.servletInitialized();

        getServletContext().setAttribute("view_instance", new AdminUIModule());

        getService().addSessionInitListener(event ->
                log.debug("Vaadin Resources Servlet Initialized")
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


