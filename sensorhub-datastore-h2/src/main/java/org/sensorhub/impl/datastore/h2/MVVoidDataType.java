/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.datastore.h2;

import java.nio.ByteBuffer;
import org.h2.mvstore.WriteBuffer;
import org.h2.mvstore.type.DataType;


/**
 * <p>
 * H2 DataType implementation that writes nothing<br/>
 * This is used for maps that are used as sorted sets and thus don't need to
 * store any value
 * </p>
 *
 * @author Alex Robin
 * @date Oct 26, 2018
 */
public class MVVoidDataType implements DataType
{
    private final static Object VALUE = new Object();
    
            
    @Override
    public int compare(Object objA, Object objB)
    {
        return 0;
    }
    

    @Override
    public int getMemory(Object obj)
    {
        return 0;
    }
    

    @Override
    public void write(WriteBuffer wbuf, Object obj)
    {        
    }
    

    @Override
    public void write(WriteBuffer wbuf, Object[] obj, int len, boolean key)
    {
    }
    

    @Override
    public Object read(ByteBuffer buf)
    {
        // always return a value even though nothing is actually written to file
        // this is needed because MVMap.contains() method checks that value is not null
        return VALUE;
    }
    

    @Override
    public void read(ByteBuffer buf, Object[] obj, int len, boolean key)
    {
        for (int i=0; i<len; i++)
            obj[i] = read(buf);
    }
}
