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

import com.vaadin.event.Action;
import com.vaadin.server.FontAwesome;
import com.vaadin.ui.*;
import com.vaadin.v7.data.Item;
import com.vaadin.v7.ui.ComboBox;
import com.vaadin.v7.ui.TreeTable;
import com.vaadin.v7.ui.VerticalLayout;
import net.opengis.OgcProperty;
import net.opengis.OgcPropertyList;
import net.opengis.gml.v32.impl.CodeWithAuthorityImpl;
import net.opengis.sensorml.v20.*;
import net.opengis.sensorml.v20.Link;
import net.opengis.sensorml.v20.impl.SettingsImpl;
import net.opengis.sensorml.v20.impl.ValueSettingImpl;
import net.opengis.swe.v20.AbstractSWEIdentifiable;
import net.opengis.swe.v20.DataComponent;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.sensorhub.api.command.IStreamingControlInterface;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.api.processing.IProcessModule;
import org.sensorhub.impl.processing.CommandStreamSink;
import org.sensorhub.impl.processing.DataStreamSource;
import org.sensorhub.impl.processing.SMLProcessConfig;
import org.sensorhub.impl.processing.SMLProcessImpl;
import org.sensorhub.ui.ProcessSelectionPopup.ProcessSelectionCallback;
import org.sensorhub.ui.data.MyBeanItem;
import org.sensorhub.utils.FileUtils;
import org.vast.data.DataRecordImpl;
import org.vast.process.ProcessInfo;
import org.vast.sensorML.AggregateProcessImpl;
import org.vast.sensorML.LinkImpl;
import org.vast.sensorML.SMLUtils;
import org.vast.swe.SWEHelper;


/**
 * <p>
 * Admin panel for sensor modules.<br/>
 * This adds a section to view structure of inputs and outputs,
 * and allows the user to send commands and view output data values.
 * </p>
 *
 * @author Alex Robin
 * @since 1.0
 */
@SuppressWarnings({"serial", "deprecation"})
public class ProcessAdminPanel extends DataSourceAdminPanel<IProcessModule<?>>
{
    Panel inputCommandsPanel, paramCommandsPanel;
    SMLProcessConfig config;
    MyBeanItem<ModuleConfig> configBean = null;
    com.vaadin.v7.ui.TextField smlPathTextField = null;
    TreeTable processTable;
    AggregateProcess aggregateProcess;

    private static final String PROP_INPUTS = "Inputs";
    private static final String PROP_OUTPUTS = "Outputs";
    private static final String PROP_PARAMS = "Parameters";
    private static final String PROP_COMPS = "Components";
    private static final String PROP_CONNS = "Connections";
    private static final String PROP_NAME = "Name";
    private static final String PROP_VALUE = "Value";

    private static final Action ADD_COMPONENT_ACTION = new Action("Add Component");
    private static final Action ADD_IO_ACTION = new Action("Add Input/Output");
    private static final Action ADD_PARAMETER_ACTION = new Action("Add Parameter");
    private static final Action ADD_CONNECTION_ACTION = new Action("Add Connection");
    private static final Action CONFIGURE_PARAMS_ACTION = new Action("Configure Parameters");
    private static final Action EDIT_CONNECTION_ACTION = new Action("Edit Connection");
    private static final Action DELETE_ACTION = new Action("Delete");
    
