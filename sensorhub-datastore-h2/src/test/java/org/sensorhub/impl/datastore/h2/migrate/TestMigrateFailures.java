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
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.type.ObjectDataType;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sensorhub.impl.datastore.h2.H2Utils;


/**
 * Every failure must leave the original file untouched.
 */
public class TestMigrateFailures
{
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();


    private Path legacyFixture(String name) throws Exception
    {
        Path original = tmp.getRoot().toPath().resolve(name + "-25.dat");
        Path legacy = tmp.getRoot().toPath().resolve(name + ".dat");
        TestSupport.populateObsDatabase(original, 3, 50, 2);
        TestSupport.exportToLegacy(original, legacy, StoreLayout.OBS_SYSTEM);
        return legacy;
    }


    private long backupCount(Path file) throws Exception
    {
        try (Stream<Path> s = Files.list(file.getParent()))
        {
            return s.filter(p -> p.getFileName().toString().contains("-backup-")).count();
        }
    }


    @Test
    public void testLockedFileIsRefusedBeforeBackup() throws Exception
    {
        Path legacy = legacyFixture("locked");
        byte[] before = Files.readAllBytes(legacy);
        try (FileChannel ch = FileChannel.open(legacy, StandardOpenOption.READ, StandardOpenOption.WRITE))
        {
            ch.lock();
            try
            {
                Migrator.run(TestSupport.options(legacy));
                fail();
            }
            catch (MigrationException e)
            {
                assertTrue(e.getMessage(), e.getMessage().contains("locked"));
            }
        }
        assertArrayEquals(before, Files.readAllBytes(legacy));
        assertEquals(0, backupCount(legacy));
    }


