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

import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.event.EventUtils;
import org.sensorhub.ui.ValueEntryPopup.ValueCallback;
import org.sensorhub.ui.api.UIConstants;
import com.vaadin.v7.data.Property.ValueChangeEvent;
import com.vaadin.v7.data.Property.ValueChangeListener;
import com.vaadin.v7.event.ItemClickEvent;
import com.vaadin.v7.event.ItemClickEvent.ItemClickListener;
import com.vaadin.v7.ui.TextField;
import com.vaadin.ui.Alignment;
import com.vaadin.ui.Button;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.ui.Window;
import com.vaadin.ui.Button.ClickEvent;


@SuppressWarnings("serial")
public class SystemSelectionPopup extends Window
{
    String sysUID;

    public SystemSelectionPopup(int width, final ValueCallback callback, IObsSystemDatabase db)
    {
        super("Select an Observing System");
        VerticalLayout layout = new VerticalLayout();
        layout.setMargin(true);
        
        // manual entry text box
        TextField manualEntry = new TextField("Manual Entry");
        manualEntry.setWidth(300, Unit.PIXELS);
        manualEntry.addStyleName(UIConstants.STYLE_SMALL);
        manualEntry.addValueChangeListener(new ValueChangeListener() {
            @Override
            public void valueChange(ValueChangeEvent event)
            {
                sysUID = (String)event.getProperty().getValue();
            }
        });
        layout.addComponent(manualEntry);
        
        // search box and table
        var searchTable = new SystemSearchList(db, new ItemClickListener() {
            @Override
            public void itemClick(ItemClickEvent event)
            {
                // select system UID
                sysUID = (String)event.getItem().getItemProperty(SystemSearchList.PROP_SYSTEM_UID).getValue();
                manualEntry.setValue(sysUID);
            }
        });        
        layout.addComponent(searchTable);
                
        // buttons bar
        HorizontalLayout buttons = new HorizontalLayout();
        buttons.setSpacing(true);
        layout.addComponent(buttons);
        layout.setComponentAlignment(buttons, Alignment.MIDDLE_CENTER);
        
        // add OK button
        Button okButton = new Button("OK");
        okButton.addStyleName(UIConstants.STYLE_SMALL);
        okButton.addClickListener(new Button.ClickListener() {
            private static final long serialVersionUID = 1L;

            @Override
            public void buttonClick(ClickEvent event)
            {
                callback.newValue(sysUID);
                close();
            }
        });
        buttons.addComponent(okButton);
        
        setContent(layout);
        center();
    }
}
