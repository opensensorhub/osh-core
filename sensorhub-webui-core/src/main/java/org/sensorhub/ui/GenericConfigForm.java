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

import java.lang.reflect.ParameterizedType;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.function.Consumer;
import org.sensorhub.api.ISensorHub;
import org.sensorhub.api.comm.ICommNetwork;
import org.sensorhub.api.comm.ICommNetwork.NetworkType;
import org.sensorhub.api.config.DisplayInfo.FieldType.Type;
import org.sensorhub.api.config.DisplayInfo.ValueRange;
import org.sensorhub.api.module.IModule;
import org.sensorhub.api.module.IModuleProvider;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.api.module.ModuleConfigBase;
import org.sensorhub.impl.module.ModuleRegistry;
import org.sensorhub.impl.sensor.SensorSystemConfig.SystemMember;
import org.sensorhub.ui.ModuleInstanceSelectionPopup.ModuleInstanceSelectionCallback;
import org.sensorhub.ui.ModuleTypeSelectionPopup.ModuleTypeSelectionCallback;
import org.sensorhub.ui.ModuleTypeSelectionPopup.ModuleTypeSelectionWithClearCallback;
import org.sensorhub.ui.NetworkAddressSelectionPopup.AddressSelectionCallback;
import org.sensorhub.ui.ObjectTypeSelectionPopup.ObjectTypeSelectionCallback;
import org.sensorhub.ui.ObjectTypeSelectionPopup.ObjectTypeSelectionWithClearCallback;
import org.sensorhub.ui.ValueEntryPopup.ValueCallback;
import org.sensorhub.ui.api.IModuleConfigForm;
import org.sensorhub.ui.api.UIConstants;
import org.sensorhub.ui.data.BaseProperty;
import org.sensorhub.ui.data.BeanUtils;
import org.sensorhub.ui.data.ComplexProperty;
import org.sensorhub.ui.data.ContainerProperty;
import org.sensorhub.ui.data.FieldProperty;
import org.sensorhub.ui.data.MapProperty;
import org.sensorhub.ui.data.MyBeanItem;
import org.sensorhub.ui.data.MyBeanItemContainer;
import com.vaadin.v7.data.Buffered.SourceException;
import com.vaadin.v7.data.Property;
import com.vaadin.v7.data.Validator.InvalidValueException;
import com.vaadin.v7.data.fieldgroup.FieldGroup;
import com.vaadin.v7.data.fieldgroup.FieldGroup.CommitEvent;
import com.vaadin.v7.data.fieldgroup.FieldGroup.CommitException;
import com.vaadin.v7.data.fieldgroup.FieldGroup.CommitHandler;
import com.vaadin.v7.data.util.converter.StringToDoubleConverter;
import com.vaadin.v7.data.util.converter.StringToFloatConverter;
import com.vaadin.v7.data.validator.IntegerRangeValidator;
import com.vaadin.v7.data.validator.StringLengthValidator;
import org.slf4j.Logger;
import org.vast.util.BaseBuilder;
import com.vaadin.server.FontAwesome;
import com.vaadin.shared.ui.MarginInfo;
import com.vaadin.v7.shared.ui.datefield.Resolution;
import com.vaadin.ui.AbstractComponent;
import com.vaadin.v7.ui.AbstractField;
import com.vaadin.v7.ui.AbstractSelect.ItemCaptionMode;
import com.vaadin.ui.Alignment;
import com.vaadin.ui.Button;
import com.vaadin.ui.Button.ClickListener;
import com.vaadin.v7.ui.CheckBox;
import com.vaadin.ui.Component;
import com.vaadin.ui.ComponentContainer;
import com.vaadin.v7.ui.DateField;
import com.vaadin.v7.ui.Field;
import com.vaadin.ui.FormLayout;
import com.vaadin.ui.GridLayout;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;
import com.vaadin.v7.ui.ListSelect;
import com.vaadin.v7.ui.PasswordField;
import com.vaadin.ui.TabSheet;
import com.vaadin.ui.TabSheet.CloseHandler;
import com.vaadin.ui.TabSheet.SelectedTabChangeEvent;
import com.vaadin.ui.TabSheet.SelectedTabChangeListener;
import com.vaadin.ui.TabSheet.Tab;
import com.vaadin.v7.ui.Table;
import com.vaadin.ui.Window;
import com.vaadin.ui.Window.CloseEvent;
import com.vaadin.ui.Window.CloseListener;
import com.vaadin.v7.ui.TextField;
import com.vaadin.ui.UI;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.ui.Button.ClickEvent;


/**
 * <p>
 * Generic form builder based on Vaadin framework
 * This auto-generates widget giving types of properties.
 * </p>
 *
 * @author Alex Robin
 * @since Feb 1, 2014
 */
@SuppressWarnings({"serial", "deprecation"})
public class GenericConfigForm extends VerticalLayout implements IModuleConfigForm, UIConstants
{
    private static final String FIELD_GEN_ERROR = "Cannot generate UI field for ";
    private static final String ADD_ITEM_ERROR = "Cannot add new item to ";
    private static final String CHANGE_OBJECT_ERROR = "Cannot change object type of ";
    protected static final String OPTION_SELECT_MSG = "Please select the desired item";
    protected static final String MAIN_CONFIG = "General";
    protected static final String ADD_BUTTON_TAB_ID = "$ADD$";
    
    protected transient List<Field<?>> labels = new ArrayList<>();
    protected transient List<Field<?>> textBoxes = new ArrayList<>();
    protected transient List<Field<?>> listBoxes = new ArrayList<>();
    protected transient List<Field<?>> numberBoxes = new ArrayList<>();
    protected transient List<Field<?>> checkBoxes = new ArrayList<>();
    protected transient List<Component> subForms = new ArrayList<>();
    
