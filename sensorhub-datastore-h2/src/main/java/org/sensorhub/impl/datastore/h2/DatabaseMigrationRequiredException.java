/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2026 GeoRobotix Innovative Research, LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.datastore.h2;

import org.sensorhub.api.datastore.DataStoreException;


/**
 * Thrown when a database file was written by an older H2 version or lacks the
 * version marker, so it must be migrated before the module can open it. The
 * message tells the operator how (autoMigrate flag or command line tool).
 */
public class DatabaseMigrationRequiredException extends DataStoreException
{
    private static final long serialVersionUID = 1L;


    public DatabaseMigrationRequiredException(String message)
    {
        super(message);
    }


    public DatabaseMigrationRequiredException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
