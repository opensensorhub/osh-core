/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2026 GeoRobotix Innovative Research, LLC. All Rights Reserved.

 ******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.sensor;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;
import org.sensorhub.api.sensor.IdentificationInfo;
import org.sensorhub.api.sensor.SensorConfig;
import org.vast.util.ResponsibleParty;

public class TestIdentificationInfo
{
    @Test
    public void overridesSurviveDescriptionRegeneration() throws Exception
    {
        var config = new SensorConfig();
        config.identificationInfo = new IdentificationInfo();
        config.identificationInfo.systemUID = "urn:test:configured";
        config.identificationInfo.systemKindUID = "urn:test:kind";
        var party = new ResponsibleParty();
        party.setOrganizationName("Operator");
        party.getEmails().addAll(List.of("first@example.org", "second@example.org"));
        party.getVoiceNumbers().addAll(List.of("123", "456"));
        party.setWebsite("https://example.org");
        config.identificationInfo.responsibleParties = List.of(party);

        var sensor = new FakeSensor();
        sensor.init(config);
        assertEquals(config.identificationInfo.systemUID, sensor.getUniqueIdentifier());
        assertFalse(sensor.randomUniqueID);
        for (int i = 0; i < 2; i++)
        {
            var description = sensor.getCurrentDescription();
            assertEquals(sensor.getUniqueIdentifier(), description.getUniqueIdentifier());
            assertEquals(config.identificationInfo.systemKindUID, description.getTypeOf().getHref());
            assertEquals(1, description.getContactsList().size());
            var contact = description.getContactsList().get(0).getContactList().get(0);
            assertEquals("Operator", contact.getOrganisationName());
            assertEquals(party.getEmails(), contact.getContactInfo().getAddress().getElectronicMailAddressList());
            assertEquals(party.getVoiceNumbers(), contact.getContactInfo().getPhone().getVoiceList());
            assertEquals(party.getWebsite(), contact.getContactInfo().getOnlineResource().getLinkage());
            sensor.updateSensorDescription();
        }
    }
    @Test
    public void rolesSerializeWithOptionalCodeList() throws Exception
    {
        for (String codeList : new String[] {null, "", "urn:test:roles"})
        {
            var info = new IdentificationInfo();
            info.systemUID = "urn:test:configured";
            var party = new ResponsibleParty();
            party.setRole("operator");
            party.setRoleCodeList(codeList);
            info.responsibleParties = List.of(party);
            var description = new org.vast.sensorML.PhysicalSystemImpl();
            org.sensorhub.utils.IdentificationInfoUtils.apply(info, description);

            var output = new java.io.StringWriter();
            var writer = new com.ctc.wstx.stax.WstxOutputFactory().createXMLStreamWriter(output);
            var bindings = new org.vast.sensorML.SMLStaxBindings();
            bindings.setNamespacePrefixes(writer);
            bindings.declareNamespacesOnRootElement();
            bindings.writeDescribedObject(writer, description);
            writer.close();
            assertTrue(output.toString().contains("codeListValue=\"operator\""));
            assertTrue(output.toString().contains(codeList == null || codeList.isBlank()
                ? "codeList.xml#CI_RoleCode" : codeList));
        }
    }
}
