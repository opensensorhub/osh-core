/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.

 ******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.ui;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Collection;
import java.util.concurrent.Flow.Subscription;
import javax.servlet.ServletContext;

import com.vaadin.shared.ui.MultiSelectMode;
import org.sensorhub.api.ISensorHub;
import org.sensorhub.api.client.ClientConfig;
import org.sensorhub.api.comm.NetworkConfig;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.database.DatabaseConfig;
import org.sensorhub.api.module.IModule;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.api.module.ModuleConfigBase;
import org.sensorhub.api.module.ModuleEvent;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.sensorhub.api.processing.ProcessConfig;
import org.sensorhub.api.security.SecurityModuleConfig;
import org.sensorhub.api.sensor.SensorConfig;
import org.sensorhub.api.service.IHttpServer;
import org.sensorhub.api.service.ServiceConfig;
import org.sensorhub.api.system.ISystemDriver;
import org.sensorhub.api.system.ISystemGroupDriver;
import org.sensorhub.impl.module.ModuleRegistry;
import org.sensorhub.impl.sensor.SensorSystem;
import org.sensorhub.impl.sensor.SensorSystemConfig;
import org.sensorhub.impl.sensor.SensorSystemConfig.SystemMember;
import org.sensorhub.ui.ModuleTypeSelectionPopup.ModuleTypeSelectionCallback;
import org.sensorhub.ui.api.IModuleAdminPanel;
import org.sensorhub.ui.api.UIConstants;
import org.sensorhub.ui.data.MyBeanItem;
import org.sensorhub.utils.ModuleUtils;
import org.sensorhub.utils.MsgUtils;
import org.slf4j.Logger;
import org.vast.ows.OWSUtils;
import com.vaadin.annotations.Push;
import com.vaadin.annotations.Theme;
import com.vaadin.v7.data.Item;
import com.vaadin.v7.data.util.converter.Converter;
import com.vaadin.v7.data.util.converter.ConverterFactory;
import com.vaadin.v7.data.util.converter.DefaultConverterFactory;
import com.vaadin.v7.data.util.converter.StringToIntegerConverter;
import com.vaadin.event.Action;
import com.vaadin.event.Action.Handler;
import com.vaadin.v7.event.ItemClickEvent;
import com.vaadin.v7.event.ItemClickEvent.ItemClickListener;
import com.vaadin.server.FontAwesome;
import com.vaadin.server.Resource;
import com.vaadin.server.ThemeResource;
import com.vaadin.server.VaadinRequest;
import com.vaadin.server.VaadinService;
import com.vaadin.server.VaadinServlet;
import com.vaadin.server.VaadinSession;
import com.vaadin.shared.communication.PushMode;
import com.vaadin.shared.ui.ContentMode;
import com.vaadin.shared.ui.ui.Transport;
import com.vaadin.v7.ui.AbstractSelect.ItemDescriptionGenerator;
import com.vaadin.ui.Accordion;
import com.vaadin.ui.Alignment;
import com.vaadin.ui.Button;
import com.vaadin.ui.Component;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.HorizontalSplitPanel;
import com.vaadin.ui.Image;
import com.vaadin.ui.Label;
import com.vaadin.ui.Notification;
import com.vaadin.ui.Button.ClickEvent;
import com.vaadin.ui.Button.ClickListener;
import com.vaadin.ui.TabSheet.SelectedTabChangeEvent;
import com.vaadin.ui.TabSheet.SelectedTabChangeListener;
import com.vaadin.ui.TabSheet.Tab;
import com.vaadin.v7.ui.Table;
import com.vaadin.v7.ui.Table.CellStyleGenerator;
import com.vaadin.v7.ui.Table.ColumnHeaderMode;
import com.vaadin.v7.ui.TreeTable;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.ui.Window;
import com.vaadin.ui.Window.CloseEvent;
import com.vaadin.ui.Window.CloseListener;


@Theme("sensorhub")
@Push(value=PushMode.MANUAL, transport=Transport.LONG_POLLING)
@SuppressWarnings({ "serial", "deprecation" })
public class AdminUI extends com.vaadin.ui.UI implements UIConstants
{
    private static final String LOG_INIT_MSG = "New connection to admin UI (from ip={}, user={})";
    private static final String LOG_ACTION_MSG = "New UI action: {} (from ip={}, user={})";

    private static final Action ADD_MODULE_ACTION = new Action("Add New Module", new ThemeResource("icons/module_add.png"));
    private static final Action ADD_SUBMODULE_ACTION = new Action("Add Submodule", new ThemeResource("icons/module_add.png"));
    private static final Action REMOVE_MODULE_ACTION = new Action("Remove Module", new ThemeResource("icons/module_delete.png"));
    private static final Action REMOVE_SUBMODULE_ACTION = new Action("Remove Submodule", new ThemeResource("icons/module_delete.png"));
    private static final Action START_MODULE_ACTION = new Action("Start", new ThemeResource("icons/enable.png"));
    private static final Action STOP_MODULE_ACTION = new Action("Stop", new ThemeResource("icons/disable.gif"));
    private static final Action RESTART_MODULE_ACTION = new Action("Restart", new ThemeResource("icons/refresh.gif"));
    private static final Action REINIT_MODULE_ACTION = new Action("Force Init", new ThemeResource("icons/refresh.gif"));
    private static final Action SELECT_ALL_MODULES_ACTION = new Action("Select All Modules");
    private static final Action DESELECT_ALL_MODULES_ACTION = new Action("Deselect All Modules");
    private static final Resource LOGO_ICON = new ThemeResource("icons/osh_logo_small.png");
    private static final String STYLE_LOGO = "logo";
    private static final String PROP_STATE = "state";
    private static final String PROP_MODULE_OBJECT = "module";

    transient Logger log;
    transient ISensorHub hub;
    transient AdminUIModule adminModule;
    transient ModuleRegistry moduleRegistry;
    transient Subscription moduleEventsSub;
    transient AdminUISecurity securityHandler;
    transient Map<Class<?>, TreeTable> moduleTables = new HashMap<>();
    transient IModule<?> moduleAddedFromUI;
    transient Accordion moduleStack;
    transient VerticalLayout configArea;
    transient IModule<?> visibleModule;


