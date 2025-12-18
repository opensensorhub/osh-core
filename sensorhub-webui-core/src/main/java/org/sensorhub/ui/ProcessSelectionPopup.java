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

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.sensorhub.api.processing.IProcessProvider;
import org.sensorhub.api.processing.ProcessingException;
import org.sensorhub.impl.processing.StreamDataSource;
import org.sensorhub.ui.api.UIConstants;
import org.vast.process.ProcessInfo;
import com.vaadin.ui.Alignment;
import com.vaadin.ui.Button;
import com.vaadin.v7.ui.TreeTable;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.ui.Window;
import com.vaadin.ui.Button.ClickEvent;
import com.vaadin.ui.Button.ClickListener;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.TextField;


/**
 * <p>
 * Popup window showing a list of selectable OSH modules
 * </p>
 *
 * @author Alex Robin
 * @since Feb 24, 2017
 */
@SuppressWarnings("serial")
public class ProcessSelectionPopup extends Window implements UIConstants
{
    static final Pattern PROCESS_NAME_REGEX = Pattern.compile("[a-zA-Z0-9_]+");
    static final String PROP_CATEGORY = "cat";
    static final String PROP_NAME = "name";
    static final String PROP_VERSION = "version";
    static final String PROP_DESC = "desc";
    static final String PROP_AUTHOR = "author";
    
    
    protected interface ProcessSelectionCallback
    {
        public void onSelected(String name, ProcessInfo info) throws ProcessingException;
    }
    
    
    public ProcessSelectionPopup(Collection<IProcessProvider> providers, final ProcessSelectionCallback callback)
    {
        super("Select Process Type");
        setWidth(1000.0f, Unit.PIXELS);
        buildDialog(providers, callback);
    }

    
    protected void buildDialog(Collection<IProcessProvider> providers, final ProcessSelectionCallback callback)
    {
        VerticalLayout layout = new VerticalLayout();
        layout.setMargin(true);
        layout.setSpacing(true);
        
        // generate table with module list
        final TreeTable table = new TreeTable();
        table.setSizeFull();
        table.setSelectable(true);
        table.addContainerProperty(PROP_NAME, String.class, null);
        table.addContainerProperty(PROP_DESC, String.class, null);
        table.addContainerProperty(PROP_VERSION, String.class, null);
        table.addContainerProperty(PROP_AUTHOR, String.class, null);
        table.setColumnHeaders("Name", "Description", "Version", "Author");
        table.setColumnWidth(PROP_NAME, 250);
        table.setPageLength(10);
        table.setMultiSelect(false);
        
        final Map<Object, ProcessInfo> processMap = new HashMap<>();
        for (IProcessProvider provider: providers)
        {
            Object parentId = table.addItem(new Object[] {
                    provider.getModuleName(),
                    provider.getModuleDescription(),
                    provider.getModuleVersion(),
                    provider.getProviderName()}, null);
            
            for (ProcessInfo info: provider.getProcessMap().values())
            {
                // For simplicity, don't let users choose this one
                if (info == StreamDataSource.INFO)
                    continue;
                
                Object id = table.addItem(new Object[] {
                        info.getName(),
                        info.getDescription(),
                        null, null}, null);
                table.setParent(id, parentId);
                table.setChildrenAllowed(id, false);
                processMap.put(id, info);
            }
        }
        layout.addComponent(table);
        
//        // link to more modules
//        Button installNew = new Button("Install More Packages...");
//        installNew.setStyleName(STYLE_LINK);
//        layout.addComponent(installNew);
//        layout.setComponentAlignment(installNew, Alignment.MIDDLE_RIGHT);
        // TODO: Use check OSGi logic
//        installNew.addClickListener(new ClickListener()
//        {
//            @Override
//            public void buttonClick(ClickEvent event)
//            {
//                //close();
//                getUI().addWindow(new DownloadModulesPopup());
//            }
//        });
        
        // buttons bar
        HorizontalLayout buttons = new HorizontalLayout();
        buttons.setSpacing(true);
        layout.addComponent(buttons);
        layout.setComponentAlignment(buttons, Alignment.MIDDLE_LEFT);
        
        // name text box
        buttons.addComponent(new Label("Process Name:"));
        final TextField textBox = new TextField();
        buttons.addComponent(textBox);
        
        // OK button
        Button okButton = new Button("OK");
        okButton.addClickListener(new Button.ClickListener() {
            @Override
            public void buttonClick(ClickEvent event)
            {
                Object selectedItemId = table.getValue();
                String name = textBox.getValue();
                
                if (name == null || !PROCESS_NAME_REGEX.matcher(name).matches())
                {
                    DisplayUtils.showErrorPopup("Please enter a valid process name", null);
                    return;
                }
                
                if (selectedItemId != null)
                {
                    ProcessInfo info = processMap.get(selectedItemId);
                    
                    try
                    {
                        callback.onSelected(name, info);
                    }
                    catch (Exception e)
                    {
                        DisplayUtils.showErrorPopup("Cannot add process", e);
                        return;
                    }                        
                }
                
                close();
            }
        });
        buttons.addComponent(okButton);
        
        setContent(layout);
        center();
    }
}
