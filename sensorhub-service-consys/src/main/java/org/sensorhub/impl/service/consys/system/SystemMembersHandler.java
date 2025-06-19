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
import java.util.Map;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.feature.FeatureKey;
import org.sensorhub.api.datastore.system.SystemFilter;
import org.sensorhub.api.system.ISystemWithDesc;
import org.sensorhub.impl.service.consys.InvalidRequestException;
import org.sensorhub.impl.service.consys.HandlerContext;
import org.sensorhub.impl.service.consys.ServiceErrors;
import org.sensorhub.impl.service.consys.RestApiServlet.ResourcePermissions;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.RequestContext.ResourceRef;
import org.sensorhub.impl.system.SystemUtils;
import org.sensorhub.impl.system.wrapper.SmlFeatureWrapper;


public class SystemMembersHandler extends SystemHandler
{
    public static final String[] NAMES = { "subsystems", "members" };
    
    
    public SystemMembersHandler(HandlerContext ctx, ResourcePermissions permissions)
    {
        super(ctx, permissions);
        eventsHandler.onlyMembers = true;
    }
    
    
    @Override
    public void doPost(RequestContext ctx) throws IOException
    {
        if (ctx.isEndOfPath() &&
            !(ctx.getParentRef().type instanceof SystemHandler))
            throw ServiceErrors.unsupportedOperation("Subsystems can only be created within a System resource");
        
        super.doPost(ctx);
    }


    @Override
    protected void buildFilter(final ResourceRef parent, final Map<String, String[]> queryParams, final SystemFilter.Builder builder) throws InvalidRequestException
    {
        super.buildFilter(parent, queryParams, builder);
        
        // filter on parent if needed
        if (parent.internalID != null)
        {
            builder.withParents()
                .withInternalIDs(parent.internalID)
                .done();
        }
    }
    
    
    @Override
    protected void subscribeToEvents(final RequestContext ctx) throws InvalidRequestException, IOException
    {
        eventsHandler.doGet(ctx);
    }
    
    
    @Override
    protected FeatureKey addEntry(final RequestContext ctx, ISystemWithDesc res) throws DataStoreException
    {        
        // cleanup sml description before storage
        var sml = res.getFullDescription();
        if (sml != null)
        {
            res = new SmlFeatureWrapper(res.getFullDescription())
                .hideOutputs()
                .hideTaskableParams()
                .defaultToValidFromNow();
        }        
        
        var groupID = ctx.getParentID();
        var parentHandler = transactionHandler.getSystemHandler(groupID);
        if (parentHandler == null)
            return null;
        var procHandler = parentHandler.addMember(res);

        // also add datastreams if outputs were specified in SML description
        if (sml != null)
            SystemUtils.addDatastreamsFromOutputs(procHandler, sml.getOutputList());
        
        return procHandler.getSystemKey();
    }
    
    
    @Override
    protected String getCanonicalResourceUrl(final String id)
    {
        return "/" + SystemHandler.NAMES[0] + "/" + id;
    }
    
    
    @Override
    public String[] getNames()
    {
        return NAMES;
    }


    @Override
    protected void validate(ISystemWithDesc resource)
    {
        // TODO Auto-generated method stub
        
    }
}
