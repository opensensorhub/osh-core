/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2026 Botts Innovative Research Inc. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.task;

import java.io.IOException;
import org.sensorhub.api.command.ICommandData;
import org.sensorhub.api.command.ICommandStreamInfo;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.common.IdEncoders;
import org.sensorhub.api.datastore.command.CommandStreamKey;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceBinding;


public interface CustomCommandFormat
{
    /**
     * @return true if this format can encode commands of the given
     * command stream. Drives the "formats" list advertised on the
     * command stream resource.
     */
    boolean isCompatible(ICommandStreamInfo csInfo);


    /**
     * @return a binding serving this format's view of the command stream
     * schema, or null if not supported. Used when the commandFormat query
     * param matches this format's mime type.
     */
    ResourceBinding<CommandStreamKey, ICommandStreamInfo> getSchemaBinding(RequestContext ctx, IdEncoders idEncoders, ICommandStreamInfo csInfo) throws IOException;


    /**
     * @param forReading true when the binding will read commands from the
     *        request body, false when it will write commands to the response
     */
    ResourceBinding<BigId, ICommandData> getCommandBinding(RequestContext ctx, IdEncoders idEncoders, ICommandStreamInfo csInfo, boolean forReading) throws IOException;
}
