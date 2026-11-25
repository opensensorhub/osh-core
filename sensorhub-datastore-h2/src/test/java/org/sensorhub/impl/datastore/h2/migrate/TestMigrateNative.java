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
import org.h2.mvstore.MVStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;


/**
 * An H2 2.5 file written before the version marker existed goes through the
 * same pipeline in-process and comes out marked.
 */
public class TestMigrateNative
{
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();


    @Test
    public void testUnmarkedFileGetsMigratedAndMarked() throws Exception
    {
        Path marked = tmp.getRoot().toPath().resolve("marked.dat");
        Path unmarked = tmp.getRoot().toPath().resolve("unmarked.dat");
        var exp = TestSupport.populateObsDatabase(marked, 5, 200, 5);

        // strip the marker: copy every map except @database into a plain 2.5 store
        try (SourceReader.Native src = new SourceReader.Native(marked, 3);
             StoreLayout.Types types = StoreLayout.Types.harvest(src, StoreLayout.OBS_SYSTEM,
                 tmp.getRoot().toPath().resolve("types.dat")))
        {
            MVStore dst = new MVStore.Builder().fileName(unmarked.toString()).open();
            try
            {
                for (String name: Migrator.copyOrder(src.getMapNames()))
                {
                    StoreLayout.MapSpec spec = types.get(name);
                    if (spec.rtree())
                    {
                        var m = dst.openMap(name, new org.h2.mvstore.rtree.MVRTreeMap.Builder<Object>()
                            .dimensions(spec.dimensions()).valueType(new RawDataType(spec.valueType())));
                        var f = new org.h2.mvstore.rtree.SpatialDataType(spec.dimensions()) {
                            public org.h2.mvstore.rtree.Spatial mk(long id, float[] mm) { return create(id, mm); }
                        };
                        src.readRTreeMap(name, spec.dimensions(), spec.valueType(),
                            (id, mm, v, dv) -> m.put(f.mk(id, mm), new RawDataType.Entry(v, dv)));
                    }
                    else
                    {
                        var m = dst.openMap(name, new org.h2.mvstore.MVBTreeMap.Builder<Object, Object>()
                            .keyType(new RawDataType(spec.keyType())).valueType(new RawDataType(spec.valueType())));
                        src.readMap(name, spec.keyType(), spec.valueType(),
                            (k, dk, v, dv) -> m.put(new RawDataType.Entry(k, dk), new RawDataType.Entry(v, dv)));
                    }
                }
                dst.commit();
            }
            finally
            {
                dst.close();
            }
        }
        assertEquals(3, FileOps.readH2Format(unmarked));
        try (SourceReader.Native r = new SourceReader.Native(unmarked, 3))
        {
            assertNull(r.getVersionMarker());
        }

        // the datastore refuses it (the module records the error instead of throwing)
        var rejected = TestSupport.openObsDatabase(unmarked);
        try
        {
            assertFalse(rejected.isStarted());
            Throwable error = rejected.getCurrentError();
            assertNotNull(error);
            while (error.getCause() != null)
                error = error.getCause();
            assertTrue(error.getMessage(), error.getMessage().contains("migration"));
        }
        finally
        {
            rejected.stop();
        }

        var report = Migrator.run(TestSupport.options(unmarked));
        assertTrue(report.instated);
        assertEquals(3, report.sourceFormat);
        assertEquals((Long)(long)exp.numObs, report.copied.get("obs_store:obs_records"));

        TestMigrateRoundTrip.assertIdenticalEntries(marked, unmarked, StoreLayout.OBS_SYSTEM);

        var db = TestSupport.openObsDatabase(unmarked);
        try
        {
            assertEquals(exp.numObs, db.getObservationStore().getNumRecords());
            assertEquals(exp.numFois, db.getFoiStore().getNumRecords());
            assertEquals(exp.spatialMatches, TestSupport.spatialMatches(db));
            assertEquals(exp.keywordMatches, TestSupport.keywordMatches(db));
        }
        finally
        {
            db.stop();
        }
    }
}
