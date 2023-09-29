/***************************** BEGIN LICENSE BLOCK ***************************
 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2023 Botts Innovative Research Inc. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.comm;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Event arguments for a connection event
 *
 * @author Michael Elmore
 * @since September 2023
 */
public class ConnectionEventArgs {
    private final InputStream inputStream;
    private final OutputStream outputStream;

    /**
     * @param inputStream  The input stream for the connection
     * @param outputStream The output stream for the connection
     */
    public ConnectionEventArgs(InputStream inputStream, OutputStream outputStream) {
        this.inputStream = inputStream;
        this.outputStream = outputStream;
    }

    /**
     * @return The input stream for the connection
     */
    public InputStream getInputStream() {
        return inputStream;
    }

    /**
     * @return The output stream for the connection
     */
    public OutputStream getOutputStream() {
        return outputStream;
    }
}