    @Override
    public void build(final MyBeanItem<ModuleConfig> beanItem, final IProcessModule<?> module)
    {
        super.build(beanItem, module);
        
        // inputs control section
        if (!module.getInputDescriptors().isEmpty())
        {
            // title
            addComponent(new Spacing());
            HorizontalLayout titleBar = new HorizontalLayout();
            titleBar.setSpacing(true);
            Label sectionLabel = new Label("Process Inputs");
            sectionLabel.addStyleName(STYLE_H3);
            sectionLabel.addStyleName(STYLE_COLORED);
            titleBar.addComponent(sectionLabel);
            titleBar.setComponentAlignment(sectionLabel, Alignment.MIDDLE_LEFT);
            titleBar.setHeight(31.0f, Unit.PIXELS);
            addComponent(titleBar);

            // control panels
            buildControlInputsPanels(module);
        }
        
        // params control section
        if (!module.getParameterDescriptors().isEmpty())
        {
            // title
            addComponent(new Spacing());
            HorizontalLayout titleBar = new HorizontalLayout();
            titleBar.setSpacing(true);
            Label sectionLabel = new Label("Process Parameters");
            sectionLabel.addStyleName(STYLE_H3);
            sectionLabel.addStyleName(STYLE_COLORED);
            titleBar.addComponent(sectionLabel);
            titleBar.setComponentAlignment(sectionLabel, Alignment.MIDDLE_LEFT);
            titleBar.setHeight(31.0f, Unit.PIXELS);
            addComponent(titleBar);

            // control panels
            buildParamInputsPanels(module);
        }
        
        // process flow section
        if (module instanceof SMLProcessImpl)
        {
            addProcessEditor();
            this.config = (SMLProcessConfig)beanItem.getBean();
            this.configBean = beanItem;

            var tabs = this.configTabs.getComponentIterator();
            GenericConfigForm configForm = null;
            while (tabs.hasNext()) {
                var tab = tabs.next();
                if (tab instanceof GenericConfigForm) {
                    configForm = (GenericConfigForm) tab;
                    break;
                }
            }

            if (configForm != null) {
                // SensorML path is the last text box in this config form
                this.smlPathTextField = (com.vaadin.v7.ui.TextField) configForm.textBoxes.get(configForm.textBoxes.size()-1);
            }
            var process = getProcessChainFromFile();
            if (process == null)
                process = new AggregateProcessImpl();
            process.setUniqueIdentifier(config.id);
            this.aggregateProcess = process;
        }
    }
    
    
    protected void buildControlInputsPanels(IProcessModule<?> module)
    {
        if (module != null)
        {
            Panel oldPanel;
            
            // command inputs
            oldPanel = inputCommandsPanel;
            inputCommandsPanel = newPanel(null);
            for (IStreamingControlInterface input: module.getCommandInputs().values())
            {
                Component sweForm = new SWEControlForm(input);
                ((Layout)inputCommandsPanel.getContent()).addComponent(sweForm);
            }

            if (oldPanel != null)
                replaceComponent(oldPanel, inputCommandsPanel);
            else
                addComponent(inputCommandsPanel);
        }
    }
    
    
    protected void buildParamInputsPanels(IProcessModule<?> module)
    {
        if (module != null)
        {
            Panel oldPanel;

            // command inputs
            oldPanel = paramCommandsPanel;
            paramCommandsPanel = newPanel(null);

            // wrap all parameters into a single datarecord so we can submit them together
            DataRecordImpl params = new DataRecordImpl();
            params.setName("Parameters");
            for (DataComponent param: module.getParameterDescriptors().values())
                params.addComponent(param.getName(), param);
            params.combineDataBlocks();

            Component sweForm = new SWEControlForm(params);
            ((Layout)paramCommandsPanel.getContent()).addComponent(sweForm);

            if (oldPanel != null)
                replaceComponent(oldPanel, paramCommandsPanel);
            else
                addComponent(paramCommandsPanel);
        }
    }
        
        
    protected void addProcessEditor()
    {
        HorizontalLayout buttonBar = new HorizontalLayout();
        buttonBar.setSpacing(true);
        addComponent(buttonBar);

        // add process button
        Button addProcessBtn = new Button("Save Process", FontAwesome.SAVE);
        addProcessBtn.addStyleName(STYLE_SMALL);
        buttonBar.addComponent(addProcessBtn);
        addProcessBtn.addClickListener( event -> {
            saveProcessChain();
        });

        Button addLoadProcessBtn = new Button("Load Process", FontAwesome.UPLOAD);
        addLoadProcessBtn.addStyleName(STYLE_SMALL);
        buttonBar.addComponent(addLoadProcessBtn);
        addLoadProcessBtn.addClickListener( event -> {
            var aggy = getProcessChainFromFile();
            if (aggy == null)
                getOshLogger().error("Error loading process chain, file name is null");
            this.aggregateProcess = aggy;
            refreshTable();
        });

        buildProcessTable();
        addComponent(processTable);
    }
    
