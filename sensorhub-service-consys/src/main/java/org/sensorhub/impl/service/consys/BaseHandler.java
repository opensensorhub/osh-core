/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.common.IdEncoder;
import org.sensorhub.api.common.IdEncoders;
import org.sensorhub.api.datastore.TemporalFilter;
import org.sensorhub.impl.service.consys.feature.FeatureIdEncoder;
import org.sensorhub.impl.service.consys.resource.IResourceHandler;
import org.sensorhub.impl.service.consys.resource.PropertyFilter;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.sensorhub.impl.service.consys.resource.ResourceLink;
import org.vast.util.Asserts;
import org.vast.util.Bbox;
import org.vast.util.TimeExtent;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;


public abstract class BaseHandler implements IResourceHandler
{
    public static final String INVALID_URI_ERROR_MSG = "Invalid resource URI";
    
    protected final Map<String, IResourceHandler> subResources = new HashMap<>();
    protected final IdEncoders idEncoders;
    protected final CurieResolver curieResolver;
    
    
    @SuppressWarnings("serial")
    public static class IdCollection extends ArrayList<Object>
    {
        boolean isUids;
        
        public boolean isUids()
        {
            return isUids;
        }
        
        @SuppressWarnings({ "unchecked", "rawtypes" })
        public Collection<BigId> getBigIds()
        {
            var col = (Collection)this;
            return (Collection<BigId>)col;
        }
        
        @SuppressWarnings({ "unchecked", "rawtypes" })
        public Collection<String> getUids()
        {
            var col = (Collection)this;
            return (Collection<String>)col;
        }
    }
    
    
    public BaseHandler()
    {
        this.idEncoders = null;
        this.curieResolver = null;
    }
    
    
    public BaseHandler(HandlerContext ctx)
    {
        this.idEncoders = Asserts.checkNotNull(ctx, HandlerContext.class);
        this.curieResolver = Asserts.checkNotNull(ctx.getCurieResolver(), IdEncoders.class);
    }
    
    
    protected BigId decodeID(final String id) throws InvalidRequestException
    {
        throw new UnsupportedOperationException();
    }
    

    @Override
    public void addSubResource(IResourceHandler handler)
    {
        addSubResource(handler, handler.getNames());
    }
    

    @Override
    public void addSubResource(IResourceHandler handler, String... names)
    {
        for (var name: names)
            subResources.put(name, handler);
    }
    

