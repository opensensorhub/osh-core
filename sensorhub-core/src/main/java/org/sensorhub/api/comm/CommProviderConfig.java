/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.

 ******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.comm;

import org.sensorhub.api.config.DisplayInfo;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.impl.module.ModuleRegistry;


/**
 * <p>
 * Base class for communication provider configuration objects
 * </p>
 *
 * @param <P> Type of protocol configuration
 *
 * @author Alex Robin
 * @date Nov 9, 2020
 */
public class CommProviderConfig<P> extends ModuleConfig
{
    @DisplayInfo(label="Protocol Options")
    public P protocol;


    @SuppressWarnings({ "rawtypes", "unchecked" })
    public ICommProvider getProvider(ModuleRegistry moduleRegistry)
    {
        try
        {
            ICommProvider commProvider = (ICommProvider) moduleRegistry.loadClass(moduleClass);
            commProvider.init(this);
            return commProvider;
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Cannot load module " + moduleClass, e);
        }
    }
}
