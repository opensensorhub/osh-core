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

import java.io.InputStream;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.sensorhub.impl.service.consys.resource.ResourceFormat;

public interface HttpClientWrapper
{
    <T> CompletableFuture<T> sendGetRequest(URI uri, ResourceFormat format, Function<InputStream, T> bodyMapper);
    CompletableFuture<String> sendPostRequest(URI uri, ResourceFormat format, byte[] body);
    <T> CompletableFuture<T> sendPostRequestAndReadResponse(URI uri, ResourceFormat format, byte[] body, Function<InputStream, T> responseBodyMapper);
    CompletableFuture<Integer> sendPutRequest(URI uri, ResourceFormat format, byte[] body);
    CompletableFuture<Set<String>> sendBatchPostRequest(URI uri, ResourceFormat format, byte[] body);
}
