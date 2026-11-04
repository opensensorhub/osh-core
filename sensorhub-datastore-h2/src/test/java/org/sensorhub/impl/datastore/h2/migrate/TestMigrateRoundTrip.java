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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sensorhub.api.datastore.obs.ObsFilter;


/**
 * Populates a real database with H2 2.5, exports it to a genuine H2 1.4 file,
 * migrates that file and checks the result through the store APIs and by
 * comparing bytes with the original 2.5 database.
 */
public class TestMigrateRoundTrip
{
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();


    @Test
    public void testObsSystemDatabase() throws Exception
    {
        Path original = tmp.getRoot().toPath().resolve("obsdb.dat");
        Path legacy = tmp.getRoot().toPath().resolve("obsdb-v1.dat");
        var exp = TestSupport.populateObsDatabase(original, 25, 3000, 40);

        TestSupport.exportToLegacy(original, legacy, StoreLayout.OBS_SYSTEM);
        assertEquals(1, FileOps.readH2Format(legacy));
        byte[] legacyBytes = Files.readAllBytes(legacy);

        var report = Migrator.run(TestSupport.options(legacy));
        assertTrue(report.instated);
        assertEquals(StoreLayout.OBS_SYSTEM, report.type);
        assertEquals(1, report.sourceFormat);
        assertEquals(3, FileOps.readH2Format(legacy));
        assertEquals((Long)(long)exp.numObs, report.copied.get("obs_store:obs_records"));
        assertEquals((Long)(long)exp.numFois, report.copied.get("foi_store:feature_records"));
        assertEquals((Long)(long)exp.numFois, report.copied.get("foi_store:feature_geom"));
        assertEquals((Long)(long)exp.numCommands, report.copied.get("cmd_store:cmd_records"));
        assertEquals((Long)(long)exp.numSystemVersions, report.copied.get("sys_store:feature_records"));
        assertTrue("legacy full-text index duplicates must be reported",
            report.duplicatesCollapsed.getOrDefault("sys_store:feature_text", 0L) > 0);

        // backup is byte-identical to the legacy file, and the original itself survives
        // untouched under the .old name
        assertNotNull(report.backup);
        assertArrayEquals(legacyBytes, Files.readAllBytes(report.backup));
        assertEquals(legacy.resolveSibling("obsdb-v1.dat.old"), report.retiredOriginal);
        assertArrayEquals(legacyBytes, Files.readAllBytes(report.retiredOriginal));
        assertFalse(Files.exists(report.interim));

        // independent byte comparison of the migrated file against the original 2.5 file,
        // before the module opens it (opening persists Kryo class mappings added on read)
        assertIdenticalEntries(original, legacy, StoreLayout.OBS_SYSTEM);

        // migrated file opens through the module and holds the same data
        var db = TestSupport.openObsDatabase(legacy);
        try
        {
            assertEquals(exp.numSystemVersions, db.getSystemDescStore().getNumRecords());
            var sys0 = db.getSystemDescStore().getCurrentVersion(TestSupport.SYS_UID_PREFIX + 0);
            assertNotNull(sys0);
            assertTrue(sys0.getDescription(), sys0.getDescription().contains("updated"));
            assertEquals(exp.numFois, db.getFoiStore().getNumRecords());
            assertEquals(exp.numDataStreams, db.getObservationStore().getDataStreams().getNumRecords());
            assertEquals(exp.numObs, db.getObservationStore().getNumRecords());
            assertEquals(exp.numCommandStreams, db.getCommandStore().getCommandStreams().getNumRecords());
            assertEquals(exp.numCommands, db.getCommandStore().getNumRecords());
            assertEquals(exp.numStatus, db.getCommandStore().getStatusReports().getNumRecords());

            for (var e: exp.obsValues.entrySet())
            {
                var obs = db.getObservationStore().get(e.getKey());
                assertNotNull("observation missing: " + e.getKey(), obs);
                assertEquals(exp.dataStreamId, obs.getDataStreamID());
                assertEquals(exp.firstFoiId, obs.getFoiID());
                for (int s = 0; s < 5; s++)
                    assertEquals(e.getValue()[s], obs.getResult().getDoubleValue(s), 0.0);
            }

            try (Stream<?> s = db.getObservationStore().select(new ObsFilter.Builder()
                .withDataStreams(exp.dataStreamId).build()))
            {
                assertEquals(exp.numObs, s.count());
            }

            // spatial and full-text indexes answer exactly as the original did
            assertEquals(exp.spatialMatches, TestSupport.spatialMatches(db));
            assertEquals(exp.keywordMatches, TestSupport.keywordMatches(db));
        }
        finally
        {
            db.stop();
        }
    }