    protected void addDataSource(final String name, final String producerURI, String outputName) {
        DataStreamSource dataSource = new DataStreamSource();
        SimpleProcess process = (SimpleProcess) SMLUtils.wrapWithProcessDescription(dataSource);
        Settings paramSettings = new SettingsImpl();
        paramSettings.addSetValue(new ValueSettingImpl("parameters/systemUID", producerURI));
        process.setConfiguration(paramSettings);

        CodeWithAuthorityImpl codeWithAuthority = new CodeWithAuthorityImpl();
        codeWithAuthority.setValue(process.getName());
        process.addName(codeWithAuthority);

        aggregateProcess.addComponent(name, process);

        if (outputName.contains("/")) {
            outputName = outputName.substring(0, outputName.indexOf("/"));
        }

        for (var param : dataSource.getParameterList())
            ((DataComponent) param).assignNewDataBlock();
        dataSource.getParameterList()
                .getComponent(DataStreamSource.SYSTEM_UID_PARAM)
                .getData()
                .setStringValue(producerURI);
        dataSource.getParameterList()
                .getComponent(DataStreamSource.OUTPUT_NAME_PARAM)
                .getData()
                .setStringValue(outputName);
        dataSource.setParentHub(getParentHub());
        dataSource.notifyParamChange();
    }


    protected void addCommandSink(final String name, final String consumerURI, final String inputName) {
        CommandStreamSink sink = new CommandStreamSink();
        for (var param : sink.getParameterList())
            ((DataComponent) param).assignNewDataBlock();
        sink.getParameterList()
                .getComponent(CommandStreamSink.SYSTEM_UID_PARAM)
                .getData()
                .setStringValue(consumerURI);
        sink.getParameterList()
                .getComponent(CommandStreamSink.OUTPUT_NAME_PARAM)
                .getData()
                .setStringValue(inputName);
        sink.setParentHub(getParentHub());
        sink.notifyParamChange();

        var process = SMLUtils.wrapWithProcessDescription(sink);
        CodeWithAuthorityImpl codeWithAuthority = new CodeWithAuthorityImpl();
        codeWithAuthority.setValue(process.getName());
        process.addName(codeWithAuthority);

        aggregateProcess.addComponent(name, process);
    }

    protected void addProcess(final String name, final ProcessInfo processInfo) {
        try {
            var process = SMLUtils.wrapWithProcessDescription(processInfo.getImplementationClass().newInstance());

            CodeWithAuthorityImpl codeWithAuthority = new CodeWithAuthorityImpl();
            codeWithAuthority.setValue(process.getName());
            process.addName(codeWithAuthority);

            aggregateProcess.addComponent(name, process);
        } catch (InstantiationException | IllegalAccessException e) {
            DisplayUtils.showErrorPopup("Error instantiating process implementation", e);
        }
    }

    protected void addConnection(final String src, final String dest) {
        aggregateProcess.addConnection(new LinkImpl(src, dest));
    }

    protected void addInput(DataComponent component) {
        component.renewDataBlock();
        aggregateProcess.addInput(component.getName(), component);
    }

    protected void addOutput(DataComponent component) {
        component.renewDataBlock();
        aggregateProcess.addOutput(component.getName(), component);
    }

    protected void addParameter(DataComponent component) {
        component.renewDataBlock();
        aggregateProcess.addParameter(component.getName(), component);
    }

