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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import javax.xml.namespace.QName;
import org.h2.mvstore.WriteBuffer;
import org.h2.mvstore.type.DataType;
import org.sensorhub.api.command.CommandData;
import org.sensorhub.api.command.CommandStatus;
import org.sensorhub.api.command.CommandStreamInfo;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.data.DataStreamInfo;
import org.sensorhub.api.data.ObsData;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.feature.FeatureFilter;
import org.sensorhub.api.datastore.feature.FeatureFilterBase;
import org.sensorhub.api.datastore.feature.FoiFilter;
import org.sensorhub.api.datastore.feature.IFeatureStoreBase;
import org.sensorhub.api.feature.FeatureId;
import org.sensorhub.impl.datastore.h2.MVFeatureDatabase;
import org.sensorhub.impl.datastore.h2.MVFeatureDatabaseConfig;
import org.sensorhub.impl.datastore.h2.MVObsSystemDatabase;
import org.sensorhub.impl.datastore.h2.MVObsSystemDatabaseConfig;
import org.sensorhub.impl.system.wrapper.SystemWrapper;
import org.sensorhub.impl.datastore.h2.migrate.spi.LegacyStore;
import org.vast.data.DataBlockDouble;
import org.vast.data.TextEncodingImpl;
import org.vast.ogc.gml.GenericFeatureImpl;
import org.vast.sensorML.SMLHelper;
import org.vast.swe.SWEHelper;
import org.vast.util.Bbox;
import org.vast.util.TimeExtent;
import net.opengis.gml.v32.impl.GMLFactory;


/**
 * Shared helpers: populate real databases through the store APIs, and export
 * an H2 2.5 database to a genuine H2 1.4 (format 1) file through the legacy
 * classloader so migration tests run against real legacy files.
 */
final class TestSupport
{
    static final int DATABASE_NUM = 1;
    static final String SYS_UID_PREFIX = "urn:osh:test:sensor:";
    static final String FOI_UID_PREFIX = "urn:osh:test:foi:";
    static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");


    /** Everything the population step wrote, so tests can check it survived */
    static final class Expected
    {
        int numSystems, numSystemVersions, numFois, numDataStreams, numObs, numCommandStreams, numCommands, numStatus;
        final Map<BigId, double[]> obsValues = new LinkedHashMap<>();
        BigId dataStreamId;
        BigId firstFoiId;
        long spatialMatches;   // features inside SPATIAL_QUERY, computed on the original database
        long keywordMatches;   // features matching KEYWORD_QUERY, computed on the original database
    }

    static final Bbox SPATIAL_QUERY = new Bbox(-1, -1, 4.6, 2.3);
    static final String KEYWORD_QUERY = "number";


    @SuppressWarnings({"rawtypes", "unchecked"})
    static long countFeatures(IFeatureStoreBase store, FeatureFilterBase filter)
    {
        try (Stream<?> s = store.select(filter))
        {
            return s.count();
        }
    }


    static long spatialMatches(IObsSystemDatabase db)
    {
        return countFeatures(db.getFoiStore(), new FoiFilter.Builder().withLocationWithin(SPATIAL_QUERY).build());
    }


    static long keywordMatches(IObsSystemDatabase db)
    {
        return countFeatures(db.getFoiStore(), new FoiFilter.Builder().withKeywords(KEYWORD_QUERY).build());
    }


    static long spatialMatches(MVFeatureDatabase db)
    {
        return countFeatures(db.getFeatureStore(), new FeatureFilter.Builder().withLocationWithin(SPATIAL_QUERY).build());
    }


    static long keywordMatches(MVFeatureDatabase db)
    {
        return countFeatures(db.getFeatureStore(), new FeatureFilter.Builder().withKeywords(KEYWORD_QUERY).build());
    }


    private TestSupport()
    {
    }


    /** Legacy jars come from the module's embedded resources unless a directory is given */
    static LegacyLoader legacyLoader() throws MigrationException
    {
        return LegacyLoader.createDefault(null);
    }


    static MVObsSystemDatabase openObsDatabase(Path file) throws Exception
    {
        var db = new MVObsSystemDatabase();
        var config = new MVObsSystemDatabaseConfig();
        config.storagePath = file.toString();
        config.databaseNum = DATABASE_NUM;
        db.setConfiguration(config);
        db.init();
        db.start();
        return db;
    }


