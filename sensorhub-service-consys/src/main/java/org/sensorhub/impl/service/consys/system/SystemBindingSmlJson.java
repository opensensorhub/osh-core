/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.system;

import java.io.IOException;
import org.sensorhub.api.common.IdEncoders;
import org.sensorhub.api.datastore.feature.FeatureKey;
import org.sensorhub.api.system.ISystemWithDesc;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.sensorml.SmlProcessBindingSmlJson;


/**
 * <p>
 * SensorML JSON formatter for system resources
 * </p>
 *
 * @author Alex Robin
 * @since July 7, 2023
 */
public class SystemBindingSmlJson extends SmlProcessBindingSmlJson<ISystemWithDesc>
{
    
    public SystemBindingSmlJson(RequestContext ctx, IdEncoders idEncoders, boolean forReading) throws IOException
    {
        super(ctx, idEncoders, forReading);
    }
    
    
    protected String encodeID(FeatureKey key)
    {
        return idEncoders.getSystemIdEncoder().encodeID(key.getInternalID());
    }
}