    @Override
    protected void init(VaadinRequest request)
    {
        // retrieve module config
        try
        {
            ServletContext servletContext = VaadinServlet.getCurrent().getServletContext();
            this.adminModule = (AdminUIModule)servletContext.getAttribute(AdminUIModule.SERVLET_PARAM_MODULE);
            this.hub = adminModule.getParentHub();
            this.log = adminModule.getLogger();
            this.moduleRegistry = hub.getModuleRegistry();
            this.securityHandler = adminModule.getSecurityHandler();
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Cannot get UI module configuration", e);
        }

        // log request
        logInitRequest(request);

        // security check
        if (!securityHandler.hasPermission(securityHandler.admin_access))
        {
            DisplayUtils.showUnauthorizedAccess(securityHandler.admin_access.getErrorMessage());
            securityHandler.clearCurrentUser();
            return;
        }

        // register new field converter for integer numbers
        ConverterFactory converterFactory = new DefaultConverterFactory() {
            @Override
            @SuppressWarnings("unchecked")
            protected <Presentation, Model> Converter<Presentation, Model> findConverter(
                    Class<Presentation> presentationType, Class<Model> modelType) {
                // Handle String <-> Integer/Short/Long
                if (presentationType == String.class &&
                        (modelType == Long.class || modelType == Integer.class || modelType == Short.class )) {
                    return (Converter<Presentation, Model>) new StringToIntegerConverter() {
                        @Override
                        protected NumberFormat getFormat(Locale locale) {
                            NumberFormat format = super.getFormat(Locale.US);
                            format.setGroupingUsed(false);
                            return format;
                        }
                    };
                }
                // Let default factory handle the rest
                return super.findConverter(presentationType, modelType);
            }
        };
        VaadinSession.getCurrent().setConverterFactory(converterFactory);

        // init main panels
        HorizontalSplitPanel splitPanel = new HorizontalSplitPanel();
        splitPanel.setMinSplitPosition(300.0f, Unit.PIXELS);
        splitPanel.setMaxSplitPosition(30.0f, Unit.PERCENTAGE);
        splitPanel.setSplitPosition(350.0f, Unit.PIXELS);
        setContent(splitPanel);

        // build left pane
        VerticalLayout leftPane = new VerticalLayout();
        leftPane.setSizeFull();
        leftPane.setSpacing(false);
        leftPane.setMargin(false);

        // header image and title
        Component header = buildHeader();
        leftPane.addComponent(header);
        leftPane.setExpandRatio(header, 0);

        // toolbar
        Component toolbar = buildToolbar();
        leftPane.addComponent(toolbar);
        leftPane.setExpandRatio(toolbar, 0);

        // accordion with several sections
        moduleTables.clear();
        final var stack = moduleStack = new Accordion();
        stack.setSizeFull();
        stack.addSelectedTabChangeListener(new SelectedTabChangeListener() {
            @Override
            public void selectedTabChange(SelectedTabChangeEvent event)
            {
                selectStackItem(stack);
            }
        });
        VerticalLayout layout;
        Tab tab;

        layout = new VerticalLayout();
        tab = stack.addTab(layout, "Sensors");
        //tab.setIcon(ACC_TAB_ICON);
        //tab.setIcon(FontAwesome.VIDEO_CAMERA);
        //tab.setIcon(FontAwesome.STETHOSCOPE);
        tab.setIcon(FontAwesome.RSS);
        buildModuleList(layout, SensorConfig.class);

        layout = new VerticalLayout();
        tab = stack.addTab(layout, "Databases");
        //tab.setIcon(ACC_TAB_ICON);
        tab.setIcon(FontAwesome.DATABASE);
        buildModuleList(layout, DatabaseConfig.class);

        layout = new VerticalLayout();
        tab = stack.addTab(layout, "Processing");
        //tab.setIcon(ACC_TAB_ICON);
        tab.setIcon(FontAwesome.GEARS);
        buildModuleList(layout, ProcessConfig.class);

        layout = new VerticalLayout();
        tab = stack.addTab(layout, "Services");
        //tab.setIcon(ACC_TAB_ICON);
        //tab.setIcon(FontAwesome.CLOUD_DOWNLOAD);
        //tab.setIcon(FontAwesome.CUBES);
        tab.setIcon(FontAwesome.TASKS);
        buildModuleList(layout, ServiceConfig.class);

        layout = new VerticalLayout();
        tab = stack.addTab(layout, "Clients");
        //tab.setIcon(ACC_TAB_ICON);
        tab.setIcon(FontAwesome.CLOUD_UPLOAD);
        buildModuleList(layout, ClientConfig.class);

        layout = new VerticalLayout();
        tab = stack.addTab(layout, "Network");
        //tab.setIcon(ACC_TAB_ICON);
        //tab.setIcon(FontAwesome.SIGNAL);
        tab.setIcon(FontAwesome.SITEMAP);
        buildNetworkModuleList(layout);

        layout = new VerticalLayout();
        tab = stack.addTab(layout, "Security");
        //tab.setIcon(ACC_TAB_ICON);
        tab.setIcon(FontAwesome.LOCK);
        buildModuleList(layout, SecurityModuleConfig.class);

        leftPane.addComponent(stack);
        leftPane.setExpandRatio(stack, 1);
        splitPanel.addComponent(leftPane);

        // init config area
        configArea = new VerticalLayout();
        configArea.setMargin(true);
        splitPanel.addComponent(configArea);

        // select first tab
        stack.setSelectedTab(0);
        selectStackItem(stack);

        // register to module registry events
        hub.getEventBus().newSubscription()
                .withTopicID(ModuleRegistry.EVENT_GROUP_ID)
                .consume(this::handleEvent)
                .thenAccept(s -> moduleEventsSub = s);
    }


    protected void selectStackItem(Accordion stack)
    {
        VerticalLayout tabLayout = (VerticalLayout)stack.getSelectedTab();
        if (tabLayout.getComponentCount() > 0)
        {
            TreeTable table = (TreeTable)tabLayout.getComponent(0);
            Object itemId = table.getValue();
            if(itemId instanceof Collection<?>)
                itemId = ((Collection<Object>)itemId).stream().findFirst().orElse(null);
            if (itemId != null)
            {
                IModule<?> module = (IModule<?>)table.getItem(itemId).getItemProperty(PROP_MODULE_OBJECT).getValue();
                selectModule(module, table);
            }
            else
                selectNone(table);
        }
    }