    static MVFeatureDatabase openFeatureDatabase(Path file) throws Exception
    {
        var db = new MVFeatureDatabase();
        var config = new MVFeatureDatabaseConfig();
        config.storagePath = file.toString();
        config.databaseNum = DATABASE_NUM;
        db.setConfiguration(config);
        db.init();
        db.start();
        return db;
    }


    /**
     * Writes systems, geo-referenced FOIs, a datastream with observations, and a
     * command stream with commands and status reports through the public APIs.
     */
    static Expected populateObsDatabase(Path file, int numFois, int numObs, int numCommands) throws Exception
    {
        var exp = new Expected();
        var db = openObsDatabase(file);
        try
        {
            var sml = new SMLHelper();
            BigId[] sysIds = new BigId[3];
            for (int i = 0; i < sysIds.length; i++)
            {
                var proc = sml.createPhysicalComponent()
                    .uniqueID(SYS_UID_PREFIX + i)
                    .name("System " + i)
                    .description("Test system number " + i)
                    .build();
                sysIds[i] = db.getSystemDescStore().add(new SystemWrapper(proc)).getInternalID();
                exp.numSystems++;
            }

            exp.numSystemVersions = exp.numSystems;

            var gml = new GMLFactory(true);
            QName fType = new QName("http://mydomain/features", "MyPointFeature");
            for (int i = 0; i < numFois; i++)
            {
                var f = new GenericFeatureImpl(fType);
                var p = gml.newPoint();
                p.setPos(new double[] {i * 0.5, i * 0.25, 0.0});
                f.setGeometry(p);
                f.setId("F" + i);
                f.setUniqueIdentifier(FOI_UID_PREFIX + i);
                f.setName("Feature " + i);
                f.setDescription("Sampling location number " + i);
                var key = db.getFoiStore().add(f);
                if (i == 0)
                    exp.firstFoiId = key.getInternalID();
                exp.numFois++;
            }

            var swe = new SWEHelper();
            var rec = swe.createRecord().name("out1");
            for (int i = 0; i < 5; i++)
                rec.addField("comp" + i, swe.createQuantity().build());
            var dsInfo = new DataStreamInfo.Builder()
                .withName("out1")
                .withSystem(new FeatureId(sysIds[0], SYS_UID_PREFIX + 0))
                .withRecordDescription(rec.build())
                .withRecordEncoding(new TextEncodingImpl())
                .build();
            exp.dataStreamId = db.getObservationStore().getDataStreams().add(dsInfo).getInternalID();
            exp.numDataStreams++;

            for (int i = 0; i < numObs; i++)
            {
                var data = new DataBlockDouble(5);
                double[] vals = new double[5];
                for (int s = 0; s < 5; s++)
                {
                    vals[s] = i + s * 0.1;
                    data.setDoubleValue(s, vals[s]);
                }
                var obs = new ObsData.Builder()
                    .withDataStream(exp.dataStreamId)
                    .withFoi(exp.firstFoiId)
                    .withPhenomenonTime(T0.plusSeconds(i))
                    .withResult(data)
                    .build();
                var key = db.getObservationStore().add(obs);
                exp.obsValues.put(key, vals);
                exp.numObs++;
            }

            var cmdRec = swe.createRecord().name("cmd1");
            for (int i = 0; i < 3; i++)
                cmdRec.addField("param" + i, swe.createQuantity().build());
            var csInfo = new CommandStreamInfo.Builder()
                .withName("cmd1")
                .withSystem(new FeatureId(sysIds[1], SYS_UID_PREFIX + 1))
                .withRecordDescription(cmdRec.build())
                .withRecordEncoding(new TextEncodingImpl())
                .build();
            var csId = db.getCommandStore().getCommandStreams().add(csInfo).getInternalID();
            exp.numCommandStreams++;

            for (int i = 0; i < numCommands; i++)
            {
                var params = new DataBlockDouble(3);
                for (int s = 0; s < 3; s++)
                    params.setDoubleValue(s, i + s);
                var ts = T0.plusSeconds(i);
                var cmd = new CommandData.Builder()
                    .withCommandStream(csId)
                    .withIssueTime(ts)
                    .withParams(params)
                    .withSender("test" + i)
                    .build();
                var cmdKey = db.getCommandStore().add(cmd);
                exp.numCommands++;
                db.getCommandStore().getStatusReports().add(CommandStatus.completed(cmdKey, TimeExtent.instant(ts)));
                exp.numStatus++;
            }

        }
        finally
        {
            db.stop();
        }

        // A second description version of system 0, written in a new session like a node
        // restart would. The full-text index compares keys by valid time in memory but does
        // not persist it, so the reloaded key compares unequal and a byte-identical duplicate
        // entry lands on disk. Real legacy databases show exactly this.
        db = openObsDatabase(file);
        try
        {
            var sml = new SMLHelper();
            var proc0v2 = sml.createPhysicalComponent()
                .uniqueID(SYS_UID_PREFIX + 0)
                .name("System 0")
                .description("Test system number 0, updated")
                .validFrom(OffsetDateTime.of(2025, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC))
                .build();
            db.getSystemDescStore().add(new SystemWrapper(proc0v2));
            exp.numSystemVersions++;

            exp.spatialMatches = spatialMatches(db);
            exp.keywordMatches = keywordMatches(db);
            assertTrue("spatial index must select something on the original", exp.spatialMatches > 0);
            assertTrue("full-text index must select something on the original", exp.keywordMatches > 0);
        }
        finally
        {
            db.stop();
        }
        return exp;
    }


