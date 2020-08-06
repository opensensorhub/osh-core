/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.client.sost;

import java.util.ArrayList;
import java.util.List;
import org.sensorhub.api.client.ClientConfig;
import org.sensorhub.api.config.DisplayInfo;
import org.sensorhub.api.config.DisplayInfo.FieldType;
import org.sensorhub.api.config.DisplayInfo.ModuleType;
import org.sensorhub.api.config.DisplayInfo.Required;
import org.sensorhub.api.data.IDataProducerModule;
import org.sensorhub.api.config.DisplayInfo.FieldType.Type;
import org.sensorhub.impl.comm.HTTPConfig;
import org.sensorhub.impl.comm.RobustIPConnectionConfig;
import com.google.gson.annotations.SerializedName;


/**
 * <p>
 * Configuration class for the SOS-T client module
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since Feb 6, 2015
 */
public class SOSTClientConfig extends ClientConfig
{
    @DisplayInfo(desc="Local ID of data source to register with SOS")
    @FieldType(Type.MODULE_ID)
    @ModuleType(IDataProducerModule.class)
    @Required
    @SerializedName(value="dataSourceID", alternate={"sensorID"})
    public String dataSourceID;
    
    
    @DisplayInfo(desc="Names of outputs that should not be pushed to remote SOS server")
    public List<String> excludedOutputs = new ArrayList<>();
    
    
    @DisplayInfo(label="SOS Endpoint", desc="SOS endpoint where the requests are sent")
    public HTTPConfig sos = new HTTPConfig();
    
    
    @DisplayInfo(label="Connection Options")
    public SOSConnectionConfig connection = new SOSConnectionConfig();
    
    
    public static class SOSConnectionConfig extends RobustIPConnectionConfig
    {
        @DisplayInfo(desc="Enable to use a persistent HTTP connection for InsertResult")
        public boolean usePersistentConnection;
        
        
        @DisplayInfo(desc="Maximum number of records in upload queue (used to compensate for variable bandwidth)")
        public int maxQueueSize = 10;

        
        @DisplayInfo(desc="Maximum number of stream errors before we try to reconnect to remote server")
        public int maxConnectErrors = 10;
    }
    
    
    public SOSTClientConfig()
    {
        this.moduleClass = SOSTClient.class.getCanonicalName();
        this.sos.resourcePath = "/sensorhub/sos";
    }
}
