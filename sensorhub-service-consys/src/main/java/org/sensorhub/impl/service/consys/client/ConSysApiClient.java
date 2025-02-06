/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2023 Sensia Software LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.net.http.HttpResponse.ResponseInfo;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import net.opengis.swe.v20.BinaryEncoding;
import org.sensorhub.api.command.CommandStreamInfo;
import org.sensorhub.api.command.ICommandData;
import org.sensorhub.api.command.ICommandStreamInfo;
import org.sensorhub.api.data.DataStreamInfo;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.data.IObsData;
import org.sensorhub.api.procedure.IProcedureWithDesc;
import org.sensorhub.api.semantic.IDerivedProperty;
import org.sensorhub.api.system.ISystemWithDesc;
import org.sensorhub.impl.service.consys.ResourceParseException;
import org.sensorhub.impl.service.consys.obs.DataStreamBindingJson;
import org.sensorhub.impl.service.consys.obs.DataStreamSchemaBindingOmJson;
import org.sensorhub.impl.service.consys.procedure.ProcedureBindingGeoJson;
import org.sensorhub.impl.service.consys.procedure.ProcedureBindingSmlJson;
import org.sensorhub.impl.service.consys.property.PropertyBindingJson;
import org.sensorhub.api.datastore.obs.IObsStore;
import org.sensorhub.impl.service.consys.obs.ObsBindingOmJson;
import org.sensorhub.impl.service.consys.obs.ObsBindingSweCommon;
import org.sensorhub.impl.service.consys.obs.ObsHandler;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.sensorhub.impl.service.consys.resource.ResourceLink;
import org.sensorhub.impl.service.consys.system.SystemBindingGeoJson;
import org.sensorhub.impl.service.consys.system.SystemBindingSmlJson;
import org.sensorhub.impl.service.consys.task.CommandStreamBindingJson;
import org.sensorhub.impl.service.consys.task.CommandStreamSchemaBindingJson;
import org.sensorhub.utils.Lambdas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.util.Asserts;
import org.vast.util.BaseBuilder;
import com.google.common.base.Strings;
import com.google.common.net.HttpHeaders;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;


public class ConSysApiClient
{
    static final String PROPERTIES_COLLECTION = "properties";
    static final String PROCEDURES_COLLECTION = "procedures";
    static final String SYSTEMS_COLLECTION = "systems";
    static final String DEPLOYMENTS_COLLECTION = "deployments";
    static final String DATASTREAMS_COLLECTION = "datastreams";
    static final String CONTROLS_COLLECTION = "controlstreams";
    static final String OBSERVATIONS_COLLECTION = "observations";
    static final String SUBSYSTEMS_COLLECTION = "subsystems";
    static final String SF_COLLECTION = "fois";

    static final Logger log = LoggerFactory.getLogger(ConSysApiClient.class);
    
    HttpClient http;
    URI endpoint;


    protected ConSysApiClient() {}
    
    
    /*------------*/
    /* Properties */
    /*------------*/
    
    public CompletableFuture<IDerivedProperty> getPropertyById(String id, ResourceFormat format)
    {
        return sendGetRequest(endpoint.resolve(PROPERTIES_COLLECTION + "/" + id), format, body -> {
            try
            {
                var ctx = new RequestContext(body);
                var binding = new PropertyBindingJson(ctx, null, null, true);
                return binding.deserialize();
            }
            catch (IOException e)
            {
                e.printStackTrace();
                throw new CompletionException(e);
            }
        });
    }
    
    
    public CompletableFuture<IDerivedProperty> getPropertyByUri(String uri, ResourceFormat format)
    {
        try
        {
            return sendGetRequest(new URI(uri), format, body -> {
                try
                {
                    var ctx = new RequestContext(body);
                    var binding = new PropertyBindingJson(ctx, null, null, true);
                    return binding.deserialize();
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                    throw new CompletionException(e);
                }
            });
        }
        catch (URISyntaxException e)
        {
            throw new IllegalArgumentException("Invalid property URI: " + uri);
        }
    }
    
    
    public CompletableFuture<String> addProperty(IDerivedProperty prop)
    {
        try
        {
            var buffer = new ByteArrayOutputStream();
            var ctx = new RequestContext(buffer);
            
            var binding = new PropertyBindingJson(ctx, null, null, false);
            binding.serialize(null, prop, false);
            
            return sendPostRequest(
                endpoint.resolve(PROPERTIES_COLLECTION),
                ResourceFormat.JSON,
                buffer.toByteArray());
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Error initializing binding", e);
        }
    }
    
    
    public CompletableFuture<Set<String>> addProperties(IDerivedProperty... properties)
    {
        return addProperties(Arrays.asList(properties));
    }
    
    
    public CompletableFuture<Set<String>> addProperties(Collection<IDerivedProperty> properties)
    {
        try
        {
            var buffer = new ByteArrayOutputStream();
            var ctx = new RequestContext(buffer);
            
            var binding = new PropertyBindingJson(ctx, null, null, false) {
                protected void startJsonCollection(JsonWriter writer) throws IOException
                {
                    writer.beginArray();
                }
                
                protected void endJsonCollection(JsonWriter writer, Collection<ResourceLink> links) throws IOException
                {
                    writer.endArray();
                    writer.flush();
                }
            };
            
            binding.startCollection();
            for (var prop: properties)
                binding.serialize(null, prop, false);
            binding.endCollection(Collections.emptyList());
            
            return sendBatchPostRequest(
                endpoint.resolve(PROPERTIES_COLLECTION),
                ResourceFormat.JSON,
                buffer.toByteArray());
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Error initializing binding", e);
        }
    }
    
    
    /*------------*/
    /* Procedures */
    /*------------*/
    
