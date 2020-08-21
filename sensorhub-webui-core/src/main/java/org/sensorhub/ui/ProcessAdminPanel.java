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

import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.api.processing.IStreamProcessModule;
import org.sensorhub.ui.data.MyBeanItem;
import org.vast.data.DataRecordImpl;
import com.vaadin.ui.Alignment;
import com.vaadin.ui.Component;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.Layout;
import com.vaadin.ui.Panel;
import net.opengis.swe.v20.DataComponent;


/**
 * <p>
 * Admin panel for stream processing modules.<br/>
 * This adds a section to view structure and values of process outputs.
 * </p>
 *
 * @author Alex Robin
 * @since 1.0
 */
@SuppressWarnings("serial")
public class ProcessAdminPanel extends DataSourceAdminPanel<IStreamProcessModule<?>>
{
    
    @Override
    public void build(final MyBeanItem<ModuleConfig> beanItem, final IStreamProcessModule<?> module)
    {
        super.build(beanItem, module);       
        
        // process info panel
        if (module.isInitialized())
        {
            /*// inputs section
            if (!module.getParameters().isEmpty())
            {
                // title
                addComponent(new Spacing());
                HorizontalLayout titleBar = new HorizontalLayout();
                titleBar.setSpacing(true);
                Label sectionLabel = new Label("Inputs");
                sectionLabel.addStyleName(STYLE_H3);
                sectionLabel.addStyleName(STYLE_COLORED);
                titleBar.addComponent(sectionLabel);
                titleBar.setComponentAlignment(sectionLabel, Alignment.MIDDLE_LEFT);
                titleBar.setHeight(31.0f, Unit.PIXELS);
                addComponent(titleBar);
                
                // control panels
                buildParamInputsPanels(module);
            }*/
            
            // control params section
            if (!module.getParameters().isEmpty())
            {
                // title
                addComponent(new Spacing());
                HorizontalLayout titleBar = new HorizontalLayout();
                titleBar.setSpacing(true);
                Label sectionLabel = new Label("Processing Parameters");
                sectionLabel.addStyleName(STYLE_H3);
                sectionLabel.addStyleName(STYLE_COLORED);
                titleBar.addComponent(sectionLabel);
                titleBar.setComponentAlignment(sectionLabel, Alignment.MIDDLE_LEFT);
                titleBar.setHeight(31.0f, Unit.PIXELS);
                addComponent(titleBar);
                
                // control panels
                buildParamInputsPanels(module);
            }
        }
    }
    
    
    protected void buildParamInputsPanels(IStreamProcessModule<?> module)
    {
        if (module != null)
        {
            Panel oldPanel;
            
            // command inputs
            oldPanel = commandsPanel;
            commandsPanel = newPanel(null);
            
            // wrap all parameters into a single datarecord so we can submit them together
            DataRecordImpl params = new DataRecordImpl();
            params.setName("Parameters");
            for (DataComponent param: module.getParameters().values())
                params.addComponent(param.getName(), param);
            params.combineDataBlocks();
            
            Component sweForm = new SWEControlForm(params);
            ((Layout)commandsPanel.getContent()).addComponent(sweForm);

            if (oldPanel != null)
                replaceComponent(oldPanel, commandsPanel);
            else
                addComponent(commandsPanel);
        }
    }
}