    protected IResourceHandler getSubResource(RequestContext ctx) throws InvalidRequestException
    {
        if (ctx == null || ctx.isEndOfPath())
            throw ServiceErrors.badRequest("Missing resource name");
        
        String resourceName = ctx.popNextPathElt();
        IResourceHandler resource = subResources.get(resourceName);
        if (resource == null)
            throw ServiceErrors.badRequest("Invalid resource name: '" + resourceName + "'");
        
        return resource;
    }
    
    
    protected PropertyFilter parseSelectArg(final Map<String, String[]> queryParams) throws InvalidRequestException
    {
        var paramValues = queryParams.get("select");
                
        if (paramValues != null)
        {
            var propFilter = new PropertyFilter();
            
            for (String val: paramValues)
            {
                for (String item: val.split(","))
                {
                    item = item.trim();
                    if (item.isEmpty())
                        throw ServiceErrors.badRequest("Invalid select parameter: " + val);
                    
                    else if (item.startsWith("!"))
                        propFilter.getExcludedProps().add(item.substring(1));
                    else
                        propFilter.getIncludedProps().add(item);
                }
                
                if (propFilter.getIncludedProps().isEmpty() && propFilter.getExcludedProps().isEmpty())
                    throw ServiceErrors.badRequest("Invalid select parameter: " + val);
            }
            
            return propFilter;
        }
        
        return null;
    }
    
    
    protected ResourceFormat parseFormat(final Map<String, String[]> queryParams) throws InvalidRequestException
    {
        // defaults to json;
        return parseFormat(queryParams, ResourceFormat.AUTO);
    }
    
    
    protected ResourceFormat parseFormat(final Map<String, String[]> queryParams, ResourceFormat defaultFormat) throws InvalidRequestException
    {
        var format = parseFormat("f", queryParams);
        if (format == null)
            format = parseFormat("format", queryParams);
        if (format == null)
            format = defaultFormat;
        
        return format;
    }
    
    
    protected ResourceFormat parseFormat(final String paramName, final Map<String, String[]> queryParams) throws InvalidRequestException
    {
        var formats = queryParams.get(paramName);
        if (formats == null)
            return null;
        var format = formats[0];
        
        // convert short format names
        var resFormat = ResourceFormat.fromShortName(format);
        if (resFormat == null)
            resFormat = ResourceFormat.fromMimeType(format);
        
        return resFormat;
    }
    
    
    protected Collection<BigId> parseResourceIds(String paramName, final Map<String, String[]> queryParams, IdEncoder idEncoder) throws InvalidRequestException
    {
        var allValues = new ArrayList<BigId>();
        
        var paramValues = parseMultiValuesArg(paramName, queryParams);
        if (paramValues != null)
        {
            for (String id: paramValues)
            {
                try
                {
                    var internalID = decodeID(id);
                    allValues.add(internalID);
                }
                catch (IllegalArgumentException e)
                {
                    throw ServiceErrors.badRequest("Invalid resource ID: " + id);
                }
            }
        }
        
        return allValues;
    }
    
    
    protected IdCollection parseResourceIdsOrUids(String paramName, final Map<String, String[]> queryParams, IdEncoder idEncoder) throws InvalidRequestException
    {
        var allValues = new IdCollection();
        
        var paramValues = parseMultiValuesArg(paramName, queryParams);
        if (paramValues != null)
        {
            // check if they are IDs or UIDs
            boolean hasBigIds = false;
            boolean hasUris = false;
            for (String id: paramValues)
            {
                // check for : char since it's mandatory in a URI
                // but not allowed in BigId
                if (id.contains(":"))
                    hasUris = true;
                else
                    hasBigIds = true;
            }
            
            if (hasUris && hasBigIds)
                throw ServiceErrors.badRequest("The ID list cannot mix unique IDs (URIs) and local IDs");
            
            // create UID collection only if features are selected
            // anything else will need a list of internal IDs
            allValues.isUids = hasUris && idEncoder instanceof FeatureIdEncoder;
            
            for (String id: paramValues)
            {
                if (hasBigIds)
                {   
                    try
                    {
                        var internalID = idEncoder.decodeID(id);
                        allValues.add(internalID);
                    }
                    catch (IllegalArgumentException e)
                    {
                        throw ServiceErrors.badRequest("Invalid resource ID: " + id);
                    }
                }
                else
                {
                    // expand CURIEs if needed
                    if (curieResolver != null)
                    {
                        var uri = curieResolver.maybeExpand(id);
                        allValues.add(uri);
                    }
                    else
                        allValues.add(id);
                }
            }
        }
        
        return allValues;
    }
    
    
    protected TemporalFilter parseTimeStampArg(String paramName, final Map<String, String[]> queryParams) throws InvalidRequestException
    {
        return parseTimeStampArgToBuilder(paramName, queryParams).build();
    }


