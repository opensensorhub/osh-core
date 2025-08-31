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

import net.opengis.sensorml.v20.AbstractProcess;
import org.sensorhub.impl.processing.OnDemandProcess;
import org.vast.sensorML.SMLHelper;
import org.vast.swe.SWEHelper;


public class TestLinearEquationProcess extends OnDemandProcess
{
    
    public TestLinearEquationProcess()
    {
        super("TestSyncOnDemand", null);
    }
    
    
    protected AbstractProcess initProcess()
    {
        var swe = new SWEHelper();
        
        // create input
        this.input = swe.createRecord()
            .name("input")
            .addField("x", swe.createQuantity().label("Input variable"))
            .build();
        
        // create output
        this.output = swe.createRecord()
            .name("output")
            .addField("y", swe.createQuantity().label("Output variable"))
            .build();
        
        // create params
        this.params = swe.createRecord()
            .name("params")
            .addField("a", swe.createQuantity().label("Gradient"))
            .addField("b", swe.createQuantity().label("Intercept"))
            .build();
        
        // create process description
        return new SMLHelper().createSimpleProcess()
            .name("TestSyncOnDemand")
            .uniqueID("urn:osh:process:test:lineareq")
            .build();
    }


    @Override
    public void execute()
    {
        var x = input.getData().getDoubleValue();
        var a = params.getData().getDoubleValue(0);
        var b = params.getData().getDoubleValue(1);
        output.getData().setDoubleValue(a*x+b);
    }
}
