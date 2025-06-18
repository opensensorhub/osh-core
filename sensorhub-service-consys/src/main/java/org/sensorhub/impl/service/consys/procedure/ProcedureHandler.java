/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.procedure;

import java.io.IOException;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.database.IProcedureDatabase;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.feature.FeatureKey;
import org.sensorhub.api.datastore.procedure.IProcedureStore;
import org.sensorhub.api.datastore.procedure.ProcedureFilter;
import org.sensorhub.api.event.IEventBus;
import org.sensorhub.api.procedure.IProcedureWithDesc;
import org.sensorhub.impl.service.consys.InvalidRequestException;
import org.sensorhub.impl.service.consys.ObsSystemDbWrapper;
import org.sensorhub.impl.service.consys.ResourceParseException;
import org.sensorhub.impl.service.consys.ServiceErrors;
import org.sensorhub.impl.service.consys.RestApiServlet.ResourcePermissions;
import org.sensorhub.impl.service.consys.feature.AbstractFeatureHandler;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceBinding;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;


public class ProcedureHandler extends AbstractFeatureHandler<IProcedureWithDesc, ProcedureFilter, ProcedureFilter.Builder, IProcedureStore>
{
    public static final String[] NAMES = { "procedures" };
    
    final IEventBus eventBus;
    final IProcedureDatabase db;
    final ProcedureEventsHandler eventsHandler;
    
    
    public ProcedureHandler(IEventBus eventBus, ObsSystemDbWrapper db, ResourcePermissions permissions)
    {
        super(db.getProcedureStore(), db.getProcedureIdEncoder(), db, permissions);
        this.eventBus = eventBus;
        this.db = db;
        
        this.eventsHandler = new ProcedureEventsHandler(eventBus, db, permissions);
        addSubResource(eventsHandler);
    }


    @Override
    protected ResourceBinding<FeatureKey, IProcedureWithDesc> getBinding(RequestContext ctx, boolean forReading) throws IOException
    {
        var format = ctx.getFormat();
        
        if (format.equals(ResourceFormat.HTML) || (format.equals(ResourceFormat.AUTO) && ctx.isBrowserHtmlRequest()))
            return new ProcedureBindingHtml(ctx, idEncoders, db, true);
        else if (format.isOneOf(ResourceFormat.AUTO, ResourceFormat.JSON, ResourceFormat.GEOJSON))
            return new ProcedureBindingGeoJson(ctx, idEncoders, db, forReading);
        else if (format.equals(ResourceFormat.SML_JSON))
            return new ProcedureBindingSmlJson(ctx, idEncoders, forReading);
        else if (format.equals(ResourceFormat.SML_XML))
            return new ProcedureBindingSmlXml(ctx, idEncoders, forReading);
        else
            throw ServiceErrors.unsupportedFormat(format);
    }
    
    
    @Override
    protected FeatureKey addEntry(RequestContext ctx, IProcedureWithDesc res) throws DataStoreException
    {
        return db.getProcedureStore().add(res);
    }


    @Override
    protected boolean updateEntry(RequestContext ctx, FeatureKey key, IProcedureWithDesc res) throws DataStoreException
    {
        try
        {
            return db.getProcedureStore().computeIfPresent(key, (k,v) -> res) != null;
        }
        catch (IllegalArgumentException e)
        {
            if (e.getCause() instanceof DataStoreException)
                throw (DataStoreException)e.getCause();
            throw e;
        }
    }


    @Override
    protected boolean deleteEntry(RequestContext ctx, FeatureKey key) throws DataStoreException
    {
        return db.getProcedureStore().remove(key) != null;
    }
    
    
    @Override
    protected void subscribeToEvents(final RequestContext ctx) throws InvalidRequestException, IOException
    {
        eventsHandler.doGet(ctx);
    }
    
    
    @Override
    protected boolean isValidID(BigId internalID)
    {
        return dataStore.contains(internalID);
    }


    @Override
    protected void validate(IProcedureWithDesc resource) throws ResourceParseException
    {
        super.validate(resource);
    }
    
    
    @Override
    public String[] getNames()
    {
        return NAMES;
    }
}
