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

import static org.sensorhub.impl.service.consys.event.ResourceEventsHandler.*;
import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import org.sensorhub.api.common.IdEncoders;
import org.sensorhub.api.system.SystemAddedEvent;
import org.sensorhub.api.system.SystemChangedEvent;
import org.sensorhub.api.system.SystemDisabledEvent;
import org.sensorhub.api.system.SystemEnabledEvent;
import org.sensorhub.api.system.SystemEvent;
import org.sensorhub.api.system.SystemRemovedEvent;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceBindingJson;
import org.sensorhub.impl.service.consys.resource.ResourceLink;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;


/**
 * <p>
 * JSON serializer for system events
 * </p>
 *
 * @author Alex Robin
 * @since Feb 26, 2022
 */
public class SystemEventBindingJson extends ResourceBindingJson<Long, SystemEvent>
{
    
    
    SystemEventBindingJson(RequestContext ctx, IdEncoders idEncoders) throws IOException
    {
        super(ctx, idEncoders, false);
    }


    @Override
    public SystemEvent deserialize(JsonReader reader) throws IOException
    {
        throw new UnsupportedOperationException();
    }


    @Override
    public void serialize(Long key, SystemEvent res, boolean showLinks, JsonWriter writer) throws IOException
    {
        var eventType =
            (res instanceof SystemAddedEvent) ? RESOURCE_ADDED_EVENT_TYPE :
            (res instanceof SystemRemovedEvent) ? RESOURCE_DELETED_EVENT_TYPE :
            (res instanceof SystemChangedEvent) ? RESOURCE_UPDATED_EVENT_TYPE :
            (res instanceof SystemEnabledEvent) ? RESOURCE_ENABLED_EVENT_TYPE :
            (res instanceof SystemDisabledEvent) ? RESOURCE_DISABLED_EVENT_TYPE :
            null;
        
        if (eventType == null)
            return;
        
        // write event message
        var sysId = !ctx.isClientSide() ? idEncoders.getSystemIdEncoder().encodeID(res.getSystemID()) : null;
        writer.beginObject();
        writer.name("time").value(Instant.ofEpochMilli(res.getTimeStamp()).toString());

        if(sysId != null)
            writer.name("system@id").value(sysId);

        writer.name("eventType").value(eventType);
        writer.endObject();
        writer.flush();
    }


    @Override
    public void startCollection() throws IOException
    {
    }


    @Override
    public void endCollection(Collection<ResourceLink> links) throws IOException
    {
    }
}
