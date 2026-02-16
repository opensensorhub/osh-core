package org.sensorhub.impl.service.consys.client.http;

import com.google.common.net.HttpHeaders;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;
import org.sensorhub.impl.service.consys.client.TokenHandler;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.Authenticator;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class OkHttpClientWrapper implements HttpClientWrapper
{
    static final Logger log = LoggerFactory.getLogger(OkHttpClientWrapper.class);
    protected OkHttpClient http;
    protected String token;
    protected TokenHandler tokenHandler;
    protected Authenticator authenticator;

    public OkHttpClientWrapper(OkHttpClient http)
    {
        this.http = http;
    }

    public OkHttpClientWrapper(OkHttpClient http, TokenHandler tokenHandler)
    {
        this.http = http;
        this.tokenHandler = tokenHandler;
    }

    @Override
    public <T> CompletableFuture<T> sendGetRequest(URI uri, ResourceFormat format, Function<InputStream, T> bodyMapper)
    {
        CompletableFuture<T> future = new CompletableFuture<>();

        Request.Builder requestBuilder = new Request.Builder()
                .url(uri.toString())
                .get()
                .addHeader(HttpHeaders.ACCEPT, format.getMimeType());

        addAuthHeader(requestBuilder);

        Request request = requestBuilder.build();

        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                future.completeExceptionally(e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (ResponseBody responseBody = response.body()) {
                    if (response.isSuccessful() && responseBody != null) {
                        try (InputStream is = responseBody.byteStream()) {
                            T result = bodyMapper.apply(is);
                            future.complete(result);
                        }
                    } else {
                        future.completeExceptionally(new IOException("HTTP error " + response.code()));
                    }
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }
        });

        return future;
    }

    @Override
    public CompletableFuture<String> sendPostRequest(URI uri, ResourceFormat format, byte[] body)
    {
        CompletableFuture<String> future = new CompletableFuture<>();

        RequestBody requestBody = RequestBody.create(body, MediaType.parse(format.getMimeType()));

        Request.Builder requestBuilder = new Request.Builder()
                .url(uri.toString())
                .post(requestBody)
                .header(HttpHeaders.ACCEPT, ResourceFormat.JSON.getMimeType())
                .header(HttpHeaders.CONTENT_TYPE, format.getMimeType());

        addAuthHeader(requestBuilder);

        Request request = requestBuilder.build();

        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                future.completeExceptionally(e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (ResponseBody responseBody = response.body()) {
                    if (response.code() == 201 || response.code() == 303) {
                        String location = response.header(HttpHeaders.LOCATION);
                        if (location == null) {
                            future.completeExceptionally(new IllegalStateException("Missing Location header in response"));
                            return;
                        }
                        future.complete(location.substring(location.lastIndexOf('/') + 1));
                    } else {
                        future.completeExceptionally(new CompletionException("HTTP Error: " + response.code() + " " + response.message(), null));
                    }
                }
            }
        });

        return future;
    }

    @Override
    public CompletableFuture<Integer> sendPutRequest(URI uri, ResourceFormat format, byte[] body)
    {
        CompletableFuture<Integer> future = new CompletableFuture<>();

        Request.Builder requestBuilder = new Request.Builder()
                .url(uri.toString())
                .put(RequestBody.create(body, MediaType.parse(format.getMimeType())))
                .addHeader(HttpHeaders.ACCEPT, format.getMimeType())
                .addHeader(HttpHeaders.CONTENT_TYPE, format.getMimeType());

        addAuthHeader(requestBuilder);

        Request request = requestBuilder.build();

        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                future.completeExceptionally(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                future.complete(response.code());
                log.debug("Response: {}", response.code());
                response.close();
            }
        });

        return future;
    }

    @Override
    public <T> CompletableFuture<T> sendPostRequestAndReadResponse(URI uri, ResourceFormat format, byte[] requestBody, Function<InputStream, T> responseBodyMapper)
    {
        CompletableFuture<T> future = new CompletableFuture<>();

        RequestBody body = RequestBody.create(requestBody, MediaType.parse(format.getMimeType()));

        Request.Builder requestBuilder = new Request.Builder()
                .url(uri.toString())
                .post(body)
                .addHeader(HttpHeaders.ACCEPT, ResourceFormat.JSON.getMimeType())
                .addHeader(HttpHeaders.CONTENT_TYPE, format.getMimeType());

        addAuthHeader(requestBuilder);

        Request request = requestBuilder.build();

        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (response.isSuccessful()) {
                        try (InputStream is = responseBody.byteStream()) {
                            T result = responseBodyMapper.apply(is);
                            future.complete(result);
                        }
                    } else {
                        String bodyStr = responseBody != null ? responseBody.string() : "";
                        try {
                            JsonObject jsonError = JsonParser.parseString(bodyStr).getAsJsonObject();
                            future.completeExceptionally(new CompletionException(
                                    jsonError.get("message").getAsString(), null));
                        } catch (JsonSyntaxException e) {
                            future.completeExceptionally(new CompletionException(
                                    "HTTP error " + response.code() + ": " + bodyStr, null));
                        }
                    }
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }
        });

        return future;
    }

    @Override
    public CompletableFuture<Set<String>> sendBatchPostRequest(URI uri, ResourceFormat format, byte[] body)
    {
        CompletableFuture<Set<String>> future = new CompletableFuture<>();

        Request.Builder requestBuilder = new Request.Builder()
                .url(uri.toString())
                .post(RequestBody.create(body, MediaType.parse(format.getMimeType())))
                .addHeader(HttpHeaders.CONTENT_TYPE, format.getMimeType());

        addAuthHeader(requestBuilder);

        Request request = requestBuilder.build();

        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (response.code() == 201 || response.code() == 303) {
                        Set<String> idList = new LinkedHashSet<>();
                        if (responseBody != null) {
                            String responseString = responseBody.string();

                            try (JsonReader reader = new JsonReader(new StringReader(responseString))) {
                                reader.beginArray();
                                while (reader.hasNext()) {
                                    var uri2 = reader.nextString();
                                    idList.add(uri2.substring(uri2.lastIndexOf('/') + 1));
                                }
                                reader.endArray();
                            } catch (IOException e) {
                                future.completeExceptionally(e);
                                return;
                            }
                        }
                        future.complete(idList);
                    } else {
                        future.completeExceptionally(new CompletionException("HTTP Error: " + response.code() + " " + response.message(), null));
                    }
                }
            }
        });

        return future;
    }

    @Override
    public void setAuthToken(String token)
    {
        this.token = token;
    }

    public OkHttpClient getOkHttpClient()
    {
        return http;
    }

    public void setAuthenticator(Authenticator authenticator)
    {
        this.authenticator = authenticator;
    }

    protected void addAuthHeader(Request.Builder requestBuilder)
    {
        if (tokenHandler != null) {
            if (tokenHandler.isExpired()) {
                tokenHandler.refreshAccessToken(http);
            }
            requestBuilder.addHeader("Authorization", "Bearer " + tokenHandler.getToken());
        }
    }
}
