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
import com.google.common.collect.Range;


/**
 * <p>
 * H2 DataType implementation for ObsKey objects
 * </p>
 *
 * @author Alex Robin
 * @date Apr 7, 2018
 */
public class MVFeatureRefDataType extends BasicDataType<Object> implements ObjectArrayDataType
{
    private static final int MIN_MEM_SIZE = 8+12+12;
    
            
    @Override
    public int compare(Object objA, Object objB)
    {
        // not needed since it's never used as key
        return 0;
    }
    

    @Override
    public int getMemory(Object obj)
    {
        return MIN_MEM_SIZE;
    }
    

    @Override
    public void write(WriteBuffer wbuf, Object obj)
    {
        MVFeatureRef ref = (MVFeatureRef)obj;
        wbuf.putVarLong(ref.getParentID());
        wbuf.putVarLong(ref.getInternalID());
        H2Utils.writeTimeRange(wbuf, ref.getValidityPeriod());
    }
    

    @Override
    public Object read(ByteBuffer buff)
    {
        long parentID = DataUtils.readVarLong(buff);
        long internalID = DataUtils.readVarLong(buff);        
        Range<Instant> timeRange = H2Utils.readTimeRange(buff);
        return new MVFeatureRef(parentID, internalID, timeRange);
    }
    

}
