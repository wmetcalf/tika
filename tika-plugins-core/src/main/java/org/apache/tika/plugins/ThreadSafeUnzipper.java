/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tika.plugins;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

import org.pf4j.util.Unzip;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe and process-safe plugin unzipper using atomic rename.
 * <p>
 * Normal publication is lock-free:
 * <ol>
 *   <li>Check if destination directory exists with completion marker - if yes, already extracted</li>
 *   <li>Extract to a temporary directory with a unique name</li>
 *   <li>Create a completion marker in the temp directory and atomically rename it to the final
 *       destination</li>
 *   <li>If rename fails (another process won), clean up temp dir</li>
 * </ol>
 * <p>
 * A stable root lock in a reserved child directory serializes only stale recovery and the
 * non-atomic fallback. The lock file is never deleted, so OS lock ownership is released
 * automatically on process death without introducing Windows lock-file deletion races. For a
 * non-atomic move, the completion marker is removed from the moving tree and created at the
 * destination only after that move succeeds, so a partially moved directory cannot appear
 * complete.
 */
public class ThreadSafeUnzipper {
    private static final Logger LOG = LoggerFactory.getLogger(TikaPluginManager.class);
    private static final String COMPLETE_MARKER = ".tika-extraction-complete";
    private static final String LOCK_DIRECTORY = "tika_lck";
    private static final String ROOT_LOCK_FILE = "root.lock";

    @FunctionalInterface
    interface MoveOperation {
        void move(Path source, Path destination, CopyOption... options) throws IOException;
    }

    /**
     * Unzips a plugin zip file to a directory with the same name (minus .zip extension).
     * Safe for concurrent calls from multiple threads or processes. See
     * documentation at the head of this class for how it works.
     *
     * @param source path to the .zip file
     * @throws IOException if extraction fails
     */
    public static void unzipPlugin(Path source) throws IOException {
        unzipPlugin(source, Files::move);
    }

    static void unzipPlugin(Path source, MoveOperation moveOperation) throws IOException {
        if (!source.getFileName().toString().endsWith(".zip")) {
            throw new IllegalArgumentException("source file name must end in '.zip'");
        }

        Path destination = getDestination(source);
        if (isReservedLockDestination(destination)) {
            throw new IllegalArgumentException(
                    "plugin file name uses reserved extraction-lock namespace: " + source);
        }

        // Already extracted - check for both directory AND completion marker
        if (isExtractionComplete(destination)) {
            LOG.debug("{} is already extracted", source);
            return;
        }

        // Destination exists but has no completion marker. Possible causes:
        // a previous extraction was killed mid-stream, the marker was deleted
        // out from under us, or something other than our extractor put files
        // there. Without this cleanup the subsequent Files.move() below will
        // fail with DirectoryNotEmptyException on every run until a human
        // manually removes the directory. Treat the half-extracted state as
        // garbage and rebuild.
        if (Files.exists(destination)) {
            if (quarantineStaleDestination(destination, moveOperation)) {
                return;
            }
        }

        // Extract to a unique temp directory
        Path tempDir = destination.resolveSibling(
                destination.getFileName() + ".tmp." + UUID.randomUUID());

        try {
            LOG.debug("extracting {} to temp dir {}", source, tempDir);
            new Unzip(source.toFile(), tempDir.toFile()).extract();

            // Create completion marker in temp dir before moving
            Files.createFile(tempDir.resolve(COMPLETE_MARKER));

            // Atomically rename to final destination
            try {
                moveOperation.move(tempDir, destination, StandardCopyOption.ATOMIC_MOVE);
                LOG.debug("successfully extracted {}", destination);
            } catch (FileAlreadyExistsException | DirectoryNotEmptyException e) {
                // Another process extracted it first - wait for completion marker
                LOG.debug("plugin already extracted by another process: {}", destination);
                waitForExtractionComplete(destination);
            } catch (AtomicMoveNotSupportedException e) {
                publishWithoutAtomicMove(tempDir, destination, moveOperation);
            } catch (FileSystemException e) {
                handleGenericAtomicMoveFailure(destination, e);
            }
        } finally {
            // Clean up temp dir if it still exists (we lost the race or there was an error)
            if (Files.exists(tempDir)) {
                deleteRecursively(tempDir);
            }
        }
    }

