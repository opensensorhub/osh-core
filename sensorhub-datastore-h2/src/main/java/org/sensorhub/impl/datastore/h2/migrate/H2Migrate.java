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

import java.nio.file.Paths;
import java.util.Locale;


/**
 * Command line entry point.
 * <pre>
 * h2migrate &lt;db-file&gt; [--type obs|feature] [--backup-dir DIR] [--legacy-libs DIR]
 *                     [--keep-interim] [--dry-run] [--quiet]
 * </pre>
 */
public final class H2Migrate
{
    static final String USAGE = String.join("\n",
        "Usage: h2migrate <db-file> [options]",
        "",
        "Migrates an OpenSensorHub H2 MVStore database written by H2 1.4.200 (file format 1),",
        "or an unmarked H2 2.x file, to the H2 2.5.250 format with a version marker.",
        "",
        "Steps: preflight, optional backup copy, interim copy, byte comparison, decode check, then the",
        "original is renamed to <db-file>.old and the migrated file takes its place.",
        "",
        "Options:",
        "  --type obs|feature   Database layout (default: detected from map names)",
        "  --backup             Also keep a verified backup copy (<db-file>.h2v1-backup-<timestamp>);",
        "                       the original is kept unmodified as <db-file>.old either way",
        "  --backup-dir DIR     Where to write that copy (default: next to the database)",
        "  --legacy-libs DIR    Directory holding h2-mvstore-1.4.200.jar and the legacy adapters",
        "                       (default: -Dh2migrate.legacyLibs or <install>/legacy-libs)",
        "  --keep-interim       Keep the <db-file>.migrating file if migration fails",
        "  --dry-run            Do everything except retiring the original and moving the migrated file in",
        "  --quiet              Only print the final report or the error",
        "  -h, --help           Show this help",
        "",
        "Exit codes: 0 success, 1 migration failed (original untouched), 2 usage error");


    private H2Migrate()
    {
    }


    public static void main(String[] args)
    {
        System.exit(run(args));
    }


    static int run(String[] args)
    {
        Migrator.Options opts = new Migrator.Options();
        boolean quiet = false;
        try
        {
            for (int i = 0; i < args.length; i++)
            {
                String a = args[i];
                switch (a)
                {
                    case "-h", "--help" -> {
                        System.out.println(USAGE);
                        return 0;
                    }
                    case "--type" -> opts.type = parseType(next(args, ++i, a));
                    case "--backup" -> opts.backup = true;
                    case "--backup-dir" -> opts.backupDir = Paths.get(next(args, ++i, a));
                    case "--legacy-libs" -> opts.legacyLibs = Paths.get(next(args, ++i, a));
                    case "--keep-interim" -> opts.keepInterim = true;
                    case "--dry-run" -> opts.dryRun = true;
                    case "--quiet" -> quiet = true;
                    default -> {
                        if (a.startsWith("-") || opts.dbFile != null)
                            throw new IllegalArgumentException("Unexpected argument: " + a);
                        opts.dbFile = Paths.get(a);
                    }
                }
            }
            if (opts.dbFile == null)
                throw new IllegalArgumentException("Missing <db-file>");
        }
        catch (IllegalArgumentException e)
        {
            System.err.println("Error: " + e.getMessage());
            System.err.println();
            System.err.println(USAGE);
            return 2;
        }

        if (!quiet)
            opts.log = System.out::println;

        try
        {
            Migrator.Report report = Migrator.run(opts);
            System.out.println();
            System.out.print(Migrator.describe(report));
            return 0;
        }
        catch (MigrationException e)
        {
            System.err.println("Migration failed: " + e.getMessage());
            if (e.getCause() != null)
                System.err.println("Cause: " + e.getCause());
            System.err.println("The original database file was not modified.");
            return 1;
        }
        catch (Exception e)
        {
            System.err.println("Migration failed with an unexpected error. The original database file was not modified.");
            e.printStackTrace(System.err);
            return 1;
        }
    }


    private static String next(String[] args, int i, String option)
    {
        if (i >= args.length)
            throw new IllegalArgumentException("Option " + option + " requires a value");
        return args[i];
    }


    private static StoreLayout parseType(String s)
    {
        switch (s.toLowerCase(Locale.ROOT))
        {
            case "obs", "obs_system", "obs-system":
                return StoreLayout.OBS_SYSTEM;
            case "feature", "features":
                return StoreLayout.FEATURE;
            default:
                throw new IllegalArgumentException("Unknown database type: " + s + " (expected obs or feature)");
        }
    }
}
