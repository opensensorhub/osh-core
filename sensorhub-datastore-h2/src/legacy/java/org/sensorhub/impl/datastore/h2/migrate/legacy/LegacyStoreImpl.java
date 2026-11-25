/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2026 GeoRobotix Innovative Research, LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.datastore.h2.migrate.legacy;

import java.nio.ByteBuffer;
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
import org.h2.mvstore.WriteBuffer;
import org.h2.mvstore.rtree.MVRTreeMap;
import org.h2.mvstore.rtree.SpatialKey;
import org.h2.mvstore.type.DataType;
import org.sensorhub.impl.datastore.h2.migrate.spi.LegacyStore;


/**
 * Read-only H2 1.4 store. Every map is opened with {@link RecordingDataType}s
 * so entries come out as exact bytes.
 */
final class LegacyStoreImpl implements LegacyStore
{
    private final String fileName;
    private final MVStore store;


    LegacyStoreImpl(String fileName)
    {
        this.fileName = fileName;
        this.store = new MVStore.Builder()
            .fileName(fileName)
            .readOnly()
            .open();
    }


    @Override
    public String getFileName()
    {
        return fileName;
    }


    @Override
    public String getH2Version()
    {
        return Constants.VERSION;
    }


    @Override
    public Set<String> getMapNames()
    {
        return Collections.unmodifiableSet(new TreeSet<>(store.getMapNames()));
    }


    @Override
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


    @Override
    public long readMap(String mapName, LegacyStore.EntryDecoder keyDecoder, LegacyStore.EntryDecoder valueDecoder, LegacyStore.RawEntrySink sink)
    {
        if (isRTree(mapName))
            throw new IllegalArgumentException("Map is an R-tree, use readRTreeMap: " + mapName);

        MVMap<Object, Object> map = store.openMap(mapName, new MVMap.Builder<Object, Object>()
            .keyType(new RecordingDataType(keyDecoder, null))
            .valueType(new RecordingDataType(valueDecoder, null)));

        long count = 0;
        Cursor<Object, Object> cursor = map.cursor(null);
        while (cursor.hasNext())
        {
            RawEntry key = (RawEntry)cursor.next();
            RawEntry value = (RawEntry)cursor.getValue();
            sink.entry(key.bytes, key.decoded, value.bytes, value.decoded);
            count++;
        }
        return count;
    }


    @Override
    public long readRTreeMap(String mapName, int dimensions, LegacyStore.EntryDecoder valueDecoder, LegacyStore.SpatialEntrySink sink)
    {
        // no metadata check here: the caller knows the map's type from the store layout,
        // and files compacted by H2 1.4 have lost the type:rtree marker
        MVRTreeMap<Object> map = store.openMap(mapName, new MVRTreeMap.Builder<Object>()
            .dimensions(dimensions)
            .valueType(new RecordingDataType(valueDecoder, null)));

        return walk(map.getRootPage(), dimensions, sink);
    }


    private long walk(Page page, int dimensions, LegacyStore.SpatialEntrySink sink)
    {
        long count = 0;
        if (page.isLeaf())
        {
            for (int i = 0; i < page.getKeyCount(); i++)
            {
                SpatialKey key = (SpatialKey)page.getKey(i);
                RawEntry value = (RawEntry)page.getValue(i);
                float[] minMax = new float[2 * dimensions];
                for (int d = 0; d < dimensions; d++)
                {
                    minMax[2 * d] = key.min(d);
                    minMax[2 * d + 1] = key.max(d);
                }
                sink.entry(key.getId(), minMax, value.bytes, value.decoded);
                count++;
            }
        }
        else
        {
            // R-tree node pages hold one child per key; the extra raw slot is an empty placeholder
            for (int i = 0; i < page.getKeyCount(); i++)
                count += walk(page.getChildPage(i), dimensions, sink);
        }
        return count;
    }


    @Override
    public void close()
    {
        store.closeImmediately();
    }


    /**
     * Key or value as stored in an H2 1.4 page: the exact bytes plus, when the
     * entry was read through a decoder, the decoded object.
     */
    static final class RawEntry
    {
        final byte[] bytes;
        final Object decoded;


        RawEntry(byte[] bytes, Object decoded)
        {
            this.bytes = bytes;
            this.decoded = decoded;
        }
    }


    /**
     * H2 1.4 {@link DataType} that finds entry boundaries by delegating the
     * decode to the caller (the real OSH datatype on the H2 2.5 side) and
     * records the exact bytes consumed. Writes emit recorded bytes verbatim.
     */
    static final class RecordingDataType implements DataType
    {
        private final LegacyStore.EntryDecoder decoder;
        private final LegacyStore.EntryComparator comparator;


        RecordingDataType(LegacyStore.EntryDecoder decoder, LegacyStore.EntryComparator comparator)
        {
            this.decoder = decoder;
            this.comparator = comparator;
        }


        @Override
        public int compare(Object a, Object b)
        {
            if (comparator == null)
                throw new UnsupportedOperationException("No key comparator configured for this map");
            RawEntry ra = (RawEntry)a;
            RawEntry rb = (RawEntry)b;
            return comparator.compare(ra.bytes, ra.decoded, rb.bytes, rb.decoded);
        }


        @Override
        public int getMemory(Object obj)
        {
            return ((RawEntry)obj).bytes.length + 32;
        }


        @Override
        public void write(WriteBuffer buff, Object obj)
        {
            buff.put(((RawEntry)obj).bytes);
        }


        @Override
        public void write(WriteBuffer buff, Object[] obj, int len, boolean key)
        {
            for (int i = 0; i < len; i++)
                write(buff, obj[i]);
        }


        @Override
        public Object read(ByteBuffer buff)
        {
            if (decoder == null)
                throw new UnsupportedOperationException("No decoder configured for this map");
            int start = buff.position();
            Object decoded = decoder.decode(buff);
            int end = buff.position();
            if (end < start)
                throw new IllegalStateException("Decoder moved buffer backwards");
            byte[] bytes = new byte[end - start];
            buff.position(start);
            buff.get(bytes);
            return new RawEntry(bytes, decoded);
        }


        @Override
        public void read(ByteBuffer buff, Object[] obj, int len, boolean key)
        {
            for (int i = 0; i < len; i++)
                obj[i] = read(buff);
        }
    }
}
