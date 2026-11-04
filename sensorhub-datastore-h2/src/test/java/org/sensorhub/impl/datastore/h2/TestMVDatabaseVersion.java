/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2026 GeoRobotix Innovative Research, LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.datastore.h2;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import org.h2.engine.Constants;
import org.h2.mvstore.DataUtils;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.MVStoreException;
import org.h2.mvstore.type.StringDataType;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.impl.module.AbstractModule;


public class TestMVDatabaseVersion
{
    @Rule
    public TemporaryFolder files = new TemporaryFolder();


    @Test
    public void testNewFilesAndReadOnlyReopen() throws Exception
    {
        for (File file: new File[] {new File(files.getRoot(), "new.dat"), files.newFile()})
        {
            try (var store = H2Utils.openMVStore(file.getPath(), new MVStore.Builder(), 1234))
            {
                assertEquals(Constants.VERSION, metadata(store).get(H2Utils.H2_VERSION_KEY));
                assertEquals(1234, store.getAutoCommitDelay());
                store.<String, String>openMap("payload").put("key", "value");
            }
            try (var store = H2Utils.openMVStore(file.getPath(), new MVStore.Builder(), 0))
            {
                assertEquals(Constants.VERSION, metadata(store).get(H2Utils.H2_VERSION_KEY));
                assertEquals("value", store.<String, String>openMap("payload").get("key"));
            }
            byte[] before = Files.readAllBytes(file.toPath());
            try (var store = H2Utils.openMVStore(file.getPath(), new MVStore.Builder().readOnly(), 0))
            {
                assertEquals(Constants.VERSION, metadata(store).get(H2Utils.H2_VERSION_KEY));
            }
            assertArrayEquals(before, Files.readAllBytes(file.toPath()));
        }
    }


    @Test
    public void testMissingMetadataMapRequiresMigration() throws Exception
    {
        assertRejected(createFile(null, false), "Database requires migration");
    }


    @Test
    public void testMissingVersionKeyRequiresMigration() throws Exception
    {
        assertRejected(createFile(null, true), "Database requires migration");
    }


    @Test
    public void testEmptyExistingMVStoreRequiresMigration() throws Exception
    {
        File file = files.newFile();
        try (var store = new MVStore.Builder().fileName(file.getPath()).open())
        {
            assertTrue(store.getMapNames().isEmpty());
        }
        assertTrue(file.length() > 0);
        assertRejected(file, "Database requires migration");
    }


    @Test
    public void testOlderVersionsRequireMigration() throws Exception
    {
        for (String version: new String[] {"2.5.249", "2.4.999", "1.4.200"})
            assertRejected(createFile(version, true), "Database requires migration");
    }


    @Test
    public void testNewerVersionsAreRejected() throws Exception
    {
        for (String version: new String[] {"2.5.251", "2.10.1", "3.0.0"})
            assertRejected(createFile(version, true), "Unsupported H2 database version " + version);
    }


    @Test
    public void testMalformedVersionsAreRejected() throws Exception
    {
        for (String version: new String[] {"", "bad", "2.5", "2.5.250.extra", "-2.5.250", "2.5.999999999999"})
            assertRejected(createFile(version, true), "Invalid H2 database version");
    }


    @Test
    public void testUnsupportedPhysicalFormatsAreNotMigrated() throws Exception
    {
        for (int format: new int[] {1, 4})
        {
            File file = files.newFile();
            // Two checksummed H2 headers, declaring an old or future native format.
            String header = "H:2,blockSize:1000,format:" + format + ",created:0";
            byte[] bytes = header.getBytes(StandardCharsets.ISO_8859_1);
            int checksum = DataUtils.getFletcher32(bytes, 0, bytes.length);
            bytes = (header + ",fletcher:" + Integer.toHexString(checksum) + "\n")
                .getBytes(StandardCharsets.ISO_8859_1);
            var buffer = ByteBuffer.allocate(8192);
            buffer.put(bytes);
            buffer.position(4096);
            buffer.put(bytes);
            Files.write(file.toPath(), buffer.array());

            var error = assertThrows(DataStoreException.class,
                () -> H2Utils.openMVStore(file.getPath(), new MVStore.Builder(), 0));
            assertTrue(error.getMessage().contains("Unsupported H2 file format"));
            assertTrue(error.getCause() instanceof MVStoreException);
            assertEquals(DataUtils.ERROR_UNSUPPORTED_FORMAT, ((MVStoreException)error.getCause()).getErrorCode());
            assertArrayEquals(buffer.array(), Files.readAllBytes(file.toPath()));
            try (var channel = FileChannel.open(file.toPath(), StandardOpenOption.WRITE);
                 var lock = channel.tryLock())
            {
                assertNotNull(lock);
            }
        }
    }


