/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2023-2025 Sensia Software LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import net.opengis.swe.v20.BinaryEncoding;
import org.sensorhub.api.command.CommandStreamInfo;
import org.sensorhub.api.command.ICommandData;
import org.sensorhub.api.command.ICommandStatus;
import org.sensorhub.api.command.ICommandStreamInfo;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.data.DataStreamInfo;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.data.IObsData;
import org.sensorhub.api.procedure.IProcedureWithDesc;
import org.sensorhub.api.semantic.IDerivedProperty;
import org.sensorhub.api.system.ISystemWithDesc;
import org.sensorhub.impl.common.IdEncodersBase32;
import org.sensorhub.impl.service.consys.client.http.IHttpClient;
import org.sensorhub.impl.service.consys.feature.FoiBindingGeoJson;
import org.sensorhub.impl.service.consys.obs.DataStreamBindingJson;
import org.sensorhub.impl.service.consys.obs.DataStreamSchemaBindingOmJson;
import org.sensorhub.impl.service.consys.obs.DataStreamSchemaBindingSweCommon;
import org.sensorhub.impl.service.consys.procedure.ProcedureBindingGeoJson;
import org.sensorhub.impl.service.consys.procedure.ProcedureBindingSmlJson;
import org.sensorhub.impl.service.consys.property.PropertyBindingJson;
import org.sensorhub.api.datastore.TemporalFilter;
import org.sensorhub.api.datastore.obs.DataStreamKey;
import org.sensorhub.impl.service.consys.obs.ObsBindingOmJson;
import org.sensorhub.impl.service.consys.obs.ObsBindingSweCommon;
import org.sensorhub.impl.service.consys.obs.ObsHandler;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceBindingJson;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.sensorhub.impl.service.consys.resource.ResourceLink;
import org.sensorhub.impl.service.consys.system.SystemBindingGeoJson;
import org.sensorhub.impl.service.consys.system.SystemBindingSmlJson;
import org.sensorhub.impl.service.consys.task.CommandBindingJson;
import org.sensorhub.impl.service.consys.task.CommandBindingSweCommon;
import org.sensorhub.impl.service.consys.task.CommandHandler;
import org.sensorhub.impl.service.consys.task.CommandStatusBindingJson;
import org.sensorhub.impl.service.consys.task.CommandStatusHandler.CommandStatusHandlerContextData;
import org.sensorhub.impl.service.consys.task.CommandStreamBindingJson;
import org.sensorhub.impl.service.consys.task.CommandStreamSchemaBindingJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.ogc.gml.IFeature;
import org.vast.util.Asserts;
import org.vast.util.BaseBuilder;
import com.google.common.base.Strings;
import com.google.common.net.UrlEscapers;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;


public class ConSysApiClient
{
    static final String PROPERTIES_COLLECTION = "properties";
    static final String PROCEDURES_COLLECTION = "procedures";
    static final String SYSTEMS_COLLECTION = "systems";
    static final String DEPLOYMENTS_COLLECTION = "deployments";
    static final String DATASTREAMS_COLLECTION = "datastreams";
    static final String OBSERVATIONS_COLLECTION = "observations";
    static final String CONTROLSTREAMS_COLLECTION = "controlstreams";
    static final String COMMANDS_COLLECTION = "commands";
    static final String SUBSYSTEMS_COLLECTION = "subsystems";
    static final String SF_COLLECTION = "samplingFeatures";

    static final Logger log = LoggerFactory.getLogger(ConSysApiClient.class);
    protected URI endpoint;
    protected TokenHandler tokenHandler;

    IHttpClient httpAdapter;

    protected ConSysApiClient() {
    }

    public ConSysApiClient(ConSysApiClientConfig config) throws SensorHubException {
        try {
            httpAdapter = (IHttpClient) Class.forName(config.httpClientImplClass)
                    .getDeclaredConstructor().newInstance();
            httpAdapter.setConfig(config);
            var tls = config.conSys.enableTLS ? "https://" : "http://";
            this.endpoint = new URI(tls + config.conSys.remoteHost + ":" + config.conSys.remotePort + config.conSys.resourcePath);
        } catch (Exception e) {
            throw new SensorHubException("Failed to instantiate http client", e);
        }

    }
    
    
    /*------------*/
    /* Properties */
    /*------------*/
    
