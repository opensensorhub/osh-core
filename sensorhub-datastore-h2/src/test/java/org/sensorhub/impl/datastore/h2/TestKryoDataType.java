/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2026 GeoRobotix Innovative Research, LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.datastore.h2;

import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.h2.mvstore.WriteBuffer;
import org.junit.Test;
import org.sensorhub.impl.datastore.h2.kryo.KryoDataType;


public class TestKryoDataType
{

    /*
     * H2 2.x reads pages of a chunk that is serialized but not yet saved from a
     * slice of the chunk buffer, so the page does not start at array index 0
     */
    @Test
    public void testReadFromSlicedBuffer()
    {
        var dataType = new KryoDataType();
        var wbuf = new WriteBuffer();
        dataType.write(wbuf, "first");
        dataType.write(wbuf, "second");
        var page = wbuf.getBuffer();
        page.flip();

        int offset = 4096;
        var chunk = new byte[offset + page.remaining()];
        Arrays.fill(chunk, 0, offset, (byte)0x7f);
        page.get(chunk, offset, page.remaining());
        var buff = ByteBuffer.wrap(chunk).position(offset).slice();

        assertEquals("first", dataType.read(buff));
        assertEquals("second", dataType.read(buff));
        assertFalse(buff.hasRemaining());
    }
}