    protected void buildProcessTable() {
        // create table to display process flow
        TreeTable table = new TreeTable("Aggregate Process");
        table.setWidth(100, Unit.PERCENTAGE);
        table.addStyleName(STYLE_SMALL);
        table.setSelectable(true);
        processTable = table;

        // TODO: Set values from aggregateProcess
        // TODO: Add columns for inputs, outputs, parameters, components, connections
        table.addContainerProperty(PROP_NAME, String.class, "");
        table.addContainerProperty(PROP_VALUE, String.class, "");

        addBaseItem(PROP_INPUTS, "0");
        addBaseItem(PROP_OUTPUTS, "0");
        addBaseItem(PROP_PARAMS, "0");
        addBaseItem(PROP_COMPS, "0");
        addBaseItem(PROP_CONNS, "0");

        table.addActionHandler(new Action.Handler() {
            @Override
            public Action[] getActions(Object target, Object sender) {
                List<Action> actions = new ArrayList<>();

                if (target == null)
                    return actions.toArray(new Action[0]);

                if (target == PROP_INPUTS || target == PROP_OUTPUTS) {
                    actions.add(ADD_IO_ACTION);
                } else if (target == PROP_PARAMS) {
                    actions.add(ADD_PARAMETER_ACTION);
                } else if (target == PROP_COMPS) {
                    actions.add(ADD_COMPONENT_ACTION);
                } else if (target == PROP_CONNS) {
                    actions.add(ADD_CONNECTION_ACTION);
                } else {
                    String parentName = table.getItem(table.getParent(target)).getItemProperty(PROP_NAME).getValue().toString();
                    if (parentName.equals(PROP_COMPS))
                        actions.add(CONFIGURE_PARAMS_ACTION);
                    else if (parentName.equals(PROP_CONNS))
                        actions.add(EDIT_CONNECTION_ACTION);
                    actions.add(DELETE_ACTION);
                }

                return actions.toArray(new Action[0]);
            }

            @Override
            public void handleAction(Action action, Object sender, Object target) {
                String parentName;
                if (target != null)
                    parentName = table.getItem(target).getItemProperty(PROP_NAME).getValue().toString();
                else {
                    parentName = null;
                }

                if (action == ADD_COMPONENT_ACTION) {
                    // show popup to select among available module types
                    final ProcessSelectionCallback callback = (name, info) -> {
                        // If data source, show popup for selecting source system
                        if (info.equals(DataStreamSource.INFO)) {
                            SystemSelectionPopup popup = new SystemSelectionPopup(800, value -> {
                                String producerURI = (String) value;
                                var outputComponentPopup = new SystemIOSelectionPopup(
                                        recordStructure -> {
                                            // TODO: Use full path for name in case user selects nested component. then we need to resolve parent as output name
                                            addDataSource(name, producerURI, SWEHelper.getComponentPath(((DataComponent) recordStructure)));
                                            refreshTable();
                                        },
                                        getParentHub().getDatabaseRegistry().getFederatedDatabase(),
                                        producerURI
                                );

                                outputComponentPopup.setModal(true);
                                getUI().addWindow(outputComponentPopup);
                            }, getParentHub().getDatabaseRegistry().getFederatedDatabase());
                            popup.setModal(true);
                            getUI().addWindow(popup);
                            // If command, show system and IO popups
                        } else if (info.equals(CommandStreamSink.INFO)) {
                            SystemSelectionPopup popup = new SystemSelectionPopup(800, value -> {
                                String consumerURI = (String) value;
                                var inputComponentPopup = new SystemIOSelectionPopup(
                                        recordStructure -> {
                                            addCommandSink(name, consumerURI, ((DataComponent) recordStructure).getName());
                                            refreshTable();
                                        },
                                        getParentHub().getDatabaseRegistry().getFederatedDatabase(),
                                        consumerURI
                                );
                                inputComponentPopup.setModal(true);
                                getUI().addWindow(inputComponentPopup);
                            }, getParentHub().getDatabaseRegistry().getFederatedDatabase());
                            popup.setModal(true);
                            getUI().addWindow(popup);
                        } else {
                            addProcess(name, info);
                        }
                        refreshTable();
                    };

                    // popup the list so the user can select what he wants
                    ProcessSelectionPopup popup = new ProcessSelectionPopup(getParentHub().getProcessingManager().getAllProcessingPackages(), callback);
                    popup.setModal(true);
                    getUI().addWindow(popup);
                } else if (action == CONFIGURE_PARAMS_ACTION) {
                    DataRecordImpl params = new DataRecordImpl();
                    params.setName("Parameters");
                    for (AbstractSWEIdentifiable param : aggregateProcess.getComponent(target.toString()).getParameterList()) {
                        DataComponent dataComponent = (DataComponent) param;
                        params.addComponent(dataComponent.getName(), dataComponent);
                    }
                    params.combineDataBlocks();

                    Window popup = new Window();
                    Component sweForm = new SWEControlForm(params, event -> {
                        popup.close();
                        refreshTable();
                    });
                    VerticalLayout layout = new VerticalLayout();
                    layout.addComponent(sweForm);
                    layout.setMargin(true);

                    popup.setContent(layout);
                    popup.setModal(true);

                    getUI().addWindow(popup);
                } else if (action == ADD_IO_ACTION) {
                    ValueEntryPopup.ValueCallback ioSelectionCallback = value -> {
                        if (parentName == null)
                            return;
                        if (Objects.equals(parentName, PROP_INPUTS)) {
                            addInput((DataComponent) value);
                        } else if (Objects.equals(parentName, PROP_OUTPUTS)) {
                            addOutput((DataComponent) value);
                        } else if (Objects.equals(parentName, PROP_PARAMS)) {
                            addParameter((DataComponent) value);
                        }
                        refreshTable();
                    };

                    SystemSelectionPopup systemSelectionPopup = new SystemSelectionPopup(800, value -> {
                        SystemIOSelectionPopup ioSelectionPopup = new SystemIOSelectionPopup(ioSelectionCallback, getParentHub().getDatabaseRegistry().getFederatedDatabase(), value.toString());
                        ioSelectionPopup.setModal(true);
                        getUI().addWindow(ioSelectionPopup);
                    }, getParentHub().getDatabaseRegistry().getFederatedDatabase());

                    systemSelectionPopup.setModal(true);

                    getUI().addWindow(systemSelectionPopup);
                } else if (action == ADD_PARAMETER_ACTION) {
                    ValueEntryPopup.ValueCallback ioSelectionCallback = value -> {
                        if (parentName == null)
                            return;
                        if (Objects.equals(parentName, PROP_PARAMS)) {
                            addParameter((DataComponent) value);
                        }
                        refreshTable();
                    };

                    SystemSelectionPopup systemSelectionPopup = new SystemSelectionPopup(800, value -> {
                        SystemIOSelectionPopup ioSelectionPopup = new SystemIOSelectionPopup(ioSelectionCallback, getParentHub().getDatabaseRegistry().getFederatedDatabase(), value.toString());
                        ioSelectionPopup.setModal(true);
                        getUI().addWindow(ioSelectionPopup);
                    }, getParentHub().getDatabaseRegistry().getFederatedDatabase());

                    systemSelectionPopup.setModal(true);

                    getUI().addWindow(systemSelectionPopup);
                } else if (action == ADD_CONNECTION_ACTION) {
                    Window popup = new Window();
                    popup.setWidth(300, Unit.PIXELS);
                    VerticalLayout layout = new VerticalLayout();
                    layout.setMargin(true);

                    ComboBox source = new ComboBox("Source");
                    source.setWidth(100, Unit.PERCENTAGE);
                    ComboBox destination = new ComboBox("Destination");
                    destination.setWidth(100, Unit.PERCENTAGE);

                    List<String> possibleConnectionItems = getPossibleConnectionItems(aggregateProcess);

                    source.addItems(possibleConnectionItems);
                    destination.addItems(possibleConnectionItems);

                    Button okButton = new Button("OK");
                    okButton.addClickListener(e -> {
                        addConnection(source.getValue().toString(), destination.getValue().toString());
                        popup.close();
                        refreshTable();
                    });

                    layout.addComponents(source, destination, okButton);

                    popup.setModal(true);
                    popup.setContent(layout);
                    popup.center();
                    getUI().addWindow(popup);
                } else if (action == EDIT_CONNECTION_ACTION) {
                    String itemId = target.toString();

                    Link connectionToEdit = null;

                    int connectionIndexToRemove = -1;
                    for (int i = 0; i < aggregateProcess.getNumConnections(); i++) {
                        Link conn = aggregateProcess.getConnectionList().get(i);
                        String connId = conn.getSource().toString() + "-" + conn.getDestination().toString();

                        if (connId.equals(itemId)) {
                            connectionToEdit = conn;
                            connectionIndexToRemove = i;
                            break;
                        }
                    }


                    Window popup = new Window("Edit Connection");
                    popup.setWidth(300, Unit.PIXELS);
                    VerticalLayout layout = new VerticalLayout();
                    layout.setMargin(true);

                    ComboBox source = new ComboBox("Source");
                    source.setWidth(100, Unit.PERCENTAGE);
                    ComboBox destination = new ComboBox("Destination");
                    destination.setWidth(100, Unit.PERCENTAGE);

                    List<String> possibleConnectionItems = getPossibleConnectionItems(aggregateProcess);

                    source.addItems(possibleConnectionItems);
                    destination.addItems(possibleConnectionItems);

                    source.setValue(connectionToEdit.getSource());
                    destination.setValue(connectionToEdit.getDestination());

                    final int finalIndex = connectionIndexToRemove;
                    Button updateButton = new Button("Update");
                    updateButton.addClickListener(e -> {
                        aggregateProcess.getConnectionList().get(finalIndex).setSource(source.getValue().toString());
                        aggregateProcess.getConnectionList().get(finalIndex).setDestination(destination.getValue().toString());

                        popup.close();
                        refreshTable();
                    });

                    Button cancelButton = new Button("Cancel");
                    cancelButton.addClickListener(e -> popup.close());

                    HorizontalLayout buttonLayout = new HorizontalLayout(updateButton, cancelButton);
                    buttonLayout.setSpacing(true);
                    layout.addComponents(source, destination, buttonLayout);

                    popup.setModal(true);
                    popup.setContent(layout);
                    popup.center();
                    getUI().addWindow(popup);

                } else if (action == DELETE_ACTION) {
                    String itemId = target.toString();

                    Object parent = table.getParent(target);
                    String propName = table.getItem(parent).getItemProperty(PROP_NAME).getValue().toString();

                    switch (propName) {
                        case PROP_COMPS:
                            if (parentName.contains(itemId)) {
                                aggregateProcess.getComponentList().remove(itemId);
                                break;
                            }
                            break;
                        case PROP_PARAMS:
                            if (itemId.contains(parentName)) {
                                aggregateProcess.getParameterList().remove(parentName);
                                break;
                            }
                            break;
                        case PROP_CONNS:
                            int idToRemove = -1;
                            for (int i = 0; i < aggregateProcess.getNumConnections(); i++) {
                                Link conn = aggregateProcess.getConnectionList().get(i);
                                String connId = conn.getSource().toString() + "-" + conn.getDestination().toString();
                                if (itemId.equals(connId)) {
                                    idToRemove = i;
                                    break;
                                }
                            }

                            if (idToRemove >= 0) {
                                aggregateProcess.getConnectionList().remove(idToRemove);
                            }
                            break;
                        case PROP_INPUTS:
                            if (itemId.contains(parentName)) {
                                aggregateProcess.getInputList().remove(parentName);
                                break;
                            }
                            break;
                        case PROP_OUTPUTS:
                            if (itemId.contains(parentName)) {
                                aggregateProcess.getOutputList().remove(parentName);
                                break;
                            }
                            break;
                        default:
                            getOshLogger().warn("Unknown type, cannot delete item ", parentName);
                            break;
                    }

                    table.removeItem(itemId);
                }
                refreshTable();
            }
        });
    }

