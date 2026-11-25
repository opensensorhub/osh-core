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

import org.sensorhub.impl.datastore.h2.MVObsSystemDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import org.h2.mvstore.MVBTreeMap;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.rtree.MVRTreeMap;
import org.h2.mvstore.rtree.Spatial;
import org.h2.mvstore.rtree.SpatialDataType;
import org.sensorhub.impl.datastore.h2.H2Utils;


/**
 * The migration pipeline: preflight, verified backup, interim copy,
 * byte-level and semantic verification, instatement.
 * The original file is never written to: it is only read, copied for the
 * backup, and finally renamed to <file>.old when the migrated file takes its
 * path. Neither the backup nor the retired original is ever deleted here.
 */
public final class Migrator
{
    /** Suffix under which the untouched original is kept after instatement */
    public static final String RETIRED_SUFFIX = ".old";

    /** Commit the interim store whenever this much unsaved memory accumulates */
    private static final int COMMIT_THRESHOLD_BYTES = 32 * 1024 * 1024;

    private static final Set<String> NEVER_COPIED = Set.of(H2Utils.DATABASE_MAP_NAME);


    public static final class Options
    {
        public Path dbFile;
        public StoreLayout type;           // null = detect from map names
        public boolean backup;              // also keep a verified copy of the original (it survives as <db-file>.old anyway)
        public Path backupDir;              // null = next to the database file
        public Path legacyLibs;             // null = system property, then the jars embedded in this module
        public boolean keepInterim;
        public boolean dryRun;
        public Consumer<String> log = s -> {};
    }


    public static final class Report
    {
        public Path dbFile;
        public Path backup;
        public Path interim;
        /** the untouched original after instatement (<db-file>.old), null for a dry run */
        public Path retiredOriginal;
        public int sourceFormat;
        public String sourceDescription;
        public StoreLayout type;
        public Map<String, Long> copied = new LinkedHashMap<>();
        /** byte-identical duplicate keys found in the source and stored once, per map */
        public Map<String, Long> duplicatesCollapsed = new LinkedHashMap<>();
        public boolean instated;
        public Duration elapsed;
    }


    private static final class SpatialFactory extends SpatialDataType
    {
        SpatialFactory(int dimensions)
        {
            super(dimensions);
        }

        @Override
        public Spatial create(long id, float... minMax)
        {
            return super.create(id, minMax);
        }
    }


    private Migrator()
    {
    }


