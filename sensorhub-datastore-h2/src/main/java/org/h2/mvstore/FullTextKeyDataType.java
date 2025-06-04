/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.h2.mvstore;

import java.nio.ByteBuffer;
import org.h2.mvstore.type.DataType;


public class FullTextKeyDataType implements DataType
{
    DataType refKeyDataType;
    
    
    public FullTextKeyDataType(DataType refKeyDataType)
    {
        this.refKeyDataType = refKeyDataType;
    }
    
    
    @Override
    public int compare(Object objA, Object objB)
    {
        FullTextKey<?> a = (FullTextKey<?>)objA;
        FullTextKey<?> b = (FullTextKey<?>)objB;
        
        var comp = a.word.compareTo(b.word);
        if (comp != 0)
            return comp;
        
        if (a.refKey == null || b.refKey == null)
            return -1;
        
        return refKeyDataType.compare(a.refKey, b.refKey);
    }
    

    @Override
    public int getMemory(Object obj)
    {
        var key = (FullTextKey<?>)obj;
        return key.word.length() + refKeyDataType.getMemory(key.refKey);
    }
    

    @Override
    public void write(WriteBuffer wbuf, Object obj)
    {
        FullTextKey<?> key = (FullTextKey<?>)obj;
        wbuf.putVarInt(key.word.length());
        wbuf.putStringData(key.word, key.word.length());
        refKeyDataType.write(wbuf, key.refKey);
    }
    

    @Override
    public void write(WriteBuffer wbuf, Object[] obj, int len, boolean key)
    {
        for (int i=0; i<len; i++)
            write(wbuf, obj[i]);
    }
    

    @Override
    public Object read(ByteBuffer buff)
    {
        String word = DataUtils.readString(buff);
        var refKey = refKeyDataType.read(buff);
        return new FullTextKey<>(word, refKey);
    }
    

    @Override
    public void read(ByteBuffer buff, Object[] obj, int len, boolean key)
    {
        for (int i=0; i<len; i++)
            obj[i] = read(buff);
    }

}
