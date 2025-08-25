package org.sensorhub.ui;

import com.vaadin.ui.Button;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.ui.Window;
import net.opengis.swe.v20.DataComponent;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.ui.api.UIConstants;

public class SystemIOSelectionPopup extends Window {

    DataComponent dataComponent;

    public SystemIOSelectionPopup(final ValueEntryPopup.ValueCallback callback, IObsSystemDatabase db, String systemUID) {
        super("Select an Input/Output Component");
        VerticalLayout layout = new VerticalLayout();
        layout.setMargin(true);

        var ioTable = new SystemIOList(db, systemUID, event ->
                dataComponent = (DataComponent) event.getItem().getItemProperty(SystemIOList.PROP_STRUCT).getValue());
        layout.addComponent(ioTable);

        Button okButton = new Button("OK");
        okButton.addStyleName(UIConstants.STYLE_SMALL);
        okButton.addClickListener( e -> {
            if (dataComponent == null)
                DisplayUtils.showErrorPopup("Please select an IO component!", new IllegalArgumentException());
           callback.newValue(dataComponent);
           close();
        });
        layout.addComponent(okButton);
        setContent(layout);
        center();
    }

}
