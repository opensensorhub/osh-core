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
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.sensorhub.impl.datastore.h2.migrate.spi.LegacyStore;


/**
 * Child-first classloader that isolates the H2 1.4 jar and the legacy
 * adapters from the H2 2.5 classes on the tool's own classpath. Everything
 * else, including the spi interfaces and the OSH datatypes, is delegated to
 * the parent so objects can be shared across the boundary.
 */
public final class LegacyLoader extends URLClassLoader
{
    public static final String LIBS_PROPERTY = "h2migrate.legacyLibs";
    /** Resource directory holding the embedded H2 1.4 jar and adapters, listed in index.txt */
    static final String EMBEDDED_DIR = "META-INF/h2migrate/";
    static final String LEGACY_PACKAGE = "org.sensorhub.impl.datastore.h2.migrate.legacy.";
    static final String H2_PACKAGE = "org.h2.";


    private LegacyLoader(URL[] urls, ClassLoader parent)
    {
        super("h2migrate-legacy", urls, parent);
    }


    /** Directory name looked up next to the directory holding this module's jar */
    public static final String LIBS_DIR_NAME = "legacy-libs";


    /**
     * Creates the loader from the default location, in this order: an explicit
     * directory, the {@value #LIBS_PROPERTY} system property, a {@value #LIBS_DIR_NAME}
     * directory next to the directory holding this module's jar (a node's lib/),
     * then the jars embedded in this module under {@value #EMBEDDED_DIR} when the
     * module was built with them.
     */
    public static LegacyLoader createDefault(Path explicit) throws MigrationException
    {
        return create(resolveLibsDir(explicit));
    }


    public static Path resolveLibsDir(Path explicit) throws MigrationException
    {
        List<Path> candidates = new ArrayList<>();
        if (explicit != null)
            candidates.add(explicit);
        String prop = System.getProperty(LIBS_PROPERTY);
        if (prop != null && !prop.isBlank())
            candidates.add(Paths.get(prop));
        Path installed = installedLibsDir();
        if (installed != null)
            candidates.add(installed);

        for (Path dir: candidates)
        {
            if (Files.isDirectory(dir) && hasLegacyH2Jar(dir))
                return dir;
        }
        if (explicit != null || (prop != null && !prop.isBlank()))
            throw new MigrationException("No h2-mvstore-1.4.x.jar found in " + candidates);
        if (hasEmbedded())
            return extractEmbedded();
        throw new MigrationException("The H2 1.4 migration libraries were not found. Looked in " + candidates +
            " and this build of the datastore module does not embed them. Put h2-mvstore-1.4.200.jar and " +
            "the sensorhub-datastore-h2 h2migrate-legacy jar in a '" + LIBS_DIR_NAME + "' directory next to the " +
            "node's lib/ directory (a source checkout builds it with ./gradlew :sensorhub-datastore-h2:h2migrateLegacyLibs), " +
            "or pass --legacy-libs / -D" + LIBS_PROPERTY);
    }


    private static Path installedLibsDir()
    {
        try
        {
            var src = LegacyLoader.class.getProtectionDomain().getCodeSource();
            if (src == null)
                return null;
            Path location = Paths.get(src.getLocation().toURI());
            Path libDir = Files.isDirectory(location) ? location : location.getParent();
            return libDir == null || libDir.getParent() == null ? null : libDir.getParent().resolve(LIBS_DIR_NAME);
        }
        catch (Exception e)
        {
            return null;
        }
    }


    static boolean hasEmbedded()
    {
        return LegacyLoader.class.getClassLoader().getResource(EMBEDDED_DIR + "index.txt") != null;
    }


    /**
     * Copies the embedded legacy jars to a temporary directory that is removed on exit.
     */
    static Path extractEmbedded() throws MigrationException
    {
        ClassLoader cl = LegacyLoader.class.getClassLoader();
        try (var index = cl.getResourceAsStream(EMBEDDED_DIR + "index.txt"))
        {
            if (index == null)
                throw new MigrationException("This build does not embed the H2 1.4 migration libraries (" +
                    EMBEDDED_DIR + "index.txt missing); use --legacy-libs or -D" + LIBS_PROPERTY);
            Path dir = Files.createTempDirectory("h2migrate-legacy-");
            dir.toFile().deleteOnExit();
            for (String name: new String(index.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R"))
            {
                if (name.isBlank())
                    continue;
                try (var in = cl.getResourceAsStream(EMBEDDED_DIR + name))
                {
                    if (in == null)
                        throw new MigrationException("Embedded legacy library missing: " + EMBEDDED_DIR + name);
                    Path target = dir.resolve(name);
                    Files.copy(in, target);
                    target.toFile().deleteOnExit();
                }
            }
            if (!hasLegacyH2Jar(dir))
                throw new MigrationException("Embedded legacy libraries do not include h2-mvstore-1.4.x.jar");
            return dir;
        }
        catch (IOException e)
        {
            throw new MigrationException("Cannot extract embedded legacy libraries", e);
        }
    }


    private static boolean hasLegacyH2Jar(Path dir)
    {
        try (Stream<Path> files = Files.list(dir))
        {
            return files.anyMatch(f -> f.getFileName().toString().matches("h2-mvstore-1\\.4\\.\\d+\\.jar"));
        }
        catch (IOException e)
        {
            return false;
        }
    }


    public static LegacyLoader create(Path libsDir) throws MigrationException
    {
        try (Stream<Path> files = Files.list(libsDir))
        {
            List<URL> urls = new ArrayList<>();
            for (Path f: files.sorted().toList())
            {
                if (f.getFileName().toString().endsWith(".jar"))
                    urls.add(f.toUri().toURL());
            }
            if (urls.isEmpty())
                throw new MigrationException("No jars found in " + libsDir);
            return new LegacyLoader(urls.toArray(new URL[0]), LegacyLoader.class.getClassLoader());
        }
        catch (IOException e)
        {
            throw new MigrationException("Cannot list legacy libs in " + libsDir, e);
        }
    }


    public LegacyStore.Factory factory() throws MigrationException
    {
        try
        {
            Class<?> impl = Class.forName(LegacyStore.Factory.IMPL_CLASS, true, this);
            return (LegacyStore.Factory)impl.getDeclaredConstructor().newInstance();
        }
        catch (ReflectiveOperationException | ClassCastException e)
        {
            throw new MigrationException("Cannot load legacy adapters from " + List.of(getURLs()), e);
        }
    }


    static boolean isIsolated(String className)
    {
        return className.startsWith(H2_PACKAGE) || className.startsWith(LEGACY_PACKAGE);
    }


    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException
    {
        synchronized (getClassLoadingLock(name))
        {
            Class<?> c = findLoadedClass(name);
            if (c == null)
            {
                if (!isIsolated(name))
                    return super.loadClass(name, resolve);
                c = findClass(name); // never fall back to the parent's H2 2.5 classes
            }
            if (resolve)
                resolveClass(c);
            return c;
        }
    }


    @Override
    public URL getResource(String name)
    {
        // resources under org/h2 must also come from the 1.4 jar
        if (name.startsWith("org/h2/"))
        {
            URL url = findResource(name);
            if (url != null)
                return url;
        }
        return super.getResource(name);
    }
}