    /**
     * Moves a stale destination to a unique quarantine path before deleting it. This prevents a
     * cleanup process from deleting a completed extraction that another process publishes at the
     * original destination after the stale-state check.
     *
     * @return {@code true} when another process completed extraction and the caller should return
     */
    private static synchronized boolean quarantineStaleDestination(Path destination,
                                                                    MoveOperation moveOperation)
            throws IOException {
        try (FileChannel channel = openLockChannel(destination);
                FileLock ignored = acquireFileLock(channel)) {
            return quarantineStaleDestinationLocked(destination, moveOperation);
        }
    }

    private static boolean quarantineStaleDestinationLocked(Path destination,
                                                             MoveOperation moveOperation)
            throws IOException {
        if (isExtractionComplete(destination)) {
            return true;
        }
        if (!Files.exists(destination)) {
            return false;
        }

        Path quarantine = destination.resolveSibling(
                destination.getFileName() + ".stale." + UUID.randomUUID());
        try {
            moveOperation.move(destination, quarantine, StandardCopyOption.ATOMIC_MOVE);
        } catch (NoSuchFileException e) {
            return isExtractionComplete(destination);
        } catch (AtomicMoveNotSupportedException e) {
            // The stable lock makes direct cleanup safe on filesystems that cannot atomically
            // quarantine. Publishers on such a filesystem must acquire this same lock before
            // exposing their destination.
            deleteRecursively(destination);
            if (Files.exists(destination)) {
                throw new IOException("could not remove locked stale extraction at "
                        + destination, e);
            }
            return false;
        } catch (FileSystemException e) {
            if (isExtractionComplete(destination)) {
                return true;
            }
            throw e;
        }

        if (isExtractionComplete(quarantine)) {
            restoreCompletedExtraction(quarantine, destination, moveOperation);
            return true;
        }

        LOG.warn("destination {} exists without a completion marker; "
                + "quarantined stale partial extraction at {}", destination, quarantine);
        deleteRecursively(quarantine);
        if (Files.exists(quarantine)) {
            throw new IOException("could not remove quarantined stale extraction at "
                    + quarantine + "; remove it manually and retry");
        }
        return false;
    }

    private static void restoreCompletedExtraction(Path quarantine, Path destination,
                                                   MoveOperation moveOperation)
            throws IOException {
        try {
            moveOperation.move(quarantine, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileSystemException e) {
            if (!isExtractionComplete(destination)) {
                throw new IOException("could not restore completed extraction from "
                        + quarantine + " to " + destination, e);
            }
            deleteRecursively(quarantine);
            if (Files.exists(quarantine)) {
                throw new IOException("could not remove duplicate completed extraction at "
                        + quarantine, e);
            }
        }
    }

    private static synchronized void publishWithoutAtomicMove(Path tempDir, Path destination,
                                                              MoveOperation moveOperation)
            throws IOException {
        try (FileChannel channel = openLockChannel(destination);
                FileLock ignored = acquireFileLock(channel)) {
            if (isExtractionComplete(destination)) {
                return;
            }
            if (Files.exists(destination)) {
                if (quarantineStaleDestinationLocked(destination, moveOperation)) {
                    return;
                }
            }

            // A non-atomic move may expose a partial destination. Keep completion out of the
            // moving tree and publish it only after the move returns successfully.
            Files.delete(tempDir.resolve(COMPLETE_MARKER));
            try {
                moveOperation.move(tempDir, destination);
            } catch (FileAlreadyExistsException | DirectoryNotEmptyException e) {
                waitForExtractionComplete(destination);
                return;
            } catch (FileSystemException e) {
                handleGenericMoveFailure(destination, e);
                return;
            }
            Files.createFile(destination.resolve(COMPLETE_MARKER));
            LOG.debug("successfully extracted {} (non-atomic)", destination);
        }
    }

    private static FileChannel openLockChannel(Path destination) throws IOException {
        Path lockDirectory = destination.getParent().resolve(LOCK_DIRECTORY);
        if (!Files.exists(lockDirectory)) {
            try {
                Files.createDirectory(lockDirectory);
            } catch (FileAlreadyExistsException e) {
                // Another process created the shared lock directory.
            }
        }
        if (!Files.isDirectory(lockDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("extraction lock path is not a directory: " + lockDirectory);
        }
        if (Files.exists(lockDirectory.resolve(COMPLETE_MARKER))) {
            throw new IOException("reserved extraction lock directory is a plugin destination: "
                    + lockDirectory);
        }
        return FileChannel.open(getLockPath(destination), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
    }

    private static FileLock acquireFileLock(FileChannel channel) throws IOException {
        while (true) {
            try {
                return channel.lock();
            } catch (OverlappingFileLockException e) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while waiting for extraction lock",
                            interrupted);
                }
            }
        }
    }

