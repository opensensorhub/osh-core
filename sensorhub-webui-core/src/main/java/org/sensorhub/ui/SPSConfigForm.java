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

import java.util.LinkedHashMap;
import java.util.Map;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.sensor.ISensorModule;
import org.sensorhub.impl.SensorHub;
import org.sensorhub.impl.service.sps.SPSConnectorConfig;
import org.sensorhub.impl.service.sps.SPSServiceConfig;
import org.sensorhub.impl.service.sps.SensorConnectorConfig;
import org.sensorhub.ui.data.BaseProperty;
import org.sensorhub.ui.data.MyBeanItem;
import com.vaadin.data.Property;
import com.vaadin.server.ExternalResource;
import com.vaadin.ui.Link;


@SuppressWarnings("serial")
public class SPSConfigForm extends GenericConfigForm
{
    protected static final String SPS_PACKAGE = "org.sensorhub.impl.service.sps.";
    protected static final String PROP_CONNECTORS = "connectors";
    protected static final String PROP_ENDPOINT = "endPoint";
    
    
    @Override
    public void build(String title, String popupText, MyBeanItem<? extends Object> beanItem, boolean includeSubForms)
    {
        super.build(title, popupText, beanItem, includeSubForms);
        
        // add link to capabilities
        Property<?> endPointProp = beanItem.getItemProperty(PROP_ENDPOINT);
        if (endPointProp != null)
        {
            String baseUrl = (String)endPointProp.getValue();
            if (baseUrl != null)
            {
                baseUrl = baseUrl.substring(1);
                String href = baseUrl + "?service=SPS&version=2.0&request=GetCapabilities";
                Link link = new Link("Link to capabilities", new ExternalResource(href), "_blank", 0, 0, null);
                this.addComponent(link);
            }
        }
        
        // add links to DescribeTasking
        SPSServiceConfig spsConf = (SPSServiceConfig)beanItem.getBean();
        if (spsConf.endPoint != null)
        {
            String baseUrl = spsConf.endPoint.substring(1);
            for (SPSConnectorConfig conf: spsConf.connectors)
            {
                if (conf instanceof SensorConnectorConfig)
                {
                    String sensorID = ((SensorConnectorConfig) conf).sensorID;
                    if (sensorID != null)
                    {
                        try
                        {
                            ISensorModule<?> sensor = SensorHub.getInstance().getSensorManager().getModuleById(sensorID);
                            String uid = sensor.getUniqueIdentifier();
                            String name = sensor.getName();
                            String href = baseUrl + "?service=SPS&version=2.0&request=DescribeTasking&procedure=" + uid;
                            Link link = new Link("DescribeTasking for " + name, new ExternalResource(href), "_blank", 0, 0, null);
                            this.addComponent(link);
                        }
                        catch (SensorHubException e)
                        {
                            AdminUIModule.getInstance().getLogger().error("Cannot add SPS link", e);
                        }
                    }
                }
            }
        }
    }


    @Override
    public Map<String, Class<?>> getPossibleTypes(String propId, BaseProperty<?> prop)
    {
        if (propId.equals(PROP_CONNECTORS))
        {
            Map<String, Class<?>> classList = new LinkedHashMap<>();
            try
            {
                classList.put("Sensor Connector", Class.forName(SPS_PACKAGE + "SensorConnectorConfig"));
                classList.put("Stream Process Connector", Class.forName(SPS_PACKAGE + "StreamProcessConnectorConfig"));             
            }
            catch (ClassNotFoundException e)
            {
                AdminUIModule.getInstance().getLogger().error("Cannot find SPS connector class", e);
            }
            return classList;
        }
        
        return super.getPossibleTypes(propId, prop);
    }
}