    @Test
    public void testFeatureDatabase() throws Exception
    {
        Path original = tmp.getRoot().toPath().resolve("featdb.dat");
        Path legacy = tmp.getRoot().toPath().resolve("featdb-v1.dat");
        var exp = TestSupport.populateFeatureDatabase(original, 300);
        int numFeatures = exp.numFois;

        TestSupport.exportToLegacy(original, legacy, StoreLayout.FEATURE);
        assertEquals(1, FileOps.readH2Format(legacy));
        byte[] legacyBytes = Files.readAllBytes(legacy);

        var opts = TestSupport.options(legacy);
        opts.type = null;    // must be detected
        opts.backup = false; // default: no copy, the retired original is the safeguard
        var report = Migrator.run(opts);
        assertEquals(StoreLayout.FEATURE, report.type);
        assertTrue(report.instated);
        assertNull(report.backup);
        assertArrayEquals(legacyBytes, Files.readAllBytes(report.retiredOriginal));
        try (Stream<Path> files = Files.list(legacy.getParent()))
        {
            assertEquals("no backup copy without --backup", 0,
                files.filter(p -> p.getFileName().toString().contains("-backup-")).count());
        }
        assertEquals((Long)(long)numFeatures, report.copied.get("feature_store:feature_records"));
        assertEquals((Long)(long)numFeatures, report.copied.get("feature_store:feature_geom"));

        assertIdenticalEntries(original, legacy, StoreLayout.FEATURE);

        var db = TestSupport.openFeatureDatabase(legacy);
        try
        {
            assertEquals(numFeatures, db.getFeatureStore().getNumRecords());
            assertEquals(exp.spatialMatches, TestSupport.spatialMatches(db));
            assertEquals(exp.keywordMatches, TestSupport.keywordMatches(db));
            assertNotNull(db.getFeatureStore().getCurrentVersion(TestSupport.FOI_UID_PREFIX + 42));
        }
        finally
        {
            db.stop();
        }
    }


    static void assertIdenticalEntries(Path a, Path b, StoreLayout type) throws Exception
    {
        Path scratch = a.resolveSibling(a.getFileName() + ".cmp-types");
        try (SourceReader.Native ra = new SourceReader.Native(a, 3);
             SourceReader.Native rb = new SourceReader.Native(b, 3);
             StoreLayout.Types types = StoreLayout.Types.harvest(ra, type, scratch))
        {
            var counts = Verifier.compareBytes(ra, rb, types.getSpecs(), java.util.Set.of(), s -> {});
            assertFalse(counts.isEmpty());
        }
    }


    /**
     * H2 1.4's MVStoreTool.compact (the old build's compactOnClose) rewrites every map as a
     * plain map and drops the type:rtree marker from the metadata. The old datastore never
     * looked at it; the migration must not either.
     */
    @Test
    public void testLegacyFileCompactedByH2v14LosesRTreeMarker() throws Exception
    {
        Path original = tmp.getRoot().toPath().resolve("compacted.dat");
        Path legacy = tmp.getRoot().toPath().resolve("compacted-v1.dat");
        var exp = TestSupport.populateObsDatabase(original, 40, 500, 5);
        TestSupport.exportToLegacy(original, legacy, StoreLayout.OBS_SYSTEM);

        try (LegacyLoader loader = TestSupport.legacyLoader())
        {
            var factory = loader.factory();
            try (var before = factory.openReadOnly(legacy.toString()))
            {
                assertTrue(before.isRTree("foi_store:feature_geom"));
            }
            factory.compact(legacy.toString());
            assertEquals(1, FileOps.readH2Format(legacy));
            try (var after = factory.openReadOnly(legacy.toString()))
            {
                assertFalse("1.4 compaction is expected to drop the marker", after.isRTree("foi_store:feature_geom"));
                assertNull(after.getMapMeta("foi_store:feature_geom").get("type"));
            }
        }

        var report = Migrator.run(TestSupport.options(legacy));
        assertTrue(report.instated);
        assertEquals((Long)(long)exp.numFois, report.copied.get("foi_store:feature_geom"));
        assertEquals((Long)(long)exp.numObs, report.copied.get("obs_store:obs_records"));

        // the migrated file carries the marker again and the spatial index answers as before
        try (SourceReader.Native migrated = new SourceReader.Native(legacy, 3))
        {
            assertTrue(migrated.isRTree("foi_store:feature_geom"));
        }
        assertIdenticalEntries(original, legacy, StoreLayout.OBS_SYSTEM);
        var db = TestSupport.openObsDatabase(legacy);
        try
        {
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