    @Test
    public void testNonH2FileIsRefused() throws Exception
    {
        Path bogus = tmp.getRoot().toPath().resolve("bogus.dat");
        Files.writeString(bogus, "this is not a database", StandardCharsets.UTF_8);
        try
        {
            Migrator.run(TestSupport.options(bogus));
            fail();
        }
        catch (MigrationException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("Not an H2 MVStore file"));
        }
        assertEquals(0, backupCount(bogus));
    }


    @Test
    public void testAlreadyMarkedFileIsRefused() throws Exception
    {
        Path marked = tmp.getRoot().toPath().resolve("marked.dat");
        TestSupport.populateObsDatabase(marked, 2, 10, 1);
        byte[] before = Files.readAllBytes(marked);
        try
        {
            Migrator.run(TestSupport.options(marked));
            fail();
        }
        catch (MigrationException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("already carries version marker"));
        }
        assertArrayEquals(before, Files.readAllBytes(marked));
        assertEquals(0, backupCount(marked));
    }


    @Test
    public void testDryRunLeavesOriginalAndKeepsInterim() throws Exception
    {
        Path legacy = legacyFixture("dryrun");
        byte[] before = Files.readAllBytes(legacy);
        var opts = TestSupport.options(legacy);
        opts.dryRun = true;
        var report = Migrator.run(opts);

        assertFalse(report.instated);
        assertArrayEquals(before, Files.readAllBytes(legacy));
        assertEquals(1, FileOps.readH2Format(legacy));
        assertTrue(Files.exists(report.interim));
        assertEquals(3, FileOps.readH2Format(report.interim));
        assertEquals(FileOps.sha256(legacy), FileOps.sha256(report.backup));

        // the interim file is a complete, marked database
        try (MVStore s = H2Utils.openMVStore(report.interim.toString(), new MVStore.Builder().readOnly(), 0))
        {
            assertTrue(s.hasMap("obs_store:obs_records"));
        }
    }


    @Test
    public void testUnknownMapAbortsAndCleansUp() throws Exception
    {
        Path legacy = legacyFixture("unknown");

        // add a map the layout does not know, using the legacy writer on the same file
        try (LegacyLoader loader = TestSupport.legacyLoader())
        {
            var w = loader.factory().openForWrite(legacy.toString());
            try
            {
                var od = new ObjectDataType();
                @SuppressWarnings("unchecked")
                var type = (org.h2.mvstore.type.DataType<Object>)(org.h2.mvstore.type.DataType<?>)od;
                var m = w.openMap("addon_store:custom", (ka, da, kb, db) -> od.compare(da, db));
                m.put(TestSupport.encode(type, "k"), "k", TestSupport.encode(type, 1));
                w.commit();
            }
            finally
            {
                w.close();
            }
        }
        byte[] before = Files.readAllBytes(legacy);

        try
        {
            Migrator.run(TestSupport.options(legacy));
            fail();
        }
        catch (MigrationException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("addon_store:custom"));
        }
        assertArrayEquals(before, Files.readAllBytes(legacy));
        assertFalse(Files.exists(legacy.resolveSibling(legacy.getFileName() + ".migrating")));
        assertFalse(Files.exists(legacy.resolveSibling(legacy.getFileName() + ".migrating-types")));
        assertEquals("layout is checked before the backup is written", 0, backupCount(legacy));
    }


    @Test
    public void testBackupDirAndKeepInterim() throws Exception
    {
        Path legacy = legacyFixture("options");
        Path backupDir = tmp.getRoot().toPath().resolve("backups/nested");
        byte[] before = Files.readAllBytes(legacy);

        // --backup-dir: the verified backup lands in the requested directory
        var opts = TestSupport.options(legacy);
        opts.dryRun = true;
        opts.backupDir = backupDir;
        var report = Migrator.run(opts);
        assertEquals(backupDir, report.backup.getParent());
        assertArrayEquals(before, Files.readAllBytes(report.backup));
        assertEquals("no backup next to the database", 0, backupCount(legacy));
        Files.delete(report.interim);

        // --keep-interim: a failure after the interim was written leaves it for inspection
        Path interim = legacy.resolveSibling(legacy.getFileName() + ".migrating");
        Files.writeString(interim, "stale", StandardCharsets.UTF_8);
        try
        {
            Migrator.run(TestSupport.options(legacy));
            fail("a pre-existing interim file must be refused");
        }
        catch (MigrationException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("Interim file already exists"));
        }
        assertTrue("existing interim file must not be deleted", Files.exists(interim));
        Files.delete(interim);

        opts = TestSupport.options(legacy);
        opts.keepInterim = true;
        opts.type = StoreLayout.FEATURE; // wrong layout: fails at the unknown-map check, before any interim
        try
        {
            Migrator.run(opts);
            fail();
        }
        catch (MigrationException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("unknown to the FEATURE layout"));
        }
        assertArrayEquals(before, Files.readAllBytes(legacy));
    }


    @Test
    public void testExistingRetiredOriginalIsRefusedBeforeBackup() throws Exception
    {
        Path legacy = legacyFixture("retired");
        Path retired = legacy.resolveSibling(legacy.getFileName() + Migrator.RETIRED_SUFFIX);
        Files.writeString(retired, "an earlier original", StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(legacy);
        try
        {
            Migrator.run(TestSupport.options(legacy));
            fail();
        }
        catch (MigrationException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("retired original already exists"));
        }
        assertArrayEquals(before, Files.readAllBytes(legacy));
        assertEquals("an earlier original", Files.readString(retired, StandardCharsets.UTF_8));
        assertEquals(0, backupCount(legacy));

        // a dry run does not retire anything, so it still works
        Files.delete(retired);
        var opts = TestSupport.options(legacy);
        opts.dryRun = true;
        var report = Migrator.run(opts);
        assertNull(report.retiredOriginal);
        assertArrayEquals(before, Files.readAllBytes(legacy));
    }


    @Test
    public void testCliUsageErrors()
    {
        assertEquals(2, H2Migrate.run(new String[0]));
        assertEquals(2, H2Migrate.run(new String[] {"--type"}));
        assertEquals(2, H2Migrate.run(new String[] {"--type", "nope", "x.dat"}));
        assertEquals(2, H2Migrate.run(new String[] {"--backup", "--backup-dir"}));
        assertEquals(0, H2Migrate.run(new String[] {"--help"}));
        assertEquals(1, H2Migrate.run(new String[] {"--quiet", tmp.getRoot().toPath().resolve("missing.dat").toString()}));
    }
}