    public CompletableFuture<IDerivedProperty> getPropertyById(String id, ResourceFormat format)
    {
        return sendGetRequest(endpoint.resolve(PROPERTIES_COLLECTION + "/" + urlPathEncode(id)), format, body -> {
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
        return sendGetRequest(endpoint.resolve(PROCEDURES_COLLECTION + "/" + urlPathEncode(id)), format, body -> {
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
        return sendGetRequest(endpoint.resolve(PROCEDURES_COLLECTION + "?id=" + urlQueryEncode(uid)), format, body -> {
            try
            {
                var ctx = new RequestContext(body);
                
                // use modified binding since the response contains a feature collection
                var binding = new ProcedureBindingGeoJson(ctx, null, null, true) {
                    protected JsonReader getJsonReader(InputStream is) throws IOException
                    {
                        var reader = super.getJsonReader(is);
                        skipToCollectionItems(reader);
                        return reader;
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
        return sendGetRequest(endpoint.resolve(SYSTEMS_COLLECTION + "/" + urlPathEncode(id)), format, body -> {
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
        return sendGetRequest(endpoint.resolve(SYSTEMS_COLLECTION + "?id=" + urlQueryEncode(uid)), format, body -> {
            try
            {
                var ctx = new RequestContext(body);
                
                // use modified binding since the response contains a feature collection
                var binding = new SystemBindingGeoJson(ctx, null, null, true) {
                    protected JsonReader getJsonReader(InputStream is) throws IOException
                    {
                        var reader = super.getJsonReader(is);
                        skipToCollectionItems(reader);
                        return reader;
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

    /*-------------------*/
    /* Sampling Features */
    /*-------------------*/
    public CompletableFuture<String> addSamplingFeature(String systemId, IFeature feature)
    {
        try
        {
            var buffer = new ByteArrayOutputStream();
            var ctx = new RequestContext(buffer);

            var binding = new FoiBindingGeoJson(ctx, null, null, false);
            binding.serialize(null, feature, false);

            return sendPostRequest(
                    endpoint.resolve(SYSTEMS_COLLECTION + "/" + systemId + "/" + SF_COLLECTION),
                    ResourceFormat.GEOJSON,
                    buffer.toByteArray());
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Error initializing binding", e);
        }
    }
    

    public CompletableFuture<Integer> updateSamplingFeature(String featureId, IFeature feature)
    {
        try
        {
            var buffer = new ByteArrayOutputStream();
            var ctx = new RequestContext(buffer);

            var binding = new FoiBindingGeoJson(ctx, null, null, false);
            binding.serialize(null, feature, false);

            return sendPutRequest(
                    endpoint.resolve(SF_COLLECTION + "/" + featureId),
                    ResourceFormat.GEOJSON,
                    buffer.toByteArray());
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Error initializing binding", e);
        }
    }
    

    public CompletableFuture<IFeature> getSamplingFeatureById(String id)
    {
        return sendGetRequest(
                endpoint.resolve(SF_COLLECTION + "/" + urlPathEncode(id)),
                ResourceFormat.GEOJSON,
                body -> {
            try
            {
                var ctx = new RequestContext(body);
                var binding = new FoiBindingGeoJson(ctx, null, null, true);
                return binding.deserialize();
            }
            catch (IOException e)
            {
                throw new CompletionException(e);
            }
        });
    }
    

    public CompletableFuture<IFeature> getSamplingFeatureByUid(String uid, ResourceFormat format)
    {
        return sendGetRequest(endpoint.resolve(SF_COLLECTION + "?id=" + urlQueryEncode(uid)), format, body -> {
            try
            {
                var ctx = new RequestContext(body);

                // use modified binding since the response contains a feature collection
                var binding = new FoiBindingGeoJson(ctx, null, null, true) {
                    protected JsonReader getJsonReader(InputStream is) throws IOException
                    {
                        var reader = super.getJsonReader(is);
                        skipToCollectionItems(reader);
                        return reader;
                    }
                };

                return binding.deserialize();
            }
            catch (IOException e)
            {
                throw new CompletionException(e);
            }
        });
    }    
    

    public CompletableFuture<Stream<IFeature>> getSystemSamplingFeatures(String systemId, ResourceFormat format)
    {
        return getSystemSamplingFeatures(systemId, format, 100);
    }
    
    
    public CompletableFuture<Stream<IFeature>> getSystemSamplingFeatures(String systemId, ResourceFormat format, int maxPageSize)
    {
        return getResourcesWithPaging((pageSize, offset) -> {
            try {
                return getSystemSamplingFeatures(systemId, format, pageSize, offset).get();
            }
            catch (Exception e) {
                throw new IOException("Error loading sampling features", e);
            }
        }, maxPageSize);
    }
    
    
    protected CompletableFuture<Stream<IFeature>> getSystemSamplingFeatures(String sysId, ResourceFormat format, int pageSize, int offset)
    {
        var request = SYSTEMS_COLLECTION + "/" + urlPathEncode(sysId) + "/" + SF_COLLECTION + "?f=" + format + "&limit=" + pageSize + "&offset=" + offset;
        log.debug("{}", request);
        
        return sendGetRequest(endpoint.resolve(request), format, body -> {
            try
            {
                /*body.mark(100000);
                ByteStreams.copy(body, System.out);
                body.reset();*/
                
                var ctx = new RequestContext(body);
                var binding = new FoiBindingGeoJson(ctx, new IdEncodersBase32(), null, true) {
                    protected JsonReader getJsonReader(InputStream is) throws IOException
                    {
                        var reader = super.getJsonReader(is);
                        skipToCollectionItems(reader);
                        return reader;
                    }
                };
                
                return StreamSupport.stream(new Spliterator<IFeature>() {

                    @Override
                    public int characteristics()
                    {
                        return Spliterator.ORDERED | Spliterator.DISTINCT;
                    }

                    @Override
                    public long estimateSize()
                    {
                        return Long.MAX_VALUE;
                    }

                    @Override
                    public boolean tryAdvance(Consumer<? super IFeature> consumer)
                    {
                        try
                        {
                            var f = binding.deserialize();
                            if (f != null)
                            {
                                consumer.accept(f);
                                return true;
                            }
                            
                            return false;
                        }
                        catch (IOException e)
                        {
                            throw new IllegalStateException("Error parsing feature", e);
                        }
                    }

                    @Override
                    public Spliterator<IFeature> trySplit()
                    {
                        return null;
                    }
                    
                }, false);
            }
            catch (IOException e)
            {
                e.printStackTrace();
                throw new CompletionException(e);
            }
        });
    }


    /*-------------*/
    /* Datastreams */
    /*-------------*/

    public CompletableFuture<IDataStreamInfo> getDatastreamById(String id, ResourceFormat format, boolean fetchSchema)
    {
        var cf1 = sendGetRequest(endpoint.resolve(DATASTREAMS_COLLECTION + "/" + urlPathEncode(id)), format, body -> {
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
            return cf1.thenCombine(getDatastreamSchema(id, ResourceFormat.SWE_JSON, ResourceFormat.JSON), (dsInfo, schemaInfo) -> {
                
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
    public CompletableFuture<IDataStreamInfo> getDataStreams(String systemId, ResourceFormat format)
    {

        return sendGetRequest(endpoint.resolve(SYSTEMS_COLLECTION + "/" + systemId + "/" + DATASTREAMS_COLLECTION), format, body -> {
            try
            {
                var ctx = new RequestContext(body);

                var binding = new DataStreamBindingJson(ctx, null, null, true, Collections.emptyMap()) {
                    protected JsonReader getJsonReader(InputStream is) throws IOException
                    {
                        var reader = super.getJsonReader(is);
                        skipToCollectionItems(reader);
                        return reader;
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

    public CompletableFuture<IDataStreamInfo> getDatastreamSchema(String id, ResourceFormat obsFormat, ResourceFormat format)
    {
        var obsFormatStr = urlQueryEncode(obsFormat.getMimeType());
        return sendGetRequest(endpoint.resolve(DATASTREAMS_COLLECTION + "/" + urlPathEncode(id) + "/schema?obsFormat="+obsFormatStr), format, body -> {
            try
            {
                var ctx = new RequestContext(body);
                ResourceBindingJson<DataStreamKey, IDataStreamInfo> binding;
                if (obsFormat.getMimeType().startsWith(ResourceFormat.SWE_FORMAT_PREFIX))
                    binding = new DataStreamSchemaBindingSweCommon(obsFormat, ctx, null, true);
                else
                    binding = new DataStreamSchemaBindingOmJson(ctx, null, true);
                return binding.deserialize();
            }
            catch (IOException e)
            {
                e.printStackTrace();
                throw new CompletionException(e);
            }
        });
    }
    

    public CompletableFuture<String> addDataStream(String sysId, IDataStreamInfo datastream)
    {
        try
        {
            var buffer = new ByteArrayOutputStream();
            var ctx = new RequestContext(buffer);
            
            var binding = new DataStreamBindingJson(ctx, null, null, false, Collections.emptyMap());
            binding.serialize(null, datastream, false);

            return sendPostRequest(
                endpoint.resolve(SYSTEMS_COLLECTION + "/" + urlPathEncode(sysId) + "/" + DATASTREAMS_COLLECTION),
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


    public CompletableFuture<Set<String>> addDataStreams(String sysId, Collection<IDataStreamInfo> datastreams)
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
                endpoint.resolve(SYSTEMS_COLLECTION + "/" + urlPathEncode(sysId) + "/" + DATASTREAMS_COLLECTION),
                ResourceFormat.JSON,
                buffer.toByteArray());
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Error initializing binding", e);
        }
    }
    public CompletableFuture<Stream<Map.Entry<String, IDataStreamInfo>>> getSystemDataStreams(String systemId, ResourceFormat format)
    {
        return getSystemDataStreams(systemId, format, 100);
    }

    public CompletableFuture<Stream<Map.Entry<String, IDataStreamInfo>>> getSystemDataStreams(String systemId, ResourceFormat format, int maxPageSize)
    {
        return getResourcesWithPaging((pageSize, offset) -> {
            try {
                return getSystemDataStreams(systemId, format, pageSize, offset).get();
            }
            catch (Exception e) {
                throw new IOException("Error loading datastreams", e);
            }
        }, maxPageSize);
    }

    protected CompletableFuture<Stream<Map.Entry<String, IDataStreamInfo>>> getSystemDataStreams(String systemId, ResourceFormat format, int pageSize, int offset)
    {
        var request = SYSTEMS_COLLECTION + "/" + systemId + "/" + DATASTREAMS_COLLECTION + "?f=" + format + "&limit=" + pageSize + "&offset=" + offset;
        log.debug("{}", request);

        return sendGetRequest(endpoint.resolve(request), format, body -> {
            try
            {

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                body.transferTo(baos);
                InputStream firstClone = new ByteArrayInputStream(baos.toByteArray());
                InputStream secondClone = new ByteArrayInputStream(baos.toByteArray());

                var ids = new ArrayDeque<>();
                var bytes = firstClone.readAllBytes();

                var jsonString = new String(bytes, StandardCharsets.UTF_8);

                JsonObject jsonObj = JsonParser.parseString(jsonString).getAsJsonObject();
                var items = jsonObj.get("items").getAsJsonArray();

                for (var item: items) {
                    ids.add(item.getAsJsonObject().get("id").getAsString());
                }

                var ctx = new RequestContext(secondClone);

                var binding = new DataStreamBindingJson(ctx, new IdEncodersBase32(), null, true, Collections.emptyMap()) {
                    protected JsonReader getJsonReader(InputStream is) throws IOException
                    {
                        var reader = super.getJsonReader(is);
                        skipToCollectionItems(reader);
                        return reader;
                    }
                };


                return StreamSupport.stream(new Spliterator<Map.Entry<String, IDataStreamInfo>>() {

                    @Override
                    public int characteristics()
                    {
                        return Spliterator.ORDERED | Spliterator.DISTINCT;
                    }

                    @Override
                    public long estimateSize()
                    {
                        return Long.MAX_VALUE;
                    }

                    @Override
                    public boolean tryAdvance(Consumer<? super Map.Entry<String, IDataStreamInfo>> consumer)
                    {
                        try
                        {
                            var f = binding.deserialize();
                            if (f != null)
                            {
                                consumer.accept(Map.<String, IDataStreamInfo>entry((String) ids.pop(), f));
                                return true;
                            }

                            return false;
                        }
                        catch (IOException e)
                        {
                            throw new IllegalStateException("Error parsing datastream", e);
                        }
                    }

                    @Override
                    public Spliterator<Map.Entry<String, IDataStreamInfo>> trySplit()
                    {
                        return null;
                    }

                }, false);


            }
            catch (IOException e)
            {
                e.printStackTrace();
                throw new CompletionException(e);
            }
        });
    }

    public CompletableFuture<Integer> updateDataStream(String dataStreamId, IDataStreamInfo dataStreamInfo)
    {
        try
        {
            var buffer = new ByteArrayOutputStream();
            var ctx = new RequestContext(buffer);

            var binding = new DataStreamBindingJson(ctx, new IdEncodersBase32(), null,false, Collections.emptyMap());
            binding.serialize(null, dataStreamInfo, false);

            return sendPutRequest(
                    endpoint.resolve(DATASTREAMS_COLLECTION + "/" + dataStreamId),
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

    public CompletableFuture<String> addControlStream(String sysId, ICommandStreamInfo cmdstream)
    {
        try
        {
            var buffer = new ByteArrayOutputStream();
            var ctx = new RequestContext(buffer);
            
            var binding = new CommandStreamBindingJson(ctx, null, null, false);
            binding.serializeCreate(cmdstream);

            return sendPostRequest(
                endpoint.resolve(SYSTEMS_COLLECTION + "/" + urlPathEncode(sysId) + "/" + CONTROLSTREAMS_COLLECTION),
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


    public CompletableFuture<Set<String>> addControlStreams(String sysId, Collection<ICommandStreamInfo> cmdstreams)
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
                endpoint.resolve(SYSTEMS_COLLECTION + "/" + urlPathEncode(sysId) + "/" + CONTROLSTREAMS_COLLECTION),
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
        var cf1 = sendGetRequest(endpoint.resolve(CONTROLSTREAMS_COLLECTION + "/" + urlPathEncode(id)), format, body -> {
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
        var obsFormatStr = urlQueryEncode(format.getMimeType());
        return sendGetRequest(endpoint.resolve(CONTROLSTREAMS_COLLECTION + "/" + urlPathEncode(id) + "/schema?obsFormat=" + obsFormatStr), format, body -> {
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
    public CompletableFuture<String> pushObs(String dsId, IDataStreamInfo dataStream, IObsData obs)
    {
        try
        {
            var buffer = new ByteArrayOutputStream();
            var ctx = new RequestContext(buffer);
            ctx.setParent(null, dsId, obs.getDataStreamID());
            ObsHandler.ObsHandlerContextData contextData = new ObsHandler.ObsHandlerContextData();
            contextData.dsInfo = dataStream;
            ctx.setData(contextData);

            if (dataStream != null && dataStream.getRecordEncoding() instanceof BinaryEncoding) {
                ctx.setData(contextData);
                ctx.setFormat(ResourceFormat.SWE_BINARY);
                var binding = new ObsBindingSweCommon(ctx, new IdEncodersBase32(), false, null);
                binding.serialize(null, obs, false);
            } else {
                ctx.setFormat(ResourceFormat.OM_JSON);
                var binding = new ObsBindingOmJson(ctx, new IdEncodersBase32(), false, null);
                binding.serialize(null, obs, false);
            }

            return sendPostRequest(
                    endpoint.resolve(DATASTREAMS_COLLECTION + "/" + urlPathEncode(dsId) + "/" + OBSERVATIONS_COLLECTION),
                    ctx.getFormat(),
                    buffer.toByteArray());
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Error initializing binding", e);
        }
    }
    

    public CompletableFuture<Stream<IObsData>> getObservations(String dsId, IDataStreamInfo dsInfo, TemporalFilter timeFilter, Set<String> foiIds, ResourceFormat format)
    {
        return getObservations(dsId, dsInfo, timeFilter, foiIds, format, 100);
    }
    
    
    public CompletableFuture<Stream<IObsData>> getObservations(String dsId, IDataStreamInfo dsInfo, TemporalFilter timeFilter, Set<String> foiIds, ResourceFormat format, int maxPageSize)
    {
        return getResourcesWithPaging((pageSize, offset) -> {
            try {
                return getObservations(dsId, dsInfo, timeFilter, foiIds, format, pageSize, offset).get();
            }
            catch (Exception e) {
                throw new IOException("Error loading observations", e);
            }
        }, maxPageSize);
    }
    
    protected CompletableFuture<Stream<IObsData>> getObservations(String dsId, IDataStreamInfo dsInfo, TemporalFilter timeFilter, Set<String> foiIds, ResourceFormat format, int pageSize, int offset)
    {
        var request = DATASTREAMS_COLLECTION + "/" + urlPathEncode(dsId) + "/observations?f=" + format + "&limit=" + pageSize + "&offset=" + offset;
                
        if (foiIds != null)
            request += "&foi=" + String.join(",", foiIds);
        
        if (timeFilter != null)
        {
            if (timeFilter.isLatestTime())
                request += "&resultTime=latest";
            else {
                request += "&phenomenonTime=";
                
                if (timeFilter.isCurrentTime())
                    request += "now";
                else if (timeFilter.endsNow())
                    request += timeFilter.getMin() + "/now";
                else if (timeFilter.beginsNow())
                    request += "now/" + timeFilter.getMax();
                else if (timeFilter.isAllTimes())
                    request += "../..";
                else if (timeFilter.getMin() == Instant.MIN)
                    request += "../" + timeFilter.getMax();
                else if (timeFilter.getMax() == Instant.MAX)
                    request += timeFilter.getMin() + "/..";
                else
                    request += timeFilter.getMin() + "/" + timeFilter.getMax();
            }
        }
        
        return sendGetRequest(endpoint.resolve(request), format, body -> {
            try
            {
                /*body.mark(100000);
                ByteStreams.copy(body, System.out);
                body.reset();*/
                
                var ctx = new RequestContext(body);
                var contextData = new ObsHandler.ObsHandlerContextData();
                contextData.dsID = BigId.NONE;
                contextData.dsInfo = dsInfo;
                ctx.setData(contextData);
                var binding = new ObsBindingOmJson(ctx, new IdEncodersBase32(), true, null);
                binding.startCollection();
                
                return StreamSupport.stream(new Spliterator<IObsData>() {

                    @Override
                    public int characteristics()
                    {
                        return Spliterator.ORDERED | Spliterator.DISTINCT;
                    }

                    @Override
                    public long estimateSize()
                    {
                        return Long.MAX_VALUE;
                    }

                    @Override
                    public boolean tryAdvance(Consumer<? super IObsData> consumer)
                    {
                        try
                        {
                            var obs = binding.deserialize();
                            if (obs != null)
                            {
                                consumer.accept(obs);
                                return true;
                            }
                            
                            return false;
                        }
                        catch (IOException e)
                        {
                            throw new IllegalStateException("Error parsing observation", e);
                        }
                    }

                    @Override
                    public Spliterator<IObsData> trySplit()
                    {
                        return null;
                    }
                    
                }, false);
            }
            catch (IOException e)
            {
                e.printStackTrace();
                throw new CompletionException(e);
            }
        });
    }


    /*----------*/
    /* Commands */
    /*----------*/

    public CompletableFuture<ICommandStatus> sendCommand(String csId, ICommandStreamInfo cmdStream, ICommandData cmd)
    {
        try
        {
            var buffer = new ByteArrayOutputStream();
            var ctx = new RequestContext(buffer);
            ctx.setParent(null, csId, cmd.getCommandStreamID());
            var contextData = new CommandHandler.CommandHandlerContextData();
            contextData.csInfo = cmdStream;
            ctx.setData(contextData);

            if (cmdStream != null && cmdStream.getRecordEncoding() instanceof BinaryEncoding) {
                ctx.setData(contextData);
                ctx.setFormat(ResourceFormat.SWE_BINARY);
                var binding = new CommandBindingSweCommon(ctx, new IdEncodersBase32(), false, null);
                binding.serialize(null, cmd, false);
            } else {
                ctx.setFormat(ResourceFormat.JSON);
                var binding = new CommandBindingJson(ctx, new IdEncodersBase32(), false, null);
                binding.serialize(null, cmd, false);
            }
            
            return sendPostRequestAndReadResponse(
                    endpoint.resolve(CONTROLSTREAMS_COLLECTION + "/" + urlPathEncode(csId) + "/" + COMMANDS_COLLECTION),
                    ctx.getFormat(),
                    buffer.toByteArray(),
                    responseBody -> {
                        try
                        {
                            var respCtx = new RequestContext(responseBody);
                            var respCtxData = new CommandStatusHandlerContextData();
                            respCtxData.csInfo = cmdStream;
                            respCtx.setData(respCtxData);
                            respCtx.setFormat(ResourceFormat.JSON);
                            var binding = new CommandStatusBindingJson(respCtx, new IdEncodersBase32(), true, null);
                            return binding.deserialize();
                        }
                        catch (IOException e)
                        {
                            e.printStackTrace();
                            throw new CompletionException(e);
                        }
                    });
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Error initializing binding", e);
        }
    }
    
    
    
    /*----------------*/
    /* Helper Methods */
    /*----------------*/

    protected <T> CompletableFuture<T> sendGetRequest(URI collectionUri, ResourceFormat format, Function<InputStream, T> bodyMapper)
    {
        return httpAdapter.sendGetRequest(collectionUri, format, bodyMapper);
    }


    protected CompletableFuture<String> sendPostRequest(URI collectionUri, ResourceFormat format, byte[] body)
    {
        return httpAdapter.sendPostRequest(collectionUri, format, body);
    }


    protected <T> CompletableFuture<T> sendPostRequestAndReadResponse(URI collectionUri, ResourceFormat format, byte[] requestBody, Function<InputStream, T> responseBodyMapper)
    {
        return httpAdapter.sendPostRequestAndReadResponse(collectionUri, format, requestBody, responseBodyMapper);
    }


    protected CompletableFuture<Integer> sendPutRequest(URI collectionUri, ResourceFormat format, byte[] body)
    {
        return httpAdapter.sendPutRequest(collectionUri, format, body);
    }


    protected CompletableFuture<Set<String>> sendBatchPostRequest(URI collectionUri, ResourceFormat format, byte[] body)
    {
        return httpAdapter.sendBatchPostRequest(collectionUri, format, body);
    }
    
    
    interface PageLoadFunction<T>
    {
        Stream<T> loadPage(int offset, int pageSize) throws IOException;
    }
    
    
    protected <T> CompletableFuture<Stream<T>> getResourcesWithPaging(PageLoadFunction<T> pageLoader, int pageSize)
    {
        var resourceStream = StreamSupport.stream(new Spliterator<T>() {
            Spliterator<T> currentBatch;
            int offset = 0;
            
            @Override
            public int characteristics()
            {
                return Spliterator.ORDERED | Spliterator.DISTINCT;
            }

            @Override
            public long estimateSize()
            {
                return Long.MAX_VALUE;
            }

            @Override
            public boolean tryAdvance(Consumer<? super T> consumer)
            {
                boolean hasNext = false;
                if (currentBatch != null)
                    hasNext = currentBatch.tryAdvance(consumer);
                
                if (!hasNext)
                {
                    try {
                        log.debug("Loading batch {}-{}", offset, offset+pageSize);
                        var batch = pageLoader.loadPage(pageSize, offset);
                        if (batch == null)
                            return false;
                    
                        offset += pageSize;
                        currentBatch = batch.spliterator();
                        hasNext = currentBatch.tryAdvance(consumer);
                    }
                    catch (Exception e)
                    {
                        throw new IllegalStateException("Error loading next page", e);
                    }
                }
                
                return hasNext;
            }

            @Override
            public Spliterator<T> trySplit()
            {
                return null;
            }
            
        }, false);
        
        return CompletableFuture.completedFuture(resourceStream);
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
    
    
    protected String urlPathEncode(String value)
    {
        return UrlEscapers.urlPathSegmentEscaper().escape(value);
    }
    
    
    protected String urlQueryEncode(String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
        ConSysApiClientConfig conSysApiClientConfig;
        ConSysApiClientBuilder(String endpoint)
        {
            this.instance = new ConSysApiClient();
            this.httpClientBuilder = HttpClient.newBuilder();
            this.conSysApiClientConfig = new ConSysApiClientConfig();
        }


        public ConSysApiClientBuilder useHttpClient(IHttpClient http)
        {
            instance.httpAdapter = http;
            return this;
        }


        public ConSysApiClientBuilder simpleAuth(String user, char[] password)
        {
            if (!Strings.isNullOrEmpty(user))
            {
                conSysApiClientConfig.conSys.user = user;
                conSysApiClientConfig.conSys.password = new String(password);
            }

            return this;
        }


        @Override
        public ConSysApiClient build()
        {
            instance.httpAdapter.setConfig(conSysApiClientConfig);
            return instance;
        }

        public ConSysApiClientBuilder tokenHandler(ConSysOAuthConfig conSysOAuth) {
            instance.tokenHandler = new TokenHandler(conSysOAuth);
            return this;
        }
    }
}
