package org.sensorhub.impl.service.landing;

import com.vaadin.annotations.VaadinServletConfiguration;
import com.vaadin.server.VaadinServlet;
import org.sensorhub.api.security.IPermission;
import org.slf4j.Logger;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;

/**
 * @author Kalyn Stricklin
 * @since Feb 2025
 */
@WebServlet(urlPatterns = {"/*"}, name = "LandingServlet", asyncSupported = true)
@VaadinServletConfiguration(ui = LandingUI.class, productionMode = false)
public class LandingServlet extends VaadinServlet {

    Logger log;
    LandingUISecurity securityHandler;
    LandingService parentService;


    // this is used by the LandingService class
    LandingServlet(LandingService service, LandingUISecurity securityHandler, Logger log) {
        this.log = log;
        this.securityHandler = securityHandler;
        this.parentService = service;
    }


    @Override
    protected void servletInitialized() throws ServletException {
        super.servletInitialized();

        getServletContext().setAttribute("landing_instance", new LandingService());

        getService().addSessionInitListener(event ->
            log.debug("Landing Servlet Initialized")
        );
    }


    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String uri = request.getRequestURI();
        String redirectURL = parentService.getPublicEndpointUrl();

        try {
            Principal user = request.getUserPrincipal();
            if (user != null) {
                securityHandler.setCurrentUser(user.getName());
            }

            if (!isIgnored(uri) && !hasAccess(request)) {
                log.debug("Access Denied: Redirecting to " + redirectURL);
                response.sendRedirect(redirectURL);
                return;
            }

            this.getService().setClassLoader(this.getClass().getClassLoader());
            super.service(request, response);


        } catch (SecurityException e) {
            log.debug("Access Forbidden: " + e.getMessage());
            response.sendRedirect(redirectURL);
        } finally {
            securityHandler.clearCurrentUser();
        }
    }



    //helper functions to determine if the authenticated user has permissions to access the requested urls

    private boolean isIgnored(String uri) {
        return uri.startsWith("/VAADIN/")
                || uri.startsWith("/sensorhub/landing/VAADIN/")
                || uri.startsWith("/sensorhub/VAADIN/")
                || uri.startsWith("/sensorhub/APP/")
                || uri.startsWith("/sensorhub/PUSH/")
                || uri.equals("/sensorhub")
                || uri.equals("/sensorhub/")
                || uri.equals("/sensorhub/HEARTBEAT/")
                || uri.equals("/sensorhub/UIDL/")
                || uri.contains("OpenSensorHub-Logo.png")
                || uri.equals("/sensorhub/logout")
                || uri.equals("/sensorhub/admin/logout");
    }

    /**
     *  checks endpoint to see if user has access using role based permissions
     *  /sensorhub/sos?
     *  /sensorhub/admin
     *  /sensorhub/api
     *  /sensorhub/discovery/rules
     */
    private boolean hasAccess(HttpServletRequest request) {
        String path = request.getRequestURI();
        log.debug("Verifying permissions for "+ path);

        if (path.equals("/sensorhub/sos") && request.getQueryString() == null) {
            log.debug("Blocked direct access to /sensorhub/sos with no query parameters.");
            return false;
        }

        // handle role-based access checks
        var permissions = parentService.getParentHub().getSecurityManager().getAllModulePermissions();

        for (IPermission permission : permissions) {
            log.debug("Evaluating permission for URI {} with permission {}", path, permission.getName());

            if ((path.equals("/sensorhub/admin") || path.equals("/sensorhub/admin/")) && permission.getName().contains("webadmin")) {
                return checkPermission(permission);
            } else if (path.contains("/sensorhub/api/") && permission.getName().contains("csapi")) {
                return checkPermission(permission);
            } else if (path.contains("/sensorhub/sos") && permission.getName().contains("sos")) {
                return checkPermission(permission);
            } else if (path.contains("/sensorhub/discovery/") && permission.getName().contains("discoveryService")) {
                return checkPermission(permission);
            }

        }

        return false;
    }

    private boolean checkPermission(IPermission permission){
        boolean hasPerm = securityHandler.hasPermission(permission);
        log.debug("Permission check for {} returned {}", permission.getName(), hasPerm);
        return hasPerm;
    }

}