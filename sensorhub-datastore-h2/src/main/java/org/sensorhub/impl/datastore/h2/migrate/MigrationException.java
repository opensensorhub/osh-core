/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2026 GeoRobotix Innovative Research, LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.datastore.h2.migrate;


/**
 * Raised when the migration cannot proceed or a verification step fails.
 * The original database file is untouched whenever this is thrown.
 */
public class MigrationException extends Exception
{
    private static final long serialVersionUID = 1L;


    public MigrationException(String message)
    {
        super(message);
    }


    public MigrationException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
