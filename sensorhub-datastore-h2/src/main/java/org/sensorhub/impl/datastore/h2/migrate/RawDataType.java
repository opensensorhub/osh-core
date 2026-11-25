/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2026 GeoRobotix Innovative Research, LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.datastore.h2.migrate;

import java.nio.ByteBuffer;
import org.h2.mvstore.WriteBuffer;
import org.h2.mvstore.type.BasicDataType;
import org.h2.mvstore.type.DataType;


/**
 * H2 2.5 datatype that moves entries as exact bytes. Reads delegate to the
 * real OSH datatype to find the entry boundary and keep the decoded object;
 * writes emit the recorded bytes verbatim; key comparison decodes lazily and
 * uses the real datatype's ordering, so the resulting B-tree is identical to
 * one built through the store implementations.
 */
public final class RawDataType extends BasicDataType<Object>
{
    public static final class Entry
    {
        private final byte[] bytes;
        private Object decoded;
        private boolean isDecoded;


        public Entry(byte[] bytes)
        {
            this.bytes = bytes;
        }


        public Entry(byte[] bytes, Object decoded)
        {
            this.bytes = bytes;
            this.decoded = decoded;
            this.isDecoded = true;
        }


        public byte[] bytes()
        {
            return bytes;
        }


        public Object decoded(DataType<Object> real)
        {
            if (!isDecoded)
            {
                decoded = real.read(ByteBuffer.wrap(bytes));
                isDecoded = true;
            }
            return decoded;
        }
    }


    private final DataType<Object> real;


    @SuppressWarnings("unchecked")
    public RawDataType(DataType<?> real)
    {
        this.real = (DataType<Object>)real;
    }


    public DataType<Object> getRealType()
    {
        return real;
    }


    @Override
    public int compare(Object a, Object b)
    {
        return real.compare(((Entry)a).decoded(real), ((Entry)b).decoded(real));
    }


    @Override
    public int getMemory(Object obj)
    {
        return ((Entry)obj).bytes.length + 32;
    }


    @Override
    public void write(WriteBuffer buff, Object obj)
    {
        buff.put(((Entry)obj).bytes);
    }


    @Override
    public Object read(ByteBuffer buff)
    {
        int start = buff.position();
        Object decoded = real.read(buff);
        int end = buff.position();
        byte[] bytes = new byte[end - start];
        buff.position(start);
        buff.get(bytes);
        return new Entry(bytes, decoded);
    }


    @Override
    public Object[] createStorage(int size)
    {
        return new Object[size];
    }
}
