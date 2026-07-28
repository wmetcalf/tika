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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ThreadSafeUnzipperTest {

    private static final String MARKER = ".tika-extraction-complete";

    /**
     * Regression: a stale destination directory left over from a previous
     * extraction (killed mid-stream, marker removed, etc.) used to wedge the
     * unzipper into a permanent DirectoryNotEmptyException loop. The fix
     * detects the no-marker case and rebuilds.
     */
    @Test
    public void testStaleDestinationIsRebuilt(@TempDir Path tmp) throws Exception {
        Path zip = writeTrivialZip(tmp.resolve("plugin.zip"));
        Path destination = tmp.resolve("plugin");

        // Simulate a half-extracted state: destination exists with some files
        // but no completion marker.
        Files.createDirectories(destination);
        Files.writeString(destination.resolve("leftover.txt"), "stale");
        assertFalse(Files.exists(destination.resolve(MARKER)),
                "precondition: no completion marker");

        ThreadSafeUnzipper.unzipPlugin(zip);

        // After unzip, the leftover file must be gone (destination rebuilt)
        // and the marker must be present (extraction completed).
        assertFalse(Files.exists(destination.resolve("leftover.txt")),
                "stale file should be removed when destination is rebuilt");
        assertTrue(Files.exists(destination.resolve(MARKER)),
                "completion marker should be present after rebuild");
        assertTrue(Files.exists(destination.resolve("inside.txt")),
                "actual zip contents should be extracted");
    }

    /**
     * Happy path: when the destination already has the completion marker,
     * extraction is a no-op (does not touch the marker or contents).
     */
    @Test
    public void testCompletedDestinationIsLeftAlone(@TempDir Path tmp) throws Exception {
        Path zip = writeTrivialZip(tmp.resolve("plugin.zip"));
        Path destination = tmp.resolve("plugin");

        // Pre-populate as if a previous extraction completed successfully.
        Files.createDirectories(destination);
        Files.writeString(destination.resolve(MARKER), "");
        Files.writeString(destination.resolve("already-here.txt"), "untouched");

        ThreadSafeUnzipper.unzipPlugin(zip);

        assertTrue(Files.exists(destination.resolve("already-here.txt")),
                "no-op extraction should not touch existing contents");
        assertTrue(Files.exists(destination.resolve(MARKER)),
                "marker should still be present");
        assertFalse(Files.exists(destination.resolve("inside.txt")),
                "no-op extraction should NOT extract zip contents over the existing dir");
    }

    @Test
    public void testConcurrentExtraction(@TempDir Path tmp) throws Exception {
        runConcurrentExtractions(tmp, false);
    }

    @Test
    public void testConcurrentStaleDestinationCleanup(@TempDir Path tmp) throws Exception {
        runConcurrentExtractions(tmp, true);
    }

    @Test
    public void testNonAtomicFallbackPublishesMarkerLast(@TempDir Path tmp) throws Exception {
        Path zip = writeTrivialZip(tmp.resolve("plugin.zip"));
        Path destination = tmp.resolve("plugin");
        AtomicBoolean nonAtomicMoveObserved = new AtomicBoolean();

        ThreadSafeUnzipper.unzipPlugin(zip, (source, target, options) -> {
            if (options.length > 0 && options[0] == StandardCopyOption.ATOMIC_MOVE) {
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(),
                        "simulated");
            }
            assertFalse(Files.exists(source.resolve(MARKER)),
                    "completion marker must not be exposed by a non-atomic move");
            nonAtomicMoveObserved.set(true);
            Files.move(source, target, options);
        });

        assertTrue(nonAtomicMoveObserved.get());
        assertTrue(Files.exists(destination.resolve("inside.txt")));
        assertTrue(Files.exists(destination.resolve(MARKER)));
    }

    @Test
    public void testPartialNonAtomicMoveIsNotAccepted(@TempDir Path tmp) throws Exception {
        Path zip = writeTrivialZip(tmp.resolve("plugin.zip"));
        Path destination = tmp.resolve("plugin");
        AtomicInteger regularMoveAttempts = new AtomicInteger();
        ThreadSafeUnzipper.MoveOperation nonAtomicMover = (source, target, options) -> {
            if (options.length > 0 && options[0] == StandardCopyOption.ATOMIC_MOVE) {
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(),
                        "simulated");
            }
            if (regularMoveAttempts.incrementAndGet() == 1) {
                Files.createDirectories(target);
                Files.copy(source.resolve("inside.txt"), target.resolve("inside.txt"));
                throw new FileSystemException(source.toString(), target.toString(),
                        "simulated partial move");
            }
            Files.move(source, target, options);
        };

        assertThrows(FileSystemException.class,
                () -> ThreadSafeUnzipper.unzipPlugin(zip, nonAtomicMover));

        assertFalse(Files.exists(destination.resolve(MARKER)),
                "a failed partial move must not appear complete");
        assertTrue(Files.exists(ThreadSafeUnzipper.getLockPath(destination)),
                "stable publisher lock file should remain for crash-safe reuse");

        ThreadSafeUnzipper.unzipPlugin(zip, nonAtomicMover);
        assertTrue(Files.exists(destination.resolve(MARKER)));
        assertTrue(Files.exists(destination.resolve("inside.txt")));
        assertTrue(regularMoveAttempts.get() >= 2);
    }

    @Test
    public void testGenericMoveRaceSynchronizesWithRecoveryLock(@TempDir Path tmp)
            throws Exception {
        Path zip = writeTrivialZip(tmp.resolve("plugin.zip"));
        Path destination = tmp.resolve("plugin");
        Path lockPath = ThreadSafeUnzipper.getLockPath(destination);
        AtomicBoolean injectedRace = new AtomicBoolean();
        CountDownLatch moveFailed = new CountDownLatch(1);

        ThreadSafeUnzipper.MoveOperation racingMover = (source, target, options) -> {
            if (source.getFileName().toString().contains(".tmp.")
                    && injectedRace.compareAndSet(false, true)) {
                moveFailed.countDown();
                throw new FileSystemException(source.toString(), target.toString(),
                        "simulated transient losing rename");
            }
            Files.move(source, target, options);
        };

        Files.createDirectories(lockPath.getParent());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock heldLock = channel.lock()) {
            Future<?> extraction = executor.submit(() -> {
                ThreadSafeUnzipper.unzipPlugin(zip, racingMover);
                return null;
            });

            assertTrue(moveFailed.await(5, TimeUnit.SECONDS),
                    "simulated generic move failure was not reached");
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> extraction.get(1200, TimeUnit.MILLISECONDS),
                    "generic move recovery must wait for the stable recovery lock");

            Files.createDirectories(destination);
            Files.writeString(destination.resolve("inside.txt"), "winner");
            Files.createFile(destination.resolve(MARKER));
            heldLock.release();
            extraction.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertTrue(Files.exists(destination.resolve(MARKER)));
        assertTrue(Files.exists(destination.resolve("inside.txt")));
    }

    @Test
    public void testAbandonedPublisherLockDoesNotWedge(@TempDir Path tmp) throws Exception {
        Path zip = writeTrivialZip(tmp.resolve("plugin.zip"));
        Path destination = tmp.resolve("plugin");
        Path lockPath = ThreadSafeUnzipper.getLockPath(destination);
        Files.createDirectories(destination);
        Files.writeString(destination.resolve("partial.txt"), "partial");
        Files.createDirectories(lockPath.getParent());

        // A process crash releases its OS lock but leaves the stable lock file behind.
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
            assertTrue(ignored.isValid());
        }
        assertTrue(Files.exists(lockPath));

        ThreadSafeUnzipper.unzipPlugin(zip);

        assertFalse(Files.exists(destination.resolve("partial.txt")));
        assertTrue(Files.exists(destination.resolve("inside.txt")));
        assertTrue(Files.exists(destination.resolve(MARKER)));
    }

    @Test
    public void testSameJvmOverlappingLockWaits(@TempDir Path tmp) throws Exception {
        Path zip = writeTrivialZip(tmp.resolve("plugin.zip"));
        Path destination = tmp.resolve("plugin");
        Path lockPath = ThreadSafeUnzipper.getLockPath(destination);
        Files.createDirectories(destination);
        Files.writeString(destination.resolve("partial.txt"), "partial");
        Files.createDirectories(lockPath.getParent());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)) {
            FileLock heldLock = channel.lock();
            Future<?> extraction = executor.submit(() -> {
                started.countDown();
                ThreadSafeUnzipper.unzipPlugin(zip);
                return null;
            });
            try {
                started.await();
                Thread.sleep(200);
                assertFalse(extraction.isDone(),
                        "same-JVM overlapping lock should wait instead of failing");
            } finally {
                heldLock.release();
            }
            extraction.get();
        } finally {
            executor.shutdownNow();
        }

        assertFalse(Files.exists(destination.resolve("partial.txt")));
        assertTrue(Files.exists(destination.resolve("inside.txt")));
        assertTrue(Files.exists(destination.resolve(MARKER)));
    }

    @Test
    public void testLockNamespaceDoesNotAliasPluginDestination(@TempDir Path tmp)
            throws Exception {
        Path firstZip = writeTrivialZip(tmp.resolve("plugin.zip"));
        Path pairedZip = writeTrivialZip(tmp.resolve("plugin.tika-extraction.lock.zip"));
        ThreadSafeUnzipper.MoveOperation nonAtomicMover = (source, target, options) -> {
            if (options.length > 0 && options[0] == StandardCopyOption.ATOMIC_MOVE) {
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(),
                        "simulated");
            }
            Files.move(source, target, options);
        };

        ThreadSafeUnzipper.unzipPlugin(firstZip, nonAtomicMover);
        ThreadSafeUnzipper.unzipPlugin(pairedZip, nonAtomicMover);

        assertTrue(Files.exists(tmp.resolve("plugin").resolve(MARKER)));
        assertTrue(Files.exists(
                tmp.resolve("plugin.tika-extraction.lock").resolve(MARKER)));
        assertTrue(Files.isDirectory(tmp.resolve("tika_lck")));
    }

    @Test
    public void testReservedLockNamespaceIsRejected(@TempDir Path tmp) throws Exception {
        Path zip = writeTrivialZip(tmp.resolve("tika_lck.zip"));

        assertThrows(IllegalArgumentException.class,
                () -> ThreadSafeUnzipper.unzipPlugin(zip));
    }

    @Test
    public void testExistingAliasOfLockDirectoryIsRejected(@TempDir Path tmp) throws Exception {
        Path lockDirectory = Files.createDirectories(tmp.resolve("tika_lck"));
        Path alias = tmp.resolve("lock-alias");
        try {
            Files.createSymbolicLink(alias, lockDirectory.getFileName());
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "symbolic links are unavailable: " + e.getMessage());
        }
        Path zip = writeTrivialZip(tmp.resolve("lock-alias.zip"));

        assertThrows(IllegalArgumentException.class,
                () -> ThreadSafeUnzipper.unzipPlugin(zip));
        assertTrue(Files.isSameFile(alias, lockDirectory));
    }

    @Test
    public void testUnsafeArchiveStemsAreRejectedBeforeMutation(@TempDir Path tmp)
            throws Exception {
        Path rootSentinel = Files.writeString(tmp.resolve("root-sentinel.txt"), "root");
        Path parentSentinel = Files.writeString(tmp.getParent().resolve(
                "parent-sentinel-" + tmp.getFileName() + ".txt"), "parent");
        try {
            for (String name : List.of(".zip", "..zip", "...zip", "plugin..zip",
                    "plugin .zip", "tika_lck..zip")) {
                Path zip = writeTrivialZip(tmp.resolve(name));
                assertThrows(IllegalArgumentException.class,
                        () -> ThreadSafeUnzipper.unzipPlugin(zip), name);
            }

            assertTrue(Files.exists(rootSentinel));
            assertTrue(Files.exists(parentSentinel));
            assertTrue(Files.isDirectory(tmp));
            assertFalse(Files.exists(tmp.resolve("tika_lck")));
        } finally {
            Files.deleteIfExists(parentSentinel);
        }
    }

    private static void runConcurrentExtractions(Path tmp, boolean createStaleDestination)
            throws Exception {
        int concurrency = 8;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        try {
            for (int iteration = 0; iteration < 20; iteration++) {
                Path zip = writeTrivialZip(tmp.resolve("plugin-" + iteration + ".zip"));
                Path destination = tmp.resolve("plugin-" + iteration);
                if (createStaleDestination) {
                    Files.createDirectories(destination);
                    Files.writeString(destination.resolve("leftover.txt"), "stale");
                }
                CountDownLatch ready = new CountDownLatch(concurrency);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();

                for (int i = 0; i < concurrency; i++) {
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        ThreadSafeUnzipper.unzipPlugin(zip);
                        return null;
                    }));
                }

                ready.await();
                start.countDown();
                for (Future<?> future : futures) {
                    future.get();
                }

                assertTrue(Files.exists(destination.resolve(MARKER)));
                assertTrue(Files.exists(destination.resolve("inside.txt")));
                assertFalse(Files.exists(destination.resolve("leftover.txt")));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static Path writeTrivialZip(Path zipPath) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            zos.putNextEntry(new ZipEntry("inside.txt"));
            zos.write("hello".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return zipPath;
    }
}
