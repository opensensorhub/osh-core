/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.datastore.view;

import org.sensorhub.api.datastore.feature.FeatureFilter;
import org.sensorhub.api.datastore.feature.IFeatureStore;
import org.sensorhub.api.datastore.feature.IFeatureStoreBase.FeatureField;
import org.vast.ogc.gml.IFeature;
import org.vast.util.Asserts;


/**
 * <p>
 * Filtered view implemented as a wrapper of the underlying {@link IFeatureStore}
 * </p>
 *
 * @author Alex Robin
 * @date July 15, 2025
 */
public class FeatureStoreView extends FeatureStoreViewBase<IFeature, FeatureField, FeatureFilter, IFeatureStore> implements IFeatureStore
{
    
    public FeatureStoreView(IFeatureStore delegate, FeatureFilter viewFilter)
    {
        super(Asserts.checkNotNull(delegate, IFeatureStore.class), viewFilter);
    }
}