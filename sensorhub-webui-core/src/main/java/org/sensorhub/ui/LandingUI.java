package org.sensorhub.ui;


import com.vaadin.annotations.Theme;
import com.vaadin.annotations.Title;
import com.vaadin.server.*;
import com.vaadin.shared.ui.ContentMode;
import com.vaadin.ui.*;
import com.vaadin.ui.themes.ValoTheme;
import com.vaadin.v7.ui.themes.Reindeer;
import org.sensorhub.api.ISensorHub;
import org.sensorhub.api.security.IPermission;
import org.sensorhub.api.service.HttpServiceConfig;
import org.sensorhub.api.service.IServiceModule;
import org.sensorhub.impl.module.ModuleRegistry;
import org.sensorhub.impl.service.AbstractHttpServiceModule;
import org.slf4j.Logger;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.Flow;

/**
 * @author Kalyn Stricklin
 * @since Feb 2025
 */
@Theme("sensorhub")
@Title("OpenSensorHub Landing Page")
public class LandingUI extends UI{

    transient AdminUIModule service;
    transient ISensorHub hub;
    transient Logger log;
    transient ModuleRegistry moduleRegistry;
    transient AdminUISecurity securityHandler;
    transient Flow.Subscription moduleEventsSub;

    private final Map<String, Object> panelMap = new HashMap<>();

    @Override
    protected void init(VaadinRequest vaadinRequest) {

        try{
            ServletContext servletContext = VaadinServlet.getCurrent().getServletContext();
            this.service = (AdminUIModule) servletContext.getAttribute(AdminUIModule.SERVLET_PARAM_MODULE);
            this.hub = service.getParentHub();
            this.log = service.getLogger();
            this.moduleRegistry = hub.getModuleRegistry();
            this.securityHandler = service.getSecurityHandler();

        }catch(Exception e){
            throw new IllegalStateException("Cannot get Landing page UI configuration", e);
        }

        //request
        logInitRequest(vaadinRequest);

        //main layout
        VerticalLayout content = new VerticalLayout();
        content.setSizeFull();
        content.setSpacing(false);
        content.setMargin(false);
        setContent(content);

        //header
        Component header = buildHeader();
        content.addComponent(header);
        content.setComponentAlignment(header, Alignment.TOP_CENTER);

        //logout button
        Component logoutButton = createLogoutButton();
        content.addComponent(logoutButton);
        content.setComponentAlignment(logoutButton, Alignment.TOP_CENTER);

        //create grid layout for buttons
        GridLayout grid = new GridLayout(2,2);
        grid.setMargin(true);
        grid.setSpacing(true);


        var modules = getParentHub().getModuleRegistry().getLoadedModules();

        for(var module : modules){

            if(!(module instanceof AbstractHttpServiceModule) && !(module instanceof IServiceModule) || panelMap.containsKey(module.getClass().getCanonicalName()))
                continue;

            //iterate over permissions and add the card if permission
            Collection<IPermission> allModulePermissions = module.getParentHub().getSecurityManager().getAllModulePermissions();

            // check if module has any permissions
            List<IPermission> matchingPerms = new ArrayList<>();
            for(IPermission permission: allModulePermissions) {
                if (permission.getName().contains(module.getConfiguration().id)) {
                    matchingPerms.add(permission);
                }
            }

            // check for allowed permissions
            List<IPermission> allowedPermissions = new ArrayList<>();
            for(IPermission permission: matchingPerms) {
                allowedPermissions.addAll(getAllowedPermissions(permission));
            }


            // if the system has permissions but the user is denied dont add panel
            if(allowedPermissions.isEmpty() && !matchingPerms.isEmpty())
                continue;

            String path = null;

            if(module instanceof AdminUIModule)
                path =  "/admin";
            else if(module.getConfiguration() instanceof HttpServiceConfig)
                path = ((HttpServiceConfig) module.getConfiguration()).endPoint;
//            else if(module.getConfiguration() instanceof ServiceConfig)
//                path = null;


            grid.addComponent(createPanel(module.getName(), path, allowedPermissions.toString()));
            panelMap.put(module.getClass().getCanonicalName(), module);
        }

        if (grid.getComponentCount() > 0) {
            content.addComponent(grid);
            content.setComponentAlignment(grid, Alignment.TOP_CENTER);

            content.setExpandRatio(header, 0);
            content.setExpandRatio(grid, 1);
        }
    }

    protected void logInitRequest(VaadinRequest req){
        if(log.isInfoEnabled()){
            String ip = req.getRemoteAddr(); //getRemoteHost

            String hostname = VaadinRequest.getCurrent().getHeader("host"); //returns ip:port

            String user = req.getRemoteUser() != null ? req.getRemoteUser() : "anon";

            log.info("New login to landing page (from ip={}, port={}, user={})", ip, hostname, user);
        }
    }

