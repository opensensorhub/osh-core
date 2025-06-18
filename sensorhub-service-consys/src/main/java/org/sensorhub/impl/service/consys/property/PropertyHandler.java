/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.property;

import java.io.IOException;
import java.util.Map;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.database.IProcedureDatabase;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.property.IPropertyStore;
import org.sensorhub.api.datastore.property.PropertyFilter;
import org.sensorhub.api.datastore.property.PropertyKey;
import org.sensorhub.api.event.IEventBus;
import org.sensorhub.api.semantic.IDerivedProperty;
import org.sensorhub.impl.service.consys.InvalidRequestException;
import org.sensorhub.impl.service.consys.ObsSystemDbWrapper;
import org.sensorhub.impl.service.consys.ServiceErrors;
import org.sensorhub.impl.service.consys.RestApiServlet.ResourcePermissions;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceBinding;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.sensorhub.impl.service.consys.resource.ResourceHandler;
import org.sensorhub.impl.service.consys.resource.RequestContext.ResourceRef;


public class PropertyHandler extends ResourceHandler<PropertyKey, IDerivedProperty, PropertyFilter, PropertyFilter.Builder, IPropertyStore>
{
    public static final int EXTERNAL_ID_SEED = 448912669;
    public static final String[] NAMES = { "properties" };
    
    final IProcedureDatabase db;
    
    
    public PropertyHandler(IEventBus eventBus, ObsSystemDbWrapper db, ResourcePermissions permissions)
    {
        super(db.getPropertyStore(), db.getPropertyIdEncoder(), db, permissions);
        this.db = db;
    }
    
    
    @Override
    protected ResourceBinding<PropertyKey, IDerivedProperty> getBinding(RequestContext ctx, boolean forReading) throws IOException
    {
        var format = ctx.getFormat();
        
        if (format.equals(ResourceFormat.HTML) || (format.equals(ResourceFormat.AUTO) && ctx.isBrowserHtmlRequest()))
        {
            var title = "All Derived properties";
            return new PropertyBindingHtml(ctx, idEncoders, db, true, title);
        }
        else if (format.isOneOf(ResourceFormat.AUTO, ResourceFormat.JSON))
            return new PropertyBindingJson(ctx, idEncoders, db, forReading);
        else if (format.getMimeType().equals("text/turtle"))
            return new PropertyBindingTurtle(ctx, idEncoders, forReading);
        else
            throw ServiceErrors.unsupportedFormat(format);
    }
    
    
    @Override
    protected boolean isValidID(BigId internalID)
    {
        return dataStore.containsKey(new PropertyKey(internalID));
    }


    @Override
    protected void buildFilter(final ResourceRef parent, final Map<String, String[]> queryParams, final PropertyFilter.Builder builder) throws InvalidRequestException
    {
        super.buildFilter(parent, queryParams, builder);
        
        // baseProperty param
        var obsProps = parseMultiValuesArg("baseProperty", queryParams);
        if (obsProps != null && !obsProps.isEmpty())
        {
            builder.withBaseProperties()
                .withUniqueIDs(obsProps)
                .done();
        }
    }


    @Override
    protected void validate(IDerivedProperty resource)
    {
        // TODO Auto-generated method stub
        
    }
    
    
    @Override
    protected PropertyKey getKey(final RequestContext ctx, final String id) throws InvalidRequestException
    {
        try
        {
            var decodedID = decodeID(id);
            return getKey(decodedID);
        }
        catch (InvalidRequestException e)
        {
         // try to decode as alias
            var propKey = db.getPropertyStore().selectKeys(new PropertyFilter.Builder()
                .withUniqueIDs("#"+id).build())
                .findFirst()
                .orElse(null);
            
            if (propKey != null)
                return propKey;
            
            throw ServiceErrors.notFound(id);
        }
    }
    
    
    @Override
    protected PropertyKey addEntry(final RequestContext ctx, final IDerivedProperty res) throws DataStoreException
    {
        return db.getPropertyStore().add(res);
    }
    
    
    @Override
    protected boolean updateEntry(final RequestContext ctx, final PropertyKey key, final IDerivedProperty res) throws DataStoreException
    {
        try
        {
            return db.getPropertyStore().computeIfPresent(key, (k,v) -> res) != null;
        }
        catch (IllegalArgumentException e)
        {
            if (e.getCause() instanceof DataStoreException)
                throw (DataStoreException)e.getCause();
            throw e;
        }
    }
    
    
    @Override
    protected boolean deleteEntry(final RequestContext ctx, final PropertyKey key) throws DataStoreException
    {
        return db.getPropertyStore().remove(key) != null;
    }


    @Override
    protected PropertyKey getKey(BigId publicID)
    {
        return new PropertyKey(publicID);
    }
    
    
    @Override
    protected void addOwnerPermissions(RequestContext ctx, String id)
    {
        
    }
    
    
    @Override
    public String[] getNames()
    {
        return NAMES;
    }
}
