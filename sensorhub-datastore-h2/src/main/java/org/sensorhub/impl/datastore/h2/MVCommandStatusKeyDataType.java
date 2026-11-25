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
import org.h2.mvstore.WriteBuffer;
import org.h2.mvstore.type.BasicDataType;
import org.sensorhub.api.common.BigId;


/**
 * <p>
 * H2 DataType implementation for internal command status key objects
 * </p>
 *
 * @author Alex Robin
 * @date Jan 5, 2022
 */
class MVCommandStatusKeyDataType extends BasicDataType<Object> implements ObjectArrayDataType
{
    int idScope;
    
    
    MVCommandStatusKeyDataType(int idScope)
    {
        this.idScope = idScope;
    }
    
    
    @Override
    public int compare(Object objA, Object objB)
    {
        MVCommandStatusKey a = (MVCommandStatusKey)objA;
        MVCommandStatusKey b = (MVCommandStatusKey)objB;
        
        // first compare command IDs
        int comp = a.cmdID.compareTo(b.cmdID);
        if (comp != 0)
            return comp;
        
        // if task IDs are equal, compare time stamps
        return a.reportTime.compareTo(b.reportTime);
    }
    

    @Override
    public int getMemory(Object obj)
    {
        var key = (MVCommandStatusKey)obj;
        return getEncodedLen(key);
    }
    
    
    public static int getEncodedLen(MVCommandStatusKey key)
    {
        return 1 + key.cmdID.size() +
            H2Utils.getInstantEncodedLen(key.reportTime);
    }
    

    @Override
    public void write(WriteBuffer wbuf, Object obj)
    {
        MVCommandStatusKey key = (MVCommandStatusKey)obj;
        //encode(wbuf, key);
        wbuf.put(key.getIdAsBytes());
    }
    
    
    public static void encode(WriteBuffer wbuf, MVCommandStatusKey key)
    {
        byte[] cmdID = key.cmdID.getIdAsBytes();
        wbuf.put((byte)cmdID.length);
        wbuf.put(cmdID);
        H2Utils.writeInstant(wbuf, key.reportTime);
    }
    

    @Override
    public Object read(ByteBuffer buff)
    {
        return decode(idScope, buff);
    }
    
    
    public static MVCommandStatusKey decode(int idScope, ByteBuffer buff)
    {
        int cmdIdLen = buff.get();
        byte[] id = new byte[cmdIdLen];
        buff.get(id);
        var cmdID = BigId.fromBytes(idScope, id);
        var reportTime = H2Utils.readInstant(buff);
        return new MVCommandStatusKey(cmdID, reportTime);
    }
    

}
