package org.sensorhub.ui;

import com.vaadin.ui.Component;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.v7.data.Item;
import com.vaadin.v7.ui.TreeTable;
import com.vaadin.v7.event.ItemClickEvent.ItemClickListener;
import net.opengis.swe.v20.DataComponent;
import org.sensorhub.api.database.IObsSystemDatabase;
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

        DataStreamFilter filter = new DataStreamFilter.Builder()
                .withSystems(new SystemFilter.Builder().withUniqueIDs(systemUID).build())
                .build();

        db.getDataStreamStore().select(filter)
                .forEach(ds -> {
                    String itemId = ds.getFullName();
                    Item item = table.addItem(itemId);
                    table.setChildrenAllowed(itemId, false);

                    if (item != null) {
                        DataComponent recordStructure = ds.getRecordStructure();
                        item.getItemProperty(PROP_NAME).setValue(ds.getOutputName());
                        item.getItemProperty(PROP_DESCRIPTION).setValue(recordStructure.getDescription());
                        item.getItemProperty(PROP_STRUCT).setValue(recordStructure);
                    }
                });
    }
}
