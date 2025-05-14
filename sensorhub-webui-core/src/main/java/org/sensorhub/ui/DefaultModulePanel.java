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

import org.sensorhub.api.ISensorHub;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.event.IEventListener;
import org.sensorhub.api.module.IModule;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.api.module.ModuleEvent;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.sensorhub.ui.api.IModuleAdminPanel;
import org.sensorhub.ui.api.IModuleConfigForm;
import org.sensorhub.ui.api.UIConstants;
import org.sensorhub.ui.data.MyBeanItem;
import com.vaadin.shared.ui.ContentMode;
import org.slf4j.Logger;
import com.vaadin.ui.Button;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.TabSheet;
import com.vaadin.ui.UI;
import com.vaadin.ui.VerticalLayout;


/**
 * <p>
 * Default implementation of module panel letting the user edit the module
 * configuration through a generic auto-generated form.
 * </p>
 *
 * @author Alex Robin
 * @param <ModuleType> Type of module supported by this panel builder
 * @since 0.5
 */
@SuppressWarnings("serial")
public class DefaultModulePanel<ModuleType extends IModule<? extends ModuleConfig>> extends VerticalLayout implements IModuleAdminPanel<ModuleType>, UIConstants, IEventListener
{
    transient ModuleType module;
    HorizontalLayout header;
    Label spinner;
    Button statusBtn;
    Button errorBtn;
    TabSheet configTabs;
    protected TabSheet pageTabs;
    VerticalLayout config;
    VerticalLayout readme;
    Readme readmeContent;
    
    
    @Override
    public void build(final MyBeanItem<ModuleConfig> beanItem, final ModuleType module)
    {
        this.module = module;

        config = new VerticalLayout();

        config.setSizeUndefined();
        config.setWidth(100.0f, Unit.PERCENTAGE);
        config.setMargin(false);
        config.setSpacing(true);
        
        // header = module name + spinner
        header = new HorizontalLayout();
        header.setSpacing(true);
        String moduleName = module.getName();
        Label title = new Label(moduleName);
        title.setDescription(module.getDescription());
        title.addStyleName(STYLE_H2);
        header.addComponent(title);
        addComponent(header);
        Label hr = new Label("<hr/>", ContentMode.HTML);
        hr.setWidth(100.0f, Unit.PERCENTAGE);
        addComponent(hr);
        
        // status message
        refreshState();
        refreshStatusMessage();
        refreshErrorMessage();
        
        if (!module.getLocalID().startsWith("$$"))
        {
            // apply changes button
            Button applyButton = new Button("Apply Changes");
            applyButton.setIcon(APPLY_ICON);
            applyButton.addStyleName(STYLE_SMALL);
            applyButton.addStyleName("apply-button");
            config.addComponent(applyButton);
            
            // config forms
            final IModuleConfigForm form = getConfigForm(beanItem);
            TabbedConfigForms tabbedConfigForm = new TabbedConfigForms(form);
            configTabs = tabbedConfigForm.configTabs;
            config.addComponent(tabbedConfigForm);
            
            // apply button action
            applyButton.addClickListener(event -> {
                AdminUI ui = (AdminUI)UI.getCurrent();
                ui.logAction("Update Config", module);
                
                // security check
                if (!ui.securityHandler.hasPermission(ui.securityHandler.module_update))
                {
                    DisplayUtils.showUnauthorizedAccess(ui.securityHandler.module_update.getErrorMessage());
                    return;
                }
                
                try
                {
                    form.commit();
                    if (module != null)
                    {
                        beforeUpdateConfig();
                        getParentHub().getModuleRegistry().updateModuleConfigAsync(module, beanItem.getBean());
                        DisplayUtils.showOperationSuccessful("Module Configuration Updated");
                    }
                }
                catch (Exception e)
                {
                    DisplayUtils.showErrorPopup(IModule.CANNOT_UPDATE_MSG, e);
                }
            });

        }
        readmeContent = new Readme(beanItem);
        readme = new VerticalLayout(readmeContent);
        // Add tabs for readme and config
        config.setMargin(true);
        config.setSpacing(true);
        readme.setMargin(true);
        readme.setSpacing(true);
        readme.setResponsive(true);
        pageTabs = new TabSheet();
        pageTabs.addTab(config, "Configuration");
        pageTabs.addTab(readme, "README");
        addComponent(pageTabs);
    }
    
    
    protected void beforeUpdateConfig() throws SensorHubException
    {
        // to be overridden by derived classes
    }
    
    
    protected void refreshState()
    {
        if (spinner == null)
        {
            ModuleState state = module.getCurrentState();
            if (state == ModuleState.INITIALIZING || state == ModuleState.STARTING)
            {
                spinner = new Label();
                spinner.addStyleName(STYLE_SPINNER);
                header.addComponent(spinner);
            }
        }
        else
        {
            header.removeComponent(spinner);
            spinner = null;
        }
    }
    
    
    protected void refreshStatusMessage()
    {
        String statusMsg = module.getStatusMessage();
        if (statusMsg != null)
        {
            Button oldBtn = statusBtn;
            
            statusBtn = new Button();
            statusBtn.setStyleName(STYLE_LINK);
            statusBtn.setIcon(INFO_ICON);
            statusBtn.setCaption(statusMsg);
            
            if (oldBtn == null)
                config.addComponent(statusBtn, 2);
            else
                config.replaceComponent(oldBtn, statusBtn);
        }
        else
        {
            if (statusBtn != null)
            {
                config.removeComponent(statusBtn);
                statusBtn = null;
            }
        }
    }
    
    
    protected void refreshErrorMessage()
    {
        final Throwable errorObj = module.getCurrentError();
        if (errorObj != null)
        {
            Button oldBtn = errorBtn;
            
            // show link with error msg
            errorBtn = new Button();
            errorBtn.setStyleName(STYLE_LINK);
            errorBtn.setIcon(ERROR_ICON);
            StringBuilder errorMsg = new StringBuilder(errorObj.getMessage().trim());
            if (errorMsg.charAt(errorMsg.length()-1) != '.')
                errorMsg.append(". ");
            if (errorObj.getCause() != null && errorObj.getCause().getMessage() != null)
            {
                if (errorObj.getCause() instanceof NoClassDefFoundError)
                    errorMsg.append("Class not found ");
                errorMsg.append(errorObj.getCause().getMessage());
            }
            errorBtn.setCaption(errorMsg.toString());
            
            // show error details on button click
            errorBtn.addClickListener(event -> {
                DisplayUtils.showErrorDetails(module, errorObj);
            });
            
            if (oldBtn == null)
                config.addComponent(errorBtn, (statusBtn == null) ? 2 : 3);
            else
                config.replaceComponent(oldBtn, errorBtn);
        }
        else
        {
            if (errorBtn != null)
            {
                config.removeComponent(errorBtn);
                errorBtn = null;
            }
        }
    }
    
    
    protected void refreshContent()
    {
        // do nothing by default
        // can be overriden by custom panels
    }
    
    
    @SuppressWarnings({ "rawtypes", "unchecked" })
    protected IModuleConfigForm getConfigForm(MyBeanItem<ModuleConfig> beanItem)
    {
        IModuleConfigForm form = getParentProducer().generateForm(beanItem.getBean().getClass());
        form.build(GenericConfigForm.MAIN_CONFIG, "General module configuration", (MyBeanItem)beanItem, false);
        return form;
    }
    
    
    @Override
    public void attach()
    {
        super.attach();
        module.registerListener(this);
    }
    
    
    @Override
    public void detach()
    {
        module.unregisterListener(this);
        super.detach();
    }


