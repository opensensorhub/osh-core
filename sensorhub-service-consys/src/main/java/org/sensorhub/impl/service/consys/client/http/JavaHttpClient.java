/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2025 GeoRobotix. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.client.http;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import org.sensorhub.impl.service.consys.ResourceParseException;
import org.sensorhub.impl.service.consys.client.ConSysApiClientConfig;
import org.sensorhub.impl.service.consys.client.TokenHandler;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.sensorhub.utils.Lambdas;
import com.google.common.net.HttpHeaders;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;


public class JavaHttpClient implements IHttpClient
{
    protected HttpClient http;
    protected TokenHandler tokenHandler;

    public JavaHttpClient() {}

    @Override
    public void setConfig(ConSysApiClientConfig config) {
        if (config.conSysOAuth.oAuthEnabled) {
            this.tokenHandler = new TokenHandler(config.conSysOAuth);
        }

        this.http = HttpClient.newBuilder()
                .authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        char[] finalPwd = config.conSys.password != null ? config.conSys.password.toCharArray() : new char[0];

                        return new PasswordAuthentication(config.conSys.user, finalPwd);
                    }
                })
                .build();
    }

    @Override
    public <T> CompletableFuture<T> sendGetRequest(URI uri, ResourceFormat format, Function<InputStream, T> bodyMapper)
    {
        var builder = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .header(HttpHeaders.ACCEPT, format.getMimeType());

        addAuthHeader(builder);


        var req = builder.build();
        BodyHandler<T> bodyHandler = resp -> {
            BodySubscriber<byte[]> upstream = BodySubscribers.ofByteArray();
            return BodySubscribers.mapping(upstream, body -> {
                if (resp.statusCode() == 200) {
                    var is = new ByteArrayInputStream(body);
                    return bodyMapper.apply(is);
                } else {
                    var error = new String(body);
                    throw new CompletionException("HTTP error " + resp.statusCode() + ": " + error, null);
                }
            });
        };

        return http.sendAsync(req, bodyHandler)
            .thenApply(resp -> {
                if (resp.statusCode() == 200)
                    return resp.body();
                else
                    throw new CompletionException("HTTP error " + resp.statusCode(), null);
            });
    }


    @Override
    public CompletableFuture<String> sendPostRequest(URI uri, ResourceFormat format, byte[] body)
    {
        var builder = HttpRequest.newBuilder()
                .uri(uri)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .header(HttpHeaders.ACCEPT, ResourceFormat.JSON.getMimeType())
                .header(HttpHeaders.CONTENT_TYPE, format.getMimeType());

        addAuthHeader(builder);

        var req = builder.build();
        return http.sendAsync(req, BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() == 201 || resp.statusCode() == 303) {
                        var location = resp.headers()
                                .firstValue(HttpHeaders.LOCATION)
                                .orElseThrow(() -> new IllegalStateException("Missing Location header in response"));
                        return location.substring(location.lastIndexOf('/') + 1);
                    } else
                        throw new CompletionException(resp.body(), null);
                });
    }


    @Override
    public <T> CompletableFuture<T> sendPostRequestAndReadResponse(URI uri, ResourceFormat format, byte[] requestBody, Function<InputStream, T> responseBodyMapper)
    {
        var builder = HttpRequest.newBuilder()
                .uri(uri)
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                .header(HttpHeaders.ACCEPT, ResourceFormat.JSON.getMimeType())
                .header(HttpHeaders.CONTENT_TYPE, format.getMimeType());

        addAuthHeader(builder);

        var req = builder.build();
        BodyHandler<T> bodyHandler = resp -> {
            BodySubscriber<byte[]> upstream = BodySubscribers.ofByteArray();
            return BodySubscribers.mapping(upstream, body -> {
                if (resp.statusCode() == 200) {
                    var is = new ByteArrayInputStream(body);
                    return responseBodyMapper.apply(is);
                } else {
                    var bodyStr = new String(body);
                    try {
                        var jsonError = (JsonObject) JsonParser.parseString(bodyStr);
                        throw new CompletionException(jsonError.get("message").getAsString(), null);
                    } catch (JsonSyntaxException e) {
                        throw new CompletionException("HTTP error " + resp.statusCode() + ": " + bodyStr, null);
                    }
                }
            });
        };

        return http.sendAsync(req, bodyHandler)
            .thenApply(resp -> {
                if (resp.statusCode() == 200)
                    return resp.body();
                else
                    throw new CompletionException("HTTP error " + resp.statusCode(), null);
            });
    }


    @Override
    public CompletableFuture<Integer> sendPutRequest(URI uri, ResourceFormat format, byte[] body)
    {
        var builder = HttpRequest.newBuilder()
                .uri(uri)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .header(HttpHeaders.ACCEPT, ResourceFormat.JSON.getMimeType())
                .header(HttpHeaders.CONTENT_TYPE, format.getMimeType());

        addAuthHeader(builder);

        var req = builder.build();
        return http.sendAsync(req, BodyHandlers.ofString())
                .thenApply(HttpResponse::statusCode);
    }


    @Override
    public CompletableFuture<Set<String>> sendBatchPostRequest(URI uri, ResourceFormat format, byte[] body)
    {
        var builder = HttpRequest.newBuilder()
                .uri(uri)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .header(HttpHeaders.CONTENT_TYPE, format.getMimeType());

        addAuthHeader(builder);

        var req = builder.build();
        return http.sendAsync(req, BodyHandlers.ofString())
                .thenApply(Lambdas.checked(resp -> {
                    if (resp.statusCode() == 201 || resp.statusCode() == 303) {
                        var idList = new LinkedHashSet<String>();
                        try (JsonReader reader = new JsonReader(new StringReader(resp.body()))) {
                            reader.beginArray();
                            while (reader.hasNext()) {
                                var uri2 = reader.nextString();
                                idList.add(uri2.substring(uri2.lastIndexOf('/') + 1));
                            }
                            reader.endArray();
                        }
                        return idList;
                    } else
                        throw new ResourceParseException(resp.body());
                }));
    }

    protected void addAuthHeader(HttpRequest.Builder requestBuilder)
    {
        if (tokenHandler != null) {
            if (tokenHandler.isExpired()) {
                tokenHandler.refreshAccessToken();
            }
            requestBuilder.header("Authorization", "Bearer " + tokenHandler.getToken());
        }
    }
}
