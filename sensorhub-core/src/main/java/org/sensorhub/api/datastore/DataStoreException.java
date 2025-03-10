/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.datastore;

import org.sensorhub.api.common.SensorHubException;


/**
 * <p>
 * Exception thrown by certain datastore transactional operations
 * </p>
 *
 * @author Alex Robin
 * @date Oct 28, 2020
 */
public class DataStoreException extends SensorHubException
{
    private static final long serialVersionUID = 1912925630294996306L;

    
    public DataStoreException(String msg)
    {
        super(msg);
    }
    
    
    public DataStoreException(String msg, Throwable e)
    {
        super(msg, e);
    }
}
