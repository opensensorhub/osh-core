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
import java.time.Instant;
import org.h2.mvstore.ObjectArrayDataType;
import org.h2.mvstore.DataUtils;
import org.h2.mvstore.WriteBuffer;
import org.h2.mvstore.type.BasicDataType;


/**
 * <p>
 * H2 DataType implementation to index observations and commands by series ID,
 * then phenomenon/actuation time.
 * </p>
 *
 * @author Alex Robin
 * @date Sep 12, 2019
 */
class MVTimeSeriesRecordKeyDataType extends BasicDataType<Object> implements ObjectArrayDataType
{
    final int idScope;
    
    
    MVTimeSeriesRecordKeyDataType(int idScope)
    {
        this.idScope = idScope;
    }
    
    
    @Override
    public int compare(Object objA, Object objB)
    {
        MVTimeSeriesRecordKey a = (MVTimeSeriesRecordKey)objA;
        MVTimeSeriesRecordKey b = (MVTimeSeriesRecordKey)objB;
        
        // first compare series IDs
        int comp = Long.compare(a.seriesID, b.seriesID);
        if (comp != 0)
            return comp;
        
        // if series IDs are equal, compare time stamps
        return a.getTimeStamp().compareTo(b.getTimeStamp());
    }
    

    @Override
    public int getMemory(Object obj)
    {
        MVTimeSeriesRecordKey key = (MVTimeSeriesRecordKey)obj;
        return getEncodedLen(key);
    }
    
    
    public static int getEncodedLen(MVTimeSeriesRecordKey key)
    {
        return DataUtils.getVarLongLen(key.seriesID) +
               H2Utils.getInstantEncodedLen(key.timeStamp);
    }
    

    @Override
    public void write(WriteBuffer wbuf, Object obj)
    {
        MVTimeSeriesRecordKey key = (MVTimeSeriesRecordKey)obj;
        //encode(wbuf, key);
        wbuf.put(key.getIdAsBytes());
    }
    
    
    public static void encode(WriteBuffer wbuf, MVTimeSeriesRecordKey key)
    {
        wbuf.putVarLong(key.seriesID);
        H2Utils.writeInstant(wbuf, key.getTimeStamp());
    }
    

    @Override
    public Object read(ByteBuffer buff)
    {
        return decode(idScope, buff);
    }
    
    
    public static MVTimeSeriesRecordKey decode(int idScope, ByteBuffer buff)
    {
        long seriesID = DataUtils.readVarLong(buff);
        Instant phenomenonTime = H2Utils.readInstant(buff);
        return new MVTimeSeriesRecordKey(idScope, seriesID, phenomenonTime);
    }
    

}