    protected Component buildHeader()
    {
        HorizontalLayout header = new HorizontalLayout();
        header.setMargin(false);
        header.setWidth(100.0f, Unit.PERCENTAGE);

        // logo
        Image img = new Image(null, LOGO_ICON);
        img.setStyleName(STYLE_LOGO);
        header.addComponent(img);
        header.setExpandRatio(img, 0);
        header.setComponentAlignment(img, Alignment.MIDDLE_LEFT);

        // title
        Label title = new Label("OpenSensorHub");
        //title.addStyleName(STYLE_H2);
        title.addStyleName(STYLE_LOGO);
        //title.setWidth(null);
        header.addComponent(title);
        header.setExpandRatio(title, 1);
        header.setComponentAlignment(title, Alignment.MIDDLE_LEFT);

        // about icon
        Button about = new Button();
        about.addStyleName(STYLE_QUIET);
        about.addStyleName(STYLE_BORDERLESS);
        about.setIcon(FontAwesome.QUESTION_CIRCLE);
        about.addClickListener(new ClickListener() {
            private static final long serialVersionUID = 1L;
            @Override
            public void buttonClick(ClickEvent event)
            {
                String version = ModuleUtils.getModuleInfo(getClass()).getModuleVersion();
                String buildNumber = ModuleUtils.getBuildNumber(getClass());
                Window popup = new Window("<b>About OpenSensorHub</b>");
                popup.setIcon(LOGO_ICON);
                popup.setCaptionAsHtml(true);
                popup.setModal(true);
                popup.setClosable(true);
                popup.setResizable(false);
                popup.center();
                VerticalLayout content = new VerticalLayout();
                content.setMargin(true);
                content.setSpacing(true);
                content.addComponent(new Label("A software platform for building smart sensor networks and the Internet of Things"));
                content.addComponent(new Label("Licenced under <a href=\"https://www.mozilla.org/en-US/MPL/2.0\"" +
                        " target=\"_blank\">Mozilla Public License v2.0</a>", ContentMode.HTML));
                content.addComponent(new Label("<b>Version:</b> " + (version != null ? version: "?"), ContentMode.HTML));
                content.addComponent(new Label("<b>Build Number:</b> " + (buildNumber != null ? buildNumber: "?"), ContentMode.HTML));

                // If the config has a friendly node name
                if (adminModule.getConfiguration().deploymentName != null && !adminModule.getConfiguration().deploymentName.isEmpty()) {

                    content.addComponent(new Label("<b>Deployment Name:</b> " + adminModule.getConfiguration().deploymentName, ContentMode.HTML));
                }
                popup.setContent(content);
                addWindow(popup);
            }
        });
        header.addComponent(about);
        header.setExpandRatio(about, 0);

        return header;
    }


    protected Component buildToolbar()
    {
        HorizontalLayout toolbar = new HorizontalLayout();
        toolbar.setWidth(100.0f, Unit.PERCENTAGE);
        toolbar.setSpacing(true);
        toolbar.setStyleName("toolbar");

        // shutdown button
        Button shutdownButton = new Button("Shutdown");
        shutdownButton.setDescription("Shutdown SensorHub");
        //shutdownButton.setIcon(DEL_ICON);
        shutdownButton.setIcon(FontAwesome.POWER_OFF);
        shutdownButton.addStyleName(STYLE_SMALL);
        shutdownButton.addStyleName(STYLE_BORDERLESS);
        shutdownButton.setWidth(100.0f, Unit.PERCENTAGE);
        shutdownButton.addClickListener(new Button.ClickListener() {
            @Override
            public void buttonClick(ClickEvent event)
            {
                // security check
                if (!securityHandler.hasPermission(securityHandler.osh_shutdown))
                {
                    DisplayUtils.showUnauthorizedAccess(securityHandler.osh_shutdown.getErrorMessage());
                    return;
                }

                final ConfirmDialog popup = new ConfirmDialog("Are you sure you want to shutdown the sensor hub?");
                popup.addCloseListener(new CloseListener() {
                    @Override
                    public void windowClose(CloseEvent e)
                    {
                        if (popup.isConfirmed())
                        {
                            logAction(securityHandler.osh_shutdown.getName());

                            disconnectFromModuleRegistry();

                            Notification notif = new Notification(
                                    FontAwesome.WARNING.getHtml() + "&nbsp; Shutdown Initiated...",
                                    "UI will stop responding",
                                    Notification.Type.ERROR_MESSAGE);
                            notif.setHtmlContentAllowed(true);
                            notif.show(getPage());

                            // disable push mode since it's sometimes throwing an exception
                            // during HTTP server stop process
                            getUI().getPushConfiguration().setPushMode(PushMode.DISABLED);

                            // shutdown in separate thread
                            new Timer().schedule(new TimerTask() {
                                @Override
                                public void run()
                                {
                                    hub.stop(false, true);
                                    System.exit(0);
                                }
                            }, 1000);
                        }
                    }
                });

                addWindow(popup);
            }
        });
        toolbar.addComponent(shutdownButton);

        // logout button
        Button logoutButton = new Button("Logout");
        logoutButton.setDescription("Logout from OSH node");
        logoutButton.setIcon(FontAwesome.SIGN_OUT);
        logoutButton.addStyleName(STYLE_SMALL);
        logoutButton.addStyleName(STYLE_BORDERLESS);
        logoutButton.setWidth(100.0f, Unit.PERCENTAGE);
        logoutButton.addClickListener(new Button.ClickListener() {
            @Override
            public void buttonClick(ClickEvent event)
            {
                final ConfirmDialog popup = new ConfirmDialog("Are you sure you want to logout?");
                popup.addCloseListener(new CloseListener() {
                    @Override
                    public void windowClose(CloseEvent e)
                    {
                        if (popup.isConfirmed())
                        {
                            logAction("logout");
                            disconnectFromModuleRegistry();

                            getUI().getSession().close();
                            var currentUrl = getUI().getPage().getLocation();
                            getUI().getPage().setLocation(currentUrl.resolve("logout"));
                        }
                    }
                });

                addWindow(popup);
            }
        });
        toolbar.addComponent(logoutButton);

        // apply changes button
        Button saveButton = new Button("Save");
        saveButton.setDescription("Save SensorHub Configuration");
        saveButton.setIcon(APPLY_ICON);
        saveButton.addStyleName(STYLE_SMALL);
        saveButton.addStyleName(STYLE_BORDERLESS);
        saveButton.setWidth(100.0f, Unit.PERCENTAGE);
        saveButton.addClickListener(new Button.ClickListener() {
            @Override
            public void buttonClick(ClickEvent event)
            {
                // security check
                if (!securityHandler.hasPermission(securityHandler.osh_saveconfig))
                {
                    DisplayUtils.showUnauthorizedAccess(securityHandler.osh_saveconfig.getErrorMessage());
                    return;
                }

                final ConfirmDialog popup = new ConfirmDialog("Are you sure you want to save the configuration (and override the previous one)?");
                popup.addCloseListener(new CloseListener() {
                    @Override
                    public void windowClose(CloseEvent e)
                    {
                        if (popup.isConfirmed())
                        {
                            logAction(securityHandler.osh_saveconfig.getName());

                            try
                            {
                                moduleRegistry.saveModulesConfiguration();
                                DisplayUtils.showOperationSuccessful("SensorHub Configuration Saved");
                            }
                            catch (Exception ex)
                            {
                                String msg = "Cannot save configuration";
                                DisplayUtils.showErrorPopup(msg, ex);
                            }
                        }
                    }
                });

                addWindow(popup);
            }
        });
        toolbar.addComponent(saveButton);

        return toolbar;
    }