    public static Report run(Options options) throws Exception
    {
        Instant t0 = Instant.now();
        Consumer<String> log = options.log;
        Path dbFile = options.dbFile.toAbsolutePath();
        Report report = new Report();
        report.dbFile = dbFile;

        // 1. preflight
        if (!Files.isRegularFile(dbFile) || Files.size(dbFile) == 0)
            throw new MigrationException("Database file is missing or empty: " + dbFile);
        FileOps.checkNotLocked(dbFile);
        int format = FileOps.readH2Format(dbFile);
        report.sourceFormat = format;
        log.accept("Source file: " + dbFile + " (H2 file format " + format + ")");

        Path interim = dbFile.resolveSibling(dbFile.getFileName() + ".migrating");
        Path scratch = dbFile.resolveSibling(dbFile.getFileName() + ".migrating-types");
        if (Files.exists(interim))
            throw new MigrationException("Interim file already exists, remove it first: " + interim);
        report.interim = interim;
        Path retired = dbFile.resolveSibling(dbFile.getFileName() + RETIRED_SUFFIX);
        if (!options.dryRun && Files.exists(retired))
            throw new MigrationException("A retired original already exists, move it away first: " + retired);

        LegacyLoader loader = null;
        if (format == 1)
        {
            Path libs = LegacyLoader.resolveLibsDir(options.legacyLibs);
            log.accept("Legacy H2 libraries: " + libs);
            loader = LegacyLoader.create(libs);
        }

        boolean success = false;
        try (SourceReader source = openSource(format, loader, dbFile))
        {
            report.sourceDescription = source.describe();
            log.accept("Reading with " + source.describe());

            if (source instanceof SourceReader.Native native_)
            {
                String marker = native_.getVersionMarker();
                if (marker != null)
                    throw new MigrationException("File already carries version marker " + marker +
                        "; nothing to migrate: " + dbFile);
            }

            StoreLayout type = options.type != null ? options.type : StoreLayout.detect(source.getMapNames());
            report.type = type;
            log.accept("Database type: " + type);

            // 2. harvest datatypes on a scratch store seeded with the class map, and make sure
            //    the layout knows every source map before anything is written next to the original
            try (StoreLayout.Types types = StoreLayout.Types.harvest(source, type, scratch))
            {
                List<String> unknown = new ArrayList<>();
                for (String name: source.getMapNames())
                {
                    if (!NEVER_COPIED.contains(name) && types.get(name) == null)
                        unknown.add(name);
                }
                if (!unknown.isEmpty())
                    throw new MigrationException("Source contains maps unknown to the " + type + " layout: " + unknown +
                        ". This tool cannot migrate them; a layout extension is required.");

                // 3. optional backup copy; the original itself is retired as <db-file>.old at the end
                if (options.backup)
                {
                    report.backup = FileOps.backup(dbFile, options.backupDir, format == 1 ? "h2v1" : "h2v2");
                    log.accept("Backup verified: " + report.backup);
                }
                else
                    log.accept("No backup copy requested; the original will be kept unmodified as " + retired);

                // 4. copy every map into the interim file, class map first
                MVStore target = H2Utils.openMVStore(interim.toString(), new MVStore.Builder(), 0);
                try
                {
                    for (String name: copyOrder(source.getMapNames()))
                    {
                        var result = copyMap(source, target, types.get(name), log);
                        report.copied.put(name, result.stored);
                        if (result.duplicates > 0)
                            report.duplicatesCollapsed.put(name, result.duplicates);
                        target.commit();
                    }
                    target.commit();
                }
                finally
                {
                    target.close();
                }
                log.accept("Interim file written: " + interim);

                // 7. pass one: compare bytes
                log.accept("Verifying bytes against source...");
                try (SourceReader migrated = new SourceReader.Native(interim, 3))
                {
                    Verifier.compareBytes(source, migrated, types.getSpecs(), NEVER_COPIED, log);
                }
            }

            // 8. pass two: decode through the store implementations
            log.accept("Verifying decoding through store implementations...");
            Map<String, Long> decoded = Verifier.decodeAll(interim, type, log);
            for (var e: report.copied.entrySet())
            {
                Long n = decoded.get(e.getKey());
                if (n == null)
                    throw new MigrationException("Map " + e.getKey() + " was not opened by the store implementations");
                // the class map may legitimately grow while the stores read pages, and opening the stores
                // registers every store the file predates in @datastores; data maps may not change
                boolean mayGrow = e.getKey().equals(MVObsSystemDatabase.KRYO_CLASS_MAP_NAME) || e.getKey().equals(H2Utils.DATASTORES_MAP_NAME);
                boolean ok = mayGrow ? n >= e.getValue() : n.equals(e.getValue());
                if (!ok)
                    throw new MigrationException("Decoded count for " + e.getKey() + " is " + n + ", expected " + e.getValue());
            }

            // 9. instate
            if (options.dryRun)
            {
                log.accept("Dry run: original left in place, interim kept at " + interim);
            }
            else
            {
                FileOps.instate(interim, dbFile, retired);
                report.instated = true;
                report.retiredOriginal = retired;
                log.accept("Original kept unmodified as " + retired);
                log.accept("Migrated database instated at " + dbFile);
            }
            success = true;
        }
        catch (DuplicateConflictException e)
        {
            throw new MigrationException(e.getMessage(), e);
        }
        finally
        {
            if (loader != null)
                loader.close();
            FileOps.deleteQuietly(scratch);
            if (!success && !options.keepInterim)
                FileOps.deleteQuietly(interim);
            if (success && report.instated)
                FileOps.deleteQuietly(interim); // already moved; no-op unless move left a copy
        }

        report.elapsed = Duration.between(t0, Instant.now());
        return report;
    }


    /**
     * Tells whether a database file must be migrated before the datastore can open it:
     * an H2 1.4 (format 1) file, or an H2 2.x file without the version marker.
     * Missing or empty files are new databases and return false.
     */
    public static boolean needsMigration(Path dbFile) throws Exception
    {
        if (!Files.isRegularFile(dbFile) || Files.size(dbFile) == 0)
            return false;
        int format = FileOps.readH2Format(dbFile);
        if (format == 1)
            return true;
        try (SourceReader.Native reader = new SourceReader.Native(dbFile, format))
        {
            return reader.getVersionMarker() == null;
        }
    }


    /**
     * Startup hook for the database modules: migrates the file in place (with
     * verification; the original is kept as <db-file>.old) when it needs it,
     * otherwise does nothing.
     * @param backup also keep a verified backup copy of the original
     * @return the report, or null if no migration was needed
     */
    public static Report migrateIfNeeded(Path dbFile, boolean backup, Consumer<String> log) throws Exception
    {
        if (!needsMigration(dbFile))
            return null;
        log.accept("Database file " + dbFile + " uses a legacy H2 format; migrating it (autoMigrate is enabled)");
        Options options = new Options();
        options.dbFile = dbFile;
        options.backup = backup;
        options.log = log;
        Report report = run(options);
        log.accept(describe(report));
        return report;
    }


    private static SourceReader openSource(int format, LegacyLoader loader, Path dbFile) throws Exception
    {
        if (format == 1)
            return new SourceReader.Legacy(loader.factory(), dbFile);
        return new SourceReader.Native(dbFile, format);
    }