    public CompletableFuture<IProcedureWithDesc> getProcedureById(String id, ResourceFormat format)
    {
        return sendGetRequest(endpoint.resolve(PROCEDURES_COLLECTION + "/" + id), format, body -> {
            try
            {
                var ctx = new RequestContext(body);
                var binding = new ProcedureBindingGeoJson(ctx, null, null, true);
                return binding.deserialize();
            }
            catch (IOException e)
            {
                e.printStackTrace();
                throw new CompletionException(e);
            }
        });
    }
    
    
    public CompletableFuture<IProcedureWithDesc> getProcedureByUid(String uid, ResourceFormat format)
    {
        return sendGetRequest(endpoint.resolve(PROCEDURES_COLLECTION + "?uid=" + uid), format, body -> {
            try
            {
                var ctx = new RequestContext(body);
                
                // use modified binding since the response contains a feature collection
                var binding = new ProcedureBindingGeoJson(ctx, null, null, true) {
                    public IProcedureWithDesc deserialize(JsonReader reader) throws IOException
                    {
                        skipToCollectionItems(reader);
                        return super.deserialize(reader);
                    }
                };
                
                return binding.deserialize();
            }
            catch (IOException e)
            {
                e.printStackTrace();
                throw new CompletionException(e);
            }
        });
    }
    
    
    public CompletableFuture<String> addProcedure(IProcedureWithDesc system)
    {
        try
        {
            var buffer = new ByteArrayOutputStream();
            var ctx = new RequestContext(buffer);
            
            var binding = new ProcedureBindingSmlJson(ctx, null, false);
            binding.serialize(null, system, false);
            
            return sendPostRequest(
                endpoint.resolve(PROCEDURES_COLLECTION),
                ResourceFormat.SML_JSON,
                buffer.toByteArray());
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Error initializing binding", e);
        }
    }
    
    
    public CompletableFuture<Set<String>> addProcedures(IProcedureWithDesc... systems)
    {
        return addProcedures(Arrays.asList(systems));
    }
    
    
    public CompletableFuture<Set<String>> addProcedures(Collection<IProcedureWithDesc> systems)
    {
        try
        {
            var buffer = new ByteArrayOutputStream();
            var ctx = new RequestContext(buffer);
            
            var binding = new ProcedureBindingSmlJson(ctx, null, false) {
                protected void startJsonCollection(JsonWriter writer) throws IOException
                {
                    writer.beginArray();
                }
                
                protected void endJsonCollection(JsonWriter writer, Collection<ResourceLink> links) throws IOException
                {
                    writer.endArray();
                    writer.flush();
                }
            };
            
            binding.startCollection();
            for (var sys: systems)
                binding.serialize(null, sys, false);
            binding.endCollection(Collections.emptyList());
            
            return sendBatchPostRequest(
                endpoint.resolve(PROCEDURES_COLLECTION),
                ResourceFormat.SML_JSON,
                buffer.toByteArray());
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Error initializing binding", e);
        }
    }
    
    
    /*---------*/
    /* Systems */
    /*---------*/