    protected void buildNetworkModuleList(VerticalLayout layout)
    {
        ArrayList<IModule<?>> moduleList = new ArrayList<>();

        // add network modules to list
        moduleList.add(moduleRegistry.getModuleByType(IHttpServer.class));
        for (IModule<?> module: moduleRegistry.getLoadedModules())
        {
            ModuleConfig config = module.getConfiguration();
            if (config != null && NetworkConfig.class.isAssignableFrom(config.getClass()))
                moduleList.add(module);
        }

        buildModuleList(layout, moduleList, NetworkConfig.class);
    }


    protected void buildModuleList(VerticalLayout layout, final Class<?> configType)
    {
        ArrayList<IModule<?>> moduleList = new ArrayList<>();

        // add federated database
        if (configType == DatabaseConfig.class)
            moduleList.add(new FederatedDbModuleAdapter(getParentHub()));

        // add selected modules to list
        for (IModule<?> module: moduleRegistry.getLoadedModules())
        {
            ModuleConfig config = module.getConfiguration();
            if (config != null && configType.isAssignableFrom(config.getClass()))
                moduleList.add(module);
        }

        buildModuleList(layout, moduleList, configType);
    }

    protected List<Object> getVisiblySelectedItemIds(TreeTable table)
    {
        List<Object> visiblySelectedItemIds = new ArrayList<>();
        for(var visibleItemId : table.getVisibleItemIds())
        {
            if(table.isSelected(visibleItemId))
                visiblySelectedItemIds.add(visibleItemId);
        }
        return visiblySelectedItemIds;
    }

