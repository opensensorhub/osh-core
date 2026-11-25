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

import static org.junit.Assert.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.type.DataType;
import org.h2.mvstore.type.ObjectDataType;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sensorhub.impl.datastore.h2.migrate.spi.LegacyStore;


/**
 * Spikes for the central mechanism: an H2 1.4 store running in the isolated
 * classloader while H2 2.5 is on the parent classpath, with entry boundaries
 * found by parent-side datatypes.
 */
public class TestLegacyLoader
{
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();


    @Test
    public void testClassIsolation() throws Exception
    {
        try (LegacyLoader loader = TestSupport.legacyLoader())
        {
            Class<?> legacyStore = Class.forName("org.h2.mvstore.MVStore", true, loader);
            assertNotSame("legacy loader must define its own org.h2 classes", MVStore.class, legacyStore);
            assertSame(loader, legacyStore.getClassLoader());

            Class<?> spi = Class.forName(LegacyStore.Factory.class.getName(), true, loader);
            assertSame("spi interfaces must be shared with the parent", LegacyStore.Factory.class, spi);

            Class<?> impl = Class.forName(LegacyStore.Factory.IMPL_CLASS, true, loader);
            assertSame(loader, impl.getClassLoader());
            assertTrue(LegacyStore.Factory.class.isAssignableFrom(impl));
        }
    }


    @Test
    public void testWriteAndReadFormat1File() throws Exception
    {
        Path file = tmp.getRoot().toPath().resolve("legacy.dat");
        var od = new ObjectDataType();
        @SuppressWarnings("unchecked")
        DataType<Object> type = (DataType<Object>)(DataType<?>)od;

        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 2000; i++)
            ids.add(i);
        Collections.shuffle(ids, new Random(42));

        try (LegacyLoader loader = TestSupport.legacyLoader())
        {
            var writer = loader.factory().openForWrite(file.toString());
            try
            {
                var m = writer.openMap("plain", (ka, da, kb, db) -> od.compare(da, db));
                for (int i: ids)
                {
                    String key = String.format("key-%05d", i);
                    m.put(TestSupport.encode(type, key), key, TestSupport.encode(type, i));
                }
                var r = writer.openRTreeMap("spatial", 3);
                for (int i = 0; i < 100; i++)
                    r.put(i, new float[] {i, i + 1, -i, -i + 1, 0, 0}, TestSupport.encode(type, i * 10));
                writer.commit();
            }
            finally
            {
                writer.close();
            }
        }

        assertEquals("H2 1.4 must write file format 1", 1, FileOps.readH2Format(file));

        try (LegacyLoader loader = TestSupport.legacyLoader();
             SourceReader.Legacy reader = new SourceReader.Legacy(loader.factory(), file))
        {
            assertTrue(reader.describe(), reader.describe().contains("1.4.200"));
            assertEquals(new TreeSet<>(List.of("plain", "spatial")), new TreeSet<>(reader.getMapNames()));
            assertFalse(reader.isRTree("plain"));
            assertTrue(reader.isRTree("spatial"));

            List<String> keys = new ArrayList<>();
            long n = reader.readMap("plain", od, od, (k, dk, v, dv) -> {
                String key = (String)dk;
                keys.add(key);
                assertArrayEquals("key bytes must be exactly what was written", TestSupport.encode(type, key), k);
                int expected = Integer.parseInt(key.substring(4));
                assertEquals(expected, dv);
                assertArrayEquals(TestSupport.encode(type, expected), v);
            });
            assertEquals(2000, n);
            List<String> sorted = new ArrayList<>(keys);
            Collections.sort(sorted);
            assertEquals("cursor must yield keys in order", sorted, keys);

            TreeSet<Long> seen = new TreeSet<>();
            long rn = reader.readRTreeMap("spatial", 3, od, (id, mm, v, dv) -> {
                seen.add(id);
                assertEquals(6, mm.length);
                assertEquals((float)id, mm[0], 0f);
                assertEquals((float)(id + 1), mm[1], 0f);
                assertEquals((int)(id * 10), dv);
            });
            assertEquals(100, rn);
            assertEquals(100, seen.size());
        }
    }
}
