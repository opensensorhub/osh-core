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
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.regex.Pattern;


/**
 * File-level steps of the migration: header inspection, lock probing,
 * verified backup, instatement by rename (the original is retired as <file>.old).
 */
final class FileOps
{
    private static final Pattern FORMAT = Pattern.compile("(?:^|,)format:(\\d+)(?:,|$)");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");


    private FileOps()
    {
    }


    /**
     * Reads the H2 file header and returns its "format" field.
     * H2 1.4 writes format 1; H2 2.x writes format 2 or 3.
     */
    static int readH2Format(Path file) throws IOException, MigrationException
    {
        byte[] head = new byte[8192];
        int n;
        try (InputStream is = Files.newInputStream(file))
        {
            n = is.readNBytes(head, 0, head.length);
        }
        String text = new String(head, 0, n, StandardCharsets.ISO_8859_1);
        int start = text.indexOf("H:2,");
        if (start < 0)
            throw new MigrationException("Not an H2 MVStore file (no header found): " + file);
        int end = text.indexOf('\n', start);
        String header = end < 0 ? text.substring(start) : text.substring(start, end);
        var m = FORMAT.matcher(header);
        if (!m.find())
            throw new MigrationException("H2 header has no format field: " + file);
        return Integer.parseInt(m.group(1));
    }


    /**
     * Fails if another process holds a lock on the file (a running node).
     */
    static void checkNotLocked(Path file) throws IOException, MigrationException
    {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE))
        {
            FileLock lock = ch.tryLock();
            if (lock == null)
                throw new MigrationException("Database file is locked by another process: " + file);
            lock.release();
        }
        catch (OverlappingFileLockException e)
        {
            throw new MigrationException("Database file is locked by this process: " + file);
        }
    }


    /**
     * Copies the file next to itself (or into backupDir) and verifies the copy
     * by length and SHA-256.
     * @return path of the verified backup
     */
    static Path backup(Path file, Path backupDir, String tag) throws IOException, MigrationException
    {
        Path dir = backupDir != null ? backupDir : file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        String stamp = ZonedDateTime.now(ZoneOffset.UTC).format(TS);
        Path backup = dir.resolve(file.getFileName() + "." + tag + "-backup-" + stamp);
        if (Files.exists(backup))
            throw new MigrationException("Backup target already exists: " + backup);

        Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
        fsync(backup);

        long srcLen = Files.size(file);
        long dstLen = Files.size(backup);
        if (srcLen != dstLen)
            throw new MigrationException("Backup length mismatch: " + srcLen + " vs " + dstLen + " for " + backup);
        String srcHash = sha256(file);
        String dstHash = sha256(backup);
        if (!srcHash.equals(dstHash))
            throw new MigrationException("Backup checksum mismatch for " + backup);
        return backup;
    }


    static String sha256(Path file) throws IOException
    {
        MessageDigest md;
        try
        {
            md = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ))
        {
            ByteBuffer buf = ByteBuffer.allocate(1 << 20);
            while (ch.read(buf) >= 0)
            {
                buf.flip();
                md.update(buf);
                buf.clear();
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }


    /**
     * Puts the interim file in place without ever overwriting or deleting the
     * original: the original is renamed to {@code retired} (its bytes untouched),
     * then the interim file is renamed onto the now free target path. If the second
     * rename fails, the original is renamed back.
     */
    static void instate(Path interim, Path target, Path retired) throws IOException
    {
        if (Files.exists(retired))
            throw new IOException("Cannot retire the original, file already exists: " + retired);
        fsync(interim);
        Files.move(target, retired, StandardCopyOption.ATOMIC_MOVE);
        try
        {
            Files.move(interim, target, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException e)
        {
            Files.move(retired, target, StandardCopyOption.ATOMIC_MOVE);
            throw e;
        }
        fsyncDirectory(target.toAbsolutePath().getParent());
    }


    static void fsync(Path file) throws IOException
    {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ))
        {
            ch.force(true);
        }
    }


    static void fsyncDirectory(Path dir)
    {
        if (dir == null)
            return;
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ))
        {
            ch.force(true);
        }
        catch (IOException e)
        {
            // directories cannot be opened for sync on every platform; best effort
        }
    }


    static void deleteQuietly(Path file)
    {
        if (file == null)
            return;
        try
        {
            Files.deleteIfExists(file);
        }
        catch (IOException e)
        {
            // best effort cleanup
        }
    }
}
