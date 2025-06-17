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
import org.sensorhub.impl.module.ModuleRegistry;
import org.sensorhub.impl.service.consys.ConSysApiService;
import org.sensorhub.impl.service.sos.SOSService;
import org.slf4j.Logger;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.concurrent.Flow;

/**
 * @author Kalyn Stricklin
 * @since Feb 2025
 */
@Theme("valo")
@Title("OpenSensorHub Landing Page")
public class LandingUI extends UI{

    transient AdminUIModule service;
    transient ISensorHub hub;
    transient Logger log;
    transient ModuleRegistry moduleRegistry;
    transient AdminUISecurity securityHandler;
    private String ip;
    String hostname;
    String user;
    transient Flow.Subscription moduleEventsSub;



    @Override
    protected void init(VaadinRequest vaadinRequest) {

        try{
            ServletContext servletContext = VaadinServlet.getCurrent().getServletContext();
            this.service = (AdminUIModule) servletContext.getAttribute(AdminUIModule.LANDING_SERVLET_PARAM_MODULE);
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

                            System.out.println("log out session: "+ getUI().getSession());
                            getUI().getSession().close();

                            //set page to /sensorhub/logout
                            getUI().getPage().setLocation("/sensorhub/logout");


                        }
                    }
                });

                addWindow(popup);
            }
        });

        content.addComponent(logoutButton);
        content.setComponentAlignment(logoutButton, Alignment.TOP_CENTER);



        //create grid layout for buttons
        GridLayout grid = new GridLayout(2,2);
        grid.setMargin(true);
        grid.setSpacing(true);

        //get all permissions avail
        var permissions = getParentHub().getSecurityManager().getAllModulePermissions();

        boolean hasWebAdmin = false;
        boolean hasDiscovery = false;
        boolean hasSos = false;
        boolean hasCsapi = false;

        //iterate over permissions and add the card if permission
        for(IPermission permission: permissions){
            
//            boolean hasPermission = getParentModule().getSecurityHandler().hasPermission(permission);
//
//
//            if(!hasPermission){
//                continue;
//            }

            //check parenthub modules as well

            var modules = getParentHub().getModuleRegistry().getLoadedModules();

            for(var module : modules){
                String permissionName = permission.getName();

                if (permissionName.contains("webadmin") && !hasWebAdmin && module instanceof AdminUIModule) {
                    var permissionsList = permission.getChildren().values();

                    var allowedPermissionsList = new ArrayList();

                    for(IPermission perm: permissionsList){

                        boolean hasPerm = getParentModule().getSecurityHandler().hasPermission(perm);

                        if(hasPerm){
                            allowedPermissionsList.add(perm);
                        }
                    }
                    if(!allowedPermissionsList.isEmpty()){

                        grid.addComponent(createPanel("Admin Panel", "/admin", allowedPermissionsList.toString()));
                        hasWebAdmin = true;
                    }

                }
                else if (permissionName.contains("discoveryService") && !hasDiscovery && module.getClass().getSimpleName().equals("DiscoveryService")) {
                    var permissionsList = permission.getChildren().values();

                    var allowedPermissionsList = new ArrayList();

                    for(IPermission perm: permissionsList){

                        for(IPermission permChildren: perm.getChildren().values()){
                            boolean hasPerm = getParentModule().getSecurityHandler().hasPermission(permChildren);

                            if(hasPerm){
                                allowedPermissionsList.add(permChildren);
                            }
                        }
                    }
                    if(!allowedPermissionsList.isEmpty()){
                        String path = ((HttpServiceConfig) module.getConfiguration()).endPoint;

                        grid.addComponent(createPanel("Discovery Service", path, allowedPermissionsList.toString()));
                        hasDiscovery = true;
                    }
                }
                else if (permissionName.contains("sos") && !hasSos && module instanceof SOSService) {
                    var permissionsList = permission.getChildren().values();

                    var allowedPermissionsList = new ArrayList();

                    for(IPermission perm: permissionsList){

                        for(IPermission permChildren: perm.getChildren().values()){
                            boolean hasPerm = getParentModule().getSecurityHandler().hasPermission(permChildren);

                            if(hasPerm){
                                allowedPermissionsList.add(permChildren);
                            }
                        }

                    }
                    if(!allowedPermissionsList.isEmpty()){
                        String path = ((SOSService) module).getConfiguration().endPoint;
                        grid.addComponent(createPanel("SOS Service", path, allowedPermissionsList.toString()));
                        hasSos = true;
                    }

                }
                else if (permissionName.contains("csapi") && !hasCsapi && module instanceof ConSysApiService) {
                    var permissionsList = permission.getChildren().values();

                    var allowedPermissionsList = new ArrayList();

                    for(IPermission perm: permissionsList){

                        boolean hasPerm = getParentModule().getSecurityHandler().hasPermission(perm);

                        if(hasPerm){
                            allowedPermissionsList.add(perm);
                        }
//                    for(IPermission permChildren: perm.getChildren().values()){
//                        log.debug("perm children: {}", permChildren);
//                        boolean hasPerm = getParentModule().getSecurityHandler().hasPermission(permChildren);
//
//                        if(hasPerm){
//                            allowedPermissionsList.add(permChildren);
//                        }
//
//                    }

                    }
                    if(!allowedPermissionsList.isEmpty()){
                        String path = ((ConSysApiService) module).getConfiguration().endPoint;
                        grid.addComponent(createPanel("Connected Systems", path, allowedPermissionsList.toString()));
                        hasCsapi = true;
                    }
                }
            }


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
            ip = req.getRemoteAddr(); //getRemoteHost

            hostname = VaadinRequest.getCurrent().getHeader("host"); //returns ip:port

            user = req.getRemoteUser() != null ? req.getRemoteUser() : "anon";

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

        String titleHtml = "<style>"
                + "@import url('https://fonts.googleapis.com/css2?family=Electrolize&display=swap');"
                + "</style>"
                + "<h1 style='font-size:26px; text-align: center; font-family: Electrolize, sans-serif;'>"
                + title
                + "</h1>";

        Label titleLabel = new Label(titleHtml, ContentMode.HTML);
        Component button = buildButtons(path);

        String permissionsHtml = "<style>"
                + "@import url('https://fonts.googleapis.com/css2?family=Electrolize&display=swap');"
                + "</style>"
                + "<h6 style='font-size:12px; text-align: center; font-family: Electrolize, sans-serif; text-wrap: wrap;'>"
                + permissions
                + "</h6>";

        Label permissionsText = new Label(permissionsHtml, ContentMode.HTML);
        layout.addComponents(titleLabel, button);
//        layout.addComponents(titleLabel, permissionsText, button);

        layout.setComponentAlignment(titleLabel, Alignment.MIDDLE_CENTER);
        layout.setComponentAlignment(button, Alignment.BOTTOM_CENTER);

        panel.setContent(layout);

        return panel;
    }


    /**
     * Helper to build vaadin buttons
     */
    private Component buildButtons(String endpoint) {

        Button button = new Button("VIEW");
        button.addStyleNames(ValoTheme.BUTTON_LARGE, ValoTheme.BUTTON_ICON_ALIGN_RIGHT);

        String baseUrl = Page.getCurrent().getLocation().getScheme() + "://" + Page.getCurrent().getLocation().getAuthority();
        String url = baseUrl + "/sensorhub" + endpoint;


        //navigate to path based on btn
        button.addClickListener(event ->{
            getUI().getPage().open(url, "_blank");
        });

        return button;
    }

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


    public ISensorHub getParentHub()
    {
        return hub;
    }


    public AdminUIModule getParentModule()
    {
        return service;
    }

}


