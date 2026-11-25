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
import org.h2.mvstore.ObjectArrayDataType;
import org.h2.mvstore.DataUtils;
import org.h2.mvstore.WriteBuffer;
import org.h2.mvstore.type.BasicDataType;


/**
 * <p>
 * H2 DataType implementation for internal DataStream key objects
 * </p>
 *
 * @author Alex Robin
 * @date Apr 7, 2018
 */
class MVTimeSeriesSystemKeyDataType extends BasicDataType<Object> implements ObjectArrayDataType
{
    private static final int MIN_MEM_SIZE = 8+8+4;
    
            
    @Override
    public int compare(Object objA, Object objB)
    {
        MVTimeSeriesSystemKey a = (MVTimeSeriesSystemKey)objA;
        MVTimeSeriesSystemKey b = (MVTimeSeriesSystemKey)objB;
        
        // first compare system internal ID
        int comp = Long.compare(a.systemID, b.systemID);
        if (comp != 0)
            return comp;
        
        // only if IDs are the same, compare output name
        comp = a.signalName.compareTo(b.signalName);
        if (comp != 0)
            return comp;
        
        // only if output names are the same, compare valid times
        // sort in reverse order so that latest version is always first
        return -Long.compare(a.validStartTime, b.validStartTime);
    }
    

    @Override
    public int getMemory(Object obj)
    {
        MVTimeSeriesSystemKey key = (MVTimeSeriesSystemKey)obj;
        return MIN_MEM_SIZE + key.signalName.length();
    }
    

    @Override
    public void write(WriteBuffer wbuf, Object obj)
    {
        MVTimeSeriesSystemKey key = (MVTimeSeriesSystemKey)obj;
        wbuf.putVarLong(key.internalID);
        wbuf.putVarLong(key.systemID);
        H2Utils.writeAsciiString(wbuf, key.signalName);
        wbuf.putVarLong(key.validStartTime);
    }
    

    @Override
    public Object read(ByteBuffer buff)
    {
        long internalID = DataUtils.readVarLong(buff);
        long sysID = DataUtils.readVarLong(buff);
        String outputName = H2Utils.readAsciiString(buff);
        long validStartTime = DataUtils.readVarLong(buff);
        return new MVTimeSeriesSystemKey(internalID, sysID, outputName, validStartTime);
    }
    

}
