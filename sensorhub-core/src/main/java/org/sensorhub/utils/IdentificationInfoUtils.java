/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2026 GeoRobotix Innovative Research, LLC. All Rights Reserved.

 ******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.utils;

import net.opengis.gml.v32.impl.ReferenceImpl;
import net.opengis.sensorml.v20.AbstractProcess;
import net.opengis.sensorml.v20.impl.ContactListImpl;
import org.isotc211.v2005.gco.impl.CodeListValueImpl;
import org.isotc211.v2005.gmd.impl.CIAddressImpl;
import org.isotc211.v2005.gmd.impl.CIContactImpl;
import org.isotc211.v2005.gmd.impl.CIOnlineResourceImpl;
import org.isotc211.v2005.gmd.impl.CIResponsiblePartyImpl;
import org.isotc211.v2005.gmd.impl.CITelephoneImpl;
import org.sensorhub.api.sensor.IdentificationInfo;
import org.sensorhub.api.utils.OshAsserts;


/** Applies configured identification overrides to SensorML descriptions. */
public final class IdentificationInfoUtils
{
    private IdentificationInfoUtils()
    {
    }


    public static void apply(IdentificationInfo info, AbstractProcess description)
    {
        if (info == null)
            return;

        OshAsserts.checkValidUID(info.systemUID);
        description.setUniqueIdentifier(info.systemUID);
        if (info.systemKindUID != null && !info.systemKindUID.isBlank())
        {
            OshAsserts.checkValidUID(info.systemKindUID);
            description.setTypeOf(new ReferenceImpl(info.systemKindUID));
        }

        if (info.responsibleParties == null || info.responsibleParties.isEmpty())
            return;

        var contacts = new ContactListImpl();
        for (var party : info.responsibleParties)
        {
            var contact = new CIResponsiblePartyImpl();
            contact.setIndividualName(party.getIndividualName());
            contact.setOrganisationName(party.getOrganizationName());
            contact.setPositionName(party.getPositionName());
            if (party.getRole() != null && !party.getRole().isBlank())
            {
                var role = new CodeListValueImpl(party.getRole());
                var codeList = party.getRoleCodeList();
                role.setCodeList(codeList == null || codeList.isBlank()
                    ? "http://www.isotc211.org/2005/resources/codeList.xml#CI_RoleCode"
                    : codeList);
                role.setCodeListValue(party.getRole());
                contact.setRole(role);
            }

            if (party.hasContactInfo())
            {
                var details = new CIContactImpl();
                contact.setContactInfo(details);
                details.setHoursOfService(party.getHoursOfService());
                details.setContactInstructions(party.getContactInstructions());
                if (party.hasAddress())
                {
                    var address = new CIAddressImpl();
                    address.getDeliveryPointList().addAll(party.getDeliveryPoints());
                    address.setCity(party.getCity());
                    address.setAdministrativeArea(party.getAdministrativeArea());
                    address.setPostalCode(party.getPostalCode());
                    address.setCountry(party.getCountry());
                    address.getElectronicMailAddressList().addAll(party.getEmails());
                    details.setAddress(address);
                }
                if (!party.getVoiceNumbers().isEmpty() || !party.getFaxNumbers().isEmpty())
                {
                    var phone = new CITelephoneImpl();
                    phone.getVoiceList().addAll(party.getVoiceNumbers());
                    phone.getFacsimileList().addAll(party.getFaxNumbers());
                    details.setPhone(phone);
                }
                if (party.getWebsite() != null)
                {
                    var website = new CIOnlineResourceImpl();
                    website.setLinkage(party.getWebsite());
                    details.setOnlineResource(website);
                }
            }
            contacts.addContact(contact);
        }
        description.getContactsList().clear();
        description.addContacts(contacts);
    }
}
