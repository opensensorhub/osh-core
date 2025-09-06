/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.resource;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Stream;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.common.IdEncoder;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.IDataStore;
import org.sensorhub.api.datastore.IQueryFilter;
import org.sensorhub.api.security.IPermission;
import org.sensorhub.api.security.IPermissionPath;
import org.sensorhub.impl.module.ModuleSecurity;
import org.sensorhub.impl.security.PermissionSetting;
import org.sensorhub.impl.service.consys.BaseHandler;
import org.sensorhub.impl.service.consys.InvalidRequestException;
import org.sensorhub.impl.service.consys.HandlerContext;
import org.sensorhub.impl.service.consys.ResourceParseException;
import org.sensorhub.impl.service.consys.ServiceErrors;
import org.sensorhub.impl.service.consys.RestApiServlet.ResourcePermissions;
import org.sensorhub.impl.service.consys.resource.RequestContext.ResourceRef;
import org.vast.util.Asserts;


/**
 * <p>
 * Base resource handler, maintaining connection to a datastore
 * </p>
 * 
 * @param <K> 
 * @param <V> 
 * @param <S> 
 * @param <F> 
 *
 * @author Alex Robin
 * @date Nov 15, 2018
 */
public abstract class BaseResourceHandler<K, V, F extends IQueryFilter, S extends IDataStore<K, V, ?, F>> extends BaseHandler implements IResourceHandler
{
    public static final String READ_ONLY_ERROR = "Resource type is read-only";
    public static final String NOT_IMPLEMENTED_ERROR = "Not implemented";
    public static final String INVALID_TIMESTAMP_ERROR_MSG = "Invalid time stamp: ";
    public static final String ALREADY_EXISTS_ERROR_MSG = "Resource already exists";
    public static final String STREAMING_UNSUPPORTED_ERROR_MSG = "Streaming not supported on this resource collection";
    public static final String EVENTS_UNSUPPORTED_ERROR_MSG = "Events not supported on this resource collection";
    
    protected final S dataStore;
    protected final IdEncoder idEncoder;
    protected final ResourcePermissions permissions;
    protected boolean readOnly = false;
    
    
    protected BaseResourceHandler(S dataStore, IdEncoder idEncoder, HandlerContext ctx, ResourcePermissions permissions)
    {
        super(ctx);
        this.dataStore = Asserts.checkNotNull(dataStore, IDataStore.class);
        this.idEncoder = Asserts.checkNotNull(idEncoder, IdEncoder.class);
        this.permissions = Asserts.checkNotNull(permissions, ResourcePermissions.class);
    }
    
    
    protected abstract ResourceBinding<K, V> getBinding(RequestContext ctx, boolean forReading) throws IOException;
    protected abstract K getKey(final RequestContext ctx, final String id) throws InvalidRequestException;
    protected abstract String encodeKey(final RequestContext ctx, K key);
    protected abstract F getFilter(final ResourceRef parent, final Map<String, String[]> queryParams, long offset, long limit) throws InvalidRequestException;
    protected abstract boolean isValidID(BigId internalID);
    protected abstract void validate(V resource) throws ResourceParseException;
        
    
    @Override
    public void doGet(final RequestContext ctx) throws IOException
    {
        // if requesting from this resource collection
        if (ctx.isEndOfPath())
        {
            if (ctx.isStreamRequest())
                subscribe(ctx);
            else
                list(ctx);
            return;
        }
        
        // otherwise there should be a specific resource ID or 'count'
        String id = ctx.popNextPathElt();
        if (ctx.isEndOfPath())
        {
            if ("count".equals(id))
                getElementCount(ctx);
            else if ("events".equals(id))
                subscribeToEvents(ctx);
            else
                getById(ctx, id);
            return;
        }
        
        // next should be nested resource
        IResourceHandler resource = getSubResource(ctx, id);
        if (resource != null)
            resource.doGet(ctx);
        else
            throw ServiceErrors.badRequest(INVALID_URI_ERROR_MSG);
    }
    
    
    @Override
    public void doPost(final RequestContext ctx) throws IOException
    {
        if (!ctx.isEndOfPath())
        {        
            // next should be resource ID
            String id = ctx.popNextPathElt();
            if (ctx.isEndOfPath())
                throw ServiceErrors.unsupportedOperation("Can only POST on collections");
            
            // next should be nested resource
            IResourceHandler resource = getSubResource(ctx, id);
            if (resource != null)
                resource.doPost(ctx);
            else
                throw ServiceErrors.badRequest(INVALID_URI_ERROR_MSG);
        }
        else
            create(ctx);
    }
    
    
    @Override
    public void doPut(final RequestContext ctx) throws IOException
    {
        if (ctx.isEndOfPath())
            throw ServiceErrors.unsupportedOperation("Can only PUT on specific resource");
     
        // next should be resource ID
        String id = ctx.popNextPathElt();
        
        if (!ctx.isEndOfPath())
        {
            // next should be nested resource
            IResourceHandler resource = getSubResource(ctx, id);
            if (resource != null)
                resource.doPut(ctx);
            else
                throw ServiceErrors.badRequest(INVALID_URI_ERROR_MSG);
        }
        else
            update(ctx, id);
    }
    
    
    @Override
    public void doDelete(final RequestContext ctx) throws IOException
    {
        if (ctx.isEndOfPath())
            throw ServiceErrors.unsupportedOperation("Can only DELETE a specific resource");
     
        // next should be resource ID
        String id = ctx.popNextPathElt();
        
        if (!ctx.isEndOfPath())
        {
            // next should be nested resource
            IResourceHandler resource = getSubResource(ctx, id);
            if (resource != null)
                resource.doDelete(ctx);
            else
                throw ServiceErrors.badRequest(INVALID_URI_ERROR_MSG);
        }
        else
            delete(ctx, id);
    }
    
    
    protected void subscribe(final RequestContext ctx) throws IOException
    {
        throw ServiceErrors.unsupportedOperation(STREAMING_UNSUPPORTED_ERROR_MSG);
    }
    
    

