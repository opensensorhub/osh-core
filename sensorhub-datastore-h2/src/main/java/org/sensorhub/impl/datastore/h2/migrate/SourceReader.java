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

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.h2.engine.Constants;
import org.h2.mvstore.Cursor;
import org.h2.mvstore.DataUtils;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.Page;
import org.h2.mvstore.rtree.MVRTreeMap;
import org.h2.mvstore.rtree.Spatial;
import org.h2.mvstore.type.DataType;
import org.h2.mvstore.type.StringDataType;
import org.sensorhub.impl.datastore.h2.H2Utils;
import org.sensorhub.impl.datastore.h2.migrate.spi.LegacyStore;


/**
 * Uniform raw access to a database file regardless of the H2 version that
 * wrote it. Entries are delivered as the exact bytes stored in the file plus
 * the objects decoded with the supplied real datatypes.
 */
public interface SourceReader extends AutoCloseable
{
    /** @return human readable description, e.g. "H2 1.4.200 file (format 1)" */
    String describe();

    Set<String> getMapNames();

    boolean isRTree(String mapName);

    long readMap(String mapName, DataType<?> keyType, DataType<?> valueType, LegacyStore.RawEntrySink sink) throws Exception;

    long readRTreeMap(String mapName, int dimensions, DataType<?> valueType, LegacyStore.SpatialEntrySink sink) throws Exception;

    @Override
    void close();


    /**
     * Reads an H2 1.4 file through the isolated legacy classloader. The real
     * H2 2.5 datatypes do the decoding; only byte buffers and JDK objects cross
     * the classloader boundary.
     */
    public static final class Legacy implements SourceReader
    {
        private final LegacyStore store;


        public Legacy(LegacyStore.Factory factory, Path file) throws MigrationException
        {
            this.store = factory.openReadOnly(file.toString());
            String version = store.getH2Version();
            if (!version.startsWith("1."))
            {
                store.close();
                throw new MigrationException("Legacy classloader loaded H2 " + version + " instead of a 1.x release");
            }
        }


        @Override
        public String describe()
        {
            return "H2 " + store.getH2Version() + " file (format 1)";
        }


        @Override
        public Set<String> getMapNames()
        {
            return store.getMapNames();
        }


        @Override
        public boolean isRTree(String mapName)
        {
            return store.isRTree(mapName);
        }


        @Override
        public long readMap(String mapName, DataType<?> keyType, DataType<?> valueType, LegacyStore.RawEntrySink sink)
        {
            return store.readMap(mapName, decoder(keyType), decoder(valueType), sink);
        }


        @Override
        public long readRTreeMap(String mapName, int dimensions, DataType<?> valueType, LegacyStore.SpatialEntrySink sink)
        {
            return store.readRTreeMap(mapName, dimensions, decoder(valueType), sink);
        }


        @SuppressWarnings("unchecked")
        private static LegacyStore.EntryDecoder decoder(DataType<?> type)
        {
            var t = (DataType<Object>)type;
            return t::read;
        }


        @Override
        public void close()
        {
            store.close();
        }
    }


    /**
     * Reads a file the embedded H2 2.5 library can open natively. Used for
     * unmarked 2.x sources and to read the interim file back during verification.
     */
    public static final class Native implements SourceReader
    {
        private final MVStore store;
        private final int format;


        public Native(Path file, int format)
        {
            this.format = format;
            this.store = new MVStore.Builder()
                .fileName(file.toString())
                .readOnly()
                .open();
        }


        @Override
        public String describe()
        {
            return "H2 " + Constants.VERSION + " readable file (format " + format + ")";
        }


        @Override
        public Set<String> getMapNames()
        {
            return Collections.unmodifiableSet(new TreeSet<>(store.getMapNames()));
        }


        public Map<String, String> getMapMeta(String mapName)
        {
            String id = store.getMetaMap().get(DataUtils.META_NAME + mapName);
            if (id == null)
                throw new IllegalArgumentException("Unknown map: " + mapName);
            String config = store.getMetaMap().get("map." + id);
            if (config == null)
                throw new IllegalStateException("Missing metadata for map: " + mapName);
            return DataUtils.parseMap(config);
        }


        @Override
        public boolean isRTree(String mapName)
        {
            return "rtree".equals(getMapMeta(mapName).get("type"));
        }


        /**
         * Reads the version marker if present.
         * @return marker value or null if the map or key is missing
         */
        public String getVersionMarker()
        {
            if (!store.hasMap(H2Utils.DATABASE_MAP_NAME))
                return null;
            MVMap<String, String> map = store.openMap(H2Utils.DATABASE_MAP_NAME, new MVMap.Builder<String, String>()
                .keyType(StringDataType.INSTANCE)
                .valueType(StringDataType.INSTANCE));
            return map.get(H2Utils.H2_VERSION_KEY);
        }


        @Override
        public long readMap(String mapName, DataType<?> keyType, DataType<?> valueType, LegacyStore.RawEntrySink sink)
        {
            if (isRTree(mapName))
                throw new IllegalArgumentException("Map is an R-tree, use readRTreeMap: " + mapName);

            MVMap<Object, Object> map = store.openMap(mapName, new MVMap.Builder<Object, Object>()
                .keyType(new RawDataType(keyType))
                .valueType(new RawDataType(valueType)));

            long count = 0;
            Cursor<Object, Object> cursor = map.cursor(null);
            while (cursor.hasNext())
            {
                var key = (RawDataType.Entry)cursor.next();
                var value = (RawDataType.Entry)cursor.getValue();
                sink.entry(key.bytes(), key.decoded(map.getKeyType()), value.bytes(), value.decoded(map.getValueType()));
                count++;
            }
            return count;
        }


        @Override
        public long readRTreeMap(String mapName, int dimensions, DataType<?> valueType, LegacyStore.SpatialEntrySink sink)
        {
            // no metadata check: the store layout decides the type, see LegacyStore.readRTreeMap
            MVRTreeMap<Object> map = store.openMap(mapName, new MVRTreeMap.Builder<Object>()
                .dimensions(dimensions)
                .valueType(new RawDataType(valueType)));

            return walk(map.getRootPage(), map.getValueType(), dimensions, sink);
        }


        private static long walk(Page<Spatial, Object> page, DataType<Object> valueType, int dimensions, LegacyStore.SpatialEntrySink sink)
        {
            long count = 0;
            if (page.isLeaf())
            {
                for (int i = 0; i < page.getKeyCount(); i++)
                {
                    Spatial key = page.getKey(i);
                    var value = (RawDataType.Entry)page.getValue(i);
                    float[] minMax = new float[2 * dimensions];
                    for (int d = 0; d < dimensions; d++)
                    {
                        minMax[2 * d] = key.min(d);
                        minMax[2 * d + 1] = key.max(d);
                    }
                    sink.entry(key.getId(), minMax, value.bytes(), value.decoded(valueType));
                    count++;
                }
            }
            else
            {
                // R-tree node pages hold one child per key; the extra raw slot is an empty placeholder
                for (int i = 0; i < page.getKeyCount(); i++)
                    count += walk(page.getChildPage(i), valueType, dimensions, sink);
            }
            return count;
        }


        @Override
        public void close()
        {
            store.closeImmediately();
        }
    }
}