    protected void buildModuleList(VerticalLayout layout, List<IModule<?>> moduleList, final Class<?> configType)
    {
        // create table to display module list
        final TreeTable table = new TreeTable();
        table.setSizeFull();
        table.setSelectable(true);
        table.setNullSelectionAllowed(false);
        table.setImmediate(true);
        table.setColumnReorderingAllowed(false);
        table.addContainerProperty(PROP_NAME, String.class, PROP_NAME);
        table.addContainerProperty(PROP_STATE, ModuleState.class, ModuleState.LOADED);
        table.addContainerProperty(PROP_MODULE_OBJECT, IModule.class, null);
        table.setColumnWidth(PROP_STATE, 100);
        table.setColumnHeaderMode(ColumnHeaderMode.HIDDEN);
        layout.addComponent(table);

        // Multiselect functionality
        table.setMultiSelect(true);
        table.setMultiSelectMode(MultiSelectMode.DEFAULT);

        moduleTables.put(configType, table);

        // add modules info as table items
        for (IModule<?> module: moduleList)
            addModuleToTable(module, table);

        // hide module object column!
        table.setVisibleColumns(PROP_NAME, PROP_STATE);

        // value converter for state field -> display as text and icon
        table.setConverter(PROP_STATE, new Converter<String, ModuleState>() {
            @Override
            public ModuleState convertToModel(String value, Class<? extends ModuleState> targetType, Locale locale)
            {
                return ModuleState.valueOf(value);
            }

            @Override
            public String convertToPresentation(ModuleState value, Class<? extends String> targetType, Locale locale)
            {
                return value.toString();
            }

            @Override
            public Class<ModuleState> getModelType()
            {
                return ModuleState.class;
            }

            @Override
            public Class<String> getPresentationType()
            {
                return String.class;
            }
        });

        table.setCellStyleGenerator(new CellStyleGenerator() {
            @Override
            public String getStyle(Table source, Object itemId, Object propertyId)
            {
                if (propertyId != null && propertyId.equals(PROP_STATE))
                {
                    ModuleState state = (ModuleState)table.getItem(itemId).getItemProperty(propertyId).getValue();
                    IModule<?> module = (IModule<?>)table.getItem(itemId).getItemProperty(PROP_MODULE_OBJECT).getValue();
                    Throwable error = module.getCurrentError();

                    if (error == null)
                    {
                        if (state == ModuleState.STARTED)
                            return "green";
                        else
                            return "red";
                    }
                    else
                    {
                        return "error";
                    }
                }

                return null;
            }
        });

        table.setItemDescriptionGenerator(new ItemDescriptionGenerator() {
            @Override
            public String generateDescription(Component source, Object itemId, Object propertyId) {
                if (propertyId != null && propertyId.equals(PROP_STATE))
                {
                    IModule<?> module = (IModule<?>)table.getItem(itemId).getItemProperty(PROP_MODULE_OBJECT).getValue();
                    Throwable error = module.getCurrentError();
                    if (error != null)
                    {
                        StringBuilder buf = new StringBuilder();
                        buf.append(error.getMessage());
                        if (error.getCause() != null)
                        {
                            buf.append("<br/>");
                            buf.append(error.getCause().getMessage());
                        }
                        return buf.toString();
                    }
                    else
                        return module.getStatusMessage();
                }

                return null;
            }
        });

        // item click listener to display selected module settings
        table.addItemClickListener(new ItemClickListener()
        {
            @Override
            public void itemClick(ItemClickEvent event)
            {
                try
                {
                    // select and open module configuration
                    IModule<?> module = (IModule<?>)event.getItem().getItemProperty(PROP_MODULE_OBJECT).getValue();
                    selectModule(module, table);
                }
                catch (Exception e)
                {
                    DisplayUtils.showErrorPopup("Unexpected error when selecting module", e);
                }
            }
        });

        // context menu
        table.addActionHandler(new Handler() {
            @Override
            public Action[] getActions(Object target, Object sender)
            {
                List<Action> actions = new ArrayList<>(10);

                if (target != null)
                {
                    var item = table.getItem(target);
                    var module = (IModule<?>)item.getItemProperty(PROP_MODULE_OBJECT).getValue();
                    var state = (ModuleState)item.getItemProperty(PROP_STATE).getValue();
                    if (state == ModuleState.STARTED)
                        actions.add(RESTART_MODULE_ACTION);
                    else
                        actions.add(START_MODULE_ACTION);

                    actions.add(STOP_MODULE_ACTION);
                    actions.add(REINIT_MODULE_ACTION);

                    actions.add(new Action("-------------------------------"));

                    if (module instanceof SensorSystem)
                        actions.add(ADD_SUBMODULE_ACTION);

                    if (table.getParent(target) != null)
                        actions.add(REMOVE_SUBMODULE_ACTION);
                    else
                        actions.add(REMOVE_MODULE_ACTION);

                    actions.add(ADD_MODULE_ACTION);
                }
                else
                    actions.add(ADD_MODULE_ACTION);

                if(!table.getVisibleItemIds().isEmpty())
                {
                    actions.add(SELECT_ALL_MODULES_ACTION);
                    actions.add(DESELECT_ALL_MODULES_ACTION);
                }

                return actions.toArray(new Action[0]);
            }

            @Override
            public void handleAction(final Action action, Object sender, Object target)
            {
                // retrieve multi-selected modules
                final List<IModule<?>> selectedModules = new ArrayList<>();

                final List<Object> selectedItemIds = getVisiblySelectedItemIds(table);

                // When using multi-select, retrieve all selected modules
                if(target != null && !getVisiblySelectedItemIds(table).isEmpty())
                {
                    for(var itemId : selectedItemIds)
                    {
                        var item = table.getItem(itemId);
                        selectedModules.add((IModule<?>)item.getItemProperty(PROP_MODULE_OBJECT).getValue());
                    }
                }

                if (action == ADD_MODULE_ACTION)
                {
                    // security check
                    if (!securityHandler.hasPermission(securityHandler.module_add))
                    {
                        DisplayUtils.showUnauthorizedAccess(securityHandler.module_add.getErrorMessage());
                        return;
                    }

                    // show popup to select among available module types
                    ModuleTypeSelectionPopup popup = new ModuleTypeSelectionPopup(configType, new ModuleTypeSelectionCallback() {
                        @Override
                        public void onSelected(ModuleConfigBase config)
                        {
                            try
                            {
                                // log action
                                logAction(action, config.moduleClass);

                                // load module instance
                                IModule<?> module = moduleRegistry.loadModule((ModuleConfig)config);

                                // no need to add module to table here
                                // it will be loaded when the LOADED event is received

                                moduleAddedFromUI = module;

                                // unselect other items after adding a new one, so that only the new item is selected
                                for(var itemId : selectedItemIds)
                                    table.unselect(itemId);
                            }
                            catch (NoClassDefFoundError e)
                            {
                                DisplayUtils.showDependencyError(config.getClass(), e);
                            }
                            catch (Exception e)
                            {
                                DisplayUtils.showErrorPopup("Cannot load module", e);
                            }
                        }
                    });
                    popup.setModal(true);
                    addWindow(popup);
                }

                else if (action == SELECT_ALL_MODULES_ACTION)
                {
                    for(var itemId : table.getVisibleItemIds())
                        table.select(itemId);
                }

                else if (action == DESELECT_ALL_MODULES_ACTION)
                {
                    for(var itemId : table.getVisibleItemIds())
                        table.unselect(itemId);
                    selectNone(table);
                }

                // Module actions supporting multiselect
                else if (!selectedModules.isEmpty())
                {
                    // possible actions when a module or modules are selected
                    if (action == REMOVE_MODULE_ACTION)
                    {
                        // security check
                        if (!securityHandler.hasPermission(securityHandler.module_remove))
                        {
                            DisplayUtils.showUnauthorizedAccess(securityHandler.module_remove.getErrorMessage());
                            return;
                        }

                        final ConfirmDialog popup = new ConfirmDialog("Are you sure you want to remove " + selectedModules.size() + " module(s)?</br>All settings will be lost.");
                        popup.addCloseListener(new CloseListener() {
                            @Override
                            public void windowClose(CloseEvent e)
                            {
                                if (popup.isConfirmed())
                                {
                                    // log module actions
                                    for(var module : selectedModules)
                                    {
                                        logAction(action, module);
                                    }

                                    for(var module : selectedModules)
                                    {
                                        try
                                        {
                                            // Parent handles submodule destruction, so error would be thrown if we try to destroy it ourselves. REMOVE_SUBMODULE_ACTION is for submodules
                                            if(module instanceof ISystemDriver && ((ISystemDriver) module).getParentSystem() != null)
                                            {
                                                // Only remove submodule from UI if parent is also being removed
                                                if(selectedModules.contains((IModule<?>) ((ISystemDriver) module).getParentSystem()))
                                                {
                                                    // Only remove from UI
                                                    table.removeItem(module.getLocalID());
                                                }
                                                continue;
                                            }

                                            if(table.hasChildren(module.getLocalID()))
                                            {
                                                var childrenIds = new ArrayList<>(table.getChildren(module.getLocalID()));
                                                // Remove children of parent, so children are not moved to top level when parent is removed
                                                for(var childId : childrenIds)
                                                    table.removeItem(childId);
                                            }

                                            // TODO dont allow REMOVE_MODULE to remove submodules, let parents remove submodules
                                            if(table.getParent(module.getLocalID()) == null)
                                            {
                                                moduleRegistry.destroyModule(module.getLocalID());
                                                table.removeItem(module.getLocalID());
                                            }
                                            selectNone(table);
                                        }
                                        catch (SensorHubException ex)
                                        {
                                            DisplayUtils.showErrorPopup(module.getName() + " could not be removed", ex);
                                        }
                                    }
                                }
                            }
                        });

                        addWindow(popup);
                    }
                    else if (action == ADD_SUBMODULE_ACTION && selectedModules.size() == 1)
                    {
                        var selectedModule = selectedModules.get(0);
                        var moduleId = selectedModule.getLocalID();

                        // security check
                        if (!securityHandler.hasPermission(securityHandler.module_add))
                        {
                            DisplayUtils.showUnauthorizedAccess(securityHandler.module_add.getErrorMessage());
                            return;
                        }

                        // show popup to select among available module types
                        ModuleTypeSelectionPopup popup = new ModuleTypeSelectionPopup(ISystemDriver.class, new ModuleTypeSelectionCallback() {
                            @Override
                            public void onSelected(ModuleConfigBase config)
                            {
                                try
                                {
                                    // log action
                                    logAction(action, config.moduleClass);

                                    var newMember = new SystemMember();
                                    newMember.config = (ModuleConfig)config;

                                    var newModule = ((SensorSystem)selectedModule).addSubsystem(newMember);

                                    var memberId = newModule.getLocalID();
                                    //table.addItem(new Object[] {newModule.getName(), newModule.getCurrentState(), newModule}, memberID);
                                    Item newItem = table.addItem(memberId);
                                    newItem.getItemProperty(PROP_NAME).setValue(newModule.getName());
                                    newItem.getItemProperty(PROP_STATE).setValue(newModule.getCurrentState());
                                    newItem.getItemProperty(PROP_MODULE_OBJECT).setValue(newModule);
                                    table.setParent(memberId, moduleId);
                                    table.setChildrenAllowed(memberId, false);

                                    selectModule(newModule, table);
                                    moduleAddedFromUI = newModule;
                                    // Need to unselect parent because of multiselect
                                    table.unselect(moduleId);
                                }
                                catch (Exception e)
                                {
                                    DisplayUtils.showErrorPopup("Cannot add submodule ", e);
                                }
                            }
                        });
                        popup.setModal(true);
                        addWindow(popup);
                    }
                    else if (action == REMOVE_SUBMODULE_ACTION)
                    {
                        // security check
                        if (!securityHandler.hasPermission(securityHandler.module_remove))
                        {
                            DisplayUtils.showUnauthorizedAccess(securityHandler.module_remove.getErrorMessage());
                            return;
                        }

                        final ConfirmDialog popup = new ConfirmDialog("Are you sure you want to remove " + selectedModules.size() + " module(s)?</br>All settings will be lost.");
                        popup.addCloseListener(new CloseListener() {
                            @Override
                            public void windowClose(CloseEvent e)
                            {
                                if (popup.isConfirmed())
                                {
                                    // log module actions
                                    for(var module : selectedModules)
                                    {
                                        logAction(action, module);
                                    }

                                    for(var module : selectedModules)
                                    {
                                        try
                                        {
                                            // get parent module
                                            var parentId = table.getParent(module.getLocalID());
                                            var parentModule = (SensorSystem)table.getItem(parentId).getItemProperty(PROP_MODULE_OBJECT).getValue();
                                            parentModule.removeSubSystem(module.getLocalID());

                                            table.removeItem(module.getLocalID());
                                            selectNone(table);
                                        }
                                        catch (SensorHubException ex)
                                        {
                                            DisplayUtils.showErrorPopup("Submodule " + module.getName() + " could not be removed", ex);
                                        }
                                    }
                                }
                            }
                        });

                        addWindow(popup);
                    }
                    else if (action == START_MODULE_ACTION)
                    {
                        // security check
                        if (!securityHandler.hasPermission(securityHandler.module_start))
                        {
                            DisplayUtils.showUnauthorizedAccess(securityHandler.module_start.getErrorMessage());
                            return;
                        }

                        final ConfirmDialog popup = new ConfirmDialog("Are you sure you want to start " + selectedModules.size() + " module(s)?");
                        popup.addCloseListener(new CloseListener() {
                            @Override
                            public void windowClose(CloseEvent e)
                            {
                                if (popup.isConfirmed())
                                {
                                    // log module actions
                                    for(var module : selectedModules)
                                    {
                                        logAction(action, module);
                                    }

                                    for(var module : selectedModules)
                                    {
                                        try
                                        {
                                            // If submodule is selected, wait for parent to start before starting
                                            if (module instanceof ISystemDriver
                                                    && selectedModules.contains((IModule<?>) ((ISystemDriver)module).getParentSystem())
                                                    && !((IModule<?>) ((ISystemDriver)module).getParentSystem()).getCurrentState().equals(ModuleState.STARTED))
                                            {
                                                ((IModule<?>) ((ISystemDriver)module).getParentSystem()).waitForState(ModuleState.STARTED, 3000);
                                                if(!module.isStarted())
                                                    moduleRegistry.startModuleAsync(module);
                                            }

                                            if (module != null)
                                                moduleRegistry.startModuleAsync(module);
                                        }
                                        catch (SensorHubException ex)
                                        {
                                            DisplayUtils.showErrorPopup(module.getName() + " could not be started", ex);
                                        }
                                    }
                                }
                            }
                        });

                        addWindow(popup);
                    }
                    else if (action == STOP_MODULE_ACTION)
                    {
                        // security check
                        if (!securityHandler.hasPermission(securityHandler.module_stop))
                        {
                            DisplayUtils.showUnauthorizedAccess(securityHandler.module_stop.getErrorMessage());
                            return;
                        }

                        final ConfirmDialog popup = new ConfirmDialog("Are you sure you want to stop " + selectedModules.size() + " module(s)?");
                        popup.addCloseListener(new CloseListener() {
                            @Override
                            public void windowClose(CloseEvent e)
                            {
                                if (popup.isConfirmed())
                                {
                                    // log module actions
                                    for(var module : selectedModules)
                                    {
                                        logAction(action, module);
                                    }

                                    for(var module : selectedModules)
                                    {
                                        try
                                        {
                                            if (module != null)
                                                moduleRegistry.stopModuleAsync(module);
                                        }
                                        catch (SensorHubException ex)
                                        {
                                            DisplayUtils.showErrorPopup(module.getName() + " could not be stopped", ex);
                                        }
                                    }
                                }
                            }
                        });

                        addWindow(popup);
                    }
                    else if (action == RESTART_MODULE_ACTION)
                    {
                        // security check
                        if (!securityHandler.hasPermission(securityHandler.module_restart))
                        {
                            DisplayUtils.showUnauthorizedAccess(securityHandler.module_restart.getErrorMessage());
                            return;
                        }

                        final ConfirmDialog popup = new ConfirmDialog("Are you sure you want to restart " + selectedModules.size() + " module(s)?");
                        popup.addCloseListener(new CloseListener() {
                            @Override
                            public void windowClose(CloseEvent e)
                            {
                                if (popup.isConfirmed())
                                {
                                    // log module actions
                                    for(var module : selectedModules)
                                    {
                                        logAction(action, module);
                                    }

                                    for(var module : selectedModules)
                                    {
                                        try
                                        {
                                            if (module != null)
                                                moduleRegistry.restartModuleAsync(module);
                                        }
                                        catch (SensorHubException ex)
                                        {
                                            DisplayUtils.showErrorPopup(module.getName() + " could not be restarted", ex);
                                        }
                                    }
                                }
                            }
                        });

                        addWindow(popup);
                    }
                    else if (action == REINIT_MODULE_ACTION)
                    {
                        // security check
                        if (!securityHandler.hasPermission(securityHandler.module_init))
                        {
                            DisplayUtils.showUnauthorizedAccess(securityHandler.module_init.getErrorMessage());
                            return;
                        }

                        final ConfirmDialog popup = new ConfirmDialog("Are you sure you want to force re-init " + selectedModules.size() + " module(s)?");
                        popup.addCloseListener(new CloseListener() {
                            @Override
                            public void windowClose(CloseEvent e)
                            {
                                if (popup.isConfirmed())
                                {
                                    // log module actions
                                    for(var module : selectedModules)
                                    {
                                        logAction(action, module);
                                    }

                                    for(var module : selectedModules)
                                    {
                                        try
                                        {
                                            if (module != null)
                                                moduleRegistry.initModuleAsync(module);
                                        }
                                        catch (SensorHubException ex)
                                        {
                                            DisplayUtils.showErrorPopup(module.getName() + " could not be reinitialized", ex);
                                        }
                                    }
                                }
                            }
                        });

                        addWindow(popup);
                    }
                }
            }
        });

        layout.setSizeFull();
        layout.setMargin(false);
    }


