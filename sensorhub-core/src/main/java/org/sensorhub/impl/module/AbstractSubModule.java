/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.module;

import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.module.IModule;
import org.sensorhub.api.module.ISubModule;
import org.sensorhub.api.module.SubModuleConfig;
import org.slf4j.Logger;
import org.vast.util.Asserts;


/**
 * <p>
 * Base implementation of ISubModule for submodule classes such as comm
 * providers, message queues, etc. 
 * </p>
 *
 * @author Alex Robin
 * @param <T> Type of module config
 * @since Feb 4, 2025
 */
public abstract class AbstractSubModule<T extends SubModuleConfig> implements ISubModule<T>
{
    protected IModule<?> parentModule;
    protected T config;
    

    @Override
    public void setParentModule(IModule<?> parentModule)
    {
        this.parentModule = Asserts.checkNotNull(parentModule, "parentModule");
    }
    
    
    @Override
    public IModule<?> getParentModule()
    {
        return Asserts.checkNotNull(parentModule, "parentModule");
    }


    @Override
    public void init(T config) throws SensorHubException
    {
        this.config = Asserts.checkNotNull(config, "config");
    }
    
    
    @Override
    public String getName()
    {
        return config != null && config.name != null ?
            config.name :
            getClass().getSimpleName();
    }
    
    
    @Override
    public T getConfiguration()
    {
        return config;
    }
    
    
    protected Logger getLogger()
    {
        return getParentModule().getLogger();
    }
}
