/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.procedure;

import org.sensorhub.api.datastore.FeatureKey;
import org.sensorhub.api.datastore.IFeatureStore;


/**
 * <p>
 * Data store for storing procedure shadows that include procedure metadata
 * as well as their latest state.
 * </p>
 *
 * @author Alex Robin
 * @date Sep 10, 2019
 */
public interface IProcedureShadowStore extends IFeatureStore<FeatureKey, IProcedureWithState>
{

    /**
     * Helper method to retrieve a procedure by its unique ID
     * @param uid The procedure UID
     * @return The procedure shadow or null if none was found with the given UID
     */
    public default IProcedureWithState get(String uid)
    {
        return getLastVersion(uid);
    }
}