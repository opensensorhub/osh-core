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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.h2.mvstore.DataUtils;
import org.h2.mvstore.MVBTreeMap;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.rtree.MVRTreeMap;
import org.h2.mvstore.type.DataType;
import org.h2.mvstore.type.ObjectDataType;
import org.sensorhub.impl.datastore.h2.H2Utils;
import org.sensorhub.impl.datastore.h2.MVCommandStoreImpl;
import org.sensorhub.impl.datastore.h2.MVDataStoreInfo;
import org.sensorhub.impl.datastore.h2.MVDatabaseConfig.IdProviderType;
import org.sensorhub.impl.datastore.h2.MVDeploymentStoreImpl;
import org.sensorhub.impl.datastore.h2.MVFeatureDatabase;
import org.sensorhub.impl.datastore.h2.MVFeatureStoreImpl;
import org.sensorhub.impl.datastore.h2.MVFoiStoreImpl;
import org.sensorhub.impl.datastore.h2.MVObsStoreImpl;
import org.sensorhub.impl.datastore.h2.MVObsSystemDatabase;
import org.sensorhub.impl.datastore.h2.MVProcedureStoreImpl;
import org.sensorhub.impl.datastore.h2.MVPropertyStoreImpl;
import org.sensorhub.impl.datastore.h2.MVSystemStoreImpl;


/**
 * The two database layouts the datastore module produces, and how the tool
 * learns the real datatype of every map: opening the stores on an empty
 * scratch MVStore creates each map exactly as the database modules do.
 */
public enum StoreLayout
{
    OBS_SYSTEM(List.of(MVObsSystemDatabase.SYSTEM_STORE_NAME, MVObsSystemDatabase.DEPL_STORE_NAME, MVObsSystemDatabase.FOI_STORE_NAME,
        MVObsSystemDatabase.OBS_STORE_NAME, MVObsSystemDatabase.CMD_STORE_NAME, MVObsSystemDatabase.PROC_STORE_NAME, MVObsSystemDatabase.PROP_STORE_NAME)),
    FEATURE(List.of(MVFeatureDatabase.FEATURE_STORE_NAME));

    // ID scope is only applied when decoding IDs, never written, so 0 is safe here
    private static final int ID_SCOPE = 0;

    private final List<String> storeNames;


    private StoreLayout(List<String> storeNames)
    {
        this.storeNames = storeNames;
    }


    public List<String> getStoreNames()
    {
        return storeNames;
    }


    /**
     * Opens (and creates if needed) every store of this layout, exactly as the
     * database modules do at startup, minus the cross-store links.
     */
    public void openStores(MVStore store)
    {
        var idProvider = IdProviderType.SEQUENTIAL;
        switch (this)
        {
            case OBS_SYSTEM:
                MVSystemStoreImpl.open(store, ID_SCOPE, idProvider, info(MVObsSystemDatabase.SYSTEM_STORE_NAME));
                MVDeploymentStoreImpl.open(store, ID_SCOPE, idProvider, info(MVObsSystemDatabase.DEPL_STORE_NAME));
                MVFoiStoreImpl.open(store, ID_SCOPE, idProvider, info(MVObsSystemDatabase.FOI_STORE_NAME));
                MVObsStoreImpl.open(store, ID_SCOPE, idProvider, info(MVObsSystemDatabase.OBS_STORE_NAME));
                MVCommandStoreImpl.open(store, ID_SCOPE, idProvider, info(MVObsSystemDatabase.CMD_STORE_NAME));
                MVProcedureStoreImpl.open(store, ID_SCOPE, idProvider, info(MVObsSystemDatabase.PROC_STORE_NAME));
                MVPropertyStoreImpl.open(store, ID_SCOPE, idProvider, info(MVObsSystemDatabase.PROP_STORE_NAME));
                break;

            case FEATURE:
                MVFeatureStoreImpl.open(store, ID_SCOPE, idProvider, info(MVFeatureDatabase.FEATURE_STORE_NAME));
                break;
        }
    }


    private static MVDataStoreInfo info(String name)
    {
        return MVDataStoreInfo.builder().withName(name).build();
    }


