package org.sensorhub.ui;

import com.vaadin.ui.Component;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.v7.data.Item;
import com.vaadin.v7.ui.TreeTable;
import com.vaadin.v7.event.ItemClickEvent.ItemClickListener;
import net.opengis.swe.v20.DataComponent;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.command.CommandStreamFilter;
import org.sensorhub.api.datastore.obs.DataStreamFilter;
import org.sensorhub.api.datastore.system.SystemFilter;
import org.sensorhub.ui.api.UIConstants;

public class SystemIOList extends VerticalLayout {

    static final String PROP_NAME = "name";
    static final String PROP_DESCRIPTION = "description";
    static final String PROP_STRUCT = "recordStructure";

    TreeTable table;

    public SystemIOList(final IObsSystemDatabase db, final String systemUID, final ItemClickListener selectionListener) {
        setMargin(false);

        addComponent(buildIOTable(selectionListener));

        updateTable(db, systemUID);
    }

    protected Component buildIOTable(final ItemClickListener selectionListener) {
        table = new TreeTable();
        table.setWidth(100, Unit.PERCENTAGE);
        table.setSelectable(true);
        table.addStyleName(UIConstants.STYLE_SMALL);
        table.addContainerProperty(PROP_NAME, String.class, null);
        table.addContainerProperty(PROP_DESCRIPTION, String.class, null);
        table.addContainerProperty(PROP_STRUCT, DataComponent.class, null);
        table.setVisibleColumns(PROP_NAME, PROP_DESCRIPTION);

        table.addItemClickListener(selectionListener);

        return table;
    }

    protected void updateTable(final IObsSystemDatabase db, final String systemUID) {
        table.removeAllItems();

        DataStreamFilter dsFilter = new DataStreamFilter.Builder()
                .withSystems(new SystemFilter.Builder().withUniqueIDs(systemUID).build())
                .build();

        // TODO Add nested IO also
        db.getDataStreamStore().select(dsFilter)
                .forEach(ds -> {
                    String itemId = ds.getFullName();
                    Item item = table.addItem(itemId);
                    table.setChildrenAllowed(itemId, true);

                    if (item != null) {
                        DataComponent recordStructure = ds.getRecordStructure();
                        item.getItemProperty(PROP_NAME).setValue(ds.getOutputName());
                        item.getItemProperty(PROP_DESCRIPTION).setValue(recordStructure.getDescription());
                        item.getItemProperty(PROP_STRUCT).setValue(recordStructure);

                        for (int i = 0; i < recordStructure.getComponentCount(); i++)
                        {
                            var child = recordStructure.getComponent(i);
                            String childId = itemId + "/" + child.getName();
                            Item childItem = table.addItem(childId);
                            table.setChildrenAllowed(childId, false);
                            childItem.getItemProperty(PROP_NAME).setValue(child.getName());
                            childItem.getItemProperty(PROP_DESCRIPTION).setValue(child.getDescription());
                            childItem.getItemProperty(PROP_STRUCT).setValue(child);
                            table.setParent(childId, itemId);
                        }
                    }
                });

        CommandStreamFilter csFilter = new CommandStreamFilter.Builder()
                .withSystems(new SystemFilter.Builder().withUniqueIDs(systemUID).build())
                .build();

        db.getCommandStreamStore().select(csFilter)
                .forEach(cs -> {
                    String itemId = cs.getFullName();
                    Item item = table.addItem(itemId);
                    table.setChildrenAllowed(itemId, true);

                    if (item != null) {
                        DataComponent recordStructure = cs.getRecordStructure();
                        item.getItemProperty(PROP_NAME).setValue(cs.getControlInputName());
                        item.getItemProperty(PROP_DESCRIPTION).setValue(recordStructure.getDescription());
                        item.getItemProperty(PROP_STRUCT).setValue(recordStructure);

                        for (int i = 0; i < recordStructure.getComponentCount(); i++)
                        {
                            var child = recordStructure.getComponent(i);
                            String childId = itemId + "/" + child.getName();
                            Item childItem = table.addItem(childId);
                            table.setChildrenAllowed(childId, false);
                            childItem.getItemProperty(PROP_NAME).setValue(child.getName());
                            childItem.getItemProperty(PROP_DESCRIPTION).setValue(child.getDescription());
                            childItem.getItemProperty(PROP_STRUCT).setValue(child);
                            table.setParent(childId, itemId);
                        }
                    }
                });
    }
}
