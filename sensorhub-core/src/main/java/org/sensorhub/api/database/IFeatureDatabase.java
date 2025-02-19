/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.database;

import org.sensorhub.api.datastore.feature.IFeatureStore;


/**
 * <p>
 * Common interface for feature databases
 * </p>
 *
 * @author Alex Robin
 * @date Oct 11, 2019
 */
public interface IFeatureDatabase extends IDatabase
{

    /**
     * @return The data store containing the feature data
     */
    IFeatureStore getFeatureStore();
    
}
