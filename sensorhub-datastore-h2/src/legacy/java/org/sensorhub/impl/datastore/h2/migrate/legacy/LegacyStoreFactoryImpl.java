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

import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.rtree.MVRTreeMap;
import org.h2.mvstore.rtree.SpatialKey;
import org.sensorhub.impl.datastore.h2.migrate.spi.LegacyStore;


/**
 * Instantiated reflectively by the tool inside the legacy classloader.
 */
public final class LegacyStoreFactoryImpl implements LegacyStore.Factory
{
    public LegacyStoreFactoryImpl()
    {
    }


    @Override
    public LegacyStore openReadOnly(String fileName)
    {
        return new LegacyStoreImpl(fileName);
    }


    @Override
    public LegacyStore.Writer openForWrite(String fileName)
    {
        return new WriterImpl(fileName);
    }


    @Override
    public void compact(String fileName)
    {
        org.h2.mvstore.MVStoreTool.compact(fileName, false);
    }


    /**
     * Writes a genuine H2 1.4 (format 1) file from raw entries. Test fixture support.
     */
    static final class WriterImpl implements LegacyStore.Writer
    {
        private final MVStore store;


        WriterImpl(String fileName)
        {
            this.store = new MVStore.Builder()
                .fileName(fileName)
                .autoCommitDisabled()
                .open();
        }


        @Override
        public MapWriter openMap(String mapName, LegacyStore.EntryComparator keyComparator)
        {
            MVMap<Object, Object> map = store.openMap(mapName, new MVMap.Builder<Object, Object>()
                .keyType(new LegacyStoreImpl.RecordingDataType(null, keyComparator))
                .valueType(new LegacyStoreImpl.RecordingDataType(null, null)));
            return (key, decodedKey, value) -> map.put(new LegacyStoreImpl.RawEntry(key, decodedKey), new LegacyStoreImpl.RawEntry(value, null));
        }


        @Override
        public RTreeMapWriter openRTreeMap(String mapName, int dimensions)
        {
            MVRTreeMap<Object> map = store.openMap(mapName, new MVRTreeMap.Builder<Object>()
                .dimensions(dimensions)
                .valueType(new LegacyStoreImpl.RecordingDataType(null, null)));
            return (id, minMax, value) -> map.put(new SpatialKey(id, minMax), new LegacyStoreImpl.RawEntry(value, null));
        }


        @Override
        public void commit()
        {
            store.commit();
        }


        @Override
        public void close()
        {
            store.close();
        }
    }
}
