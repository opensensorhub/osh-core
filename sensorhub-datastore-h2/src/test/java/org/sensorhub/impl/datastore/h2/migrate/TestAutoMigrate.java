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
import org.sensorhub.impl.datastore.h2.MVFeatureDatabase;
import org.sensorhub.impl.datastore.h2.MVFeatureDatabaseConfig;
import org.sensorhub.impl.datastore.h2.MVObsSystemDatabase;
import org.sensorhub.impl.datastore.h2.MVObsSystemDatabaseConfig;


/**
 * The database module migrates a legacy file at startup only when autoMigrate is set;
 * otherwise it refuses it with a message that tells the operator what to do.
 */
public class TestAutoMigrate
{
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();


    private MVObsSystemDatabase database(Path file, boolean autoMigrate) throws Exception
    {
        var db = new MVObsSystemDatabase();
        var config = new MVObsSystemDatabaseConfig();
        config.storagePath = file.toString();
        config.databaseNum = TestSupport.DATABASE_NUM;
        config.autoMigrate = autoMigrate;
        db.setConfiguration(config);
        db.init();
        db.start();
        return db;
    }


    private MVFeatureDatabase featureDatabase(Path file, boolean autoMigrate) throws Exception
    {
        var db = new MVFeatureDatabase();
        var config = new MVFeatureDatabaseConfig();
        config.storagePath = file.toString();
        config.databaseNum = TestSupport.DATABASE_NUM;
        config.autoMigrate = autoMigrate;
        db.setConfiguration(config);
        db.init();
        db.start();
        return db;
    }


    /** All messages in the cause chain; the migration hint sits on the wrapping exception */
    private static String chainMessages(Throwable error)
    {
        var sb = new StringBuilder();
        for (Throwable t = error; t != null; t = t.getCause())
            sb.append(t.getMessage()).append(" | ");
        return sb.toString();
    }


    @Test
    public void testLegacyFileRefusedUnlessAutoMigrate() throws Exception
    {
        Path original = tmp.getRoot().toPath().resolve("obs-25.dat");
        Path legacy = tmp.getRoot().toPath().resolve("obs.dat");
        var exp = TestSupport.populateObsDatabase(original, 4, 100, 3);
        TestSupport.exportToLegacy(original, legacy, StoreLayout.OBS_SYSTEM);
        byte[] before = Files.readAllBytes(legacy);

        // default: refused, file untouched, message names the config flag and the command
        var refused = database(legacy, false);
        try
        {
            assertFalse(refused.isStarted());
            String msg = chainMessages(refused.getCurrentError());
            assertTrue(msg, msg.contains("autoMigrate"));
            assertTrue(msg, msg.contains(H2Migrate.class.getName()));
        }
        finally
        {
            refused.stop();
        }
        assertArrayEquals(before, Files.readAllBytes(legacy));
        assertEquals(1, FileOps.readH2Format(legacy));

        // autoMigrate: migrated in place, original retired as .old, then started normally
        var db = database(legacy, true);
        try
        {
            assertTrue(String.valueOf(db.getCurrentError()), db.isStarted());
            assertEquals(exp.numObs, db.getObservationStore().getNumRecords());
            assertEquals(exp.numFois, db.getFoiStore().getNumRecords());
            assertEquals(exp.spatialMatches, TestSupport.spatialMatches(db));
        }
        finally
        {
            db.stop();
        }
        assertEquals(3, FileOps.readH2Format(legacy));
        assertArrayEquals("original must survive untouched as .old", before,
            Files.readAllBytes(legacy.resolveSibling("obs.dat" + Migrator.RETIRED_SUFFIX)));
        try (Stream<Path> files = Files.list(legacy.getParent()))
        {
            assertEquals("no backup copy unless backupOnMigrate is set", 0,
                files.filter(p -> p.getFileName().toString().contains("-backup-")).count());
        }

        // second start: nothing to migrate, still opens
        db = database(legacy, true);
        try
        {
            assertTrue(db.isStarted());
            assertEquals(exp.numObs, db.getObservationStore().getNumRecords());
        }
        finally
        {
            db.stop();
        }
    }


    @Test
    public void testFeatureDatabaseAutoMigrate() throws Exception
    {
        Path original = tmp.getRoot().toPath().resolve("feat-25.dat");
        Path legacy = tmp.getRoot().toPath().resolve("feat.dat");
        var exp = TestSupport.populateFeatureDatabase(original, 50);
        TestSupport.exportToLegacy(original, legacy, StoreLayout.FEATURE);
        byte[] before = Files.readAllBytes(legacy);

        var refused = featureDatabase(legacy, false);
        try
        {
            assertFalse(refused.isStarted());
            String msg = chainMessages(refused.getCurrentError());
            assertTrue(msg, msg.contains("autoMigrate"));
        }
        finally
        {
            refused.stop();
        }
        assertArrayEquals(before, Files.readAllBytes(legacy));

        var db = featureDatabase(legacy, true);
        try
        {
            assertTrue(String.valueOf(db.getCurrentError()), db.isStarted());
            assertEquals(exp.numFois, db.getFeatureStore().getNumRecords());
            assertEquals(exp.spatialMatches, TestSupport.spatialMatches(db));
            assertEquals(exp.keywordMatches, TestSupport.keywordMatches(db));
        }
        finally
        {
            db.stop();
        }
        assertEquals(3, FileOps.readH2Format(legacy));
    }
}