    protected void addModuleToTable(IModule<?> module, TreeTable table)
    {
        String moduleID = module.getLocalID();

        Item newItem = table.addItem(moduleID);
        if (newItem == null) // in case module was already added
            return;

        newItem.getItemProperty(PROP_NAME).setValue(module.getName());
        newItem.getItemProperty(PROP_STATE).setValue(module.getCurrentState());
        newItem.getItemProperty(PROP_MODULE_OBJECT).setValue(module);

        // add submodules
        if (module instanceof ISystemGroupDriver)
        {
            var subModules = ((ISystemGroupDriver<?>) module).getMembers();
            for (var subModule: subModules.values())
            {
                if (subModule instanceof IModule)
                {
                    IModule<?> member = (IModule<?>)subModule;
                    var memberID = member.getLocalID();
                    table.addItem(new Object[] {member.getName(), member.getCurrentState(), member}, memberID);
                    table.setParent(memberID, moduleID);
                    table.setChildrenAllowed(memberID, false);
                }
            }
        }
        else
        {
            table.setChildrenAllowed(moduleID, false);
        }

        // select if module was just added from UI
        if (moduleAddedFromUI == module)
        {
            selectModule(module, table);
            moduleAddedFromUI = null;
        }

        // also select if first item added
        else if (table.size() == 1)
            table.select(moduleID);
    }


