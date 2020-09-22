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

import java.util.HashMap;
import org.sensorhub.api.processing.IStreamProcessModule;
import org.sensorhub.api.sensor.ISensorControlInterface;
import org.sensorhub.api.sensor.SensorException;
import org.sensorhub.ui.api.UIConstants;
import org.vast.data.DataRecordImpl;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataChoice;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataRecord;
import net.opengis.swe.v20.SimpleComponent;
import com.vaadin.data.Property.ValueChangeEvent;
import com.vaadin.data.Property.ValueChangeListener;
import com.vaadin.shared.ui.label.ContentMode;
import com.vaadin.ui.Alignment;
import com.vaadin.ui.Button;
import com.vaadin.ui.Component;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.ListSelect;
import com.vaadin.ui.TextField;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.ui.AbstractSelect.ItemCaptionMode;
import com.vaadin.ui.Button.ClickEvent;
import com.vaadin.ui.Button.ClickListener;


@SuppressWarnings("serial")
public class SWEControlForm extends SWECommonForm
{
    transient ISensorControlInterface controlInput;
    transient IStreamProcessModule<?> process;
    transient DataComponent command;
    
    
    public SWEControlForm(final ISensorControlInterface controlInput)
    {
        this.addSpacing = true;
        this.controlInput = controlInput;
        this.command = controlInput.getCommandDescription().copy();
        this.command.assignNewDataBlock();
        buildForm();
    }
    
    
    public SWEControlForm(final IStreamProcessModule<?> process)
    {
        this.addSpacing = true;
        this.process = process;
        
        // wrap all parameters into a single datarecord so we can submit them together
        DataRecordImpl params = new DataRecordImpl();
        params.setName("Parameters");
        for (DataComponent param: process.getParameterDescriptors().values())
            params.addComponent(param.getName(), param);
        params.combineDataBlocks();
        
        this.command = params.copy();
        this.command.setData(params.getData().clone());
        
        buildForm();
    }
    
    
    protected void buildForm()
    {
        removeAllComponents();
        setSpacing(true);
        
        if (command instanceof DataRecord)
        {
            for (int i = 0; i < command.getComponentCount(); i++)
                addComponent(buildWidget(command.getComponent(i), false));
        }
        else
            addComponent(buildWidget(command, false));
        
        // send button
        Button sendBtn = new Button("Send Command");
        sendBtn.addStyleName(UIConstants.STYLE_SMALL);
        addComponent(sendBtn);
        setComponentAlignment(sendBtn, Alignment.MIDDLE_LEFT);
        sendBtn.addClickListener(new ClickListener()
        {   
            private static final long serialVersionUID = 1L;

            @Override
            public void buttonClick(ClickEvent event)
            {
                try
                {
                    if (controlInput != null)
                        controlInput.execCommand(command.getData());
                    else
                    {
                        // TODO include only parameters that have actually changed
                        HashMap<String, DataBlock> newParamData = new HashMap<>();
                        for (DataComponent param: ((DataRecord)command).getFieldList())
                            newParamData.put(param.getName(), param.getData());
                        process.updateParameters(newParamData);
                    }
                }
                catch (SensorException e)
                {
                    DisplayUtils.showErrorPopup("Error while sending command", e);
                }
            }
        });
    }
    
    
    @Override
    protected Component buildWidget(final DataComponent component, boolean showValues)
    {
        if (component instanceof DataChoice)
        {
            final DataChoice dataChoice = (DataChoice)component;
            if (dataChoice.getSelectedItem() == null)
                dataChoice.setSelectedItem(0);
            
            VerticalLayout layout = new VerticalLayout();
            layout.setSpacing(true);
                        
            // combo to select command type
            HorizontalLayout header = getCaptionLayout(component);
            ListSelect combo = new ListSelect();
            combo.addStyleName(UIConstants.STYLE_SMALL);
            combo.setItemCaptionMode(ItemCaptionMode.ID);
            combo.setNullSelectionAllowed(false);
            combo.setRows(1);
            for (int i = 0; i < component.getComponentCount(); i++)
            {
                DataComponent c = component.getComponent(i);
                combo.addItem(c.getName());
            }
            combo.select(dataChoice.getSelectedItem().getName());
            combo.addValueChangeListener(new ValueChangeListener() {
                private static final long serialVersionUID = 1L;
                @Override
                public void valueChange(ValueChangeEvent event)
                {
                    // select choice item and redraw
                    dataChoice.setSelectedItem((String)event.getProperty().getValue());
                    buildForm();
                }
        
            });            
            header.addComponent(combo);
            layout.addComponent(header);
            
            // display form for selected item
            Component w = buildWidget(dataChoice.getSelectedItem(), showValues);
            layout.addComponent(w);            
                
            return layout;
        }
        else if (component instanceof SimpleComponent)
        {
            HorizontalLayout layout = getCaptionLayout(component);
            final TextField f = new TextField();
            f.addStyleName(UIConstants.STYLE_SMALL);
            f.setValue(component.getData().getStringValue());
            layout.addComponent(f);
            f.addValueChangeListener(new ValueChangeListener() {
                private static final long serialVersionUID = 1L;
                @Override
                public void valueChange(ValueChangeEvent event)
                {
                    component.getData().setStringValue(f.getValue());
                }
            });
            return layout;            
        }
        else
        {
            Component widget = super.buildWidget(component, showValues);
            ((SpacingHandler)widget).setSpacing(true);
            return widget;
        }
    }
    
    
    protected HorizontalLayout getCaptionLayout(DataComponent component)
    {
        HorizontalLayout header = new HorizontalLayout();
        header.setSpacing(true);
        
        Label l = new Label();
        l.addStyleName(UIConstants.STYLE_SMALL);
        l.setContentMode(ContentMode.HTML);
        l.setValue(getCaption(component, false));
        l.setDescription(getTooltip(component));
        header.addComponent(l);
        header.setComponentAlignment(l, Alignment.MIDDLE_LEFT);
        
        return header;
    }
    
}
