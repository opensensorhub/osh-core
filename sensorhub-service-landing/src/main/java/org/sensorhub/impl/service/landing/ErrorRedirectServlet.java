package org.sensorhub.impl.service.landing;


import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 *
 * @author Kalyn Stricklin
 * @since February 2025
 */
@WebServlet(urlPatterns = {"/error/forbidden", "/error/invalid", "/error/notfound"})
public class ErrorRedirectServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws IOException {
        httpServletResponse.sendRedirect(httpServletRequest.getContextPath());
    }
}