    protected void selectModule(IModule<?> module, TreeTable table)
    {
        table.select(module.getLocalID());
        ModuleConfig config = module.getConfiguration().clone();
        MyBeanItem<ModuleConfig> beanItem = new MyBeanItem<>(config);
        openModuleInfo(beanItem, module);
    }


    protected void selectNone(TreeTable table)
    {
        Object itemId = table.getValue();
        if (itemId != null)
            table.unselect(itemId);
        configArea.removeAllComponents();
    }


    protected void openModuleInfo(MyBeanItem<ModuleConfig> beanItem, IModule<?> module)
    {
        // do nothing if config area hasn't been created yet
        if (configArea == null)
            return;

        configArea.removeAllComponents();

        // get panel for this config object
        IModuleAdminPanel<IModule<?>> panel = adminModule.generatePanel(module);
        Label moduleVersion = new Label("<b>Version: </b>" + getModuleVersion(module), ContentMode.HTML);
        panel.addComponent(moduleVersion);
        panel.build(beanItem, module);

        // generate module admin panel
        configArea.addComponent(panel);
        visibleModule = module;
    }


    public void handleEvent(final org.sensorhub.api.event.Event e)
    {
        if (e instanceof ModuleEvent)
        {
            final IModule<?> module = (IModule<?>)e.getSource();
            final ModuleConfig config = module.getConfiguration();

            // find table and item corresponding to module
            TreeTable table = null;
            Item item = null;
            for (var t: moduleTables.values())
            {
                item = t.getItem(module.getLocalID());
                if (item != null)
                {
                    table = t;
                    break;
                }
            }

            // if no table was found, perhaps module was just loaded
            // so try to find table to add it to
            if (table == null)
            {
                for (Class<?> configClass: moduleTables.keySet())
                {
                    if (configClass.isAssignableFrom(config.getClass()))
                    {
                        table = moduleTables.get(configClass);
                        break;
                    }
                }
            }

            // update table according to event type
            final TreeTable foundTable = table;
            final Item foundItem = item;
            switch (((ModuleEvent)e).getType())
            {
                case LOADED:
                    if (foundTable != null)
                    {
                        access(new Runnable() {
                            @Override
                            public void run()
                            {
                                // add module to table
                                if (foundTable != null)
                                {
                                    addModuleToTable(module, foundTable);
                                    push();
                                }
                            }
                        });
                    }
                    break;

                case DELETED:
                    if (foundTable != null)
                    {
                        access(new Runnable() {
                            @Override
                            public void run()
                            {
                                // add module to table
                                if (foundTable != null)
                                {
                                    var wasSelected = foundTable.isSelected(module.getLocalID());
                                    foundTable.removeItem(module.getLocalID());
                                    if (wasSelected)
                                        selectNone(foundTable);

                                    // cleanup error state
                                    setModuleErrorState(foundTable, module, false);

                                    push();
                                }
                            }
                        });
                    }
                    break;

                case CONFIG_CHANGED:
                    if (foundItem != null)
                    {
                        access(new Runnable() {
                            @Override
                            public void run()
                            {
                                // Add submodule if added NOT from UI
                                if (config instanceof SensorSystemConfig && module instanceof SensorSystem)
                                {
                                    for (var subsystem : ((SensorSystemConfig) config).subsystems)
                                    {
                                        // Only add non-existent children
                                        if(foundTable.getChildren(module.getLocalID()) == null
                                                || !foundTable.getChildren(module.getLocalID()).contains(subsystem.config.id))
                                        {
                                            // Get submodule from parent and add to module table
                                            IModule<?> member = ((SensorSystem) module).getMembers().get(subsystem.config.id);
                                            var memberId = member.getLocalID();
                                            Item newItem = foundTable.addItem(memberId);
                                            newItem.getItemProperty(PROP_NAME).setValue(member.getName());
                                            newItem.getItemProperty(PROP_STATE).setValue(member.getCurrentState());
                                            newItem.getItemProperty(PROP_MODULE_OBJECT).setValue(member);
                                            foundTable.setParent(memberId, module.getLocalID());
                                            foundTable.setChildrenAllowed(memberId, false);
                                        }
                                    }
                                }
                                // update module name
                                foundItem.getItemProperty(PROP_NAME).setValue(config.name);
                                push();
                            }
                        });
                    }
                    break;

                case STATE_CHANGED:
                case ERROR:
                    if (foundItem != null)
                    {
                        access(new Runnable() {
                            @Override
                            public void run()
                            {
                                // update module state
                                ModuleState state = ((IModule<?>)e.getSource()).getCurrentState();
                                if (foundItem != null)
                                    foundItem.getItemProperty(PROP_STATE).setValue(state);

                                // set/clear error flags on accordion headers
                                if (foundTable != null)
                                    setModuleErrorState(foundTable, module, module.getCurrentError() != null);

                                // update config panel if currently visible
                                if (module == visibleModule)
                                    selectModule(module, foundTable);

                                push();
                            }
                        });
                    }
                    break;

                default:
            }
        }
    }


