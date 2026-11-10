/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2026 GeoRobotix Innovative Research, LLC. All Rights Reserved.

 ******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.ui.data;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;
import org.vast.util.ResponsibleParty;

public class TestResponsiblePartyProperties
{
    @Test
    @SuppressWarnings("unchecked")
    public void contactListsAreEditableAndCommitted()
    {
        var party = new ResponsibleParty();
        var lists = List.of(party.getEmails(), party.getVoiceNumbers(),
            party.getFaxNumbers(), party.getDeliveryPoints());
        var names = List.of("emails", "voiceNumbers", "faxNumbers", "deliveryPoints");
        lists.forEach(list -> list.add("old"));
        var item = new MyBeanItem<>(party, "contact.");
        for (int i = 0; i < names.size(); i++)
        {
            var property = item.getItemProperty("contact." + names.get(i));
            assertTrue(property instanceof ContainerProperty);
            var listProperty = (ContainerProperty)property;
            var container = listProperty.getValue();
            assertEquals(String.class, container.getBeanType());
            assertEquals(1, container.size());
            container.removeAllItems();
            container.addBean("first");
            container.addBean("second");
            listProperty.setValue(container);
            assertEquals(List.of("first", "second"), lists.get(i));
        }
        assertNotNull(item.getItemProperty("contact.organizationName"));
    }
}
