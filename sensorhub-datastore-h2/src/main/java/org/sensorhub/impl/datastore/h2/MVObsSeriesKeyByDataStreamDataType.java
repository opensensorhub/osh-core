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
 * H2 DataType implementation to index observation series by datastream ID,
 * then result time, then foi ID.
 * </p>
 *
 * @author Alex Robin
 * @date Sep 12, 2019
 */
class MVObsSeriesKeyByDataStreamDataType extends BasicDataType<Object> implements ObjectArrayDataType
{
    private static final int MEM_SIZE = 8+12+8; // long ID + instant + long ID
    
    
    @Override
    public int compare(Object objA, Object objB)
    {
        MVTimeSeriesKey a = (MVTimeSeriesKey)objA;
        MVTimeSeriesKey b = (MVTimeSeriesKey)objB;
        
        // first compare datastream IDs
        int comp = Long.compare(a.dataStreamID, b.dataStreamID);
        if (comp != 0)
            return comp;
        
        // if datastream IDs are the same, compare result time stamps
        comp = a.resultTime.compareTo(b.resultTime);
        if (comp != 0)
            return comp;
        
        // if result times are equal, compare foi IDs
        return Long.compare(a.foiID, b.foiID);
    }
    

    @Override
    public int getMemory(Object obj)
    {
        return MEM_SIZE;
    }
    

    @Override
    public void write(WriteBuffer wbuf, Object obj)
    {
        MVTimeSeriesKey key = (MVTimeSeriesKey)obj;
        wbuf.putVarLong(key.dataStreamID);
        wbuf.putVarLong(key.foiID);
        H2Utils.writeInstant(wbuf, key.resultTime);
    }
    

    @Override
    public Object read(ByteBuffer buff)
    {
        long dataStreamID = DataUtils.readVarLong(buff);
        long foiID = DataUtils.readVarLong(buff);
        Instant resultTime = H2Utils.readInstant(buff);        
        return new MVTimeSeriesKey(dataStreamID, foiID, resultTime);
    }
    

}