    @Test
    public void testBothDatabaseModulesCreateAndReopen() throws Exception
    {
        for (boolean featureDatabase: new boolean[] {false, true})
        {
            File file = files.newFile();
            var database = database(file, featureDatabase, false);
            try
            {
                database.init();
                database.start();
                assertTrue(database.isStarted());
            }
            finally
            {
                database.stop();
            }
            byte[] before = Files.readAllBytes(file.toPath());
            database = database(file, featureDatabase, true);
            try
            {
                database.init();
                database.start();
                assertTrue(database.isStarted());
            }
            finally
            {
                database.stop();
            }
            assertArrayEquals(before, Files.readAllBytes(file.toPath()));
        }
    }


    @Test
    public void testBothDatabaseModulesRejectBeforeReadingApplicationMaps() throws Exception
    {
        for (boolean featureDatabase: new boolean[] {false, true})
        {
            File file = createFile(null, false);
            byte[] before = Files.readAllBytes(file.toPath());
            var database = database(file, featureDatabase, false);
            try
            {
                database.init();
                database.start();
                assertFalse(database.isStarted());
                Throwable error = database.getCurrentError();
                assertNotNull(error);
                while (error.getCause() != null)
                    error = error.getCause();
                assertTrue(error.getMessage(), error.getMessage().contains("Database requires migration"));
            }
            finally
            {
                database.stop();
            }
            assertUnchangedAndUnlocked(file, before);
        }
    }


    private MVMap<String, String> metadata(MVStore store)
    {
        return store.openMap(H2Utils.DATABASE_MAP_NAME, new MVMap.Builder<String, String>()
            .keyType(StringDataType.INSTANCE).valueType(StringDataType.INSTANCE));
    }


    private File createFile(String version, boolean metadataMap) throws Exception
    {
        File file = files.newFile();
        try (var store = new MVStore.Builder().fileName(file.getPath()).open())
        {
            if (metadataMap)
            {
                var metadata = metadata(store);
                if (version != null)
                    metadata.put(H2Utils.H2_VERSION_KEY, version);
            }
            // Deliberately incompatible application metadata: validation must precede decoding it.
            var datastores = store.<String, String>openMap(H2Utils.DATASTORES_MAP_NAME);
            datastores.put("sys_store", "not Kryo data");
            datastores.put("feature_store", "not Kryo data");
        }
        return file;
    }


    private void assertRejected(File file, String message) throws Exception
    {
        byte[] before = Files.readAllBytes(file.toPath());
        for (boolean readOnly: new boolean[] {false, true})
        {
            var builder = new MVStore.Builder();
            if (readOnly)
                builder.readOnly();
            var error = assertThrows(DataStoreException.class,
                () -> H2Utils.openMVStore(file.getPath(), builder, 1000));
            assertTrue(error.getMessage(), error.getMessage().contains(message));
            assertUnchangedAndUnlocked(file, before);
        }
    }


    private void assertUnchangedAndUnlocked(File file, byte[] before) throws Exception
    {
        assertArrayEquals(before, Files.readAllBytes(file.toPath()));
        // A writable reopen proves rejection released the lock, without committing changes.
        var store = new MVStore.Builder().fileName(file.getPath()).autoCommitDisabled().open();
        store.closeImmediately();
        assertArrayEquals(before, Files.readAllBytes(file.toPath()));
    }


    private AbstractModule<?> database(File file, boolean featureDatabase, boolean readOnly)
    {
        if (featureDatabase)
        {
            var database = new MVFeatureDatabase();
            var config = new MVFeatureDatabaseConfig();
            config.databaseNum = 1;
            config.storagePath = file.getPath();
            config.readOnly = readOnly;
            database.setConfiguration(config);
            return database;
        }
        var database = new MVObsSystemDatabase();
        var config = new MVObsSystemDatabaseConfig();
        config.databaseNum = 1;
        config.storagePath = file.getPath();
        config.readOnly = readOnly;
        // In particular, failed startup must not compact an incompatible file.
        config.compactOnClose = !readOnly;
        database.setConfiguration(config);
        return database;
    }
}