    private List<String> getPossibleConnectionItems(AbstractProcess process) {
        List<String> possibleConnectionItems = new ArrayList<>();

        collectFromIOList("inputs", process.getInputList(), possibleConnectionItems);
        collectFromIOList("outputs", process.getOutputList(), possibleConnectionItems);
        collectFromIOList("parameters", process.getParameterList(), possibleConnectionItems);

        if (process instanceof AggregateProcess aggregate) {
            OgcPropertyList<AbstractProcess> components = aggregate.getComponentList();
            for (int i = 0; i < aggregate.getNumComponents(); i++) {
                var component = components.getProperties().get(i);
                String prefix = "components/" + component.getName() + "/";
                List<String> possibleComponentPaths = getPossibleConnectionItems(component.getValue());
                possibleConnectionItems.addAll(possibleComponentPaths.stream()
                        .map(s -> prefix + s)
                        .toList());
            }
        }

        return possibleConnectionItems;
    }

    private void collectFromIOList(String prefix, IOPropertyList ioList, List<String> paths) {
        for (int i = 0; i < ioList.size(); i++) {
            DataComponent component = ioList.getComponent(i);
            String topLevelPath = prefix + "/" + component.getName();
            paths.add(topLevelPath);  // inputs/sensorLocation
            collectNestedPaths(topLevelPath, component, paths);
        }
    }

