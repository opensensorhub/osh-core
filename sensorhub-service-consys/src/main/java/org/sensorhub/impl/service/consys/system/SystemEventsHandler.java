/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2022 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.system;

import java.io.IOException;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.event.EventUtils;
import org.sensorhub.api.system.SystemEvent;
import org.sensorhub.impl.service.consys.InvalidRequestException;
import org.sensorhub.impl.service.consys.HandlerContext;
import org.sensorhub.impl.service.consys.RestApiServlet.ResourcePermissions;
import org.sensorhub.impl.service.consys.event.ResourceEventsHandler;
import org.sensorhub.impl.service.consys.resource.RequestContext;


public class SystemEventsHandler extends ResourceEventsHandler<SystemEvent>
{
    final IObsSystemDatabase db;
    boolean onlyMembers = false;
    
    
    protected SystemEventsHandler(HandlerContext ctx, ResourcePermissions permissions)
    {
        super("system", ctx, permissions);
        this.db = ctx.getReadDb();
    }
    

    @Override
    public void subscribe(RequestContext ctx) throws InvalidRequestException, IOException
    {
        var queryParams = ctx.getParameterMap();
        //var filter = getFilter(ctx.getParentRef(), queryParams, 0, Long.MAX_VALUE);
        var responseFormat = parseFormat(queryParams);
        ctx.setFormatOptions(responseFormat, parseSelectArg(queryParams));
        var serializer = new SystemEventBindingJson(ctx, idEncoders);
        
        // use registry topic if all system events are requested
        // otherwise use specific system topic
        String topic = null;
        String sysUid;
        if (ctx.getParentID() != null)
        {
            var sysId = ctx.getParentID();
            sysUid = db.getSystemDescStore().getCurrentVersion(sysId).getUniqueIdentifier();
            topic = EventUtils.getSystemStatusTopicID(sysUid);
        }
        else
        {
            topic = EventUtils.getSystemRegistryTopicID();
            sysUid = null;
        }
        
        // build subscription options
        var subscriptionBuilder = eventBus.newSubscription(SystemEvent.class)
            .withTopicID(topic)
            .withEventType(SystemEvent.class);
        
        // skip this system events if we only want members events
        if (onlyMembers && sysUid != null)
            subscriptionBuilder.withFilter(e -> !e.getSystemUID().equals(sysUid));
        
        subscribe(ctx, subscriptionBuilder, serializer);
    }

}
