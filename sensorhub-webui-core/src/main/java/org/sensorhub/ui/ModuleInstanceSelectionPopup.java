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

import com.vaadin.ui.Alignment;
import com.vaadin.ui.Button;
import com.vaadin.ui.UI;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.ui.Window;
import com.vaadin.ui.Button.ClickEvent;
import com.vaadin.v7.ui.TreeTable;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.data.IDataProducerModule;
import org.sensorhub.api.module.IModule;
import org.sensorhub.impl.sensor.SensorSystem;
import org.sensorhub.ui.api.UIConstants;

import java.util.HashMap;
import java.util.Map;


@SuppressWarnings("serial")
public class ModuleInstanceSelectionPopup extends Window
{
        
    @SuppressWarnings("rawtypes")
    public interface ModuleInstanceSelectionCallback
    {
        public void onSelected(IModule module) throws SensorHubException;
    }
    
    
    public ModuleInstanceSelectionPopup(final Class<?> moduleType, final ModuleInstanceSelectionCallback callback)
    {
        super("Select Module");
        VerticalLayout layout = new VerticalLayout();
        layout.setSpacing(true);
        
        // generate table with module list
        final TreeTable table = new TreeTable();
        table.setSizeFull();
        table.setSelectable(true);
        table.setColumnReorderingAllowed(true);        
        table.addContainerProperty(UIConstants.PROP_NAME, String.class, null);
        table.addContainerProperty(UIConstants.PROP_ID, String.class, null);
        table.setColumnHeaders(new String[] {"Module Name", "ID"});
        table.setPageLength(10);
        table.setMultiSelect(false);
        
        final Map<Object, IModule<?>> moduleMap = new HashMap<>();
        for (IModule<?> module: ((AdminUI)UI.getCurrent()).getParentHub().getModuleRegistry().getLoadedModules())
        {
            Class<?> moduleClass = module.getClass();
            if (moduleType.isAssignableFrom(moduleClass))
            {
                Object id = table.addItem(new Object[] {
                        module.getName(),
                        module.getLocalID()}, null);
                moduleMap.put(id, module);
                if(module instanceof SensorSystem)
                {
                    var subModules = ((SensorSystem) module).getMembers();
                    for(IDataProducerModule<?> member : subModules.values())
                    {
                        if(moduleType.isAssignableFrom(member.getClass())) {
                            Object memberID = table.addItem(new Object[]{
                                    member.getName(),
                                    member.getLocalID()}, null);
                            moduleMap.put(memberID, member);
                            table.setParent(memberID, id);
                            table.setChildrenAllowed(memberID, false);
                        }
                    }
                }
                else
                {
                    table.setChildrenAllowed(id, false);
                }
            }
        }
        layout.addComponent(table);
        
        // add OK button
        Button okButton = new Button("OK");
        okButton.addClickListener(new Button.ClickListener() {
            private static final long serialVersionUID = 1L;

            @Override
            public void buttonClick(ClickEvent event)
            {
                Object selectedItemId = table.getValue();
                
                if (selectedItemId != null)
                {
                    IModule<?> module = moduleMap.get(selectedItemId);
                    
                    try
                    {
                    if (module != null)
                        callback.onSelected(module);
                }
                    catch (Exception e)
                    {
                        DisplayUtils.showErrorPopup("Cannot select module", e);
                        return;
                    }
                }
                
                close();
            }
        });
        layout.addComponent(okButton);
        layout.setComponentAlignment(okButton, Alignment.MIDDLE_CENTER);
        
        setContent(layout);
        center();
    }
}