    private void collectNestedPaths(String parentPath, DataComponent component, List<String> paths) {
        for (int i = 0; i < component.getComponentCount(); i++) {
            DataComponent child = component.getComponent(i);
            String childPath = parentPath + "/" + child.getName();
            paths.add(childPath);
            collectNestedPaths(childPath, child, paths);
        }
    }

    private void addBaseItem(String name, String value) {
        // Add a new item to the process table
        Item newItem = processTable.addItem(name);
        newItem.getItemProperty(PROP_NAME).setValue(name);
        newItem.getItemProperty(PROP_VALUE).setValue(value);
        processTable.setChildrenAllowed(name, false);
    }

    private void addOrUpdateIOComponent(String itemId, DataComponent component) {
        Item item = processTable.addItem(itemId);
        if (item == null)
            item = processTable.getItem(itemId);
        item.getItemProperty(PROP_NAME).setValue(component.getName());
        String dataType = "UNKNOWN";
        try {
            dataType = component.getData().getDataType().toString();
        } catch (Exception e) {}
        item.getItemProperty(PROP_VALUE).setValue(dataType);
    }

    protected void refreshTable() {
        try {
            // TODO: Refresh the process table with the current state of the aggregate process
            // This should update the table with the current inputs, outputs, parameters, components, and connections
            if (aggregateProcess == null)
                return;

            processTable.removeAllItems();
            addBaseItem(PROP_INPUTS, "0");
            addBaseItem(PROP_OUTPUTS, "0");
            addBaseItem(PROP_PARAMS, "0");
            addBaseItem(PROP_COMPS, "0");
            addBaseItem(PROP_CONNS, "0");

            if (aggregateProcess.getNumInputs() >= 0) {
                if (aggregateProcess.getNumInputs() > 0)
                    processTable.setChildrenAllowed(PROP_INPUTS, true);
                processTable.getItem(PROP_INPUTS).getItemProperty(PROP_VALUE).setValue(String.valueOf(aggregateProcess.getNumInputs()));
                for (int i = 0; i < aggregateProcess.getNumInputs(); i++) {
                    DataComponent input = aggregateProcess.getInputList().getComponent(i);
                    var itemId = "inputs/" + SWEHelper.getComponentPath(input);
                    addOrUpdateIOComponent(itemId, input);
                    processTable.setParent(itemId, PROP_INPUTS);
                    processTable.setChildrenAllowed(itemId, false);
                }
            }
            if (aggregateProcess.getNumOutputs() >= 0) {
                if (aggregateProcess.getNumOutputs() > 0)
                    processTable.setChildrenAllowed(PROP_OUTPUTS, true);
                processTable.getItem(PROP_OUTPUTS).getItemProperty(PROP_VALUE).setValue(String.valueOf(aggregateProcess.getNumOutputs()));
                for (int i = 0; i < aggregateProcess.getNumOutputs(); i++) {
                    DataComponent output = aggregateProcess.getOutputList().getComponent(i);
                    var itemId = "outputs/" + SWEHelper.getComponentPath(output);
                    addOrUpdateIOComponent(itemId, output);
                    processTable.setParent(itemId, PROP_OUTPUTS);
                    processTable.setChildrenAllowed(itemId, false);
                }
            }
            if (aggregateProcess.getNumParameters() >= 0) {
                if (aggregateProcess.getNumParameters() > 0)
                    processTable.setChildrenAllowed(PROP_PARAMS, true);
                processTable.getItem(PROP_PARAMS).getItemProperty(PROP_VALUE).setValue(String.valueOf(aggregateProcess.getNumParameters()));
                for (int i = 0; i < aggregateProcess.getNumParameters(); i++) {
                    DataComponent param = aggregateProcess.getParameterList().getComponent(i);
                    var itemId = "parameters/" + SWEHelper.getComponentPath(param);
                    addOrUpdateIOComponent(itemId, param);
                    processTable.setParent(itemId, PROP_PARAMS);
                    processTable.setChildrenAllowed(itemId, false);
                }
            }
            if (aggregateProcess.getNumComponents() >= 0) {
                if (aggregateProcess.getNumComponents() > 0)
                    processTable.setChildrenAllowed(PROP_COMPS, true);
                processTable.getItem(PROP_COMPS).getItemProperty(PROP_VALUE).setValue(String.valueOf(aggregateProcess.getNumComponents()));
                OgcPropertyList<AbstractProcess> comps =  aggregateProcess.getComponentList();
                for (int i = 0; i < aggregateProcess.getNumComponents(); i++) {
                    OgcProperty<AbstractProcess> component = comps.getProperties().get(i);
                    AbstractProcess process = component.getValue();
                    Item item = processTable.addItem(component.getName());
                    if (item == null)
                        item = processTable.getItem(component.getName());
                    item.getItemProperty(PROP_NAME).setValue(process.getName() + " (" + component.getName() + ")");
                    if (Objects.equals(process.getTypeOf().getHref(), DataStreamSource.INFO.getUri())) {
                        // If data source, set value to the source UID
                        item.getItemProperty(PROP_VALUE).setValue(((DataComponent)process.getParameter(DataStreamSource.SYSTEM_UID_PARAM)).getData().getStringValue());
                    } else if (Objects.equals(process.getTypeOf().getHref(), CommandStreamSink.INFO.getUri())) {
                        // If data source, set value to the target UID / inputName
                        item.getItemProperty(PROP_VALUE).setValue(
                                ((DataComponent)process.getParameter(CommandStreamSink.SYSTEM_UID_PARAM)).getData().getStringValue() + "#"
                                        + ((DataComponent)process.getParameter(CommandStreamSink.OUTPUT_NAME_PARAM)).getData().getStringValue());
                    } else {
                        StringBuilder paramsList = new StringBuilder();
                        for (int paramIndex = 0; paramIndex < process.getParameterList().size(); paramIndex++) {
                            DataComponent param = process.getParameterList().getComponent(paramIndex);
                            paramsList.append(param.getName());
                            if (paramIndex != process.getParameterList().size() - 1)
                                paramsList.append(", ");
                        }
                        item.getItemProperty(PROP_VALUE).setValue(paramsList.toString());
                    }
                    processTable.setParent(component.getName(), PROP_COMPS);
                    processTable.setChildrenAllowed(component.getName(), false);
                }
            }
            if (aggregateProcess.getNumConnections() >= 0) {
                if (aggregateProcess.getNumConnections() > 0)
                    processTable.setChildrenAllowed(PROP_CONNS, true);
                processTable.getItem(PROP_CONNS).getItemProperty(PROP_VALUE).setValue(String.valueOf(aggregateProcess.getNumConnections()));
                for (int i = 0; i < aggregateProcess.getNumConnections(); i++) {
                    Link connection = aggregateProcess.getConnectionList().get(i);
                    String itemId = connection.getSource() + "-" + connection.getDestination();
                    Item item = processTable.addItem(itemId);
                    if (item == null)
                        item = processTable.getItem(itemId);
                    item.getItemProperty(PROP_NAME).setValue("Source Component: " + connection.getSource());
                    item.getItemProperty(PROP_VALUE).setValue("Destination Component: " + connection.getDestination());
                    processTable.setParent(itemId, PROP_CONNS);
                    processTable.setChildrenAllowed(itemId, false);
                }
            }
        } catch (Exception e) {
            getOshLogger().error("Error refreshing table", e);
        }
    }

