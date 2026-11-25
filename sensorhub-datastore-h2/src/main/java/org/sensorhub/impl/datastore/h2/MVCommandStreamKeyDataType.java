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
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.datastore.command.CommandStreamKey;


/**
 * <p>
 * H2 DataType implementation for internal DataStream key objects
 * </p>
 *
 * @author Alex Robin
 * @date Mar 26, 2021
 */
class MVCommandStreamKeyDataType extends BasicDataType<Object> implements ObjectArrayDataType
{
    final int idScope;
    
    
    MVCommandStreamKeyDataType(int idScope)
    {
        this.idScope = idScope;
    }
    
    
    @Override
    public int compare(Object objA, Object objB)
    {
        CommandStreamKey a = (CommandStreamKey)objA;
        CommandStreamKey b = (CommandStreamKey)objB;
        
        return Long.compare(
            a.getInternalID().getIdAsLong(),
            b.getInternalID().getIdAsLong());
    }
    

    @Override
    public int getMemory(Object obj)
    {
        var id = ((CommandStreamKey)obj).getInternalID().getIdAsLong();
        return DataUtils.getVarLongLen(id);
    }
    

    @Override
    public void write(WriteBuffer wbuf, Object obj)
    {
        CommandStreamKey key = (CommandStreamKey)obj;
        wbuf.putVarLong(key.getInternalID().getIdAsLong());
    }
    

    @Override
    public Object read(ByteBuffer buff)
    {
        long internalID = DataUtils.readVarLong(buff);
        return new CommandStreamKey(BigId.fromLong(idScope, internalID));
    }
    

}
