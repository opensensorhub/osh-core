/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2022 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.obs;

import java.io.IOException;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.common.IdEncoders;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.data.IObsData;
import org.sensorhub.api.datastore.obs.DataStreamKey;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceBinding;


public interface CustomObsFormat
{
    /**
     * @return true if this format can encode observations of the given
     * datastream. Drives the "formats" list advertised on the datastream
     * resource and explicit format selection.
     */
    boolean isCompatible(IDataStreamInfo dsInfo);


    /**
     * @return true if this format should be auto-selected for browser requests
     * with no explicit format (e.g. video formats playable in a browser).
     * Defaults to {@link #isCompatible}; override to return false for formats
     * that should be advertised but only served when explicitly requested.
     */
    default boolean isAutoSelectable(IDataStreamInfo dsInfo)
    {
        return isCompatible(dsInfo);
    }


    ResourceBinding<DataStreamKey, IDataStreamInfo> getSchemaBinding(RequestContext ctx, IdEncoders idEncoders, IDataStreamInfo dsInfo) throws IOException;


    ResourceBinding<BigId, IObsData> getObsBinding(RequestContext ctx, IdEncoders idEncoders, IDataStreamInfo dsInfo) throws IOException;
}
