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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.sensorhub.api.common.BigId;
import org.sensorhub.impl.service.consys.RestApiSecurity;
import org.sensorhub.impl.service.consys.RestApiServlet;
import org.sensorhub.impl.service.consys.stream.StreamHandler;
import org.slf4j.Logger;
import org.vast.util.Asserts;
import com.google.common.base.Strings;


public class RequestContext
{
    private final RestApiServlet servlet;
    private final HttpServletRequest req;
    private final HttpServletResponse resp;
    private final StreamHandler streamHandler;
    private final InputStream inputStream;
    private final String requestPathInfo;
    private final Deque<String> path;
    private final Map<String, String[]> queryParams;
    private String contentType;
    private ResourceRef parentResource = new ResourceRef();
    private ResourceFormat format;
    private PropertyFilter propFilter;
    private Set<String> resourceUris;
    private long correlationID;
    private long requestTimeout;
    
    
    /*
     * Auxiliary data generated during the request handling process for
     * consumption by later processing stages
     */
    Object data;
    
    
    public static class ResourceRef
    {
        @SuppressWarnings("rawtypes")
        public BaseResourceHandler type;
        public BigId internalID;
        public Instant validTime;
        public String id;
    }
    
    
    /*
     * Constructor for use in client POST
     */
    public RequestContext(StreamHandler streamHandler)
    {
        this.servlet = null;
        this.req = null;
        this.resp = null;
        this.requestPathInfo = null;
        this.streamHandler = Asserts.checkNotNull(streamHandler, StreamHandler.class);
        this.inputStream = null;
        this.path = null;
        this.queryParams = null;
    }
    
    
    /*
     * Constructor for use in client GET
     */
    public RequestContext(InputStream bodyInputStream)
    {
        this.servlet = null;
        this.req = null;
        this.resp = null;
        this.requestPathInfo = null;
        this.streamHandler = null;
        this.inputStream = Asserts.checkNotNull(bodyInputStream, InputStream.class);
        this.path = null;
        this.queryParams = null;
    }
    
    
    /*
     * Constructor for classic HTTP requests
     */
    public RequestContext(RestApiServlet servlet, HttpServletRequest req, HttpServletResponse resp)
    {
        this.servlet = Asserts.checkNotNull(servlet, Servlet.class);
        this.req = Asserts.checkNotNull(req, HttpServletRequest.class);
        this.resp = Asserts.checkNotNull(resp, HttpServletResponse.class);
        this.requestPathInfo = req.getPathInfo();
        this.streamHandler = null;
        this.inputStream = null;
        this.path = splitPath(req.getPathInfo());
        this.queryParams = req.getParameterMap();
        this.contentType = req.getContentType();
    }
    
    
    /*
     * Constructor for streaming websocket requests
     */
    public RequestContext(RestApiServlet servlet, HttpServletRequest req, HttpServletResponse resp, StreamHandler streamHandler)
    {
        this.servlet = Asserts.checkNotNull(servlet, Servlet.class);
        this.req = Asserts.checkNotNull(req, HttpServletRequest.class);
        this.resp = Asserts.checkNotNull(resp, HttpServletResponse.class);
        this.requestPathInfo = req.getPathInfo();
        this.streamHandler = Asserts.checkNotNull(streamHandler, StreamHandler.class);
        this.inputStream = null;
        this.path = splitPath(req.getPathInfo());
        this.queryParams = req.getParameterMap();
        this.contentType = req.getContentType();
    }
    
    
    /*
     * Constructor for other protocols providing their own path/params and input stream (e.g. MQTT publish)
     */
    public RequestContext(RestApiServlet servlet, URI resourceURI, InputStream is)
    {
        this.servlet = Asserts.checkNotNull(servlet, Servlet.class);
        this.req = null;
        this.resp = null;
        this.requestPathInfo = null;
        this.streamHandler = null;
        this.inputStream = Asserts.checkNotNull(is, InputStream.class);
        this.path = splitPath(resourceURI.getPath());
        this.queryParams = parseQueryParams(resourceURI.getQuery());
    }
    
    
    /*
     * Constructor for other protocols providing their own path/params and stream handler (e.g. MQTT subscribe)
     */
    public RequestContext(RestApiServlet servlet, URI resourceURI, StreamHandler streamHandler)
    {
        this.servlet = Asserts.checkNotNull(servlet, Servlet.class);
        this.req = null;
        this.resp = null;
        this.requestPathInfo = null;
        this.streamHandler = Asserts.checkNotNull(streamHandler, StreamHandler.class);
        this.inputStream = null;
        this.path = splitPath(resourceURI.getPath());
        this.queryParams = parseQueryParams(resourceURI.getQuery());
    }
    
    
    private Deque<String> splitPath(String pathStr)
    {
        if (pathStr != null)
        {
            String[] pathElts = pathStr.split("/");
            var path = new ArrayDeque<String>(pathElts.length);
            for (String elt: pathElts)
            {
                if (!Strings.isNullOrEmpty(elt))
                    path.addLast(elt);
            }
            
            return path;
        }
        
        return new ArrayDeque<String>(0);
    }
    
    
    private Map<String, String[]> parseQueryParams(String queryStr)
    {
        // parse query params
        Map<String, String[]> params = new HashMap<>();
        if (!Strings.isNullOrEmpty(queryStr))
        {
            for (var param: queryStr.split("&"))
            {
                var tokens = param.split("=");
                if (tokens.length == 2)
                    params.put(tokens[0], new String[] {tokens[1]});
                else
                    params.put(tokens[0], null);
            }
        }
        
        return params;
    }
    
    
    public boolean isEndOfPath()
    {
        return path.isEmpty();
    }
    
    
    public String popNextPathElt()
    {
        if (path.isEmpty())
            return null;
        return path.pollFirst();
    }
    
    
    public BigId getParentID()
    {
        return parentResource.internalID;
    }
    
    
    public ResourceRef getParentRef()
    {
        return parentResource;
    }
    
    
    public void setParent(@SuppressWarnings("rawtypes") BaseResourceHandler parentHandler, String id)
    {
        parentResource.type = parentHandler;
        parentResource.id = id;
    }
    
    
    public void setParent(@SuppressWarnings("rawtypes") BaseResourceHandler parentHandler, String id, BigId internalID)
    {
        parentResource.type = parentHandler;
        parentResource.id = id;
        parentResource.internalID = internalID;
    }
    
    
    public void setParent(@SuppressWarnings("rawtypes") BaseResourceHandler parentHandler, String id, BigId internalID, Instant validTime)
    {
        parentResource.type = parentHandler;
        parentResource.id = id;
        parentResource.internalID = internalID;
        parentResource.validTime = validTime;
    }
    
    
    public void setResponseFormat(ResourceFormat format)
    {
        setFormat(format);
        this.setResponseContentType(format.getMimeType());
    }
    
    
    public void setFormatOptions(ResourceFormat format, PropertyFilter propFilter)
    {
        setResponseFormat(format);
        this.propFilter = propFilter;
    }
    
    
    public void setFormat(ResourceFormat format)
    {
        this.format = Asserts.checkNotNull(format, ResourceFormat.class);
    }
    
    
    public ResourceFormat getFormat()
    {
        return format;
    }


