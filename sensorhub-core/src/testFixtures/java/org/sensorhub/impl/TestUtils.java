/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl;

import net.opengis.sensorml.v20.AbstractProcess;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import org.junit.Assert;
import org.vast.sensorML.SMLUtils;
import org.vast.swe.SWEUtils;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;


public class TestUtils
{
   
    public static void assertEquals(DataComponent comp1, DataComponent comp2) throws Exception
    {
        SWEUtils sweUtils = new SWEUtils(SWEUtils.V2_0);
        
        ByteArrayOutputStream os1 = new ByteArrayOutputStream(1024);
        sweUtils.writeComponent(os1, comp1, true, false);
        byte[] buf1 = os1.toByteArray();
        
        ByteArrayOutputStream os2 = new ByteArrayOutputStream(1024);
        sweUtils.writeComponent(os2, comp2, true, false);
        byte[] buf2 = os2.toByteArray();
        
        Assert.assertTrue("Data components are not equal", Arrays.equals(buf1,  buf2));
    }
    
    
    public static void assertEquals(DataBlock data1, DataBlock data2) throws Exception
    {
        Assert.assertEquals("Data blocks are not the same size", data1.getAtomCount(), data2.getAtomCount());
        
        for (int i=0; i<data1.getAtomCount(); i++)
            Assert.assertEquals("Data blocks values are not equal at index=" + i, data1.getStringValue(i), data2.getStringValue(i));
    }
    
    
    public static void assertEquals(AbstractProcess p1, AbstractProcess p2) throws Exception
    {
        SMLUtils smlUtils = new SMLUtils(SMLUtils.V2_0);
        
        ByteArrayOutputStream os1 = new ByteArrayOutputStream(1024);
        smlUtils.writeProcess(os1, p1, false);
        byte[] buf1 = os1.toByteArray();
        
        ByteArrayOutputStream os2 = new ByteArrayOutputStream(1024);
        smlUtils.writeProcess(os2, p2, false);
        byte[] buf2 = os2.toByteArray();
        
        Assert.assertTrue("SensorML descriptions are not equal", Arrays.equals(buf1,  buf2));
    }
}
