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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

import org.sensorhub.impl.service.consys.ResourceParseException;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;

import com.google.common.net.HttpHeaders;
import com.google.gson.stream.JsonReader;

public class HttpURLConnectionWrapper implements HttpClientWrapper
{
    protected Authenticator authenticator;

    public HttpURLConnectionWrapper()
    {
    }


    @Override
    public <T> CompletableFuture<T> sendGetRequest(URI uri, ResourceFormat format, Function<InputStream, T> bodyMapper)
    {
        return CompletableFuture.supplyAsync(() -> {
            HttpURLConnection connection = null;
            try {
                if (authenticator != null)
                    Authenticator.setDefault(authenticator);

                URL url = uri.toURL();
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty(HttpHeaders.ACCEPT, format.getMimeType());

                int responseCode = connection.getResponseCode();
                if (responseCode == 200) {
                    try (InputStream is = connection.getInputStream()) {
                        return bodyMapper.apply(is);
                    }
                } else {
                    throw new CompletionException("HTTP error " + responseCode, null);
                }
            } catch (IOException e) {
                throw new CompletionException(e);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    @Override
    public CompletableFuture<String> sendPostRequest(URI uri, ResourceFormat format, byte[] body)
    {
        return CompletableFuture.supplyAsync(() -> {
            HttpURLConnection connection = null;
            try {
                if (authenticator != null)
                    Authenticator.setDefault(authenticator);

                URL url = uri.toURL();
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty(HttpHeaders.ACCEPT, ResourceFormat.JSON.getMimeType());
                connection.setRequestProperty(HttpHeaders.CONTENT_TYPE, format.getMimeType());

                connection.setDoOutput(true);

                try (OutputStream os = connection.getOutputStream()) {
                    os.write(body);
                }

                int responseCode = connection.getResponseCode();
                if (responseCode == 201 || responseCode == 303) {
                    String location = connection.getHeaderField(HttpHeaders.LOCATION);
                    if (location == null) {
                        throw new IllegalStateException("Missing Location header in response.");
                    }
                    return location.substring(location.lastIndexOf('/') + 1);
                } else {
                    throw new CompletionException(connection.getResponseMessage(), null);
                }
            } catch (IOException e) {
                throw new CompletionException(e);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    @Override
    public <T> CompletableFuture<T> sendPostRequestAndReadResponse(URI uri, ResourceFormat format, byte[] body, Function<InputStream, T> responseBodyMapper)
    {
        return CompletableFuture.supplyAsync(() -> {
            HttpURLConnection connection = null;
            try {
                if (authenticator != null)
                    Authenticator.setDefault(authenticator);

                URL url = uri.toURL();
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty(HttpHeaders.ACCEPT, ResourceFormat.JSON.getMimeType());
                connection.setRequestProperty(HttpHeaders.CONTENT_TYPE, format.getMimeType());

                connection.setDoOutput(true);

                try (OutputStream os = connection.getOutputStream()) {
                    os.write(body);
                }

                int responseCode = connection.getResponseCode();
                if (responseCode == 200) {
                    try (InputStream is = connection.getInputStream()) {
                        return responseBodyMapper.apply(is);
                    }
                } else {
                    throw new CompletionException("HTTP error " + responseCode + ": " + connection.getResponseMessage(), null);
                }
            } catch (IOException e) {
                throw new CompletionException(e);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    @Override
    public CompletableFuture<Integer> sendPutRequest(URI uri, ResourceFormat format, byte[] body)
    {
        return CompletableFuture.supplyAsync(() -> {
            HttpURLConnection connection = null;
            try {
                if (authenticator != null)
                    Authenticator.setDefault(authenticator);

                URL url = uri.toURL();
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("PUT");
                connection.setRequestProperty(HttpHeaders.ACCEPT, ResourceFormat.JSON.getMimeType());
                connection.setRequestProperty(HttpHeaders.CONTENT_TYPE, format.getMimeType());

                connection.setDoOutput(true);

                try (OutputStream os = connection.getOutputStream()) {
                    os.write(body);
                }

                return connection.getResponseCode();
            } catch (IOException e) {
                throw new CompletionException(e);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    @Override
    public CompletableFuture<Set<String>> sendBatchPostRequest(URI uri, ResourceFormat format, byte[] body)
    {
        return CompletableFuture.supplyAsync(() -> {
            HttpURLConnection connection = null;
            try {
                if (authenticator != null) {
                    Authenticator.setDefault(authenticator);
                }

                URL url = uri.toURL();
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty(HttpHeaders.CONTENT_TYPE, format.getMimeType());

                connection.setDoOutput(true);

                try (OutputStream os = connection.getOutputStream()) {
                    os.write(body);
                }

                int responseCode = connection.getResponseCode();
                if (responseCode == 201 || responseCode == 303) {
                    Set<String> idList = new LinkedHashSet<>();
                    try (InputStream is = connection.getInputStream();
                         JsonReader reader = new JsonReader(new InputStreamReader(is))) {
                        reader.beginArray();
                        while (reader.hasNext()) {
                            String uri2 = reader.nextString();
                            idList.add(uri2.substring(uri2.lastIndexOf('/') + 1));
                        }
                        reader.endArray();
                    }
                    return idList;
                } else {
                    throw new ResourceParseException(connection.getResponseMessage());
                }
            } catch (IOException e) {
                throw new CompletionException(e);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

}