    protected transient List<IModuleConfigForm> allForms = new ArrayList<>();
    protected transient FieldGroup fieldGroup;
    protected transient boolean tabJustRemoved;
    protected transient IModuleConfigForm parentForm;
    
    
    @Override
    public void build(String propId, ComplexProperty prop, boolean includeSubForms)
    {
        String title = prop.getLabel();
        if (title == null)
            title = DisplayUtils.getPrettyName(propId);
        
        build(title, prop.getDescription(), prop.getValue(), includeSubForms);
    }
    
    
    @Override
    public void build(String title, String popupText, MyBeanItem<Object> beanItem, boolean includeSubForms)
    {
        labels.clear();
        textBoxes.clear();
        listBoxes.clear();
        numberBoxes.clear();
        checkBoxes.clear();
        subForms.clear();
        
        setSpacing(false);
        setMargin(false);
        
        // header
        setCaption(title);
        setDescription(popupText);
        
        // form layout
        FormLayout form = new FormLayout();
        form.setWidth(100.0f, Unit.PERCENTAGE);
        addComponent(form);
        
        // add widget for each visible attribute
        if (beanItem != null)
        {
            fieldGroup = new FieldGroup(beanItem);
            
            for (Object propId: fieldGroup.getUnboundPropertyIds())
            {
                Property<?> prop = fieldGroup.getItemDataSource().getItemProperty(propId);
                if (!isFieldVisible((String)propId))
                    continue;
                
                // sub objects with multiplicity > 1
                if (prop instanceof ContainerProperty)
                    buildListComponent((String)propId, (ContainerProperty)prop, fieldGroup);
                
                // sub object
                else if (prop instanceof ComplexProperty)
                {
                    Component subform = buildSubForm((String)propId, (ComplexProperty)prop);
                    if (subform == null)
                        continue;
                    subForms.add(subform);
                }
                
                // scalar field
                else
                {
                    Field<?> field = null;
                    
                    try
                    {
                        String label = null;
                        if (prop instanceof FieldProperty)
                            label = ((FieldProperty)prop).getLabel();
                        if (label == null)
                            label = DisplayUtils.getPrettyName((String)propId);
                        
                        String desc = null;
                        if (prop instanceof FieldProperty)
                            desc = ((FieldProperty)prop).getDescription();
                        
                        field = buildAndBindField(label, (String)propId, prop);
                        if (field == null)
                            continue;
                        ((AbstractField<?>)field).setDescription(desc);
                    }
                    catch (SourceException e)
                    {
                        getOshLogger().trace(FIELD_GEN_ERROR + propId, e);
                        continue;
                    }
                    catch (Exception e)
                    {
                        getOshLogger().error(FIELD_GEN_ERROR + propId, e);
                        continue;
                    }
                    
                    // add to one of the widget lists so we can order by widget type
                    Class<?> propType = prop.getType();
                    if (propType.equals(String.class))
                    {
                        if (field instanceof Label)
                            labels.add(field);
                        else
                            textBoxes.add(field);
                    }
                    else if (Enum.class.isAssignableFrom(propType))
                        listBoxes.add(field);
                    else if (Number.class.isAssignableFrom(propType))
                        numberBoxes.add(field);
                    else if (field instanceof DateField)
                        listBoxes.add(field);
                    else if (field instanceof CheckBox)
                        checkBoxes.add(field);
                    else
                        subForms.add(field);
                }
            }
        }
            
        // main form
        for (Field<?> w: labels)
            form.addComponent(w);
        for (Field<?> w: textBoxes)
            form.addComponent(w);
        for (Field<?> w: listBoxes)
            form.addComponent(w);
        for (Field<?> w: numberBoxes)
            form.addComponent(w);
        for (Field<?> w: checkBoxes)
            form.addComponent(w);
        
        // subforms
        if (includeSubForms)
        {
            for (Component subForm: subForms)
            {
                Label sectionLabel = new Label(subForm.getCaption());
                sectionLabel.setDescription(subForm.getDescription());
                sectionLabel.addStyleName(STYLE_H3);
                sectionLabel.addStyleName(STYLE_COLORED);
                addComponent(sectionLabel);
                subForm.setCaption(null);
                addComponent(subForm);
            }
        }
    }
    
    
    protected boolean isFieldVisible(String propId)
    {
        return true;
    }
    
    
    /**
     * Method called to generate and bind the Field component corresponding to a
     * scalar property
     * @param label
     * @param propId
     * @param prop
     * @return the generated Field object
     */
    @SuppressWarnings("unchecked")
    protected Field<?> buildAndBindField(String label, String propId, Property<?> prop)
    {
        Field<?> field = fieldGroup.buildAndBind(label, propId);
        field.setInvalidCommitted(true);
        field.addStyleName(UIConstants.STYLE_SMALL);
        Class<?> propType = prop.getType();
        
        // disable edit (read only)
        if (propId.equals(PROP_ID) ||
            propId.endsWith(PROP_MODULECLASS) || propId.equals(PROP_VERSION))
            field.setReadOnly(true);
        
        // show these only for top level modules
        else if (propId.endsWith("." + PROP_ID) ||
                 propId.endsWith("." + PROP_AUTOSTART))
            field.setVisible(false);        
        
        // size depending on field type
        if (propType.equals(String.class))
        {
            field.setWidth(500, Unit.PIXELS);
        }
        else if (propType.equals(int.class) || propType.equals(Integer.class))
        {
            field.setWidth(100, Unit.PIXELS);
        }
        else if (propType.equals(float.class) || propType.equals(Float.class))
        {
            field.setWidth(100, Unit.PIXELS);
            
            // allow for more digits
            ((TextField)field).setConverter(new StringToFloatConverter() {
                @Override
                protected NumberFormat getFormat(Locale locale)
                {
                    NumberFormat f = NumberFormat.getInstance(locale);
                    f.setMaximumFractionDigits(8);
                    return f;
                }
            });
        }
        else if (propType.equals(double.class) || propType.equals(Double.class))
        {
            field.setWidth(150, Unit.PIXELS);
            
            // allow for more digits
            ((TextField)field).setConverter(new StringToDoubleConverter() {
                @Override
                protected NumberFormat getFormat(Locale locale)
                {
                    NumberFormat f = NumberFormat.getInstance(locale);
                    f.setMaximumFractionDigits(16);
                    return f;
                }
            });
        }
        else if (Enum.class.isAssignableFrom(propType))
        {
            ((ListSelect)field).setRows(3);
            field.setWidth(200, Unit.PIXELS);
        }
        
        if (field instanceof TextField)
        {
            ((TextField)field).setImmediate(true);
            ((TextField)field).setNullSettingAllowed(true);
            ((TextField)field).setNullRepresentation("");
        }
        
        else if (field instanceof DateField)
        {
            ((DateField) field).setTimeZone(TimeZone.getTimeZone("UTC"));
            ((DateField) field).setResolution(Resolution.SECOND);
            ((DateField) field).setDateFormat("yyyy-MM-dd HH:mm:ss '(UTC)'");
            field.setWidth(250, Unit.PIXELS);
        }
        
        // special fields
        if (prop instanceof BaseProperty)
        {
            BaseProperty<?> advProp = (BaseProperty<?>)prop;
            Type fieldType = advProp.getFieldType();
            
            if (fieldType != null)
            {
                switch (fieldType)
                {
                    case MODULE_ID:
                        Class<?> moduleClass = advProp.getModuleType();
                        if (moduleClass == null)
                            moduleClass = IModule.class;
                        field = makeModuleSelectField((Field<Object>)field, moduleClass);
                        break;
                        
                    case SYSTEM_UID:
                        field = makeSystemSelectField((Field<Object>)field);
                        break;
                        
                    case REMOTE_ADDRESS:
                        NetworkType addressType = advProp.getAddressType();
                        if (addressType == null)
                            addressType = NetworkType.IP;
                        field = makeAddressSelectField((Field<Object>)field, addressType);
                        break;
                        
                    case PASSWORD:
                        field = makePasswordField((TextField)field);
                        break;
                        
                    default:
                }
            }
            
            // required
            if (advProp.isRequired())
            {
                if (propType.equals(String.class))
                    field.addValidator(new StringLengthValidator(MSG_REQUIRED_FIELD, 1, Integer.MAX_VALUE, false));
                else if (propType.equals(int.class) || propType.equals(Integer.class))
                    field.addValidator(new IntegerRangeValidator(MSG_REQUIRED_FIELD, Integer.MIN_VALUE, Integer.MAX_VALUE));
            }
                
            // valid range
            ValueRange range = advProp.getValueRange();
            if (range != null)
            {
                String msg = String.format("Value should be within [%d - %d] range", range.min(), range.max());
                field.addValidator(new IntegerRangeValidator(msg, (int)range.min(), (int)range.max()));
            }
        }
        
        return field;
    }
    
    
    protected Field<Object> makeSystemSelectField(Field<Object> field)
    {
        return new FieldWrapper<Object>(field) {
            @Override
            protected Component initContent()
            {
                HorizontalLayout layout = new HorizontalLayout();
                layout.setSpacing(true);

                // inner field
                innerField.setReadOnly(true);
                layout.addComponent(innerField);
                layout.setComponentAlignment(innerField, Alignment.MIDDLE_LEFT);
                final Field<Object> wrapper = this;

                // select system button
                Button selectBtn = new Button(FontAwesome.SEARCH);
                selectBtn.setDescription("Lookup System");
                selectBtn.addStyleName(STYLE_QUIET);
                layout.addComponent(selectBtn);
                layout.setComponentAlignment(selectBtn, Alignment.MIDDLE_LEFT);
                selectBtn.addClickListener(new ClickListener() {
                    @Override
                    public void buttonClick(ClickEvent event)
                    {
                        // show popup to select among systems registered in federated database
                        SystemSelectionPopup popup = new SystemSelectionPopup(800, (selected) -> {
                            innerField.setReadOnly(false);
                            wrapper.setValue(selected);
                            innerField.setReadOnly(true);
                        }, getParentHub().getDatabaseRegistry().getFederatedDatabase());
                        popup.setModal(true);
                        getUI().addWindow(popup);
                    }
                });

                return layout;
            }
        };
    }
    
    
    @SuppressWarnings("rawtypes")
    protected Field<Object> makeModuleSelectField(Field<Object> field, final Class<?> moduleType)
    {
        return new FieldWrapper<Object>(field) {
            @Override
            protected Component initContent()
            {
                HorizontalLayout layout = new HorizontalLayout();
                layout.setSpacing(true);
                
                // inner field
                innerField.setReadOnly(true);
                layout.addComponent(innerField);
                layout.setComponentAlignment(innerField, Alignment.MIDDLE_LEFT);
                final Field<Object> wrapper = this;
                
                // select module button
                Button selectBtn = new Button(FontAwesome.SEARCH);
                selectBtn.setDescription("Lookup Module");
                selectBtn.addStyleName(STYLE_QUIET);
                layout.addComponent(selectBtn);
                layout.setComponentAlignment(selectBtn, Alignment.MIDDLE_LEFT);
                selectBtn.addClickListener(new ClickListener() {
                    @Override
                    public void buttonClick(ClickEvent event)
                    {
                        // show popup to select among available module types
                        ModuleInstanceSelectionPopup popup = new ModuleInstanceSelectionPopup(moduleType, new ModuleInstanceSelectionCallback() {
                            @Override
                            public void onSelected(IModule module)
                            {
                                innerField.setReadOnly(false);
                                wrapper.setValue(module.getLocalID());
                                innerField.setReadOnly(true);
                            }
                        });
                        popup.setModal(true);
                        getUI().addWindow(popup);
                    }
                });
                
                return layout;
            }
        };
    }
    
    
    protected Field<Object> makeAddressSelectField(Field<Object> field, final NetworkType addressType)
    {
        return new FieldWrapper<Object>(field) {
            @Override
            protected Component initContent()
            {
                HorizontalLayout layout = new HorizontalLayout();
                layout.setSpacing(true);
                
                // inner field
                layout.addComponent(innerField);
                layout.setComponentAlignment(innerField, Alignment.MIDDLE_LEFT);
                final Field<Object> wrapper = this;
                
                // select module button
                Button selectBtn = new Button(FontAwesome.SEARCH);
                selectBtn.setDescription("Lookup Address");
                selectBtn.addStyleName(STYLE_QUIET);
                layout.addComponent(selectBtn);
                layout.setComponentAlignment(selectBtn, Alignment.MIDDLE_LEFT);
                selectBtn.addClickListener(new ClickListener() {
                    @Override
                    public void buttonClick(ClickEvent event)
                    {
                        // error if no networks are available
                        boolean netAvailable = false;
                        Collection<ICommNetwork<?>> networks = getParentHub().getNetworkManager().getLoadedModules(addressType);
                        for (ICommNetwork<?> network: networks)
                        {
                            if (network.isStarted())
                            {
                                netAvailable = true;
                                break;
                            }
                        }
                        if (!netAvailable)
                        {
                            DisplayUtils.showErrorPopup("No network scanner available for " + addressType + " address lookup", null);
                            return;
                        }
                        
                        // show popup to select among available module types
                        NetworkAddressSelectionPopup popup = new NetworkAddressSelectionPopup(addressType, new AddressSelectionCallback() {
                            @Override
                            public void onSelected(String address)
                            {
                                innerField.setReadOnly(false);
                                wrapper.setValue(address);
                                innerField.setReadOnly(true);
                            }
                        });
                        popup.setModal(true);
                        getUI().addWindow(popup);
                    }
                });
                
                return layout;
            }
        };
    }
    
    
    protected Field<String> makePasswordField(Field<String> field)
    {
        return new FieldWrapper<String>(field) {
            private PasswordField passwordField;
            @Override
            protected Component initContent()
            {
                final HorizontalLayout layout = new HorizontalLayout();
                layout.setSpacing(true);
                
                ((TextField)innerField).setBuffered(false);
                
                // create and show password field by default
                passwordField = new PasswordField();
                passwordField.setNullRepresentation("");
                passwordField.setBuffered(false);
                passwordField.setWidth(innerField.getWidth(), innerField.getWidthUnits());
                passwordField.setPropertyDataSource(innerField.getPropertyDataSource());
                layout.addComponent(passwordField);
                layout.setComponentAlignment(passwordField, Alignment.MIDDLE_LEFT);
                
                // show/hide button
                final Button showBtn = new Button(FontAwesome.EYE);
                showBtn.addStyleName(STYLE_QUIET);
                showBtn.setDescription("Show Password");
                showBtn.setData(false);
                layout.addComponent(showBtn);
                layout.setComponentAlignment(showBtn, Alignment.MIDDLE_LEFT);
                showBtn.addClickListener(new ClickListener() {
                    @Override
                    public void buttonClick(ClickEvent event)
                    {
                        boolean checked = !(boolean)showBtn.getData();
                        showBtn.setData(checked);
                        
                        if (checked)
                        {
                            layout.replaceComponent(passwordField, innerField);
                            showBtn.setIcon(FontAwesome.EYE_SLASH);
                        }
                        else
                        {
                            layout.replaceComponent(innerField, passwordField);
                            showBtn.setIcon(FontAwesome.EYE);
                        }
                    }
                    
                });
                
                return layout;
            }
        };
    }
    
    
    protected ComponentContainer buildSubForm(final String propId, final ComplexProperty prop)
    {
        Class<?> beanType = prop.getBeanType();
        MyBeanItem<Object> childBeanItem = prop.getValue();
        
        // generate custom form for this bean type
        IModuleConfigForm subform;
        if (childBeanItem != null)
            subform = getParentModule().generateForm(childBeanItem.getBean().getClass());
        else
            subform = getParentModule().generateForm(beanType);
        subform.build(propId, prop, true);
        subform.setParentForm(this);
        
        // add change button if property is changeable module or object config
        if (ModuleConfigBase.class.isAssignableFrom(beanType))
        {
            addChangeModuleButton(subform, propId, prop, beanType);
        }
        else
        {
            // add change button if property can have multiple types
            Map<String, Class<?>> possibleTypes = getPossibleTypes(propId, prop);
            if (childBeanItem == null || !(possibleTypes == null || possibleTypes.isEmpty()))
                addChangeObjectButton(subform, propId, prop, possibleTypes);
        }
        
        
        if (childBeanItem != null)
            allForms.add(subform);
        
        return subform;
    }
    
    
    protected void addChangeModuleButton(final ComponentContainer parentForm, final String propId, final ComplexProperty prop, final Class<?> objectType)
    {
        final Button chgButton = new Button();
        //chgButton.addStyleName(STYLE_QUIET);
        chgButton.addStyleName(STYLE_SMALL);
        chgButton.addStyleName(STYLE_SECTION_BUTTONS);
        chgButton.setIcon(EDIT_ICON);
        if (prop.getValue() == null)
            chgButton.setCaption("Add");
        else
            chgButton.setCaption("Modify");
        
        chgButton.addClickListener(new ClickListener() {
            @Override
            public void buttonClick(ClickEvent event)
            {
                Collection<IModuleProvider> moduleTypes = getPossibleModuleTypes(propId, objectType);
                
                // show popup to select among available module types
                ModuleTypeSelectionPopup popup = new ModuleTypeSelectionPopup(moduleTypes, new ModuleTypeSelectionWithClearCallback() {
                    @Override
                    public void onSelected(ModuleConfigBase config)
                    {
                        config.name = null;
                        
                        try
                        {
                            updateSubForm(chgButton, config, propId, prop);
                        }
                        catch (Exception e)
                        {
                            DisplayUtils.showErrorPopup(CHANGE_OBJECT_ERROR + propId, e);
                        }
                    }

                    @Override
                    public void onClearSelection()
                    {
                        updateSubForm(chgButton, null, propId, prop);
                    }
                });
                
                popup.setModal(true);
                getUI().addWindow(popup);
            }
        });
        
        chgButton.setData(parentForm);
        ((VerticalLayout)parentForm).addComponent(chgButton, 0);
    }
    
    
    protected void addChangeObjectButton(final ComponentContainer parentForm, final String propId, final ComplexProperty prop, final Map<String, Class<?>> typeList)
    {
        final Button chgButton = new Button();
        //chgButton.addStyleName(STYLE_QUIET);
        chgButton.addStyleName(STYLE_SMALL);
        chgButton.addStyleName(STYLE_SECTION_BUTTONS);
        chgButton.setIcon(EDIT_ICON);
        if (prop.getValue() == null)
            chgButton.setCaption("Add");
        else
            chgButton.setCaption("Modify");
                
        // show popup to select among available module types
        final ObjectTypeSelectionWithClearCallback callback = new ObjectTypeSelectionWithClearCallback() {
            @Override
            public void onSelected(Class<?> objectType)
            {
                try
                {
                    var obj = objectType.newInstance();
                    if (obj instanceof BaseBuilder)
                        obj = ((BaseBuilder<?>)obj).build();
                    updateSubForm(chgButton, obj, propId, prop);
                }
                catch (Exception e)
                {
                    DisplayUtils.showErrorPopup(CHANGE_OBJECT_ERROR + propId, e);
                }
            }

            @Override
            public void onClearSelection()
            {
                updateSubForm(chgButton, null, propId, prop);
            }
        };
        
        // if choice is limited to a single possibility
        if (typeList.size() <= 1)
        {
            if (prop.getValue() != null)
            {
                // don't display remove button if value is required
                if (prop.isRequired())
                    return;
                chgButton.setCaption("Remove");
            }   
            
            chgButton.addClickListener(new ClickListener() {
                @Override
                public void buttonClick(ClickEvent event)
                {
                    if (prop.getValue() == null)
                    {
                        // if empty list, we use the declared type
                        if (typeList == null || typeList.isEmpty())
                            callback.onSelected(prop.getBeanType());
                        
                        // if single item list,use the only possibility
                        else if (typeList.size() == 1)
                        {
                            Class<?> firstType = typeList.values().iterator().next();
                            callback.onSelected(firstType);
                        }
                    }
                    else
                        callback.onClearSelection();
                }
            });
        }
        else
        {
            chgButton.addClickListener(new ClickListener() {
                @Override
                public void buttonClick(ClickEvent event)
                {
                    // we popup the list so the user can select what he wants
                    ObjectTypeSelectionPopup popup = new ObjectTypeSelectionPopup(OPTION_SELECT_MSG, typeList, callback);
                    popup.setModal(true);
                    getUI().addWindow(popup);
                }
            });
        }
        
        chgButton.setData(parentForm);
        ((VerticalLayout)parentForm).addComponent(chgButton, 0);
    }
    
    
    protected void updateSubForm(final Button chgButton, final Object newBean, final String propId, final ComplexProperty prop)
    {
        Component oldForm = (Component)chgButton.getData();
        
        // generate new form (will be empty if bean is null)
        MyBeanItem<Object> newItem = null;
        if (newBean != null)
            newItem = new MyBeanItem<>(newBean, propId + ".");
        prop.setValue(newItem);
        ComponentContainer newForm = buildSubForm(propId, prop);
        newForm.setCaption(null);
        
        // replace old form in UI
        if (oldForm != null)
        {
            allForms.remove(oldForm);
            ((ComponentContainer)oldForm.getParent()).replaceComponent(oldForm, newForm);
        }
    }
    
    
    protected void buildListComponent(final String propId, final ContainerProperty prop, final FieldGroup fieldGroup)
    {
        // skip SensorSystem members since they are already shown in the tree
        Class<?> eltType = prop.getValue().getBeanType();
        if (eltType == SystemMember.class)
            return;
        
        // use simple list for string lists
        if (!(prop instanceof MapProperty) && BeanUtils.isSimpleType(eltType))
        {
            Component list = (prop.getFieldType() == Type.SYSTEM_UID) ?
                buildSystemList((String)propId, prop) :
                buildSimpleList((String)propId, prop, eltType);
            if (list == null)
                return;
            fieldGroup.bind((Field<?>)list, propId);
            listBoxes.add((Field<?>)list);
        }
        
        // use multi column table if collection contains only simple elements
        else if (BeanUtils.isSimpleType(eltType) || prop.getFieldType() == Type.TABLE)
        {
            Component table = buildTable((String)propId, prop, eltType);
            if (table == null)
                return;
            subForms.add(table);
        }
        
        // else use tab sheet
        else
        {
            Component subform = buildTabs(propId, prop, fieldGroup);
            if (subform == null)
                return;
            subForms.add(subform);
        }
    }
    
    
    @SuppressWarnings("unchecked")
    protected Component buildSimpleList(final String propId, final ContainerProperty prop, final Class<?> eltType, final Consumer<ValueCallback> valueProvider)
    {
        String label = prop.getLabel();
        if (label == null)
            label = DisplayUtils.getPrettyName((String)propId);
        
        final MyBeanItemContainer<Object> container = prop.getValue();
        final ListSelect listBox = new ListSelect(label, container);
        listBox.setValue(container);
        listBox.setItemCaptionMode(ItemCaptionMode.ITEM);
        listBox.setImmediate(true);
        listBox.setBuffered(true);
        listBox.setNullSelectionAllowed(false);
        listBox.setDescription(prop.getDescription());
        //listBox.setWidth(250, Unit.PIXELS);
        listBox.addStyleName(UIConstants.STYLE_SMALL);
        listBox.setRows(Math.max(2, Math.min(5, container.size())));
        
        return new FieldWrapper<Object>(listBox) {
            @Override
            protected Component initContent()
            {
                HorizontalLayout layout = new HorizontalLayout();
                layout.setSpacing(true);
                
                // inner field
                layout.addComponent(innerField);
                layout.setComponentAlignment(innerField, Alignment.MIDDLE_LEFT);
                
                VerticalLayout buttons = new VerticalLayout();
                buttons.setMargin(false);
                buttons.setSpacing(false);
                layout.addComponent(buttons);
                
                // add button
                Button addBtn = new Button(ADD_ICON);
                addBtn.addStyleName(STYLE_QUIET);
                addBtn.addStyleName(STYLE_SMALL);
                buttons.addComponent(addBtn);
                addBtn.addClickListener(new ClickListener() {
                    @Override
                    public void buttonClick(ClickEvent event)
                    {
                        // create callback to add new value
                        ValueCallback callback = new ValueCallback() {
                            @Override
                            public void newValue(Object value)
                            {
                                container.addBean(value);
                                // grow list size with max at 5
                                listBox.setRows(Math.max(2, Math.min(5, container.size())));
                            }
                        };
                        
                        valueProvider.accept(callback);
                    }
                });
                
                // remove button
                Button delBtn = new Button(DEL_ICON);
                delBtn.addStyleName(STYLE_QUIET);
                delBtn.addStyleName(STYLE_SMALL);
                buttons.addComponent(delBtn);
                delBtn.addClickListener(new ClickListener() {
                    @Override
                    public void buttonClick(ClickEvent event)
                    {
                        Object itemId = listBox.getValue();
                        container.removeItem(itemId);
                    }
                });
                
                return layout;
            }
            
            @Override
            public void commit() throws SourceException, InvalidValueException
            {
                // override commit here because the ListSelect setValue() method
                // only sets the index of the selected item, and not the list content
                prop.setValue(container);
            }
        };
    }
    
    
    @SuppressWarnings("unchecked")
    protected Component buildSimpleList(final String propId, final ContainerProperty prop, final Class<?> eltType)
    {
        return buildSimpleList(propId, prop, eltType, callback -> {
            List<Object> valueList = GenericConfigForm.this.getPossibleValues(propId);
            
            Window popup;
            if (Enum.class.isAssignableFrom(eltType))
                popup = new ValueEnumPopup(500, callback, ((Class<Enum<?>>)eltType).getEnumConstants());
            else
                popup = new ValueEntryPopup(500, callback, valueList);
            
            popup.setModal(true);
            getUI().addWindow(popup); 
        });
    }
    
    
    protected Component buildSystemList(final String propId, final ContainerProperty prop)
    {
        return buildSimpleList(propId, prop, String.class, callback -> {
            var popup = new SystemSelectionPopup(800, callback, getParentHub().getDatabaseRegistry().getFederatedDatabase());
            popup.setModal(true);
            getUI().addWindow(popup); 
        });
    }
    
    
    @SuppressWarnings("unchecked")
    protected Component buildTable(final String propId, final ContainerProperty prop, final Class<?> eltType)
    {
        final MyBeanItemContainer<Object> container = prop.getValue();
        final Table table = new Table();
        table.setSizeFull();
        table.setSelectable(true);
        table.setNullSelectionAllowed(false);
        table.setImmediate(true);
        table.setColumnReorderingAllowed(false);
        table.setContainerDataSource(container);
        
        FieldWrapper<Object> wrapper = new FieldWrapper<Object>(table) {
            @Override
            protected Component initContent()
            {
                HorizontalLayout layout = new HorizontalLayout();
                layout.setSpacing(true);
                
                // inner field
                layout.addComponent(innerField);
                layout.setComponentAlignment(innerField, Alignment.MIDDLE_LEFT);
                
                VerticalLayout buttons = new VerticalLayout();
                layout.addComponent(buttons);
                
                // add button
                Button addBtn = new Button(ADD_ICON);
                addBtn.addStyleName(STYLE_QUIET);
                addBtn.addStyleName(STYLE_SMALL);
                buttons.addComponent(addBtn);
                addBtn.addClickListener(new ClickListener() {
                    @Override
                    public void buttonClick(ClickEvent event)
                    {
                        try
                        {
                            Map<String, Class<?>> typeList = GenericConfigForm.this.getPossibleTypes(propId, prop);
                            
                            // create callback to add table item
                            ObjectTypeSelectionCallback callback = new ObjectTypeSelectionCallback() {
                                @Override
                                public void onSelected(Class<?> objectType)
                                {
                                    try
                                    {
                                        // add new item to container
                                        container.addBean(objectType.newInstance(), propId + PROP_SEP);
                                    }
                                    catch (Exception e)
                                    {
                                        DisplayUtils.showErrorPopup(ADD_ITEM_ERROR + propId, e);
                                    }
                                }
                            };
                            
                            if (typeList == null || typeList.isEmpty())
                            {
                                // we use the declared type
                                callback.onSelected(container.getBeanType());
                            }
                            else if (typeList.size() == 1)
                            {
                                // we automatically use the only type in the list
                                Class<?> firstType = typeList.values().iterator().next();
                                callback.onSelected(firstType);
                            }
                            else
                            {
                                // we popup the list so the user can select what he wants
                                ObjectTypeSelectionPopup popup = new ObjectTypeSelectionPopup(OPTION_SELECT_MSG, typeList, callback);
                                popup.setModal(true);
                                getUI().addWindow(popup);
                            }
                        }
                        catch (Exception e)
                        {
                            DisplayUtils.showErrorPopup(ADD_ITEM_ERROR + propId, e);
                        }
                    }
                });
                
                // remove button
                Button delBtn = new Button(DEL_ICON);
                delBtn.addStyleName(STYLE_QUIET);
                delBtn.addStyleName(STYLE_SMALL);
                buttons.addComponent(delBtn);
                delBtn.addClickListener(new ClickListener() {
                    @Override
                    public void buttonClick(ClickEvent event)
                    {
                        Object itemId = table.getValue();
                        container.removeItem(itemId);
                    }
                });
                
                return layout;
            }
            
            @Override
            public void commit()
            {
                // override commit here because the ListSelect setValue() method
                // only sets the index of the selected item, and not the list content
                prop.setValue(container);
            }
        };
        
        // set title and popup
        String title = prop.getLabel();
        if (title == null)
            title = DisplayUtils.getPrettyName((String)propId);
        wrapper.setCaption(title);
        wrapper.setDescription(prop.getDescription());
        
        return wrapper;
    }
    
    
    @SuppressWarnings("unchecked")
    protected Component buildTabs(final String propId, final ContainerProperty prop, final FieldGroup fieldGroup)
    {
        GridLayout layout = new GridLayout();
        layout.setWidth(100.0f, Unit.PERCENTAGE);
        
        // set title and popup
        String title = prop.getLabel();
        if (title == null)
            title = DisplayUtils.getPrettyName((String)propId);
        layout.setCaption(title);
        layout.setDescription(prop.getDescription());
        
        // create one tab per item in container
        final MyBeanItemContainer<Object> container = prop.getValue();
        final TabSheet tabs = new TabSheet();
        tabs.setSizeFull();
        int tabIndex = 0;
        for (Object itemId: container.getItemIds())
        {
            MyBeanItem<Object> childBeanItem = container.getItem(itemId);
            addTab(tabs, itemId, childBeanItem, tabIndex++);
        }
        
        // add fake tab with icon to add new items
        tabs.addTab(new VerticalLayout(), "", UIConstants.ADD_ICON).setId(ADD_BUTTON_TAB_ID);
        
        // also add empty tab so click on the '+' tab can be detected with tab changed events
        tabs.addTab(new VerticalLayout(), "").setStyleName("empty-tab");
        
        // initial selection
        if (tabs.getComponentCount() > 2)
            tabs.setSelectedTab(0); // select first item
        else
            tabs.setSelectedTab(1); // select empty tab
        
        // catch close event to delete item
        tabs.setCloseHandler(new CloseHandler() {
            private static final long serialVersionUID = 1L;
            @Override
            public void onTabClose(TabSheet tabsheet, Component tabContent)
            {
                final Tab tab = tabs.getTab(tabContent);
                
                final ConfirmDialog popup = new ConfirmDialog("Are you sure you want to delete " + tab.getCaption() + "?</br>All settings will be lost.");
                popup.addCloseListener(new CloseListener() {
                    private static final long serialVersionUID = 1L;
                    @Override
                    public void windowClose(CloseEvent e)
                    {
                        if (popup.isConfirmed())
                        {                    
                            // retrieve id of item shown on tab
                            AbstractComponent tabContent = (AbstractComponent)tab.getComponent();
                            Object itemId = tabContent.getData();
                            
                            // remove from UI
                            int deletedTabPos = tabs.getTabPosition(tab);
                            tabJustRemoved = true;
                            tabs.removeTab(tab);
                            if (deletedTabPos > 0)
                                tabs.setSelectedTab(deletedTabPos-1);
                            else if (tabs.getComponentCount() > 2)
                                tabs.setSelectedTab(deletedTabPos);
                            else
                                tabs.setSelectedTab(1); // select empty tab
                            
                            // remove from container
                            container.removeItem(itemId);
                        }
                    }
                });
                
                popup.setModal(true);
                getUI().addWindow(popup);
            }
        });
        
        // catch select event on '+' tab to add new item
        tabs.addSelectedTabChangeListener(new SelectedTabChangeListener() {
            @Override
            public void selectedTabChange(SelectedTabChangeEvent event)
            {
                Component selectedTab = event.getTabSheet().getSelectedTab();
                final Tab tab = tabs.getTab(selectedTab);
                final int selectedTabPos = tabs.getTabPosition(tab);
                
                // case of + tab to add new item
                if (ADD_BUTTON_TAB_ID.equals(tab.getId()) && !tabJustRemoved)
                {
                    // select something in case add is canceled
                    if (tabs.getComponentCount() > 2)
                        tabs.setSelectedTab(selectedTabPos-1); // select last item
                    else
                        tabs.setSelectedTab(selectedTabPos+1); // select empty tab
                    
                    try
                    {
                        // if bean is a module config, let user pick a module
                        if (ModuleConfigBase.class.isAssignableFrom(container.getBeanType()))
                        {
                            Collection<IModuleProvider> moduleTypes = getPossibleModuleTypes(propId, container.getBeanType());
                            
                            // show popup to select among available module types
                            var callback = new ModuleTypeSelectionCallback() {
                                @Override
                                public void onSelected(ModuleConfigBase config)
                                {
                                    try
                                    {
                                        // add new item to container
                                        MyBeanItem<Object> childBeanItem = container.addBean(config, propId + PROP_SEP);                                                                        
                                        Tab newTab = addTab(tabs, container.lastItemId(), childBeanItem, selectedTabPos);
                                        tabs.setSelectedTab(newTab);
                                    }
                                    catch (Exception e)
                                    {
                                        DisplayUtils.showErrorPopup(ADD_ITEM_ERROR + propId, e);
                                    }
                                }
                            };
    
                            if (moduleTypes.size() == 1)
                            {
                                // we automatically use the only type in the list
                                Class<?> firstType = moduleTypes.iterator().next().getModuleConfigClass();
                                callback.onSelected((ModuleConfig)firstType.newInstance());
                            }
                            else
                            {
                                // popup the list so the user can select what he wants
                                var popup = new ModuleTypeSelectionPopup(moduleTypes, callback);
                                popup.setModal(true);
                                getUI().addWindow(popup);
                            }
                        }
                        
                        // else if possible types are not modules
                        else
                        {
                            Map<String, Class<?>> typeList = GenericConfigForm.this.getPossibleTypes(propId, prop);
                            
                            // create callback to add table item
                            var callback = new ObjectTypeSelectionCallback() {
                                @Override
                                public void onSelected(Class<?> objectType)
                                {
                                    try
                                    {
                                        // add new item to container
                                        MyBeanItem<Object> childBeanItem = container.addBean(objectType.newInstance(), propId + PROP_SEP);                                                                        
                                        Tab newTab = addTab(tabs, container.lastItemId(), childBeanItem, selectedTabPos);
                                        tabs.setSelectedTab(newTab);
                                    }
                                    catch (Exception e)
                                    {
                                        DisplayUtils.showErrorPopup(ADD_ITEM_ERROR + propId, e);
                                    }
                                }
                            };
                            
                            if (typeList == null || typeList.isEmpty())
                            {
                                // we use the declared type
                                callback.onSelected(container.getBeanType());
                            }
                            else if (typeList.size() == 1)
                            {
                                // we automatically use the only type in the list
                                Class<?> firstType = typeList.values().iterator().next();
                                callback.onSelected(firstType);
                            }
                            else
                            {
                                // we popup the list so the user can select what he wants
                                String title = "Please select the desired option";
                                var popup = new ObjectTypeSelectionPopup(title, typeList, callback);
                                popup.setModal(true);
                                getUI().addWindow(popup);
                            }
                        }
                    }
                    catch (Exception e)
                    {
                        DisplayUtils.showErrorPopup(ADD_ITEM_ERROR + propId, e);
                    }
                }
                
                // reset flag to allow adding
                tabJustRemoved = false;
            }
        });
        
        // also register commit handler
        fieldGroup.addCommitHandler(new CommitHandler() {
            private static final long serialVersionUID = 1L;
            @Override
            public void preCommit(CommitEvent commitEvent)
            {
                // nothing to do
            }

            @Override
            public void postCommit(CommitEvent commitEvent)
            {
                // make sure new items are transfered to model
                prop.setValue(prop.getValue());
            }
        });
        
        layout.addComponent(tabs);
        return layout;
    }
    
    
    protected void addObjectToTable()
    {
        
    }
    
    
    protected void addModuleToTable()
    {
        
    }
    
    
    protected Tab addTab(TabSheet tabs, final Object itemId, final MyBeanItem<Object> beanItem, final int tabIndex)
    {
        // generate subform
        IModuleConfigForm subform = getParentModule().generateForm(beanItem.getBean().getClass());
        subform.build(null, null, beanItem, true);
        subform.setParentForm(this);
        subform.setMargin(new MarginInfo(false, false, true, false));
        allForms.add(subform);
        
        // create tab
        String caption = getTabCaption(itemId, beanItem, tabIndex);
        final Tab tab = tabs.addTab(subform, caption, null, tabIndex);
        tab.setClosable(true);
        
        // store item id so we can map the tab with the corresponding bean item
        ((AbstractComponent)subform).setData(itemId);
        
        return tab;
    }
    
    
    protected String getTabCaption(Object itemId, MyBeanItem<?> beanItem, int tabIndex)
    {
        if (itemId instanceof String)
            return (String)itemId;
        
        String beanItemId = beanItem.getItemId();
        if (beanItemId != null)
            return beanItemId;
        
        return "Item " + (tabIndex+1);
    }
    
    
    @Override
    public void commit() throws CommitException
    {
        fieldGroup.commit();
        for (IModuleConfigForm form: allForms)
            form.commit();
    }
    
    
    @Override
    public List<Component> getSubForms()
    {
        return subForms;
    }
    
    
    @Override
    public Collection<IModuleProvider> getPossibleModuleTypes(String propId, Class<?> configType)
    {
        final ModuleRegistry registry = getParentHub().getModuleRegistry();
        final Collection<IModuleProvider> providers = new ArrayList<>();
        for (IModuleProvider provider: registry.getInstalledModuleTypes())
        {
            Class<?> configClass = provider.getModuleConfigClass();
            if (configType.isAssignableFrom(configClass))
                providers.add(provider);
        }
        
        return providers;
    }


    @Override
    public Map<String, Class<?>> getPossibleTypes(String propId, BaseProperty<?> prop)
    {
        return Collections.emptyMap();
    }
    
    
    @Override
    public List<Object> getPossibleValues(String propId)
    {
        return Collections.emptyList();
    }
    
    
    @Override
    public IModuleConfigForm getParentForm()
    {
        return this.parentForm;
    }


    @Override
    public void setParentForm(IModuleConfigForm parentForm)
    {
        this.parentForm = parentForm;
    }
    
    
    protected ISensorHub getParentHub()
    {
        return ((AdminUI)UI.getCurrent()).getParentHub();
    }
    
    
    protected AdminUIModule getParentModule()
    {
        return ((AdminUI)UI.getCurrent()).getParentModule();
    }
    
    
    protected Logger getOshLogger()
    {
        return ((AdminUI)UI.getCurrent()).getOshLogger();
    }
}
