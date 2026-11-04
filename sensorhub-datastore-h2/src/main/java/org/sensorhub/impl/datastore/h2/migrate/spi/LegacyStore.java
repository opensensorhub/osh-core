/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2026 GeoRobotix Innovative Research, LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.datastore.h2.migrate.spi;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;


/**
 * Contract between the H2 2.5 side of the migration and the H2 1.4 adapters that
 * run in an isolated classloader. Everything in here uses JDK types only, so the
 * two sides can share these interfaces while sharing no H2 class. The adapters
 * implement {@link Factory}, which the tool loads by name ({@link Factory#IMPL_CLASS}).
 */
public interface LegacyStore extends AutoCloseable
{
    String getFileName();

    /** @return H2 library version used to open the file (e.g. "1.4.200") */
    String getH2Version();

    Set<String> getMapNames();

    /** @return the parsed "map.&lt;id&gt;" metadata for the named map */
    Map<String, String> getMapMeta(String mapName);

    /** @return true when the map metadata carries the R-tree marker (type:rtree) */
    boolean isRTree(String mapName);

    /**
     * Iterates a B-tree map in key order.
     * @return number of entries delivered to the sink
     */
    long readMap(String mapName, EntryDecoder keyDecoder, EntryDecoder valueDecoder, RawEntrySink sink);

    /**
     * Iterates every leaf entry of an R-tree map by walking its pages. The map is
     * opened as an R-tree whether or not its metadata still says so: H2 1.4's
     * compaction rewrites maps generically and drops the type:rtree marker, while
     * the datastore keeps opening such maps as R-trees.
     * @return number of entries delivered to the sink
     */
    long readRTreeMap(String mapName, int dimensions, EntryDecoder valueDecoder, SpatialEntrySink sink);

    @Override
    void close();


    /**
     * Entry point implemented inside the legacy classloader. The tool locates the
     * implementation by name ({@link #IMPL_CLASS}) and casts it to this interface.
     */
    interface Factory
    {
        String IMPL_CLASS = "org.sensorhub.impl.datastore.h2.migrate.legacy.LegacyStoreFactoryImpl";

        LegacyStore openReadOnly(String fileName);

        Writer openForWrite(String fileName);

        /**
         * Compacts a file with H2 1.4's own MVStoreTool, exactly as the pre-upgrade
         * datastore's compactOnClose did. Test fixture support: 1.4 compaction drops
         * the R-tree type marker from map metadata.
         */
        void compact(String fileName);
    }


    /**
     * Writes an H2 1.4 MVStore file from raw entries. This exists to build
     * genuine format-1 test fixtures from the current datatypes; it is not part
     * of the forward migration path.
     */
    interface Writer extends AutoCloseable
    {
        interface MapWriter
        {
            void put(byte[] key, Object decodedKey, byte[] value);
        }

        interface RTreeMapWriter
        {
            void put(long id, float[] minMax, byte[] value);
        }

        MapWriter openMap(String mapName, EntryComparator keyComparator);

        RTreeMapWriter openRTreeMap(String mapName, int dimensions);

        void commit();

        @Override
        void close();
    }


    /**
     * Decodes one key or value from its position in a buffer, advancing the
     * buffer position past exactly the bytes that belong to that entry.
     * Implemented on the H2 2.5 side by delegating to the real OSH datatype.
     * Only JDK types cross the classloader boundary.
     */
    interface EntryDecoder
    {
        Object decode(ByteBuffer buff);
    }


    /**
     * Orders two raw keys. Implemented on the H2 2.5 side with the real
     * datatype's compare method. Used only when writing legacy files.
     */
    interface EntryComparator
    {
        int compare(byte[] keyA, Object decodedA, byte[] keyB, Object decodedB);
    }


    /**
     * Receives B-tree entries as the exact bytes stored in the source file,
     * together with the decoded objects produced by the {@link EntryDecoder}s.
     */
    interface RawEntrySink
    {
        void entry(byte[] key, Object decodedKey, byte[] value, Object decodedValue);
    }


    /**
     * Receives R-tree leaf entries. The spatial key is transferred structurally
     * (id plus min/max per dimension) and the value as exact bytes.
     */
    interface SpatialEntrySink
    {
        /**
         * @param id spatial key id
         * @param minMax bounds as min0, max0, min1, max1, ...
         * @param value exact value bytes
         * @param decodedValue value decoded by the supplied {@link EntryDecoder}
         */
        void entry(long id, float[] minMax, byte[] value, Object decodedValue);
    }
}
