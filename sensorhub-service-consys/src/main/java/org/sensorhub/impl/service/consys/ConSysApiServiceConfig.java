/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys;

import java.util.ArrayList;
import java.util.List;
import org.sensorhub.api.config.DisplayInfo;
import org.sensorhub.api.config.DisplayInfo.FieldType;
import org.sensorhub.api.config.DisplayInfo.ModuleType;
import org.sensorhub.api.config.DisplayInfo.FieldType.Type;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.security.SecurityConfig;
import org.sensorhub.impl.datastore.view.ObsSystemDatabaseViewConfig;
import org.sensorhub.impl.service.ogc.OGCServiceConfig;
import org.sensorhub.impl.system.VirtualSystemGroupConfig;


/**
 * <p>
 * Configuration class for the SWE API service module
 * </p>
 *
 * @author Alex Robin
 * @since Oct 12, 2020
 */
public class ConSysApiServiceConfig extends OGCServiceConfig
{
    @DisplayInfo(desc="Metadata of system group that will be created to contain all systems "
        + "registered through this service. Only systems in this group will be modifiable by this service")
    public VirtualSystemGroupConfig virtualSystemGroup = null;
    
    
    @FieldType(Type.MODULE_ID)
    @ModuleType(IObsSystemDatabase.class)
    @DisplayInfo(label="Database ID", desc="ID of database module used for persisting data received by this service. "
        + "If none is provided, new systems registered through this service will be available on the hub, but "
        + "with no persistence guarantee across restarts. Only the latest observation from each datastream will be "
        + "available and older observations will be discarded")
    public String databaseID = null;


    @DisplayInfo(desc="Filtered view to select systems exposed as read-only through this service")
    public ObsSystemDatabaseViewConfig exposedResources = null;


    @DisplayInfo(desc="Mapping of custom formats mime-types to custom serializer classes")
    public List<CustomFormatConfig> customFormats = new ArrayList<>();


    @DisplayInfo(desc="Security related options")
    public SecurityConfig security = new SecurityConfig();


    @DisplayInfo(desc="Set to true to enable transactional operation support")
    public boolean enableTransactional = false;


    @DisplayInfo(label="Max Limit", desc="Maximum number of resources returned in a single page")
    public int maxResponseLimit = 100000;
    
    
    @DisplayInfo(desc="Default live time-out for new offerings created via SOS-T")
    public double defaultLiveTimeout = 600.0;
    
    
    @DisplayInfo(label="URI Prefix Map", desc="Mappings used by CURIE to URI resolver")
    public List<String> uriPrefixMap = new ArrayList<>();


    public ConSysApiServiceConfig()
    {
        this.moduleClass = ConSysApiService.class.getCanonicalName();
        this.endPoint = "/api";
    }
}
