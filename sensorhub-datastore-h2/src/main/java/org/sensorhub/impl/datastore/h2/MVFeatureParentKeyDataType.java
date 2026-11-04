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
import org.sensorhub.api.datastore.feature.FeatureKey;


/**
 * <p>
 * DataType implementation for MVResourceParentKey objects.<br/>
 * When used as key in an index, sorting is by parentID ID, then internal ID.
 * </p>
 *
 * @author Alex Robin
 * @date Oct 28, 2018
 */
class MVFeatureParentKeyDataType extends BasicDataType<Object> implements ObjectArrayDataType
{
    final int idScope;
    final boolean storeValidTime;
    
    
    MVFeatureParentKeyDataType(int idScope, boolean storeValidTime)
    {
        this.idScope = idScope;
        this.storeValidTime = storeValidTime;
    }
    
    
    @Override
    public int compare(Object objA, Object objB)
    {
        FeatureKey a = (FeatureKey)objA;
        FeatureKey b = (FeatureKey)objB;
        
        // first compare parent ID part of the key
        var parentA = a instanceof MVFeatureParentKey ? ((MVFeatureParentKey)a).getParentID() : 0;
        var parentB = b instanceof MVFeatureParentKey ? ((MVFeatureParentKey)b).getParentID() : 0;
        int comp = Long.compare(parentA, parentB);
        if (comp != 0)
            return comp;
        
        // if parent IDs are the same, compare internal IDs
        comp = Long.compare(a.getInternalID().getIdAsLong(), b.getInternalID().getIdAsLong());
        if (comp != 0)
            return comp;
        
        // if internal IDs are the same, compare valid time
        return a.getValidStartTime().compareTo(b.getValidStartTime());
    }
    

    @Override
    public int getMemory(Object obj)
    {
        MVFeatureParentKey key = (MVFeatureParentKey)obj;
        return DataUtils.getVarLongLen(key.getParentID()) +
               DataUtils.getVarLongLen(key.getInternalID().getIdAsLong());
    }
    

    @Override
    public void write(WriteBuffer wbuf, Object obj)
    {
        MVFeatureParentKey key = (MVFeatureParentKey)obj;
        wbuf.putVarLong(key.getParentID());
        wbuf.putVarLong(key.getInternalID().getIdAsLong());
        
        if (storeValidTime)
            H2Utils.writeInstant(wbuf, key.getValidStartTime());
    }
    

    @Override
    public Object read(ByteBuffer buf)
    {
        long parentID = DataUtils.readVarLong(buf);
        long internalID = DataUtils.readVarLong(buf);
        
        if (storeValidTime)
        {
            var validStartTime = H2Utils.readInstant(buf);
            return new MVFeatureParentKey(idScope, parentID, internalID, validStartTime);
        }
        else
            return new MVFeatureParentKey(idScope, parentID, internalID, FeatureKey.TIMELESS);
    }
    

}