    /**
     * Picks the layout from the map names found in a source file.
     * @throws MigrationException if the names match neither or both layouts
     */
    public static StoreLayout detect(Set<String> mapNames) throws MigrationException
    {
        boolean feature = mapNames.stream().anyMatch(n -> n.startsWith(MVFeatureDatabase.FEATURE_STORE_NAME + ":"));
        boolean obs = mapNames.stream().anyMatch(n -> n.startsWith(MVObsSystemDatabase.SYSTEM_STORE_NAME + ":") || n.startsWith(MVObsSystemDatabase.OBS_STORE_NAME + ":"));
        if (feature && !obs)
            return FEATURE;
        if (obs && !feature)
            return OBS_SYSTEM;
        throw new MigrationException("Cannot detect database type from map names " + mapNames +
            "; use --type obs or --type feature");
    }


    /** Dimensions used by every OSH spatial index (see SpatialIndex) */
    public static final int SPATIAL_DIMENSIONS = 3;


    /**
     * The real H2 2.5 datatypes an OSH store uses for one map, learned by letting
     * the store implementations open the map themselves.
     */
    public record MapSpec(String name, DataType<Object> keyType, DataType<Object> valueType, boolean rtree, int dimensions)
    {
    }


    /**
     * Learns the real datatype of every map by letting the store implementations
     * open an empty scratch database. The scratch store first receives the source
     * database's Kryo class map, because the Kryo datatypes resolve class ids
     * through the class map of the store they were opened on. The scratch store
     * stays open for as long as the harvested datatypes are in use.
     */
    public static final class Types implements AutoCloseable
    {
        private final MVStore scratch;
        private final Path scratchPath;
        private final Map<String, MapSpec> specs;


        private Types(MVStore scratch, Path scratchPath, Map<String, MapSpec> specs)
        {
            this.scratch = scratch;
            this.scratchPath = scratchPath;
            this.specs = Collections.unmodifiableMap(specs);
        }


        public static Types harvest(SourceReader source, StoreLayout layout, Path scratchPath) throws Exception
        {
            Files.deleteIfExists(scratchPath);
            MVStore scratch = H2Utils.openMVStore(scratchPath.toString(), new MVStore.Builder(), 0);
            try
            {
                if (source.getMapNames().contains(MVObsSystemDatabase.KRYO_CLASS_MAP_NAME))
                {
                    // typed copy: String -> Integer through H2's default datatype, whose
                    // encoding is identical in 1.4 and 2.5
                    MVMap<String, Integer> classMap = scratch.openMap(MVObsSystemDatabase.KRYO_CLASS_MAP_NAME,
                        new MVBTreeMap.Builder<String, Integer>());
                    var od = new ObjectDataType();
                    source.readMap(MVObsSystemDatabase.KRYO_CLASS_MAP_NAME, od, od,
                        (k, dk, v, dv) -> classMap.put((String)dk, (Integer)dv));
                    scratch.commit();
                }

                layout.openStores(scratch);
                scratch.commit();

                Map<String, MapSpec> specs = new TreeMap<>();
                for (String name: scratch.getMapNames())
                {
                    String id = scratch.getMetaMap().get(DataUtils.META_NAME + name);
                    MVMap<Object, Object> map = scratch.getMap(DataUtils.parseHexInt(id));
                    if (map == null)
                        throw new MigrationException("Store implementations did not open map " + name);

                    boolean rtree = map instanceof MVRTreeMap;
                    @SuppressWarnings("unchecked")
                    var keyType = (DataType<Object>)map.getKeyType();
                    @SuppressWarnings("unchecked")
                    var valueType = (DataType<Object>)map.getValueType();
                    specs.put(name, new MapSpec(name, keyType, valueType, rtree, rtree ? SPATIAL_DIMENSIONS : 0));
                }
                return new Types(scratch, scratchPath, specs);
            }
            catch (Exception | Error e)
            {
                scratch.closeImmediately();
                Files.deleteIfExists(scratchPath);
                throw e;
            }
        }


        public Map<String, MapSpec> getSpecs()
        {
            return specs;
        }


        public MapSpec get(String mapName)
        {
            return specs.get(mapName);
        }


        @Override
        public void close() throws IOException
        {
            scratch.closeImmediately();
            Files.deleteIfExists(scratchPath);
        }
    }
}