    public CompletableFuture<ISystemWithDesc> getSystemById(String id, ResourceFormat format)
    {
        return sendGetRequest(endpoint.resolve(SYSTEMS_COLLECTION + "/" + id), format, body -> {
            try
            {
                var ctx = new RequestContext(body);
                var binding = new SystemBindingGeoJson(ctx, null, null, true);
                return binding.deserialize();
            }
            catch (IOException e)
            {
                e.printStackTrace();
                throw new CompletionException(e);
            }
        });
    }

    public CompletableFuture<ISystemWithDesc> getSystemByUid(String uid, ResourceFormat format) throws ExecutionException, InterruptedException
    {
        return sendGetRequest(endpoint.resolve(SYSTEMS_COLLECTION + "?uid=" + uid), format, body -> {
            try
            {
                var ctx = new RequestContext(body);
                
                // use modified binding since the response contains a feature collection
                var binding = new SystemBindingGeoJson(ctx, null, null, true) {
                    public ISystemWithDesc deserialize(JsonReader reader) throws IOException
                    {
                        skipToCollectionItems(reader);
                        return super.deserialize(reader);
                    }
                };
                
                return binding.deserialize();
            }
            catch (IOException e)
            {
                e.printStackTrace();
                throw new CompletionException(e);
            }
        });
    }


    public CompletableFuture<String> addSystem(ISystemWithDesc system)
    {
        try
        {
            var buffer = new ByteArrayOutputStream();
            var ctx = new RequestContext(buffer);

            var binding = new SystemBindingSmlJson(ctx, null, false);
            binding.serialize(null, system, false);

            return sendPostRequest(
                endpoint.resolve(SYSTEMS_COLLECTION),
                ResourceFormat.SML_JSON,
                buffer.toByteArray());
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Error initializing binding", e);
        }
    }

    public CompletableFuture<Integer> updateSystem(String systemID, ISystemWithDesc system)
    {
        try
        {
            var buffer = new ByteArrayOutputStream();
            var ctx = new RequestContext(buffer);

            var binding = new SystemBindingSmlJson(ctx, null, false);
            binding.serialize(null, system, false);

            return sendPutRequest(
                    endpoint.resolve(SYSTEMS_COLLECTION + "/" + systemID),
                    ResourceFormat.SML_JSON,
                    buffer.toByteArray());
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Error initializing binding", e);
        }
    }

    public CompletableFuture<String> addSubSystem(String systemID, ISystemWithDesc system)
    {
        try
        {
            var buffer = new ByteArrayOutputStream();
            var ctx = new RequestContext(buffer);

            var binding = new SystemBindingSmlJson(ctx, null, false);
            binding.serialize(null, system, false);

            return sendPostRequest(
                    endpoint.resolve(SYSTEMS_COLLECTION + "/" + systemID + "/" + SUBSYSTEMS_COLLECTION),
                    ResourceFormat.SML_JSON,
                    buffer.toByteArray());
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Error initializing binding", e);
        }
    }

    public CompletableFuture<Set<String>> addSystems(ISystemWithDesc... systems)
    {
        return addSystems(Arrays.asList(systems));
    }


    public CompletableFuture<Set<String>> addSystems(Collection<ISystemWithDesc> systems)
    {
        try
        {
            var buffer = new ByteArrayOutputStream();
            var ctx = new RequestContext(buffer);

            var binding = new SystemBindingSmlJson(ctx, null, false) {
                protected void startJsonCollection(JsonWriter writer) throws IOException
                {
                    writer.beginArray();
                }

                protected void endJsonCollection(JsonWriter writer, Collection<ResourceLink> links) throws IOException
                {
                    writer.endArray();
                    writer.flush();
                }
            };

            binding.startCollection();
            for (var sys: systems)
                binding.serialize(null, sys, false);
            binding.endCollection(Collections.emptyList());

            return sendBatchPostRequest(
                endpoint.resolve(SYSTEMS_COLLECTION),
                ResourceFormat.SML_JSON,
                buffer.toByteArray());
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Error initializing binding", e);
        }
    }


    /*-------------*/
    /* Datastreams */
    /*-------------*/

