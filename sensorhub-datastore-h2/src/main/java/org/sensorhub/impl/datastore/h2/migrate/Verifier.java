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

import org.sensorhub.impl.datastore.h2.MVObsSystemDatabase;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import org.h2.mvstore.Cursor;
import org.h2.mvstore.DataUtils;
import org.h2.mvstore.MVBTreeMap;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.Page;
import org.h2.mvstore.rtree.MVRTreeMap;
import org.h2.mvstore.rtree.Spatial;
import org.sensorhub.impl.datastore.h2.H2Utils;


/**
 * Two independent checks of the migrated file.
 * Pass one compares the source and the interim file entry by entry as bytes:
 * map name sets, per-map counts and a SHA-256 over every key and value
 * (ordered for B-trees, order-independent for R-trees).
 * Pass two opens the interim file through the real store implementations and
 * decodes every entry with the real datatypes.
 */
final class Verifier
{
    private Verifier()
    {
    }


    /** Per-map streaming digest of length-prefixed key and value bytes */
    private static final class MapDigest
    {
        final MessageDigest md = newDigest();
        final List<byte[]> unordered = new ArrayList<>();
        long count;

        void add(byte[] key, byte[] value, boolean ordered)
        {
            count++;
            if (ordered)
            {
                update(md, key);
                update(md, value);
            }
            else
            {
                MessageDigest one = newDigest();
                update(one, key);
                update(one, value);
                unordered.add(one.digest());
            }
        }

        byte[] result()
        {
            if (!unordered.isEmpty())
            {
                unordered.sort(Arrays::compare);
                for (byte[] d: unordered)
                    md.update(d);
            }
            return md.digest();
        }

        static void update(MessageDigest md, byte[] bytes)
        {
            md.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
            md.update(bytes);
        }
    }


