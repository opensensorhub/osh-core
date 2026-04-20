/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2026 GeoRobotix Innovative Research, LLC. All Rights Reserved.

 ******************************* END LICENSE BLOCK ***************************/

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