    public CompletableFuture<IDataStreamInfo> getDatastreamById(String id, ResourceFormat format, boolean fetchSchema)
    {
        var cf1 = sendGetRequest(endpoint.resolve(DATASTREAMS_COLLECTION + "/" + id), format, body -> {
            try
            {
                var ctx = new RequestContext(body);
                var binding = new DataStreamBindingJson(ctx, null, null, true, Collections.emptyMap());
                return binding.deserialize();
            }
            catch (IOException e)
            {
                e.printStackTrace();
                throw new CompletionException(e);
            }
        });
        
        if (fetchSchema)
        {
            return cf1.thenCombine(getDatastreamSchema(id, ResourceFormat.JSON, ResourceFormat.JSON), (dsInfo, schemaInfo) -> {
                
                schemaInfo.getRecordStructure().setName(dsInfo.getOutputName());
                
                dsInfo = DataStreamInfo.Builder.from(dsInfo)
                    .withRecordDescription(schemaInfo.getRecordStructure())
                    .build();
                
                return dsInfo;
            });
        }
        else
            return cf1;
        
    }
    
    public CompletableFuture<IDataStreamInfo> getDatastreamSchema(String id, ResourceFormat obsFormat, ResourceFormat format)
    {
        return sendGetRequest(endpoint.resolve(DATASTREAMS_COLLECTION + "/" + id + "/schema?obsFormat="+obsFormat), format, body -> {
            try
            {
                var ctx = new RequestContext(body);
                var binding = new DataStreamSchemaBindingOmJson(ctx, null, true);
                return binding.deserialize();
            }
            catch (IOException e)
            {
                e.printStackTrace();
                throw new CompletionException(e);
            }
        });
    }

    public CompletableFuture<String> addDataStream(String systemId, IDataStreamInfo datastream)
    {
        try
        {
            var buffer = new ByteArrayOutputStream();
            var ctx = new RequestContext(buffer);
            
            var binding = new DataStreamBindingJson(ctx, null, null, false, Collections.emptyMap());
            binding.serialize(null, datastream, false);

            return sendPostRequest(
                endpoint.resolve(SYSTEMS_COLLECTION + "/" + systemId + "/" + DATASTREAMS_COLLECTION),
                ResourceFormat.JSON,
                buffer.toByteArray());
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Error initializing binding", e);
        }
    }


    public CompletableFuture<Set<String>> addDataStreams(String systemId, IDataStreamInfo... datastreams)
    {
        return addDataStreams(systemId, Arrays.asList(datastreams));
    }


    public CompletableFuture<Set<String>> addDataStreams(String systemId, Collection<IDataStreamInfo> datastreams)
    {
        try
        {
            var buffer = new ByteArrayOutputStream();
            var ctx = new RequestContext(buffer);
            
            var binding = new DataStreamBindingJson(ctx, null, null, false, Collections.emptyMap()) {
                protected void startJsonCollection(JsonWriter writer) throws IOException
                {
                    writer.beginArray();
                }

                protected void endJsonCollection(JsonWriter writer, Collection<ResourceLink> links) throws IOException
                {
                    writer.endArray();
                    writer.flush();
                }
            };

            binding.startCollection();
            for (var ds: datastreams)
                binding.serialize(null, ds, false);
            binding.endCollection(Collections.emptyList());

            return sendBatchPostRequest(
                endpoint.resolve(SYSTEMS_COLLECTION + "/" + systemId + "/" + DATASTREAMS_COLLECTION),
                ResourceFormat.JSON,
                buffer.toByteArray());
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Error initializing binding", e);
        }
    }


    /*-----------------*/
    /* Control Streams */
    /*-----------------*/

    public CompletableFuture<String> addControlStream(String systemId, ICommandStreamInfo cmdstream)
    {
        try
        {
            var buffer = new ByteArrayOutputStream();
            var ctx = new RequestContext(buffer);
            
            var binding = new CommandStreamBindingJson(ctx, null, null, false);
            binding.serializeCreate(cmdstream);

            return sendPostRequest(
                endpoint.resolve(SYSTEMS_COLLECTION + "/" + systemId + "/" + CONTROLS_COLLECTION),
                ResourceFormat.JSON,
                buffer.toByteArray());
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Error initializing binding", e);
        }
    }


    public CompletableFuture<Set<String>> addControlStreams(String systemId, ICommandStreamInfo... cmdstreams)
    {
        return addControlStreams(systemId, Arrays.asList(cmdstreams));
    }