    protected void subscribeToEvents(final RequestContext ctx) throws IOException
    {
        throw ServiceErrors.unsupportedOperation(EVENTS_UNSUPPORTED_ERROR_MSG);
    }
    
    
    protected void getById(final RequestContext ctx, final String id) throws IOException
    {
        // check permissions
        ctx.getSecurityHandler().checkResourcePermission(permissions.get, id);
        
        // get resource key
        var key = getKey(ctx, id);
        if (key == null)
            throw ServiceErrors.notFound(id);
        
        getByKey(ctx, key);
    }
    
    
    protected void getByKey(final RequestContext ctx, K key) throws IOException
    {
        // fetch from data store
        final V res = dataStore.get(key);
        if (res != null)
        {
            var queryParams = ctx.getParameterMap();
            var responseFormat = parseFormat(queryParams);
            ctx.setFormatOptions(responseFormat, parseSelectArg(queryParams));
            var binding = getBinding(ctx, false);
            
            // set content type
            ctx.setResponseContentType(responseFormat.getMimeType());
            
            // write a single resource to servlet output
            binding.serialize(key, res, true);
        }
        else
            throw ServiceErrors.notFound();
    }
    
    
    protected void list(final RequestContext ctx) throws IOException
    {
        // check permissions
        var parentId = ctx.getParentRef().id;
        ctx.getSecurityHandler().checkParentPermission(permissions.list, parentId);
        
        // parse offset & limit
        var queryParams = ctx.getParameterMap();
        var offset = parseLongArg("offset", queryParams);
        if (offset == null)
            offset = 0L;
        
        var limit = parseLongArg("limit", queryParams);
        if (limit == null)
            limit = 100L;
        limit = Math.min(limit, 10000);
        
        // create datastore filter
        F filter = getFilter(ctx.getParentRef(), queryParams, offset, limit);
        var responseFormat = parseFormat(queryParams);
        ctx.setFormatOptions(responseFormat, parseSelectArg(queryParams));
        
        // set default content type
        ctx.setResponseContentType(ResourceFormat.JSON.getMimeType());
        
        // stream and serialize all resources to servlet output
        var binding = getBinding(ctx, false);
        binding.startCollection();
        
        // fetch from DB and temporarily handle paging here
        try (var results = postProcessResultList(dataStore.selectEntries(filter), filter))
        {
            var it = results.skip(offset)
                .limit(limit+1) // get one more so we know when to enable paging
                .iterator();
            
            var count = 0;
            while (it.hasNext())
            {
                if (count++ >= limit)
                    break;
                var e = it.next();
                binding.serialize(e.getKey(), e.getValue(), false);
            }
            
            binding.endCollection(getPagingLinks(ctx, offset, limit, count > limit));
        }
    }
    
    
    protected Stream<Entry<K, V>> postProcessResultList(Stream<Entry<K, V>> results, F filter)
    {
        return results;
    }
    
    
    protected void create(final RequestContext ctx) throws IOException
    {
        if (readOnly)
            throw ServiceErrors.unsupportedOperation(READ_ONLY_ERROR);
        
        // check permissions
        var parentId = ctx.getParentRef().id;
        ctx.getSecurityHandler().checkParentPermission(permissions.create, parentId);
        
        // read and ingest one or more resources
        int count = 0;
        String url = null;
        try
        {
            // get content type
            var format = ResourceFormat.fromMimeType(ctx.getRequestContentType());
            if (format == null)
                throw ServiceErrors.badRequest("Missing content type");
            ctx.setFormatOptions(format, null);
            
            // parse one or more resource
            try
            {
                V res;
                var binding = getBinding(ctx, true);
                while ((res = binding.deserialize()) != null)
                {
                    
                        count++;
                        validate(res);
                    
                        // add resource to datastore
                        K k = addEntry(ctx, res);
                        
                        if (k != null)
                        {
                            // encode resource ID and generate URL
                            String id = encodeKey(ctx, k);
                            url = getCanonicalResourceUrl(id);
                            ctx.getLogger().debug("Added resource {}", url);
                            
                            // add default owner permissions
                            addOwnerPermissions(ctx, id);
                        }
                        
                        if (url != null)
                            ctx.addResourceUri(url);
                }
                
                if (!dataStore.isReadOnly())
                    dataStore.commit();
            }
            catch (DataStoreException e)
            {
                throw ServiceErrors.requestRejected("Ingest Error: " + e.getMessage());
            }
            
            if (count == 0)
                throw ServiceErrors.invalidPayload("No data provided");
        }
        catch (ResourceParseException e)
        {
            throw ServiceErrors.invalidPayload("Invalid payload: " + e.getMessage(), e);
        }
    }
    
    
    protected K addEntry(final RequestContext ctx, final V res) throws DataStoreException
    {
        throw new DataStoreException(NOT_IMPLEMENTED_ERROR);
    }
    
    
    protected void update(final RequestContext ctx, final String id) throws IOException
    {
        if (readOnly)
            throw ServiceErrors.unsupportedOperation(READ_ONLY_ERROR);
        
        // check permissions
        ctx.getSecurityHandler().checkResourcePermission(permissions.update, id);
        
        // get resource key
        var key = getKey(ctx, id);
        if (key == null)
            throw ServiceErrors.notFound(id);
        
        // parse payload
        V res;
        try
        {
            // get content type
            var format = ResourceFormat.fromMimeType(ctx.getRequestContentType());
            if (format == null)
                throw ServiceErrors.badRequest("Missing content type");
            ctx.setFormatOptions(format, null);
            
            // parse one resource
            var binding = getBinding(ctx, true);
            res = binding.deserialize();
            if (res == null)
                throw ServiceErrors.invalidPayload("No data provided");
            
            validate(res);
        }
        catch (ResourceParseException e)
        {
            throw ServiceErrors.invalidPayload("Invalid payload: " + e.getMessage(), e);
        }
        
        // update in datastore
        try
        {
            if (updateEntry(ctx, key, res))
            {
                if (!dataStore.isReadOnly())
                    dataStore.commit();
                ctx.getLogger().debug("Updated resource {}, key={}", id, key);
            }
            else
                throw ServiceErrors.notFound(id);
        }
        catch (DataStoreException e)
        {
            var msg = "Cannot update '" + id + "': ";
            throw ServiceErrors.requestRejected(msg + e.getMessage());
        }
    }
    
    
    protected boolean updateEntry(final RequestContext ctx, final K key, final V res) throws DataStoreException
    {
        throw new DataStoreException(NOT_IMPLEMENTED_ERROR);
    }
    
    
    protected void delete(final RequestContext ctx, final String id) throws IOException
    {
        if (readOnly)
            throw ServiceErrors.unsupportedOperation(READ_ONLY_ERROR);
        
        // check permissions
        ctx.getSecurityHandler().checkResourcePermission(permissions.delete, id);
        
        // get resource key
        var key = getKey(ctx, id);
        if (key == null)
            throw ServiceErrors.notFound(id);
        
        // delete from datastore
        try
        {
            if (deleteEntry(ctx, key))
            {
                if (!dataStore.isReadOnly())
                    dataStore.commit();
                ctx.getLogger().info("Deleted resource {}, key={}", id, key);
            }
            else
                throw ServiceErrors.notFound(id);
        }
        catch (DataStoreException e)
        {
            var msg = "Cannot delete '" + id + "': ";
            throw ServiceErrors.requestRejected(msg + e.getMessage());
        }
    }
    
    
    protected boolean deleteEntry(final RequestContext ctx, final K key) throws DataStoreException
    {
        throw new DataStoreException(NOT_IMPLEMENTED_ERROR);
    }
    
    
    protected void getElementCount(final RequestContext ctx) throws IOException
    {
        F filter = getFilter(ctx.getParentRef(), ctx.getParameterMap(), 0, Long.MAX_VALUE-1);
        
        // get count from datastore
        long count = dataStore.countMatchingEntries(filter);
        
        // write out as json
        Writer writer = new OutputStreamWriter(ctx.getOutputStream());
        writer.write("{\"count\":");
        writer.write(Long.toString(count));
        writer.write('}');
        writer.flush();
    }
    
    
    protected IResourceHandler getSubResource(RequestContext ctx, String id) throws InvalidRequestException
    {
        IResourceHandler resource = getSubResource(ctx);
        if (resource == null)
            return null;
        
        // decode internal ID for nested resource
        var internalID = decodeID(id);
        
        // check that resource ID valid
        if (!isValidID(internalID))
            throw ServiceErrors.notFound(id);
                
        ctx.setParent(this, id, internalID);
        return resource;
    }
    
    
    protected String getCanonicalResourceUrl(final String id)
    {
        StringBuilder buf = new StringBuilder();
        buf.append('/').append(getNames()[0]);
        buf.append('/').append(id);
        return buf.toString();
    }
    
    
    protected BigId decodeID(final String id) throws InvalidRequestException
    {
        try
        {
            return idEncoder.decodeID(id);
        }
        catch (IllegalArgumentException e)
        {
            throw ServiceErrors.notFound(id);
        }
    }
    
    
    protected void addOwnerPermissions(final RequestContext ctx, final String id)
    {
    }
    
    
    protected void addPermissionsToCurrentUser(final RequestContext ctx, final IPermission... permissions)
    {
        var sec = (ModuleSecurity)ctx.getSecurityHandler();
        if (!sec.getSecurityManager().isAccessControlEnabled())
            return;
        
        var userDB = sec.getSecurityManager().getUserRegistry();
        var user = sec.getCurrentUser();
        
        var permList = new ArrayList<IPermissionPath>();
        for (var p: permissions)
        {
            if (!sec.hasPermission(p))
                permList.add(new PermissionSetting(p));
        }
        
        if (!permList.isEmpty())
            userDB.addAllowPermissions(user.getId(), permList);
    }


    public ResourcePermissions getPermissions()
    {
        return permissions;
    }
}