    public PropertyFilter getPropertyFilter()
    {
        return propFilter;
    }


    public Object getData()
    {
        return data;
    }

    
    public void setData(Object data)
    {
        this.data = data;
    }
    
    
    public String getApiRootURL()
    {
        return servlet != null ? servlet.getApiRootURL(req) : "/";
    }
    
    
    public String getRequestPath()
    {
        return requestPathInfo != null ? requestPathInfo : "";
    }
    
    
    public String getRequestUrl()
    {
        if (req == null)
            return null;
        
        return getApiRootURL() + getRequestPath();
    }
    
    
    public String getRequestUrlWithQuery(Map<String, String[]> queryParams)
    {
        return getRequestUrlWithQuery(queryParams, false);
    }
    
    
    public String getRequestUrlWithQuery(Map<String, String[]> queryParams, boolean appendToExistingParams)
    {
        if (req == null)
            return null;
        
        if (appendToExistingParams)
        {
            var allQueryParams = new HashMap<>(this.queryParams);
            allQueryParams.putAll(queryParams);
            queryParams = allQueryParams;
        }
        
        return getRequestUrl() + buildQueryString(queryParams);
    }
    
    
    public String getRequestContentType()
    {
        return this.contentType;
    }
    
    
    public void setRequestContentType(String contentType)
    {
        this.contentType = contentType;
    }
    
    
    public String getRequestHeader(String name)
    {
        return req != null ? req.getHeader(name) : null;
    }
    
    
    public boolean isBrowserHtmlRequest()
    {
        var acceptHdr = getRequestHeader("Accept");
        return acceptHdr != null && acceptHdr.contains("text/html");
    }
    
    
    public long getRequestTimeout()
    {
        return requestTimeout > 0 ? requestTimeout :
               req != null ? req.getAsyncContext().getTimeout()-500 : 0;
    }
    
    
    /**
     * Sets the request timeout.
     * If not set, it will default to the timeout configured by the HTTP server.
     * @param requestTimeout The timeout value in milliseconds
     */
    public void setRequestTimeout(long requestTimeout)
    {
        Asserts.checkArgument(requestTimeout > 0, "Timeout must be > 0");
        this.requestTimeout = requestTimeout;
    }
    
    
    public void setResponseContentType(String mimeType)
    {
        if (resp != null)
            resp.setContentType(mimeType);
    }
    
    
    public void setResponseHeader(String name, String value)
    {
        if (resp != null)
            resp.addHeader(name, value);
    }
    
    
    public Map<String, String[]> getParameterMap()
    {
        return queryParams;
    }
    
    
    public String getParameter(String name)
    {
        var paramValues = queryParams.get(name);
        if (paramValues != null && paramValues.length > 0)
            return paramValues[0];
        else
            return null;
    }
    
    
    public StreamHandler getStreamHandler()
    {
        return streamHandler;
    }
    
    
    public InputStream getInputStream() throws IOException
    {
        return req != null ? new BufferedInputStream(req.getInputStream()) : inputStream;
    }
    
    
    public OutputStream getOutputStream() throws IOException
    {
        if (streamHandler != null)
            return streamHandler.getOutputStream();
        else if (resp != null)
            return resp.getOutputStream();
        else
            return null;
    }
    
    
    public boolean isStreamRequest()
    {
        return streamHandler != null;
    }


    public Set<String> getResourceUris()
    {
        return resourceUris != null ? resourceUris : Collections.emptySet();
    }


    public void addResourceUri(String uri)
    {
        if (resourceUris == null)
            resourceUris = new LinkedHashSet<>();
        resourceUris.add(uri);
    }


    public long getCorrelationID()
    {
        return correlationID;
    }
    
    
    public void setCorrelationID(long id)
    {
        this.correlationID = id;
    }
    
    
    public Logger getLogger()
    {
        return servlet.getLogger();
    }
    
    
    public RestApiSecurity getSecurityHandler()
    {
        return servlet.getSecurityHandler();
    }
    
    
    public static String buildQueryString(Map<String, String[]> queryParams)
    {
        var buf = new StringBuilder();
        buf.append('?');
        
        for (var e: queryParams.entrySet())
        {
            buf.append(e.getKey()).append("=");
            for (var s: e.getValue())
                buf.append(URLEncoder.encode(s,  StandardCharsets.UTF_8)).append(',');
            buf.setCharAt(buf.length()-1, '&');
        }
        
        buf.setLength(buf.length()-1);
        return buf.toString();
    }
}
