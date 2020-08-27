/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.sos;

import java.util.ArrayList;
import java.util.List;
import org.sensorhub.api.config.DisplayInfo;
import org.sensorhub.api.persistence.StorageConfig;
import org.sensorhub.api.security.SecurityConfig;
import org.sensorhub.impl.service.ogc.OGCServiceConfig;
import org.sensorhub.impl.service.swe.OfferingList;


/**
 * <p>
 * Configuration class for the SOS service module
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since Sep 7, 2013
 */
public class SOSServiceConfig extends OGCServiceConfig
{
    
    @DisplayInfo(desc="Set to true to enable transactional operation support")
    public boolean enableTransactional = false;
    
    
    @DisplayInfo(label="Max Observations Returned", desc="Maximum number of observations returned by a historical GetObservation request (for each selected offering)")
    public int maxObsCount = 100;
    
    
    @DisplayInfo(label="Max Records Returned", desc="Maximum number of result records returned by a historical GetResult request")
    public int maxRecordCount = 100000;
    
    
    @DisplayInfo(desc="Storage configuration to use for newly registered sensors")
    public StorageConfig newStorageConfig;
    
    
    @DisplayInfo(desc="Default live time-out for new offerings created via SOS-T")
    public double defaultLiveTimeout = 600.0;
    
    
    @DisplayInfo(label="Offerings", desc="Configuration of data providers for SOS offerings")
    public OfferingList<SOSProviderConfig> dataProviders = new OfferingList<>();
    
    
    @DisplayInfo(desc="Configuration of data consumers for SOS offerings created by SOS-T")
    public OfferingList<SOSConsumerConfig> dataConsumers = new OfferingList<>();
    
    
    @DisplayInfo(desc="Mapping of custom formats mime-types to custom serializer classes")
    public List<SOSCustomFormatConfig> customFormats = new ArrayList<>();
    
    
    @DisplayInfo(desc="Security related options")
    public SecurityConfig security = new SecurityConfig();
    
    
    public SOSServiceConfig()
    {
        this.moduleClass = SOSService.class.getCanonicalName();
        this.endPoint = "/sos";
    }
}
