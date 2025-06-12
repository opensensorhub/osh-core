/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.sos;

import java.util.stream.Collectors;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.datastore.obs.ObsFilter;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.sensorhub.impl.service.swe.SWEService;
import org.vast.ows.sos.SOSServiceCapabilities;
import com.google.common.base.Strings;


/**
 * <p>
 * Implementation of SensorHub generic SOS service.
 * This service is automatically configured (mostly) from information obtained
 * from the selected data sources (sensors, storages, processes, etc).
 * </p>
 *
 * @author Alex Robin
 * @since Sep 7, 2013
 */
public class SOSService extends SWEService<SOSServiceConfig>
{
    TimeOutMonitor timeOutMonitor;


    @Override
    protected void doInit() throws SensorHubException
    {        
        super.doInit();
        
        // validate config
        for (var providerConfig: config.customDataProviders)
        {
            if (Strings.isNullOrEmpty(providerConfig.systemUID))
                throw new SensorHubException("Provider configuration must specify a system unique ID");
        }
        
        for (var formatConfig: config.customFormats)
        {
            if (Strings.isNullOrEmpty(formatConfig.mimeType))
                throw new SensorHubException("Custom format must specify a mime type");
        }        
        
        this.securityHandler = new SOSSecurity(this, config.security.enableAccessControl);
    }
    
    
    protected ObsFilter getResourceFilter()
    {
        if (config.exposedResources != null)
        {
            return config.exposedResources.getObsFilter();
        }
        
        // else if some custom providers are configured, build a filter to expose them (and nothing else)
        else if (config.exposedResources == null && config.customDataProviders != null && !config.customDataProviders.isEmpty())
        {
            var sysUIDs = config.customDataProviders.stream()
                .map(config -> config.systemUID)
                .collect(Collectors.toSet());
            
            return new ObsFilter.Builder()
                .withSystems().withUniqueIDs(sysUIDs).done()
                .build();
        }
        
        return null;
    }


    @Override
    protected void doStart() throws SensorHubException
    {
        super.doStart();
        
        // init timeout monitor
        timeOutMonitor = new TimeOutMonitor(threadPool);

        // deploy servlet
        servlet = new SOSServlet(this, (SOSSecurity)this.securityHandler, getLogger());
        deploy();

        setState(ModuleState.STARTED);
    }


    public SOSServiceCapabilities getCapabilities()
    {
        if (isStarted())
            return (SOSServiceCapabilities)servlet.updateCapabilities();
        else
            return null;
    }


    public TimeOutMonitor getTimeOutMonitor()
    {
        return timeOutMonitor;
    }


    public SOSServlet getServlet()
    {
        return (SOSServlet)servlet;
    }
}