    public CompletableFuture<Set<String>> addControlStreams(String systemId, Collection<ICommandStreamInfo> cmdstreams)
    {
        try
        {
            var buffer = new ByteArrayOutputStream();
            var ctx = new RequestContext(buffer);
            
            var binding = new CommandStreamBindingJson(ctx, null, null, false) {
                protected void startJsonCollection(JsonWriter writer) throws IOException
                {
                    writer.beginArray();
                }

                protected void endJsonCollection(JsonWriter writer, Collection<ResourceLink> links) throws IOException
                {
                    writer.endArray();
                    writer.flush();
                }
            };

            binding.startCollection();
            for (var ds: cmdstreams)
                binding.serializeCreate(ds);
            binding.endCollection(Collections.emptyList());

            return sendBatchPostRequest(
                endpoint.resolve(SYSTEMS_COLLECTION + "/" + systemId + "/" + CONTROLS_COLLECTION),
                ResourceFormat.JSON,
                buffer.toByteArray());
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Error initializing binding", e);
        }
    }


    public CompletableFuture<ICommandStreamInfo> getControlStreamById(String id, ResourceFormat format, boolean fetchSchema)
    {
        var cf1 = sendGetRequest(endpoint.resolve(CONTROLS_COLLECTION + "/" + id), format, body -> {
            try
            {
                var ctx = new RequestContext(body);
                var binding = new CommandStreamBindingJson(ctx, null, null, true);
                return binding.deserialize();
            }
            catch (IOException e)
            {
                e.printStackTrace();
                throw new CompletionException(e);
            }
        });

        if (fetchSchema)
        {
            return cf1.thenCombine(getControlStreamSchema(id, ResourceFormat.JSON, ResourceFormat.JSON), (csInfo, schemaInfo) -> {

                schemaInfo.getRecordStructure().setName(csInfo.getControlInputName());

                csInfo = CommandStreamInfo.Builder.from(csInfo)
                        .withRecordDescription(schemaInfo.getRecordStructure())
                        .build();

                return csInfo;
            });
        }
        else
            return cf1;

    }


    public CompletableFuture<ICommandStreamInfo> getControlStreamSchema(String id, ResourceFormat obsFormat, ResourceFormat format)
    {
        return sendGetRequest(endpoint.resolve(CONTROLS_COLLECTION + "/" + id + "/schema?obsFormat=" + obsFormat), format, body -> {
            try
            {
                var ctx = new RequestContext(body);
                var binding = new CommandStreamSchemaBindingJson(ctx, null, true);
                return binding.deserialize();
            }
            catch (IOException e)
            {
                e.printStackTrace();
                throw new CompletionException(e);
            }
        });
    }


    /*--------------*/
    /* Observations */
    /*--------------*/
    // TODO: Be able to push different kinds of observations such as video
    public CompletableFuture<String> pushObs(String dataStreamId, IDataStreamInfo dataStream, IObsData obs, IObsStore obsStore)
    {
        try
        {
            ObsHandler.ObsHandlerContextData contextData = new ObsHandler.ObsHandlerContextData();
            contextData.dsInfo = dataStream;

            var buffer = new ByteArrayOutputStream();
            var ctx = new RequestContext(buffer);

            if(dataStream != null && dataStream.getRecordEncoding() instanceof BinaryEncoding) {
                ctx.setData(contextData);
                ctx.setFormat(ResourceFormat.SWE_BINARY);
                var binding = new ObsBindingSweCommon(ctx, null, false, obsStore);
                binding.serialize(null, obs, false);
            } else {
                ctx.setFormat(ResourceFormat.OM_JSON);
                var binding = new ObsBindingOmJson(ctx, null, false, obsStore);
                binding.serialize(null, obs, false);
            }

            return sendPostRequest(
                    endpoint.resolve(DATASTREAMS_COLLECTION + "/" + dataStreamId + "/" + OBSERVATIONS_COLLECTION),
                    ctx.getFormat(),
                    buffer.toByteArray());
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Error initializing binding", e);
        }
    }


    /*----------*/
    /* Commands */
    /*----------*/

    public CompletableFuture<String> sendCommand(String controlId, ICommandData cmd)
    {
        return null;
    }
    
    
    
    /*----------------*/
    /* Helper Methods */
    /*----------------*/

    protected <T> CompletableFuture<T> sendGetRequest(URI collectionUri, ResourceFormat format, Function<InputStream, T> bodyMapper)
    {
        var req = HttpRequest.newBuilder()
            .uri(collectionUri)
            .GET()
            .header(HttpHeaders.ACCEPT, format.getMimeType())
            .build();

        var bodyHandler = new BodyHandler<T>() {
            @Override
            public BodySubscriber<T> apply(ResponseInfo resp)
            {
                //var upstream = BodySubscribers.ofInputStream();
                var upstream = BodySubscribers.ofByteArray();
                return BodySubscribers.mapping(upstream, body -> {
                    log.debug("GET response\n{}", new String(body));
                    var is = new ByteArrayInputStream(body);
                    return bodyMapper.apply(is);
                });
            }
        };

        return http.sendAsync(req, bodyHandler)
            .thenApply(resp ->  {
                if (resp.statusCode() == 200)
                    return resp.body();
                else
                    throw new CompletionException("HTTP error " + resp.statusCode(), null);
            });
    }