    @Override
    public void handleEvent(final org.sensorhub.api.event.Event e)
    {
        if (!isAttached())
            return;

        if (e instanceof ModuleEvent)
        {
            switch (((ModuleEvent)e).getType())
            {
                case STATUS:
                    getUI().access(() -> {
                        refreshStatusMessage();
                        if (isAttached())
                            getUI().push();
                    });
                    break;
                    
                case ERROR:
                    getUI().access(() -> {
                        refreshErrorMessage();
                        if (isAttached())
                            getUI().push();
                    });
                    break;
                    
                case STATE_CHANGED:
                    getUI().access(() -> {
                        refreshState();
                        refreshStatusMessage();
                        refreshErrorMessage();
                        refreshContent();
                        if (isAttached())
                            getUI().push();
                    });  
                    break;
                    
                case CONFIG_CHANGED:
                    getUI().access(() -> {
                        readme.removeAllComponents();
                        pageTabs.removeAllComponents();
                        readmeContent = null;
                        readme = null;
                        DefaultModulePanel.this.removeAllComponents();
                        DefaultModulePanel.this.build(new MyBeanItem<>(module.getConfiguration()), module);
                        if (isAttached())
                            getUI().push();
                    });  
                    break;
                    
                default:
                    return;
            }
        }
    }

    
    protected ISensorHub getParentHub()
    {
        return ((AdminUI)UI.getCurrent()).getParentHub();
    }
    
    
    protected AdminUIModule getParentProducer()
    {
        return ((AdminUI)UI.getCurrent()).getParentModule();
    }
    
    
    protected Logger getOshLogger()
    {
        return ((AdminUI)UI.getCurrent()).getOshLogger();
    }

}
