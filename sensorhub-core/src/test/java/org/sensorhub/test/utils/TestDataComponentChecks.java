/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.test.utils;

import static org.junit.Assert.*;
import org.junit.Test;
import org.sensorhub.utils.DataComponentChecks;
import org.vast.swe.helper.GeoPosHelper;
import org.vast.swe.helper.RasterHelper;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataType;


public class TestDataComponentChecks
{
    GeoPosHelper geoHelper = new GeoPosHelper();
    RasterHelper imgHelper = new RasterHelper();
    
    
    @Test
    public void testCheckCompatibleRecords()
    {
        DataComponent rec1 = geoHelper.newLocationVectorLatLon("test1");
        DataComponent rec2 = geoHelper.newLocationVectorLatLon("test2");
        assertTrue(DataComponentChecks.checkStructCompatible(rec1, rec2));
    }
    
    
    @Test
    public void testCheckIncompatibleRecords()
    {
        DataComponent rec1 = geoHelper.newLocationVectorLatLon("test1");
        DataComponent rec2 = geoHelper.newLocationVectorLLA("test2");        
        assertFalse(DataComponentChecks.checkStructCompatible(rec1, rec2));
    }
    
    
    @Test
    public void testCheckCompatibleArrays()
    {
        DataComponent array1 = imgHelper.newRgbImage(10, 20, DataType.FLOAT);
        DataComponent array2 = imgHelper.newRgbImage(10, 20, DataType.FLOAT);
        assertTrue(DataComponentChecks.checkStructCompatible(array1, array2));
    }
    
    
    @Test
    public void testCheckIncompatibleArrayElt()
    {
        DataComponent array1 = imgHelper.newRgbImage(10, 20, DataType.FLOAT);
        DataComponent array2 = imgHelper.newGrayscaleImage(10, 20, DataType.FLOAT);
        assertFalse(DataComponentChecks.checkStructCompatible(array1, array2));
    }
    
    
    @Test
    public void testCheckIncompatibleArraySizes()
    {
        DataComponent array1 = imgHelper.newRgbImage(10, 20, DataType.FLOAT);
        DataComponent array2 = imgHelper.newRgbImage(10, 30, DataType.FLOAT);
        assertFalse(DataComponentChecks.checkStructCompatible(array1, array2));
    }

}