    private static MessageDigest newDigest()
    {
        try
        {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
    }


    private static byte[] spatialKeyBytes(long id, float[] minMax)
    {
        ByteBuffer b = ByteBuffer.allocate(8 + 4 * minMax.length);
        b.putLong(id);
        for (float f: minMax)
            b.putFloat(f);
        return b.array();
    }


    /**
     * Pass one.
     * @param skip source maps that are intentionally not copied
     * @return per-map entry counts as seen in the interim file
     */
    static Map<String, Long> compareBytes(SourceReader source, SourceReader target, Map<String, StoreLayout.MapSpec> specs,
        Set<String> skip, Consumer<String> log) throws Exception
    {
        Set<String> expected = new TreeSet<>(source.getMapNames());
        expected.removeAll(skip);
        expected.remove(H2Utils.DATABASE_MAP_NAME);
        Set<String> actual = new TreeSet<>(target.getMapNames());
        actual.remove(H2Utils.DATABASE_MAP_NAME);
        if (!expected.equals(actual))
            throw new MigrationException("Map sets differ: source " + expected + " vs migrated " + actual);

        Map<String, Long> counts = new TreeMap<>();
        for (String name: expected)
        {
            StoreLayout.MapSpec spec = specs.get(name);
            if (spec == null)
                throw new MigrationException("No datatype known for map " + name);
            // the store layout decides the map type; metadata may only disagree in one
            // direction (marker dropped by H2 1.4 compaction), never claim an R-tree for a B-tree
            if (!spec.rtree() && (source.isRTree(name) || target.isRTree(name)))
                throw new MigrationException("Map " + name + " is marked as an R-tree in the file but the store layout uses a B-tree");
            if (spec.rtree() && !target.isRTree(name))
                throw new MigrationException("Migrated map " + name + " lost its R-tree marker");

            MapDigest src = new MapDigest();
            MapDigest dst = new MapDigest();
            if (spec.rtree())
            {
                source.readRTreeMap(name, spec.dimensions(), spec.valueType(),
                    (id, mm, v, dv) -> src.add(spatialKeyBytes(id, mm), v, false));
                target.readRTreeMap(name, spec.dimensions(), spec.valueType(),
                    (id, mm, v, dv) -> dst.add(spatialKeyBytes(id, mm), v, false));
            }
            else
            {
                // the source may hold byte-identical duplicate keys that the copy stored once
                var dedupe = new Migrator.DuplicateFilter(name);
                source.readMap(name, spec.keyType(), spec.valueType(), (k, dk, v, dv) -> {
                    if (!dedupe.isDuplicate(k, v))
                        src.add(k, v, true);
                });
                target.readMap(name, spec.keyType(), spec.valueType(), (k, dk, v, dv) -> dst.add(k, v, true));
                if (dedupe.duplicates > 0)
                    log.accept("  note: " + name + " has " + dedupe.duplicates + " byte-identical duplicate key(s) in the source");
            }

            if (src.count != dst.count)
                throw new MigrationException("Entry count differs for " + name + ": source " + src.count +
                    " vs migrated " + dst.count);
            if (!Arrays.equals(src.result(), dst.result()))
                throw new MigrationException("Entry bytes differ for " + name + " (" + src.count + " entries)");
            counts.put(name, dst.count);
            log.accept("  verified " + name + ": " + dst.count + " entries, bytes identical");
        }
        return counts;
    }


    /**
     * Pass two: open through the store implementations and decode everything.
     * @return per-map entry counts decoded with the real datatypes
     */
    static Map<String, Long> decodeAll(Path file, StoreLayout type, Consumer<String> log) throws Exception
    {
        Map<String, Long> counts = new TreeMap<>();
        MVStore store = H2Utils.openMVStore(file.toString(), new MVStore.Builder().readOnly(), 0);
        try
        {
            // The datastore's Kryo datatypes may register additional class mappings while
            // reading pages (getMemory serializes values). Record what the open adds so the
            // caller can tell it apart from a copy defect.
            Set<String> classesBefore = new TreeSet<>();
            if (store.hasMap(MVObsSystemDatabase.KRYO_CLASS_MAP_NAME))
                classesBefore.addAll(store.openMap(MVObsSystemDatabase.KRYO_CLASS_MAP_NAME, new MVBTreeMap.Builder<String, Integer>()).keySet());

            type.openStores(store);
            for (String name: new TreeSet<>(store.getMapNames()))
            {
                String id = store.getMetaMap().get(DataUtils.META_NAME + name);
                MVMap<Object, Object> map = store.getMap(DataUtils.parseHexInt(id));
                if (map == null)
                    throw new MigrationException("Store implementations did not open map " + name);

                long n;
                if (map instanceof MVRTreeMap)
                {
                    @SuppressWarnings("unchecked")
                    var rtree = (MVRTreeMap<Object>)(MVMap<?, ?>)map;
                    n = countLeaves(rtree.getRootPage());
                }
                else
                {
                    n = 0;
                    Cursor<Object, Object> cursor = map.cursor(null);
                    while (cursor.hasNext())
                    {
                        cursor.next();
                        cursor.getValue();
                        n++;
                    }
                }
                counts.put(name, n);
                log.accept("  decoded " + name + ": " + n + " entries");
            }

            if (store.hasMap(MVObsSystemDatabase.KRYO_CLASS_MAP_NAME))
            {
                Set<String> added = new TreeSet<>(store.openMap(MVObsSystemDatabase.KRYO_CLASS_MAP_NAME,
                    new MVBTreeMap.Builder<String, Integer>()).keySet());
                added.removeAll(classesBefore);
                if (!added.isEmpty())
                    log.accept("  note: opening the stores registered " + added.size() + " additional Kryo class mapping(s): " +
                        added + " (datastore behavior on first read, not a migration defect)");
            }
        }
        finally
        {
            store.closeImmediately();
        }
        return counts;
    }


    private static long countLeaves(Page<Spatial, Object> page)
    {
        long n = 0;
        if (page.isLeaf())
        {
            for (int i = 0; i < page.getKeyCount(); i++)
            {
                page.getKey(i);
                page.getValue(i);
                n++;
            }
        }
        else
        {
            // R-tree node pages hold one child per key; the extra raw slot is an empty placeholder
            for (int i = 0; i < page.getKeyCount(); i++)
                n += countLeaves(page.getChildPage(i));
        }
        return n;
    }
}