    protected AggregateProcess getProcessChainFromFile() {
        // Read process as SensorML file if exists
        String smlPath = config.getSensorMLPath();
        if (!FileUtils.isSafeFilePath(smlPath))
            return null;

        AggregateProcess process;
        try (InputStream is = new FileInputStream(smlPath)) {
            process = (AggregateProcess) new SMLUtils(SMLUtils.V2_0).readProcess(is);
        } catch (Exception e) {
            process = null;
        }
        return process;
    }
    
    protected void saveProcessChain()
    {

        // save SensorML file
        AtomicReference<String> smlPath = new AtomicReference<>(config.getSensorMLPath());
        if (!FileUtils.isSafeFilePath(smlPath.get())) {
            Window popup = new Window();
            popup.setWidth(300, Unit.PIXELS);
            VerticalLayout layout = new VerticalLayout();
            layout.setMargin(true);

            TextField newFilePath = new TextField("SensorML File path");
            newFilePath.setWidth(100, Unit.PERCENTAGE);

            Button okButton = new Button("OK");
            okButton.addClickListener(event -> {
                smlPath.set(newFilePath.getValue());

                writeProcess(smlPath.get());

                popup.close();
            });

            layout.addComponents(newFilePath, okButton);

            popup.setModal(true);
            popup.setContent(layout);
            popup.center();
            getUI().addWindow(popup);
        } else {
            writeProcess(smlPath.get());
        }
    }

    private void writeProcess(String path)
    {
        // Clear component I/O in case process implementation needs to use params to add I/O. Also, not needed in XML
        for (var component : aggregateProcess.getComponentList())
        {
            component.getOutputList().clear();
            component.getInputList().clear();
        }

        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(path)))
        {
            new SMLUtils(SMLUtils.V2_0).writeProcess(os, aggregateProcess, true);
            DisplayUtils.showOperationSuccessful("Saved SensorML description to " + path, 1000);

            if (this.smlPathTextField != null)
                this.smlPathTextField.setValue(path);
        }
        catch (Exception e)
        {
            DisplayUtils.showErrorPopup(String.format("Cannot write SensorML description at '%s'", path), e);
        }
    }

}
