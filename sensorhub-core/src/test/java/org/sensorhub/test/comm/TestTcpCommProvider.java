/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.test.comm;

import java.io.InputStream;
import org.sensorhub.impl.comm.TCPCommProvider;
import org.sensorhub.impl.comm.TCPCommProviderConfig;


/*
 * For this test to work, the server certificate must be added to a keystore,
 * which can be done using the JDK keytool command:
 * >> keytool -import -alias 34.123.122.135 -file takserver_cert -keystore osh-keystore.p12
 * 
 * Optionally, if a client cert is also needed to authenticate, it must also
 * be imported into the keystore. For example:
 * 
 * >> keytool -v -importkeystore -srckeystore user.p12 -srcstoretype PKCS12 -destkeystore osh-keystore.p12 -deststoretype PKCS12
 *
 * The keystore is then provided to the VM with the following system properties:
 * -Djavax.net.ssl.keyStore="{path_to}/osh-keystore.p12"
 * -Djavax.net.ssl.keyStorePassword="{password you used when creating the keystore}"
 * -Djavax.net.ssl.trustStore="{path_to}/osh-keystore.p12"
 * -Djavax.net.ssl.trustStorePassword="{password you used when creating the keystore}"
 * 
 */
public class TestTcpCommProvider
{
    
    
    public static void main(String[] args) throws Exception
    {
        TCPCommProvider comm = new TCPCommProvider();
        TCPCommProviderConfig config = new TCPCommProviderConfig();
        config.protocol.remoteHost = "34.123.122.135";
        config.protocol.remotePort = 8089;
        config.protocol.enableTLS = true;
        comm.init(config);
        comm.start();
        
        InputStream is = comm.getInputStream();
        int c;
        while ((c = is.read()) >= 0)
            System.out.print((char)c);
        
        comm.stop();
    }
}
