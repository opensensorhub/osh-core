/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.utils;

import org.vast.data.DataIterator;
import org.vast.util.Asserts;
import net.opengis.swe.v20.DataArray;
import net.opengis.swe.v20.DataComponent;


/**
 * <p>
 * Helper methods to check various aspects of SWE Common component structures
 * </p>
 *
 * @author Alex Robin
 * @since Sep 19, 2020
 */
public class DataComponentChecks
{

    /**
     * Check if two data components have compatible structures
     * @param rec1
     * @param rec2
     * @return True if structures are compatible, false otherwise
     */
    public static boolean checkStructCompatible(DataComponent rec1, DataComponent rec2)
    {
        Asserts.checkNotNull(rec1, DataComponent.class);
        Asserts.checkNotNull(rec2, DataComponent.class);
        
        StringBuilder buf1 = structCompatString(rec1);
        StringBuilder buf2 = structCompatString(rec2);
        //System.out.printf("%s\n%s\n", buf1.toString(), buf2.toString());
        return buf1.toString().equals(buf2.toString());
    }
    
    
    private static StringBuilder structCompatString(DataComponent root)
    {
        StringBuilder buf = new StringBuilder();
        
        DataIterator it = new DataIterator(root);
        while (it.hasNext())
        {
            DataComponent c = it.next();
            buf.append(c.getName()).append(':');
            buf.append(getComponentType(c.getClass()));
            if (c instanceof DataArray)
            {
                boolean varSize = ((DataArray) c).isVariableSize();
                if (varSize)
                    buf.append("[?]");
                else
                    buf.append('[').append(c.getComponentCount()).append(']');
            }
            buf.append('|');
        }
        
        return buf;
    }
    
    
    private static String getComponentType(Class<?> c)
    {
        for (Class<?> i: c.getInterfaces())
        {
            if (DataComponent.class.isAssignableFrom(i))
                return i.getSimpleName();
        }
        
        if (c.getSuperclass() != null)
            return getComponentType(c.getSuperclass());
        else
            return null;
    }
}