    protected CompletableFuture<String> sendPostRequest(URI collectionUri, ResourceFormat format, byte[] body)
    {
        var req = HttpRequest.newBuilder()
            .uri(collectionUri)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .header(HttpHeaders.ACCEPT, ResourceFormat.JSON.getMimeType())
            .header(HttpHeaders.CONTENT_TYPE, format.getMimeType())
            .build();

        return http.sendAsync(req, BodyHandlers.ofString())
            .thenApply(resp ->  {
                if (resp.statusCode() == 201 || resp.statusCode() == 303)
                {
                    var location = resp.headers()
                        .firstValue(HttpHeaders.LOCATION)
                        .orElseThrow(() -> new IllegalStateException("Missing Location header in response"));
                    return location.substring(location.lastIndexOf('/')+1);
                }
                else
                    throw new CompletionException(resp.body(), null);
            });
    }

    protected CompletableFuture<Integer> sendPutRequest(URI collectionUri, ResourceFormat format, byte[] body)
    {
        var req = HttpRequest.newBuilder()
                .uri(collectionUri)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .header(HttpHeaders.ACCEPT, ResourceFormat.JSON.getMimeType())
                .header(HttpHeaders.CONTENT_TYPE, format.getMimeType())
                .build();

        return http.sendAsync(req, BodyHandlers.ofString())
                .thenApply(HttpResponse::statusCode);
    }


    protected CompletableFuture<Set<String>> sendBatchPostRequest(URI collectionUri, ResourceFormat format, byte[] body)
    {
        var req = HttpRequest.newBuilder()
            .uri(collectionUri)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .header(HttpHeaders.CONTENT_TYPE, format.getMimeType())
            .build();

        return http.sendAsync(req, BodyHandlers.ofString())
            .thenApply(Lambdas.checked(resp ->  {
                if (resp.statusCode() == 201 || resp.statusCode() == 303)
                {
                    var idList = new LinkedHashSet<String>();
                    try (JsonReader reader = new JsonReader(new StringReader(resp.body())))
                    {
                        reader.beginArray();
                        while (reader.hasNext())
                        {
                            var uri = reader.nextString();
                            idList.add(uri.substring(uri.lastIndexOf('/')+1));
                        }
                        reader.endArray();
                    }
                    return idList;
                }
                else
                    throw new ResourceParseException(resp.body());
            }));
    }
    
    
    protected void skipToCollectionItems(JsonReader reader) throws IOException
    {
        // skip to array of collection items
        reader.beginObject();
        while (reader.hasNext())
        {
            var name = reader.nextName();
            if ("items".equals(name) || "features".equals(name))
                break;
            else
                reader.skipValue();
        }
    }
    


    /* Builder stuff */

    public static ConSysApiClientBuilder newBuilder(String endpoint)
    {
        Asserts.checkNotNull(endpoint, "endpoint");
        return new ConSysApiClientBuilder(endpoint);
    }


    public static class ConSysApiClientBuilder extends BaseBuilder<ConSysApiClient>
    {
        HttpClient.Builder httpClientBuilder;


        ConSysApiClientBuilder(String endpoint)
        {
            this.instance = new ConSysApiClient();
            this.httpClientBuilder = HttpClient.newBuilder();

            try
            {
                if (!endpoint.endsWith("/"))
                    endpoint += "/";
                instance.endpoint = new URI(endpoint);
            }
            catch (URISyntaxException e)
            {
                throw new IllegalArgumentException("Invalid URI " + endpoint);
            }
        }


        public ConSysApiClientBuilder useHttpClient(HttpClient http)
        {
            instance.http = http;
            return this;
        }


        public ConSysApiClientBuilder simpleAuth(String user, char[] password)
        {
            if (!Strings.isNullOrEmpty(user))
            {
                var finalPwd = password != null ? password : new char[0];
                httpClientBuilder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(user, finalPwd);
                    }
                });
            }

            return this;
        }


        public ConSysApiClient build()
        {
            if (instance.http == null)
                instance.http = httpClientBuilder.build();

            return instance;
        }
    }
}
