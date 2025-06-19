/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2022 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.procedure;

import java.io.IOException;
import org.sensorhub.api.system.SystemEvent;
import org.sensorhub.impl.service.consys.InvalidRequestException;
import org.sensorhub.impl.service.consys.HandlerContext;
import org.sensorhub.impl.service.consys.RestApiServlet.ResourcePermissions;
import org.sensorhub.impl.service.consys.event.ResourceEventsHandler;
import org.sensorhub.impl.service.consys.resource.RequestContext;


public class ProcedureEventsHandler extends ResourceEventsHandler<SystemEvent>
{
    
    protected ProcedureEventsHandler(HandlerContext ctx, ResourcePermissions permissions)
    {
        super("procedure", ctx, permissions);
    }
    

    @Override
    public void subscribe(RequestContext ctx) throws InvalidRequestException, IOException
    {
        
    }

}
