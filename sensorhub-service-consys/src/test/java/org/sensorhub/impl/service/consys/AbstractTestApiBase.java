/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2021 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.module.IModule;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.sensorhub.impl.SensorHub;
import org.sensorhub.impl.datastore.h2.MVObsSystemDatabaseConfig;
import org.sensorhub.impl.service.HttpServer;
import org.sensorhub.impl.service.HttpServerConfig;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;


public abstract class AbstractTestApiBase
{
    protected static Logger log = LoggerFactory.getLogger(AbstractTestApiBase.class);

    protected static final String PROCEDURE_COLLECTION = "procedures";
    protected static final String PROPERTY_COLLECTION = "properties";
    protected static final String SYSTEM_COLLECTION = "systems";
    protected static final String MEMBER_COLLECTION = "subsystems";
    protected static final String DEPLOYMENT_COLLECTION = "deployments";
    protected static final String FOI_COLLECTION = "samplingFeatures";
    protected static final String DATASTREAM_COLLECTION = "datastreams";
    protected static final String CONTROL_COLLECTION = "controlstreams";
    protected static final String OBS_COLLECTION = "observations";
    protected static final String CMD_COLLECTION = "commands";
    
    static final int SERVER_PORT = 8888;
    static final long TIMEOUT = 10000;
    protected SensorHub hub;
    protected File dbFile;
    protected ConSysApiService service;
    protected IObsSystemDatabase db;
    protected String apiRootUrl;
    protected Gson gson = new GsonBuilder().setPrettyPrinting().create();
    
    
    static
    {
        setDebug(false);
    }
    
    
    protected static void setDebug(boolean debug)
    {
        if (debug)
            ((ch.qos.logback.classic.Logger)log).setLevel(ch.qos.logback.classic.Level.DEBUG);
        else
            ((ch.qos.logback.classic.Logger)log).setLevel(ch.qos.logback.classic.Level.INFO);
    }
    
    
    @Before
    public void setup() throws IOException, SensorHubException
    {
        // use temp DB file
        dbFile = File.createTempFile("sweapi-db-", ".dat");
        dbFile.deleteOnExit();
        
        // get instance with in-memory DB
        hub = new SensorHub();
        hub.start();
        var moduleRegistry = hub.getModuleRegistry();
        
        // start HTTP server
        HttpServerConfig httpConfig = new HttpServerConfig();
        httpConfig.httpPort = SERVER_PORT;
        var httpServer = (HttpServer)moduleRegistry.loadModule(httpConfig, TIMEOUT);
        
        // start DB
        MVObsSystemDatabaseConfig dbCfg = new MVObsSystemDatabaseConfig();
        dbCfg.storagePath = dbFile.getAbsolutePath();
        dbCfg.databaseNum = 2;
        dbCfg.readOnly = false;
        dbCfg.name = "SWE API Database";
        dbCfg.autoStart = true;
        db = (IObsSystemDatabase)moduleRegistry.loadModule(dbCfg, TIMEOUT);
        ((IModule<?>)db).waitForState(ModuleState.STARTED, TIMEOUT);
        
        // start SensorThings service
        ConSysApiServiceConfig swaCfg = new ConSysApiServiceConfig();
        swaCfg.databaseID = dbCfg.id;
        swaCfg.endPoint = "/api";
        swaCfg.name = "ConSys API Service";
        swaCfg.autoStart = true;
        service = (ConSysApiService)moduleRegistry.loadModule(swaCfg, TIMEOUT);
        apiRootUrl = httpServer.getPublicEndpointUrl(swaCfg.endPoint);
    }
    
    
    protected String concat(String url, String path)
    {
        if (!url.endsWith("/") && !path.startsWith("/"))
            url += "/";
        else if (url.endsWith("/") && path.startsWith("/"))
            url = url.substring(0, url.length()-1);
        return url + path;
    }
    
    
    protected static String getResourceId(String url)
    {
        return url.substring(url.lastIndexOf('/')+1);
    }
    
    
    protected void checkJsonProp(String propName, String expectedValue, JsonElement actual)
    {
        assertTrue(actual instanceof JsonObject);
        var val = ((JsonObject)actual).get(propName).getAsString();
        assertEquals(expectedValue, val);
    }
    
    
    protected String checkId(String url, JsonElement actual)
    {
        var id = getResourceId(url);
        checkJsonProp("id", getResourceId(url), actual);
        return id;
    }
    
    
    protected void checkFeatureProp(String propName, String expectedValue, JsonElement actual)
    {
        assertTrue(actual instanceof JsonObject);
        var val = ((JsonObject)actual).getAsJsonObject("properties").get(propName).getAsString();
        assertEquals(expectedValue, val);
    }
    
    
    protected void checkFeatureProp(String propName, Double expectedValue, JsonElement actual)
    {
        assertTrue(actual instanceof JsonObject);
        var val = ((JsonObject)actual).getAsJsonObject("properties").get(propName).getAsDouble();
        assertEquals(expectedValue, val, 1e-12);
    }
    
    
    protected void checkFeatureName(String expectedName, JsonElement actual)
    {
        checkFeatureProp("name", expectedName, actual);
    }
    
    
    protected void checkFeatureValidTime(String expectedBegin, String expectedEnd, JsonElement actual)
    {
        assertTrue(actual instanceof JsonObject);
        var validTime = (JsonArray)((JsonObject)actual).getAsJsonObject("properties").get("validTime");
        
        assertEquals(expectedBegin, validTime.get(0).getAsString());
        assertEquals(expectedEnd, validTime.get(1).getAsString());
    }
    
    
    protected void checkCollectionItemIds(Collection<String> expectedIds, JsonElement collection)
    {
        var items = ((JsonObject)collection).get("items").getAsJsonArray();
        var itemList = Lists.newArrayList(Iterables.transform(items, elt -> (JsonObject)elt));
        checkCollectionItemIds(expectedIds, itemList);
    }
    
    
    protected void checkCollectionItemIds(Collection<String> expectedIds, List<JsonObject> items)
    {
        var numItems = items.size();
        assertEquals(expectedIds.size(), numItems);
        
        var collectionsIds = new HashSet<>();
        for (int i = 0; i < numItems; i++)
        {
            var member = (JsonObject)items.get(i);
            collectionsIds.add(member.get("id").getAsString());
        }
        
        for (var id: expectedIds)
        {
            if (id.contains("/")) // make it work with both id and urls
                id = getResourceId(id);
            assertTrue("Resource with id " + id + " missing from collection", collectionsIds.contains(id));
        }
    }
    
    
    protected JsonElement parseJsonResponse(HttpResponse<String> resp) throws IOException
    {
        log.debug("Response Body:\n" + resp.body());
        return JsonParser.parseString(resp.body());
    }
    
    
    protected HttpResponse<String> sendGetRequest(String path) throws IOException
    {
        try
        {
            HttpClient client = HttpClient.newHttpClient();
            
            HttpRequest request = HttpRequest.newBuilder()
                .GET()
                .uri(URI.create(concat(apiRootUrl, path)))
                .header("Accept", ResourceFormat.JSON.getMimeType())
                .build();
            
            log.info(request.method() + " " + request.uri());
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
        catch (InterruptedException e)
        {
            throw new IOException(e);
        }
    }
    
    
    protected HttpResponse<String> sendGetRequestAndCheckStatus(String path, int expectedStatus) throws IOException
    {
        var resp = sendGetRequest(path);
        assertEquals(expectedStatus, resp.statusCode());
        return resp;
    }
    
    
    protected JsonElement sendGetRequestAndParseJson(String url) throws IOException
    {
        var resp = sendGetRequest(url);
        checkStatusCode(resp, 200);
        return parseJsonResponse(resp);
    }
    
    
    protected List<JsonObject> sendGetRequestAndGetItems(String url, int expectedNumItems) throws IOException
    {
        var json = sendGetRequestAndParseJson(url);
        var items = ((JsonObject)json).get("items").getAsJsonArray();
        if (expectedNumItems >= 0)
            assertEquals(expectedNumItems, items.size());
        return Lists.newArrayList(Iterables.transform(items, elt -> (JsonObject)elt));
    }
    
    
    protected HttpResponse<String> sendPostRequest(String path, JsonElement json) throws IOException
    {
        return sendPostRequest(path, json, ResourceFormat.JSON.getMimeType());
    }
    
    
    protected HttpResponse<String> sendPostRequest(String path, JsonElement json, String contentType) throws IOException
    {
        try
        {
            HttpClient client = HttpClient.newHttpClient();
            
            var jsonString = gson.toJson(json);
            HttpRequest request = HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(jsonString))
                .uri(URI.create(concat(apiRootUrl, path)))
                .header("Content-Type", contentType)
                .header("Accept", ResourceFormat.JSON.getMimeType())
                .build();
            
            log.info(request.method() + " " + request.uri());
            log.debug("Request Body:\n" + jsonString);
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
        catch (InterruptedException e)
        {
            throw new IOException(e);
        }
    }
    
    
    protected JsonElement sendPostRequestAndParseJson(String path, JsonElement json) throws IOException
    {
        return sendPostRequestAndParseJson(path, json, ResourceFormat.JSON.getMimeType());
    }
    
    
    protected JsonElement sendPostRequestAndParseJson(String path, JsonElement json, String contentType) throws IOException
    {
        var resp = sendPostRequest(path, json, contentType);
        checkStatusCode(resp, 201);
        return parseJsonResponse(resp);
    }
    
    
    protected List<String> sendPostRequestAndParseUrlList(String path, JsonElement json) throws IOException
    {
        return sendPostRequestAndParseUrlList(path, json, ResourceFormat.JSON.getMimeType());
    }
    
    
    protected List<String> sendPostRequestAndParseUrlList(String path, JsonElement json, String contentType) throws IOException
    {
        var jsonResp = sendPostRequestAndParseJson(path, json, contentType);
        assertTrue(jsonResp instanceof JsonArray);
        var array = jsonResp.getAsJsonArray();
        var urlList = new ArrayList<String>(array.size());
        for (int i = 0; i < array.size(); i++)
            urlList.add(array.get(i).getAsString());
        return urlList;
    }
    
    
    protected HttpResponse<String> sendPutRequest(String path, JsonElement json) throws IOException
    {
        return sendPutRequest(path, json, ResourceFormat.JSON.getMimeType());
    }
    
    
    protected HttpResponse<String> sendPutRequest(String path, JsonElement json, String contentType) throws IOException
    {
        try
        {
            HttpClient client = HttpClient.newHttpClient();
            
            var jsonString = gson.toJson(json);
            HttpRequest request = HttpRequest.newBuilder()
                .PUT(HttpRequest.BodyPublishers.ofString(jsonString))
                .uri(URI.create(concat(apiRootUrl, path)))
                .header("Content-Type", contentType)
                .build();
            
            log.info(request.method() + " " + request.uri());
            log.debug("Request Body:\n" + jsonString);
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
        catch (InterruptedException e)
        {
            throw new IOException(e);
        }
    }
    
    
    protected HttpResponse<String> sendPutRequestAndCheckStatus(String path, JsonElement json, int expectedStatus) throws IOException
    {
        return sendPutRequestAndCheckStatus(path, json, ResourceFormat.JSON.getMimeType(), expectedStatus);
    }
    
    
    protected HttpResponse<String> sendPutRequestAndCheckStatus(String path, JsonElement json, String contentType, int expectedStatus) throws IOException
    {
        var resp = sendPutRequest(path, json, contentType);
        assertEquals(expectedStatus, resp.statusCode());
        return resp;
    }
    
    
    protected HttpResponse<String> sendDeleteRequest(String path) throws IOException
    {
        try
        {
            HttpClient client = HttpClient.newHttpClient();
            
            HttpRequest request = HttpRequest.newBuilder()
                .DELETE()
                .uri(URI.create(concat(apiRootUrl, path)))
                .build();
            
            log.info(request.method() + " " + request.uri());
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
        catch (InterruptedException e)
        {
            throw new IOException(e);
        }
    }
    
    
    protected HttpResponse<String> sendDeleteRequestAndCheckStatus(String path, int expectedStatus) throws IOException
    {
        var resp = sendDeleteRequest(path);
        assertEquals(expectedStatus, resp.statusCode());
        return resp;
    }
    
    
    protected String getLocation(HttpResponse<?> resp)
    {
        checkStatusCode(resp, 201);
        var url = resp.headers().firstValue("Location").orElse(null);
        assertNotNull(url);
        return url;
    }
    
    
    protected void checkStatusCode(HttpResponse<?> resp, int expectedCode)
    {
        if (resp.statusCode() != expectedCode)
            log.error("Error {}:\n{}", resp.statusCode(), resp.body());
        assertEquals(expectedCode, resp.statusCode());
    }
    
    
    @After
    public void cleanup()
    {
        try
        {
            if (hub != null)
                hub.stop();
        }
        finally
        {
            if (dbFile != null)
                dbFile.delete();
        }
    }

}
