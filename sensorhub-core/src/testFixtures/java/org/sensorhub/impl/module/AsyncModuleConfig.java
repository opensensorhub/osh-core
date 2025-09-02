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

import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.api.module.ModuleEvent.ModuleState;


public class AsyncModuleConfig extends ModuleConfig
{
    public String moduleIDNeededForInit;
    public ModuleState moduleStateNeededForInit;
    public boolean useWaitLoopForInit;
    public boolean useThreadForInit;
    public long initDelay;
    public long initExecTime;
    
    public String moduleIDNeededForStart;
    public ModuleState moduleStateNeededForStart;
    public boolean useWaitLoopForStart;
    public boolean useThreadForStart;
    public long startDelay;
    public long startExecTime;
    
    public boolean useThreadForStop;
    public long stopDelay;
    public long stopExecTime; 
    
    // not part of config, but used in tests
    boolean initEventReceived;
    boolean startEventReceived;
    boolean stopEventReceived;
    
    
    AsyncModuleConfig()
    {
        this.moduleClass = AsyncModule.class.getCanonicalName();
    }
}