    protected TemporalFilter.Builder parseTimeStampArgToBuilder(String paramName, final Map<String, String[]> queryParams) throws InvalidRequestException
    {
        var timeVal = getSingleParam(paramName, queryParams);
        if (timeVal == null)
            return null;

        try
        {
            if (timeVal.equals("latest"))
            {
                return new TemporalFilter.Builder()
                        .withLatestTime();
            }
            else if (timeVal.startsWith("latest/"))
            {
                return new TemporalFilter.Builder()
                        .withLatestTime()
                        .withRangeBeginningNow(Instant.MAX);
            }
            else
            {
                return new TemporalFilter.Builder()
                        .fromTimeExtent(TimeExtent.parse(timeVal));
            }
        }
        catch (Exception e)
        {
            throw ServiceErrors.badRequest("Invalid time parameter: " + timeVal);
        }
    }
    
    
    protected Bbox parseBboxArg(String paramName, final Map<String, String[]> queryParams) throws InvalidRequestException
    {
        var bboxCoords = parseMultiValuesArg(paramName, queryParams);
        if (bboxCoords == null || bboxCoords.isEmpty())
            return null;
        
        try
        {
            Bbox bbox = new Bbox();
            bbox.setMinX(Double.parseDouble(bboxCoords.get(0)));
            bbox.setMinY(Double.parseDouble(bboxCoords.get(1)));
            bbox.setMaxX(Double.parseDouble(bboxCoords.get(2)));
            bbox.setMaxY(Double.parseDouble(bboxCoords.get(3)));
            bbox.checkValid();
            return bbox;
        }
        catch (Exception e)
        {
            throw ServiceErrors.badRequest("Invalid bounding box: " + bboxCoords);
        }
    }
    
    
    protected Geometry parseGeomArg(String paramName, final Map<String, String[]> queryParams) throws InvalidRequestException
    {
        var wkt = getSingleParam(paramName, queryParams);
        if (wkt == null)
            return null;
        
        try
        {
            return new WKTReader().read(wkt);
        }
        catch (ParseException e)
        {
            throw ServiceErrors.badRequest("Invalid geometry: " + wkt);
        }
    }
    
    
    protected Long parseLongArg(String paramName, final Map<String, String[]> queryParams) throws InvalidRequestException
    {
        var paramValue = getSingleParam(paramName, queryParams);
        if (paramValue == null)
            return null;
        
        try
        {
            return Long.parseLong(paramValue);
        }
        catch (NumberFormatException e)
        {
            throw ServiceErrors.badRequest("Invalid " + paramName + " parameter: " + paramValue);
        }
    }
    
    
    protected Double parseDoubleArg(String paramName, final Map<String, String[]> queryParams) throws InvalidRequestException
    {
        var paramValue = getSingleParam(paramName, queryParams);
        if (paramValue == null)
            return null;
        
        try
        {
            return Double.parseDouble(paramValue);
        }
        catch (NumberFormatException e)
        {
            throw ServiceErrors.badRequest("Invalid " + paramName + " parameter: " + paramValue);
        }
    }
    
    
    protected List<String> parseMultiValuesArg(String paramName, final Map<String, String[]> queryParams)
    {
        var allValues = new ArrayList<String>();
        
        var paramValues = queryParams.get(paramName);
        if (paramValues != null)
        {
            for (String val: paramValues)
            {
                for (String item: val.split(","))
                {
                    if (!item.isBlank())
                        allValues.add(item);
                }
            }
        }
        
        return allValues;
    }
    
    
    protected String getSingleParam(String paramName, final Map<String, String[]> queryParams) throws InvalidRequestException
    {
        var paramValues = parseMultiValuesArg(paramName, queryParams);
        
        if (paramValues.size() > 1)
            throw ServiceErrors.badRequest("Parameter '" + paramName + "' must have a single value");
        
        if (paramValues.isEmpty())
            return null;
        
        return paramValues.iterator().next();
    }
    
    
    protected Collection<ResourceLink> getPagingLinks(final RequestContext ctx, long offset, long limit, boolean hasMore) throws InvalidRequestException
    {
        var resourcePath = ctx.getRequestUrl();
        var queryParams = new HashMap<>(ctx.getParameterMap());
        var links = new ArrayList<ResourceLink>();
        
        // prev link
        if (offset > 0)
        {
            var prevOffset = Math.max(0, offset-limit);
            queryParams.put("offset", new String[] { Long.toString(prevOffset) });
            
            links.add(new ResourceLink.Builder()
                .rel("prev")
                .href(resourcePath + RequestContext.buildQueryString(queryParams))
                .type(ctx.getFormat().getMimeType())
                .build());
        }
        
        // next link
        if (hasMore)
        {
            var nextOffset = offset+limit;
            queryParams.put("offset", new String[] { Long.toString(nextOffset) });
            
            links.add(new ResourceLink.Builder()
                .rel("next")
                .href(resourcePath + RequestContext.buildQueryString(queryParams))
                .type(ctx.getFormat().getMimeType())
                .build());
        }
        
        return links;
    }
    

}