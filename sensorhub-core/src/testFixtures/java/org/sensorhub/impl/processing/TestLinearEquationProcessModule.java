/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2022 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.processing;

import org.sensorhub.api.processing.IDataProcess;
import org.sensorhub.api.processing.ProcessConfig;


public class TestLinearEquationProcessModule extends AbstractProcessWrapperModule<ProcessConfig>
{
    
    public TestLinearEquationProcessModule()
    {
        
    }


    @Override
    protected IDataProcess initProcess()
    {
        return new TestLinearEquationProcess();
    }
    
    
    @Override
    public void doInit()
    {
        super.doInit();
        
        var params = process.getParameterDescriptors().get("params");
        params.getData().setDoubleValue(0, 2.5);
        params.getData().setDoubleValue(1, -3.6);
    }
}