    /** Class map first, datastore registry second, then everything else sorted */
    static List<String> copyOrder(Set<String> names)
    {
        List<String> order = new ArrayList<>();
        if (names.contains(MVObsSystemDatabase.KRYO_CLASS_MAP_NAME))
            order.add(MVObsSystemDatabase.KRYO_CLASS_MAP_NAME);
        if (names.contains(H2Utils.DATASTORES_MAP_NAME))
            order.add(H2Utils.DATASTORES_MAP_NAME);
        for (String n: new TreeSet<>(names))
        {
            if (!order.contains(n) && !NEVER_COPIED.contains(n))
                order.add(n);
        }
        return order;
    }


    record CopyResult(long stored, long duplicates)
    {
    }


    private static CopyResult copyMap(SourceReader source, MVStore target, StoreLayout.MapSpec spec, Consumer<String> log) throws Exception
    {
        long n;
        long duplicates = 0;
        if (spec.rtree())
        {
            if (!source.isRTree(spec.name()))
                log.accept("  note: " + spec.name() + " has no R-tree marker in the source metadata " +
                    "(dropped by H2 1.4 compaction); it is read as an R-tree and the marker is restored");
            var factory = new SpatialFactory(spec.dimensions());
            MVRTreeMap<Object> map = target.openMap(spec.name(), new MVRTreeMap.Builder<Object>()
                .dimensions(spec.dimensions())
                .valueType(new RawDataType(spec.valueType())));
            n = source.readRTreeMap(spec.name(), spec.dimensions(), spec.valueType(), (id, minMax, v, dv) -> {
                map.put(factory.create(id, minMax), new RawDataType.Entry(v));
                commitIfNeeded(target);
            });
        }
        else
        {
            MVMap<Object, Object> map = target.openMap(spec.name(), new MVBTreeMap.Builder<Object, Object>()
                .keyType(new RawDataType(spec.keyType()))
                .valueType(new RawDataType(spec.valueType())));
            var dedupe = new DuplicateFilter(spec.name());
            source.readMap(spec.name(), spec.keyType(), spec.valueType(), (k, dk, v, dv) -> {
                if (dedupe.isDuplicate(k, v))
                    return;
                map.put(new RawDataType.Entry(k, dk), new RawDataType.Entry(v));
                commitIfNeeded(target);
            });
            n = dedupe.kept;
            duplicates = dedupe.duplicates;
        }
        log.accept("  copied " + spec.name() + ": " + n + " entries" + (spec.rtree() ? " (rtree)" : "") +
            (duplicates > 0 ? ", collapsed " + duplicates + " byte-identical duplicate key(s)" : ""));
        return new CopyResult(n, duplicates);
    }


    private static void commitIfNeeded(MVStore target)
    {
        if (target.getUnsavedMemory() > COMMIT_THRESHOLD_BYTES)
            target.commit();
    }


    /**
     * Legacy B-trees can hold consecutive entries whose key bytes are identical.
     * The datastore compares some keys on fields it never persists (for example the
     * full-text index compares feature valid time but stores only the feature id),
     * so two versions of one feature produced two on-disk entries that decode to the
     * same key. They collapse to one entry here; identical keys with different values
     * are refused because that would silently drop data.
     */
    /** Unchecked so it can cross the sink callbacks; converted to MigrationException in run() */
    static final class DuplicateConflictException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        DuplicateConflictException(String message)
        {
            super(message);
        }
    }


    static final class DuplicateFilter
    {
        private final String mapName;
        private byte[] lastKey;
        private byte[] lastValue;
        long kept;
        long duplicates;

        DuplicateFilter(String mapName)
        {
            this.mapName = mapName;
        }

        boolean isDuplicate(byte[] key, byte[] value)
        {
            if (lastKey != null && java.util.Arrays.equals(lastKey, key))
            {
                if (!java.util.Arrays.equals(lastValue, value))
                    throw new DuplicateConflictException("Map " + mapName + " holds byte-identical duplicate keys with " +
                        "different values; refusing to choose one (key " + java.util.HexFormat.of().formatHex(key) + ")");
                duplicates++;
                return true;
            }
            lastKey = key;
            lastValue = value;
            kept++;
            return false;
        }
    }


    static String describe(Report r)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Database:   ").append(r.dbFile).append('\n');
        sb.append("Source:     ").append(r.sourceDescription).append('\n');
        sb.append("Type:       ").append(r.type).append('\n');
        sb.append("Backup:     ").append(r.backup != null ? r.backup : "none requested").append('\n');
        sb.append("Result:     ").append(r.instated ? "instated at " + r.dbFile : "dry run, interim at " + r.interim).append('\n');
        if (r.retiredOriginal != null)
            sb.append("Original:   kept unmodified as ").append(r.retiredOriginal).append('\n');
        sb.append("Elapsed:    ").append(r.elapsed).append('\n');
        sb.append("Maps:\n");
        for (var e: r.copied.entrySet())
        {
            Long dup = r.duplicatesCollapsed.get(e.getKey());
            sb.append(String.format("  %-40s %10d%s%n", e.getKey(), e.getValue(),
                dup == null ? "" : "   (+" + dup + " byte-identical duplicate key(s) collapsed)"));
        }
        return sb.toString();
    }
}