    /**
     * Helper to build vaadin panels with buttons
     */
    private Panel createPanel(String title, String path, String permissions) {
        Panel panel = new Panel();
        panel.setHeight("250px");
        panel.setWidth("450px");
        panel.setStyleName(Reindeer.PANEL_LIGHT);

        VerticalLayout layout = new VerticalLayout();
        layout.setSizeFull();
        layout.setMargin(true);
        layout.setDefaultComponentAlignment(Alignment.MIDDLE_CENTER);

        String titleHtml = "<style>"
                + "@import url('https://fonts.googleapis.com/css2?family=Electrolize&display=swap');"
                + "</style>"
                + "<h1 style='font-size:26px; text-align: center; font-family: Electrolize, sans-serif;'>"
                + title
                + "</h1>";

        Label titleLabel = new Label(titleHtml, ContentMode.HTML);
        Component button = buildEndpointComponent(path);

//        String permissionsHtml = "<style>"
//                + "@import url('https://fonts.googleapis.com/css2?family=Electrolize&display=swap');"
//                + "</style>"
//                + "<h6 style='font-size:12px; text-align: center; font-family: Electrolize, sans-serif; text-wrap: wrap;'>"
//                + permissions
//                + "</h6>";

//        Label permissionsText = new Label(permissionsHtml, ContentMode.HTML);
//        layout.addComponents(titleLabel, permissionsText, button);

        layout.addComponents(titleLabel, button);
        panel.setContent(layout);

        return panel;
    }

    /**
     * Builds the endpoint component
     * @param endpoint
     * @return button or label
     */
    private Component buildEndpointComponent(String endpoint) {
        Button button = new Button("VIEW");
        button.addStyleNames(ValoTheme.BUTTON_LARGE, ValoTheme.BUTTON_ICON_ALIGN_RIGHT);

        String title = "No accessible endpoint";

        String titleHtml = "<style>"
                + "@import url('https://fonts.googleapis.com/css2?family=Electrolize&display=swap');"
                + "</style>"
                + "<h6 style='font-size:18px; text-align: center; font-family: Electrolize, sans-serif;'>"
                + title
                + "</h6>";

        Label textLabel = new Label(titleHtml, ContentMode.HTML);

        // validate endpoint and pass back the button or the label
        boolean validPath = false;

        if(endpoint != null){
            try{
                String baseUrl = Page.getCurrent().getLocation().getScheme() + "://" + Page.getCurrent().getLocation().getAuthority();
                String url = baseUrl + "/sensorhub" + endpoint;
                URL myurl = new URL(url);

//                HttpURLConnection huc = (HttpURLConnection) myurl.openConnection();
//                huc.setRequestMethod("GET");
//                huc.connect();
//                if(huc.getResponseCode() == 200)
                validPath = true;

                //add navigation to button
                button.addClickListener(event ->{
                    getUI().getPage().open(url, "_blank");
                });

            } catch (MalformedURLException e) {
                log.warn("Invalid URL for module: {}" , e.getMessage());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }


        return validPath ? button : textLabel;
    }


    /**
     * builds the header with the osh logo
     * @return header
     */
    private Component buildHeader() {
        Image osh_logo = new Image();
        osh_logo.setSource(new ClassResource("/icons/OpenSensorHub-Logo.png"));

        VerticalLayout headerContainer = new VerticalLayout();
        headerContainer.setSpacing(false);
        headerContainer.setMargin(false);
        headerContainer.setWidthFull();

        headerContainer.addComponent(osh_logo);
        headerContainer.setComponentAlignment(osh_logo, Alignment.TOP_CENTER);

        return headerContainer;
    }


    /**
     *  builds the component for logout button
     * @return button
     */
    private Component createLogoutButton(){
        // logout button
        Button logoutButton = new Button("Logout");
        logoutButton.setDescription("Logout from OSH node");
        logoutButton.setIcon(FontAwesome.SIGN_OUT);
        logoutButton.addStyleName(ValoTheme.BUTTON_LARGE);
        logoutButton.setWidth("200px");

        getParentHub().getSecurityManager();
        logoutButton.addClickListener(new Button.ClickListener() {
            @Override
            public void buttonClick(Button.ClickEvent event)
            {
                final ConfirmDialog popup = new ConfirmDialog("Are you sure you want to logout?");
                popup.addCloseListener(new Window.CloseListener() {
                    @Override
                    public void windowClose(Window.CloseEvent e)
                    {
                        if (popup.isConfirmed())
                        {


                            //disconnect from module registry
                            // unregister from module registry events
                            if (moduleEventsSub != null)
                                moduleEventsSub.cancel();

                            VaadinServletRequest request = (VaadinServletRequest) VaadinService.getCurrentRequest();
                            HttpSession httpSession = request.getSession(false);

                            if(httpSession != null){
                                httpSession.invalidate();

                            }

                            log.debug("log out session: "+ getUI().getSession());
                            getUI().getSession().close();

                            //set page to /sensorhub/logout
                            getUI().getPage().setLocation("/sensorhub/logout");


                        }
                    }
                });

                addWindow(popup);
            }
        });

        return logoutButton;
    }

    /**
     * checks to see if given permissions are authorized to access for each module
     * @param permission
     * @return
     */
    private List<IPermission> getAllowedPermissions(IPermission permission){

        var permissionList = permission.getChildren().values();
        List<IPermission> allowedPermissionsList = new ArrayList();

        for(IPermission perm: permissionList){
            boolean hasPerm = getParentModule().getSecurityHandler().hasPermission(perm);

            if(hasPerm){
                allowedPermissionsList.add(perm);
            }
        }

        return allowedPermissionsList;
    }


    public ISensorHub getParentHub()
    {
        return hub;
    }

    public AdminUIModule getParentModule()
    {
        return service;
    }

}