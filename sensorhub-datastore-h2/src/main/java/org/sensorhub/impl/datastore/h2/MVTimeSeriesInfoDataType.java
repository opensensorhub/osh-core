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
 * H2 DataType implementation to serialize/deserialize ObsSeriesInfo objects
 * </p>
 *
 * @author Alex Robin
 * @date Sep 13, 2019
 */
public class MVTimeSeriesInfoDataType extends BasicDataType<Object> implements ObjectArrayDataType
{
    private static final int MEM_SIZE = 8; // long ID
    
    
    @Override
    public int compare(Object a, Object b)
    {
        // not used as index
        return 0;
    }


    @Override
    public int getMemory(Object obj)
    {
        return MEM_SIZE;
    }


    @Override
    public void write(WriteBuffer wbuf, Object obj)
    {
        MVTimeSeriesInfo info = (MVTimeSeriesInfo)obj;
        wbuf.putVarLong(info.id);
    }


    @Override
    public Object read(ByteBuffer buff)
    {
        long id = DataUtils.readVarLong(buff);
        return new MVTimeSeriesInfo(id);
    }


}
