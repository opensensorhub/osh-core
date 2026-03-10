/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2025 GeoRobotix. All Rights Reserved.

 ******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.client;

import com.google.common.net.HttpHeaders;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.text.MessageFormat;

/**
 * Token handler impl for OAuth 2.0 Client
 */
public class OAuthTokenHandler implements ITokenHandler {

    private String token;

    private static final Logger logger = LoggerFactory.getLogger(OAuthTokenHandler.class);

    private long expirationTime;

    private final ConSysOAuthConfig config;

    public OAuthTokenHandler(ConSysOAuthConfig config) {

        this.config = config;
    }

    public String getToken() {
        return token;
    }


    public void refreshAccessToken() {

        try {

            String data = MessageFormat.format("grant_type=client_credentials&client_id={0}&client_secret={1}",
                    config.clientID, config.clientSecret);

            URL url = URI.create(config.tokenEndpoint).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded");
            connection.setDoOutput(true);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(data.getBytes());
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {

                logger.error("Failed to retrieve access token: {}", responseCode);

            } else {

                try (InputStream is = connection.getInputStream()) {

                    JsonObject jsonObject = JsonParser.parseReader(new InputStreamReader(is)).getAsJsonObject();

                    token = jsonObject.get("access_token").getAsString();
                    expirationTime = jsonObject.get("expires_in").getAsInt() * 1000L + System.currentTimeMillis();
                }
            }
        } catch (IOException e) {

            logger.error("Failed to retrieve access token due to exception: {}", e.getMessage());
        }
    }

    public boolean isExpired() {

        return System.currentTimeMillis() > expirationTime;
    }
}