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
package org.apache.tika.utils;

import java.awt.image.BufferedImage;

import io.github.wmetcalf.rosettasquint.hash.AverageHash;
import io.github.wmetcalf.rosettasquint.hash.ColorHash;
import io.github.wmetcalf.rosettasquint.hash.DHash;
import io.github.wmetcalf.rosettasquint.hash.PHash;

import org.apache.tika.metadata.ImageHash;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Property;

/**
 * Computes perceptual image hashes (phash, dhash, ahash, colorhash) from a
 * {@link BufferedImage} and sets them on a {@link Metadata} instance.
 *
 * <p>Implementation is a thin wrapper over
 * <a href="https://github.com/wmetcalf/rosetta-squint">rosetta-squint</a>,
 * a port of Python {@code imagehash} 4.3.2 + its color-hash extension whose
 * hex output matches byte-for-byte across Python / Java / Rust / Go / JS /
 * Swift. The earlier in-tree DCT/Lanczos implementation produced
 * imagehash-compatible-ish values; switching to the published library gives
 * us a single source of truth that other languages can cross-check against.
 *
 * <p>This class is in tika-core so parser modules outside
 * tika-parser-image-module (e.g. tika-parser-microsoft-module,
 * tika-parser-xml-module) can call it for vector/container formats that
 * are rasterized at parse time (EMF, WMF, SVG, HEIF).
 */
public final class ImageHashUtils {

    /** Default {@code hash_size}: 8 produces 64-bit (16-hex-char) hashes,
     *  matching the Python {@code imagehash} default. */
    private static final int HASH_SIZE = 8;
    /** Default {@code binbits} for colorhash — matches Python
     *  {@code imagehash.colorhash(binbits=4)}. */
    private static final int COLOR_HASH_BINBITS = 4;

    private ImageHashUtils() {
    }

    /**
     * Compute all four hashes for {@code image} and set them on
     * {@code metadata}. All exceptions are swallowed so that a hash
     * failure on one algorithm never aborts a parse or blocks the
     * others.
     */
    public static void setHashes(BufferedImage image, Metadata metadata) {
        if (image == null || metadata == null) {
            return;
        }
        trySet(metadata, ImageHash.PHASH,     () -> computePhash(image));
        trySet(metadata, ImageHash.DHASH,     () -> computeDhash(image));
        trySet(metadata, ImageHash.AHASH,     () -> computeAhash(image));
        trySet(metadata, ImageHash.COLORHASH, () -> computeColorHash(image));
    }

    /** Perceptual hash (DCT-based). Discriminates well; slowest of the four. */
    public static String computePhash(BufferedImage img) {
        return PHash.compute(img, HASH_SIZE).toString();
    }

    /** Difference hash. Fast, strong discriminator — good for spotting
     *  small modifications (re-saves, crops, watermarks). */
    public static String computeDhash(BufferedImage img) {
        return DHash.compute(img, HASH_SIZE).toString();
    }

    /** Average hash. Cheapest; weakest discriminator. Useful as a coarse
     *  pre-filter or for blank/uniform-image detection. */
    public static String computeAhash(BufferedImage img) {
        return AverageHash.compute(img, HASH_SIZE).toString();
    }

    /** Color-histogram hash. Orthogonal to the structural hashes above —
     *  catches color-palette differences pHash/dHash/aHash miss. */
    public static String computeColorHash(BufferedImage src) {
        return ColorHash.compute(src, COLOR_HASH_BINBITS).toString();
    }

    @FunctionalInterface
    private interface HashSupplier {
        String get();
    }

    private static void trySet(Metadata md, Property key, HashSupplier sup) {
        try {
            md.set(key, sup.get());
        } catch (Exception ignored) {
            // non-fatal — a hash failure on one algo never aborts the parse
            // or blocks the others.
        }
    }
}
