/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2023 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.demodata;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ExecutionException;
import org.sensorhub.api.command.ICommandStreamInfo;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.data.IObsData;
import org.sensorhub.api.semantic.IDerivedProperty;
import org.sensorhub.impl.service.consys.client.ConSysApiClient;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.vast.ogc.gml.GeoJsonBindings;
import org.vast.ogc.gml.IFeature;
import org.vast.sensorML.SMLJsonBindings;
import com.google.common.net.HttpHeaders;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;
import net.opengis.sensorml.v20.AbstractProcess;
import net.opengis.sensorml.v20.Deployment;
import net.opengis.sensorml.v20.DescribedObject;


public class Api
{
    static String API_ROOT = "http://localhost:8181/sensorhub/api/";
    static String CREDENTIALS = "admin:test";
//    static String API_ROOT = "https://api.georobotix.io/ogc/demo1/api/";
//    static String CREDENTIALS = "admin:admin@demo";
    static Gson gson = new GsonBuilder().setPrettyPrinting().create();
    
    
    static String addOrUpdateProperty(IDerivedProperty prop, boolean replace) throws IOException
    {
        var user = CREDENTIALS.split(":")[0];
        var pwd = CREDENTIALS.split(":")[1];
        var client = ConSysApiClient.newBuilder(API_ROOT)
            .simpleAuth(user, pwd.toCharArray())
            .build();
        
        /*var id = client.getPropertyByUri(prop.getURI(), ResourceFormat.JSON).get();
        if (id == null)
            throw new IllegalArgumentException("Parent system not found: " + sysUid);*/
        
        try
        {
            return client.addProperty(prop).get();
        }
        catch (InterruptedException | ExecutionException e)
        {
            throw new IOException(e);
        }
    }
    
    
    static String addOrUpdateProcedure(AbstractProcess obj, boolean replace) throws IOException
    {
        return addOrUpdateSmlResource("procedures", obj, replace);
    }
    
    
    static String addOrUpdateSystem(AbstractProcess obj, boolean replace) throws IOException
    {
        return addOrUpdateSmlResource("systems", obj, replace);
    }
    
    
    static String addOrUpdateSubsystem(String parentUid, AbstractProcess obj, boolean replace) throws IOException
    {
        var id = getFeatureByUid("systems", parentUid);
        if (id == null)
            throw new IllegalArgumentException("Parent system not found: " + parentUid);
        return addOrUpdateSmlResource("systems/" + id + "/members", obj, replace);
    }
    
    
    static String addOrUpdateSF(String parentUid, IFeature f, boolean replace) throws IOException
    {
        var id = getFeatureByUid("systems", parentUid);
        if (id == null)
            throw new IllegalArgumentException("Parent system not found: " + parentUid);
        return addOrUpdateGeoJsonResource("systems/" + id + "/fois", f, replace);
    }
    
    
    static String addOrUpdateDeployment(Deployment obj, boolean replace) throws IOException
    {
        return addOrUpdateSmlResource("deployments", obj, replace);
    }
    
    
    static String addOrUpdateSmlResource(String resourceType, DescribedObject obj, boolean replace) throws IOException
    {
        // check if procedure exists and retrieve ID
        var id = getFeatureByUid(resourceType, obj.getUniqueIdentifier());
        
        var strWriter = new StringWriter();
        var bindings = new SMLJsonBindings();
        bindings.writeDescribedObject(new JsonWriter(strWriter), obj);
        System.out.println(strWriter.toString());
        
        if (id == null || !replace)
        {
            var resp = sendPostRequest(resourceType, strWriter.toString(), "application/sml+json");
            id = resp.headers().firstValue(HttpHeaders.LOCATION).get();
        }
        else
            sendPutRequest(resourceType + "/" + id, strWriter.toString(), "application/sml+json");
        
        return id;
    }
    
    
    static String addOrUpdateGeoJsonResource(String resourceType, IFeature obj, boolean replace) throws IOException
    {
        // check if feature exists and retrieve ID
        var id = getFeatureByUid(resourceType, obj.getUniqueIdentifier());
        
        var strWriter = new StringWriter();
        var bindings = new GeoJsonBindings();
        bindings.writeFeature(new JsonWriter(strWriter), obj);
        
        if (id == null || !replace)
        {
            var resp = sendPostRequest(resourceType, strWriter.toString(), "application/geo+json");
            id = resp.headers().firstValue(HttpHeaders.LOCATION).get();
        }
        else
            sendPutRequest(resourceType + "/" + id, strWriter.toString(), "application/geo+json");
        
        return id;
    }
    
    
    static String addOrUpdateDataStream(IDataStreamInfo dsInfo, boolean replace) throws IOException
    {
        var sysUid = dsInfo.getSystemID().getUniqueID();
        var id = getFeatureByUid("systems", sysUid);
        if (id == null)
            throw new IllegalArgumentException("Parent system not found: " + sysUid);
        
        var user = CREDENTIALS.split(":")[0];
        var pwd = CREDENTIALS.split(":")[1];
        var client = ConSysApiClient.newBuilder(API_ROOT)
            .simpleAuth(user, pwd.toCharArray())
            .build();
        
        try
        {
            return client.addDataStream(id, dsInfo).get();
        }
        catch (InterruptedException | ExecutionException e)
        {
            throw new IOException(e);
        }
    }
    
    
    static String addOrUpdateControlStream(ICommandStreamInfo csInfo, boolean replace) throws IOException
    {
        var sysUid = csInfo.getSystemID().getUniqueID();
        var id = getFeatureByUid("systems", sysUid);
        if (id == null)
            throw new IllegalArgumentException("Parent system not found: " + sysUid);
        
        var user = CREDENTIALS.split(":")[0];
        var pwd = CREDENTIALS.split(":")[1];
        var client = ConSysApiClient.newBuilder(API_ROOT)
            .simpleAuth(user, pwd.toCharArray())
            .build();
        
        try
        {
            return client.addControlStream(id, csInfo).get();
        }
        catch (InterruptedException | ExecutionException e)
        {
            throw new IOException(e);
        }
    }
    
    
    static String addOrUpdateObs(String dsId, String foiId, Instant time, JsonElement result, boolean replace) throws IOException
    {
        var json = new JsonObject();
        if (foiId != null)
            json.addProperty("foi@id", foiId);
        json.addProperty("phenomenonTime", time.toString());
        json.add("result", result);
        
        var resp = sendPostRequest("/datastreams/" + dsId + "/observations", json.toString(), "application/om+json");
        
        /*if (!replace)
        {
            var resp = sendPostRequest("/datastreams/" + dsId + "/observations", strWriter.toString(), "application/om+json");
            id = resp.headers().firstValue(HttpHeaders.LOCATION).get();
        }
        else
            sendPutRequest(resourceType + "/" + id, strWriter.toString(), "application/geo+json");*/
        
        return null;
    }
    
    
    static String getFeatureByUid(String path, String uid) throws IOException
    {
        HttpClient client = HttpClient.newHttpClient();
        
        try
        {
            HttpRequest request = HttpRequest.newBuilder()
                .GET()
                .uri(URI.create(API_ROOT + path + "?select=id&uid=" + uid))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(CREDENTIALS.getBytes()))
                .build();
            
            System.out.println(request);
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            
            // print error and send exception
            if (statusCode >= 300)
            {
                System.err.println(response.body());
                throw new IOException("Received HTTP error code " + statusCode);
            }
            
            // return resource ID if response is non-empty
            var json = JsonParser.parseReader(new StringReader(response.body()));
            var items = json.getAsJsonObject().get("items").getAsJsonArray();
            if (!items.isEmpty())
                return items.get(0).getAsJsonObject().get("id").getAsString();
            
            return null;
        }
        catch (InterruptedException e)
        {
            throw new IOException(e);
        }
    }
    
    
    static JsonElement sendGetRequest(String path) throws IOException
    {
        try
        {
            HttpClient client = HttpClient.newHttpClient();
            
            HttpRequest request = HttpRequest.newBuilder()
                .GET()
                .uri(URI.create(API_ROOT + path))
                .build();
            
            System.out.println(request);
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            
            // print error and send exception
            if (statusCode >= 300)
            {
                System.err.println(response.body());
                throw new IOException("Received HTTP error code " + statusCode);
            }
            
            return JsonParser.parseString(response.body());
        }
        catch (InterruptedException e)
        {
            throw new IOException(e);
        }
    }
    
    
    static HttpResponse<String> sendPostRequest(String path, JsonElement body, String contentType) throws IOException
    {
        var jsonString = gson.toJson(body);
        return sendPostRequest(path, jsonString, "application/json");
    }
    
    
    static HttpResponse<String> sendPostRequest(String path, String body, String contentType) throws IOException
    {
        try
        {
            HttpClient client = HttpClient.newHttpClient();
            
            HttpRequest request = HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .uri(URI.create(API_ROOT + path))
                .header("Content-Type", contentType)
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(CREDENTIALS.getBytes()))
                .build();
            
            System.out.println(request);// + "\n" + jsonString);
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            
            // print error and send exception
            if (statusCode >= 300)
            {
                System.err.println(response.body());
                throw new IOException("Received HTTP error code " + statusCode);
            }
            
            return response;
        }
        catch (InterruptedException e)
        {
            throw new IOException(e);
        }
    }
    
    
    static HttpResponse<String> sendPutRequest(String path, String body, String contentType) throws IOException
    {
        try
        {
            HttpClient client = HttpClient.newHttpClient();
            
            HttpRequest request = HttpRequest.newBuilder()
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .uri(URI.create(API_ROOT + path))
                .header("Content-Type", contentType)
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(CREDENTIALS.getBytes()))
                .build();
            
            System.out.println(request);// + "\n" + jsonString);
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            
            // print error and send exception
            if (statusCode >= 300)
            {
                System.err.println(response.body());
                throw new IOException("Received HTTP error code " + statusCode);
            }
            
            return response;
        }
        catch (InterruptedException e)
        {
            throw new IOException(e);
        }
    }
    
    
    static HttpResponse<String> sendDeleteRequest(String path, boolean cascade) throws IOException
    {
        try
        {
            HttpClient client = HttpClient.newHttpClient();
            
            HttpRequest request = HttpRequest.newBuilder()
                .DELETE()
                .uri(URI.create(API_ROOT + path + (cascade ? "?cascade=true" : "")))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(CREDENTIALS.getBytes()))
                .build();
            
            System.out.println(request);// + "\n" + jsonString);
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            
            // print error and send exception
            if (statusCode >= 300)
            {
                System.err.println(response.body());
                throw new IOException("Received HTTP error code " + statusCode);
            }
            
            return response;
        }
        catch (InterruptedException e)
        {
            throw new IOException(e);
        }
    }

}
