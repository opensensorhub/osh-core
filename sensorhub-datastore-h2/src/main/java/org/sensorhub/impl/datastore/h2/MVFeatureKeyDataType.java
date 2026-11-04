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
import java.util.Comparator;
import org.h2.mvstore.ObjectArrayDataType;
import org.h2.mvstore.DataUtils;
import org.h2.mvstore.WriteBuffer;
import org.h2.mvstore.type.BasicDataType;
import org.sensorhub.api.datastore.feature.FeatureKey;


/**
 * <p>
 * H2 DataType implementation for FeatureKey objects
 * </p>
 *
 * @author Alex Robin
 * @date Apr 7, 2018
 */
class MVFeatureKeyDataType extends BasicDataType<Object> implements ObjectArrayDataType
{
    Comparator<Instant> timeCompare = Comparator.nullsFirst(Comparator.naturalOrder());
    int idScope;
    
    
    MVFeatureKeyDataType(int idScope)
    {
        this.idScope = idScope;
    }
    
    
    @Override
    public int compare(Object objA, Object objB)
    {
        FeatureKey a = (FeatureKey)objA;
        FeatureKey b = (FeatureKey)objB;
        
        // first compare internal ID part of the key
        int idComp = Long.compare(a.getInternalID().getIdAsLong(), b.getInternalID().getIdAsLong());
        if (idComp != 0)
            return idComp;
        
        // only if IDs are the same, compare valid start time
        return timeCompare.compare(a.getValidStartTime(), b.getValidStartTime());
    }
    

    @Override
    public int getMemory(Object obj)
    {
        FeatureKey key = (FeatureKey)obj;
        return DataUtils.getVarLongLen(key.getInternalID().getIdAsLong()) + 
               H2Utils.getInstantEncodedLen(key.getValidStartTime());
    }
    

    @Override
    public void write(WriteBuffer wbuf, Object obj)
    {
        FeatureKey key = (FeatureKey)obj;
        wbuf.putVarLong(key.getInternalID().getIdAsLong());
        H2Utils.writeInstant(wbuf, key.getValidStartTime());
    }
    

    @Override
    public Object read(ByteBuffer buff)
    {
        long internalID = DataUtils.readVarLong(buff); 
        Instant validStartTime = H2Utils.readInstant(buff);
        return new FeatureKey(idScope, internalID, validStartTime);
    }
    

}