    protected void setModuleErrorState(TreeTable moduleTable, IModule<?> module, boolean hasError)
    {
        var tab = moduleStack.getTab(moduleTable.getParent());
        if (tab != null)
        {
            var errors = (ModuleErrors)tab.getComponentError();

            if (hasError)
            {
                if (errors == null)
                    errors = new ModuleErrors();

                errors.setModuleErrorState(module, true);
                tab.setComponentError(errors);
            }
            else
            {
                if (errors != null)
                {
                    if (!errors.setModuleErrorState(module, false))
                        tab.setComponentError(null);
                }
            }
        }
    }


    protected void logInitRequest(VaadinRequest req)
    {
        if (log.isInfoEnabled())
        {
            String ip = req.getRemoteAddr();
            String user = req.getRemoteUser() != null ? req.getRemoteUser() : OWSUtils.ANONYMOUS_USER;
            log.info(LOG_INIT_MSG, ip, user);
        }
    }


    protected void logAction(String action)
    {
        if (log.isInfoEnabled())
        {
            VaadinRequest req = VaadinService.getCurrentRequest();
            String ip = req.getRemoteAddr();
            String user = (req.getRemoteUser() != null) ? req.getRemoteUser() : OWSUtils.ANONYMOUS_USER;
            log.info(LOG_ACTION_MSG, action, ip, user);
        }
    }


    protected void logAction(String action, String item)
    {
        if (log.isInfoEnabled())
            logAction(action + " " + item);
    }


    protected void logAction(String action, IModule<?> module)
    {
        if (log.isInfoEnabled())
            logAction(action, MsgUtils.moduleString(module));
    }


    protected void logAction(Action action, String item)
    {
        logAction(action.getCaption(), item);
    }


    protected void logAction(Action action, IModule<?> module)
    {
        logAction(action.getCaption(), module);
    }


    protected void disconnectFromModuleRegistry()
    {
        // unregister from module registry events
        if (moduleEventsSub != null)
            moduleEventsSub.cancel();
    }


    @Override
    public void detach()
    {
        disconnectFromModuleRegistry();
        super.detach();
    }


    public ISensorHub getParentHub()
    {
        return hub;
    }


    public AdminUIModule getParentModule()
    {
        return adminModule;
    }


    public AdminUISecurity getSecurityHandler()
    {
        return securityHandler;
    }


    public Logger getOshLogger()
    {
        return adminModule.getLogger();
    }

    protected String getModuleVersion(IModule<?> module){
        return ModuleUtils.getModuleInfo(module.getClass()).getModuleVersion();
    }
}