    static Path getLockPath(Path destination) {
        return destination.getParent().resolve(LOCK_DIRECTORY).resolve(ROOT_LOCK_FILE);
    }

    static boolean isLockDirectory(Path path) {
        return path.getFileName() != null &&
                path.getFileName().toString().equalsIgnoreCase(LOCK_DIRECTORY);
    }

    private static boolean isReservedLockDestination(Path destination) throws IOException {
        if (isLockDirectory(destination)) {
            return true;
        }
        Path lockDirectory = destination.getParent().resolve(LOCK_DIRECTORY);
        if (!Files.exists(destination) || !Files.exists(lockDirectory)) {
            return false;
        }
        try {
            return Files.isSameFile(destination, lockDirectory);
        } catch (NoSuchFileException e) {
            // A concurrent extractor moved the destination after the existence check.
            return false;
        }
    }

    /**
     * Checks if extraction is complete by verifying both directory exists and completion marker is present.
     */
    private static boolean isExtractionComplete(Path destination) {
        return Files.isDirectory(destination) && Files.exists(destination.resolve(COMPLETE_MARKER));
    }

    /**
     * Waits for extraction to complete by polling for the completion marker.
     * This is called when we detect another process is extracting.
     */
    private static void waitForExtractionComplete(Path destination) throws IOException {
        waitForExtractionComplete(destination, 60000);
    }

    private static void waitForExtractionComplete(Path destination, long maxWaitMs)
            throws IOException {
        long pollIntervalMs = 100;
        long waited = 0;

        while (waited < maxWaitMs) {
            if (isExtractionComplete(destination)) {
                LOG.debug("extraction completed by another process: {}", destination);
                return;
            }
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for extraction to complete", e);
            }
            waited += pollIntervalMs;
        }

        throw new IOException("timed out waiting for extraction to complete: " + destination);
    }

    /**
     * Some providers report a losing directory rename as a generic
     * {@link FileSystemException}, rather than as {@link FileAlreadyExistsException} or
     * {@link DirectoryNotEmptyException}. Treat it as a successful concurrent extraction only
     * when the destination's completion marker proves that another process won the race.
     */
    private static synchronized void handleGenericAtomicMoveFailure(
            Path destination, FileSystemException exception) throws IOException {
        try (FileChannel channel = openLockChannel(destination);
                FileLock ignored = acquireFileLock(channel)) {
            // A stale-destination recovery can move a completed destination out of the way
            // briefly. Synchronizing with its stable lock gives that recovery path time to
            // finish without relying on an arbitrary polling deadline.
            handleGenericMoveFailure(destination, exception);
        } catch (IOException recoveryFailure) {
            if (recoveryFailure != exception) {
                exception.addSuppressed(recoveryFailure);
            }
            throw exception;
        }
    }

    private static void handleGenericMoveFailure(Path destination, FileSystemException exception)
            throws IOException {
        if (isExtractionComplete(destination)) {
            LOG.debug("plugin already extracted by another process: {}", destination);
            return;
        }
        throw exception;
    }

    private static Path getDestination(Path source) {
        String fName = source.getFileName().toString();
        fName = fName.substring(0, fName.length() - 4);
        if (fName.isEmpty() || fName.equals(".") || fName.equals("..") ||
                fName.endsWith(".") || fName.endsWith(" ")) {
            throw new IllegalArgumentException("unsafe plugin archive name: " + source);
        }

        Path parent = source.toAbsolutePath().normalize().getParent();
        Path destination = parent.resolve(fName).normalize();
        if (!parent.equals(destination.getParent())) {
            throw new IllegalArgumentException(
                    "plugin destination must be an immediate child of its root: " + source);
        }
        return destination;
    }

    private static void deleteRecursively(Path path) {
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            LOG.warn("failed to delete temp file: {}", p, e);
                        }
                    });
        } catch (IOException | UncheckedIOException e) {
            LOG.warn("failed to clean up temp directory: {}", path, e);
        }
    }
}