    static Expected populateFeatureDatabase(Path file, int numFeatures) throws Exception
    {
        var exp = new Expected();
        var db = openFeatureDatabase(file);
        try
        {
            var gml = new GMLFactory(true);
            QName fType = new QName("http://mydomain/features", "MyPointFeature");
            for (int i = 0; i < numFeatures; i++)
            {
                var f = new GenericFeatureImpl(fType);
                var p = gml.newPoint();
                p.setPos(new double[] {-i, i * 2.0, 0.0});
                f.setGeometry(p);
                f.setId("F" + i);
                f.setUniqueIdentifier(FOI_UID_PREFIX + i);
                f.setName("Feature " + i);
                f.setDescription("Generic feature number " + i);
                db.getFeatureStore().add(f);
                exp.numFois++;
            }
            exp.spatialMatches = spatialMatches(db);
            exp.keywordMatches = keywordMatches(db);
            assertTrue("spatial index must select something on the original", exp.spatialMatches > 0);
            assertTrue("full-text index must select something on the original", exp.keywordMatches > 0);
        }
        finally
        {
            db.stop();
        }
        return exp;
    }


    /**
     * Reverse direction, test support only: writes every map of an H2 2.5
     * database into a genuine H2 1.4 file with identical entry bytes.
     */
    static void exportToLegacy(Path source, Path dest, StoreLayout type) throws Exception
    {
        Files.deleteIfExists(dest);
        LegacyLoader loader = legacyLoader();
        try (SourceReader.Native src = new SourceReader.Native(source, 3);
             StoreLayout.Types types = StoreLayout.Types.harvest(src, type, dest.resolveSibling(dest.getFileName() + ".types")))
        {
            LegacyStore.Writer writer = loader.factory().openForWrite(dest.toString());
            try
            {
                for (String name: Migrator.copyOrder(src.getMapNames()))
                {
                    StoreLayout.MapSpec spec = types.get(name);
                    assertNotNull("no spec for " + name, spec);
                    if (spec.rtree())
                    {
                        var mw = writer.openRTreeMap(name, spec.dimensions());
                        src.readRTreeMap(name, spec.dimensions(), spec.valueType(), (id, mm, v, dv) -> mw.put(id, mm, v));
                    }
                    else
                    {
                        // Preserve byte-identical duplicate keys exactly as the datastore leaves them on disk:
                        // a distinct entry that compares equal is ordered after the existing one instead of
                        // replacing it, so the legacy fixture carries the same duplicates a real node produced.
                        var mw = writer.openMap(name, (ka, da, kb, dB) -> {
                            int c = spec.keyType().compare(da, dB);
                            return c != 0 ? c : (ka == kb ? 0 : 1);
                        });
                        src.readMap(name, spec.keyType(), spec.valueType(), (k, dk, v, dv) -> mw.put(k, dk, v));
                    }
                    writer.commit();
                }
            }
            finally
            {
                writer.close();
            }
        }
        finally
        {
            loader.close();
        }
    }


    static byte[] encode(DataType<Object> type, Object value)
    {
        WriteBuffer b = new WriteBuffer();
        type.write(b, value);
        ByteBuffer bb = b.getBuffer();
        bb.flip();
        byte[] out = new byte[bb.remaining()];
        bb.get(out);
        return out;
    }


    static Migrator.Options options(Path dbFile)
    {
        var o = new Migrator.Options();
        o.dbFile = dbFile;
        o.backup = true;
        o.log = s -> System.out.println("[h2migrate] " + s);
        return o;
    }
}
