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
package org.apache.tika.parser.ocr;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.Locale;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.apache.tika.io.TemporaryResources;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ToXMLContentHandler;

public class TesseractOCRResourceSafetyTest {

    @TempDir
    private Path tempDir;

    @Test
    public void testHashSkipsIoBeforeFileSizeEligibility() throws Exception {
        TesseractOCRConfig config = new TesseractOCRConfig();
        config.setMaxFileSizeToOcr(100);

        assertNull(TesseractOCRParser.sha256HexIfEligible(
                tempDir.resolve("does-not-exist"), 101, config));

        Path eligible = tempDir.resolve("eligible.bin");
        Files.writeString(eligible, "abc", UTF_8);
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                TesseractOCRParser.sha256HexIfEligible(eligible, 3, config));
    }

    @Test
    public void testCacheTextPopulationIsCappedWhileStreaming() throws Exception {
        Path output = tempDir.resolve("output.txt");
        Files.writeString(output, "12345", UTF_8);

        assertEquals("12345", TesseractOCRParser.readCacheableUtf8(output, 5));
        assertNull(TesseractOCRParser.readCacheableUtf8(output, 4));
        assertEquals("12345",
                TesseractOCRParser.readCacheableUtf8(output, Integer.MAX_VALUE));
    }

    @Test
    public void testCacheTextPopulationPreservesWhitespaceExactly()
            throws Exception {
        Path output = tempDir.resolve("whitespace.txt");
        String expected = "\n  cached OCR output  \n";
        Files.writeString(output, expected, UTF_8);

        assertEquals(expected,
                TesseractOCRParser.readCacheableUtf8(output, 1024));
    }

    @Test
    public void testCacheIdentityIncludesOcrConfiguration() {
        String imageHash = "image-hash";
        TesseractOCRConfig parserConfig = new TesseractOCRConfig();
        TesseractOCRConfig defaultConfig = new TesseractOCRConfig();
        String englishKey =
                TesseractOCRParser.ocrCacheKey(imageHash, parserConfig, defaultConfig);

        parserConfig.setLanguage("fra");
        String frenchKey =
                TesseractOCRParser.ocrCacheKey(imageHash, parserConfig, defaultConfig);

        assertNotEquals(englishKey, frenchKey,
                "the same bytes OCR'd with different language models must not alias");
    }

    @Test
    public void testCacheIdentityRejectsIncompatibleOutputModes() {
        TesseractOCRConfig parserConfig = new TesseractOCRConfig();
        TesseractOCRConfig defaultConfig = new TesseractOCRConfig();

        parserConfig.setPageSegMode("0");
        assertNull(TesseractOCRParser.ocrCacheKey(
                "image-hash", parserConfig, defaultConfig));

        parserConfig.setPageSegMode("1");
        parserConfig.setOutputType(TesseractOCRConfig.OUTPUT_TYPE.HOCR);
        assertNull(TesseractOCRParser.ocrCacheKey(
                "image-hash", parserConfig, defaultConfig));
    }

    @Test
    public void testSuccessfulOutputIsCachedBeforeCleanup()
            throws Exception {
        Path output = tempDir.resolve("successful-output.txt");
        String expected = "successful OCR output";
        Files.writeString(output, expected, UTF_8);
        OcrResultCache cache = new OcrResultCache(0, false);

        TesseractOCRParser.cacheAndDeleteOcrOutput(
                output.toFile(), cache, "image-hash");

        assertEquals(expected, cache.get("image-hash"));
        assertFalse(Files.exists(output));
    }

    @Test
    public void testCacheHitReplaysNormalOcrXhtmlStructure() throws Exception {
        Path image = writeImage("cache-hit.png", 10, 10);
        TesseractOCRConfig config = new TesseractOCRConfig();
        OcrResultCache cache = new OcrResultCache(0, false);
        String imageHash = TesseractOCRParser.sha256HexIfEligible(
                image, Files.size(image), config);
        String cacheKey = TesseractOCRParser.ocrCacheKey(
                imageHash, config, new TesseractOCRConfig());
        String cachedText = "\n  cached OCR output  \n";
        assertTrue(cache.putIfWithinBudget(cacheKey, cachedText));

        ParseContext context = new ParseContext();
        context.set(TesseractOCRConfig.class, config);
        context.set(OcrResultCache.class, cache);
        TesseractOCRParser parser = new TesseractOCRParser();
        Field hasTesseract =
                TesseractOCRParser.class.getDeclaredField("hasTesseract");
        hasTesseract.setAccessible(true);
        hasTesseract.set(parser, true);
        ToXMLContentHandler output = new ToXMLContentHandler();

        try (TikaInputStream input = TikaInputStream.get(image)) {
            parser.parse(input, output, new Metadata(), context);
        }

        assertTrue(output.toString().contains(
                "<div class=\"ocr\">" + cachedText + "</div>"));
    }

    @Test
    public void testZeroDisablesDownscalingAndBorrowsSafeInput() throws Exception {
        Path image = writeImage("disabled.png", 300, 180);
        try (TemporaryResources tmp = new TemporaryResources();
             TikaInputStream original = TikaInputStream.get(image);
             TesseractOCRParser.PreparedOcrInput prepared =
                     TesseractOCRParser.prepareOcrInput(original, tmp, 0, new Metadata())) {
            assertSame(original, prepared.stream());
            assertFalse(prepared.isSkipped());
        }
    }

    @Test
    public void testZeroStillRejectsOversizedDeclaredImageBeforeRasterDecode()
            throws Exception {
        Path image = tempDir.resolve("oversized-without-downscaling.png");
        Files.write(image, pngWithDimensions(200_000, 200_000));
        Metadata metadata = new Metadata();

        try (TemporaryResources tmp = new TemporaryResources();
             TikaInputStream original = TikaInputStream.get(image);
             TesseractOCRParser.PreparedOcrInput prepared =
                     TesseractOCRParser.prepareOcrInput(original, tmp, 0, metadata)) {
            assertTrue(prepared.isSkipped());
            assertNull(prepared.stream());
        }

        assertEquals("image_safety_limit",
                metadata.get("X-Tika-OCR-Skipped-Reason"));
        assertNotNull(metadata.get(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING));
        assertNotNull(metadata.get("ExploitClass"));
    }

    @Test
    public void testCustomPositiveMaxDimensionIsHonored() throws Exception {
        Path image = writeImage("custom.png", 300, 180);
        try (TemporaryResources tmp = new TemporaryResources();
             TikaInputStream original = TikaInputStream.get(image);
             TesseractOCRParser.PreparedOcrInput prepared =
                     TesseractOCRParser.prepareOcrInput(original, tmp, 100, new Metadata())) {
            assertNotSame(original, prepared.stream());
            BufferedImage scaled = ImageIO.read(prepared.stream().getPath().toFile());
            assertEquals(100, scaled.getWidth());
            assertEquals(60, scaled.getHeight());
        }
    }

    @Test
    public void testOversizedDeclaredImageIsRejectedBeforeRasterDecode() throws Exception {
        Path image = tempDir.resolve("oversized.png");
        Files.write(image, pngWithDimensions(200_000, 200_000));
        Metadata metadata = new Metadata();

        try (TemporaryResources tmp = new TemporaryResources();
             TikaInputStream original = TikaInputStream.get(image);
             TesseractOCRParser.PreparedOcrInput prepared =
                     TesseractOCRParser.prepareOcrInput(original, tmp, 2000, metadata)) {
            assertTrue(prepared.isSkipped());
            assertNull(prepared.stream());
        }

        assertEquals("image_safety_limit", metadata.get("X-Tika-OCR-Skipped-Reason"));
        assertEquals("true", metadata.get(TikaCoreProperties.TRUNCATED_METADATA));
        assertNotNull(metadata.get(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING));
        assertEquals("OCR analysis incomplete; image text may not have been analyzed",
                metadata.get("ExploitClass"));
    }

    @Test
    public void testUnknownImageFormatIsSkippedAndSignaled() throws Exception {
        Path image = tempDir.resolve("unknown-image.bin");
        Files.writeString(image, "not an ImageIO format", UTF_8);
        Metadata metadata = new Metadata();

        try (TemporaryResources tmp = new TemporaryResources();
             TikaInputStream original = TikaInputStream.get(image);
             TesseractOCRParser.PreparedOcrInput prepared =
                     TesseractOCRParser.prepareOcrInput(
                             original, tmp, 2000, metadata)) {
            assertTrue(prepared.isSkipped());
            assertNull(prepared.stream());
        }

        assertEquals("image_safety_limit",
                metadata.get("X-Tika-OCR-Skipped-Reason"));
        assertNotNull(metadata.get(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING));
        assertNotNull(metadata.get("ExploitClass"));
    }

    @Test
    public void testOversizedLaterImageFrameIsRejected()
            throws Exception {
        Path image = tempDir.resolve("multiframe-reader.img");
        Files.write(image, new byte[]{0x7d});
        MultiFrameImageReaderSpi provider =
                new MultiFrameImageReaderSpi();
        IIORegistry registry = IIORegistry.getDefaultInstance();
        registry.registerServiceProvider(provider);
        Metadata metadata = new Metadata();
        try {
            try (TemporaryResources tmp = new TemporaryResources();
                 TikaInputStream original = TikaInputStream.get(image);
                 TesseractOCRParser.PreparedOcrInput prepared =
                         TesseractOCRParser.prepareOcrInput(
                                 original, tmp, 2000, metadata)) {
                assertTrue(prepared.isSkipped());
                assertNull(prepared.stream());
            }
        } finally {
            registry.deregisterServiceProvider(provider);
        }

        assertEquals("image_safety_limit",
                metadata.get("X-Tika-OCR-Skipped-Reason"));
        assertNotNull(metadata.get(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING));
        assertNotNull(metadata.get("ExploitClass"));
    }

    @Test
    public void testUncheckedImageReaderFailureIsSkippedAndSignaled()
            throws Exception {
        Path image = tempDir.resolve("runtime-reader.img");
        Files.writeString(image, "reader-specific input", UTF_8);
        ThrowingImageReaderSpi provider = new ThrowingImageReaderSpi();
        IIORegistry registry = IIORegistry.getDefaultInstance();
        registry.registerServiceProvider(provider);
        Metadata metadata = new Metadata();
        try {
            try (TemporaryResources tmp = new TemporaryResources();
                 TikaInputStream original = TikaInputStream.get(image);
                 TesseractOCRParser.PreparedOcrInput prepared =
                         assertDoesNotThrow(
                                 () -> TesseractOCRParser.prepareOcrInput(
                                         original, tmp, 2000, metadata))) {
                assertTrue(prepared.isSkipped());
                assertNull(prepared.stream());
            }
        } finally {
            registry.deregisterServiceProvider(provider);
        }

        assertEquals("image_safety_limit",
                metadata.get("X-Tika-OCR-Skipped-Reason"));
        assertNotNull(metadata.get(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING));
    }

    @Test
    public void testUncheckedImageReaderCleanupFailureIsSkippedAndSignaled()
            throws Exception {
        Path image = tempDir.resolve("cleanup-reader.img");
        Files.write(image, new byte[]{0x7e});
        CleanupThrowingImageReaderSpi provider =
                new CleanupThrowingImageReaderSpi(
                        new IllegalStateException(
                                "simulated image reader cleanup failure"));
        IIORegistry registry = IIORegistry.getDefaultInstance();
        registry.registerServiceProvider(provider);
        Metadata metadata = new Metadata();
        try {
            try (TemporaryResources tmp = new TemporaryResources();
                 TikaInputStream original = TikaInputStream.get(image);
                 TesseractOCRParser.PreparedOcrInput prepared =
                         assertDoesNotThrow(
                                 () -> TesseractOCRParser.prepareOcrInput(
                                         original, tmp, 2000, metadata))) {
                assertTrue(prepared.isSkipped());
                assertNull(prepared.stream());
            }
        } finally {
            registry.deregisterServiceProvider(provider);
        }

        assertEquals("image_safety_limit",
                metadata.get("X-Tika-OCR-Skipped-Reason"));
        assertNotNull(metadata.get(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING));
        assertNotNull(metadata.get("ExploitClass"));
    }

    @Test
    public void testImageReaderCleanupSecurityExceptionPropagates()
            throws Exception {
        Path image = tempDir.resolve("cleanup-security-reader.img");
        Files.write(image, new byte[]{0x7e});
        SecurityException denial =
                new SecurityException(
                        "simulated image reader cleanup policy denial");
        CleanupThrowingImageReaderSpi provider =
                new CleanupThrowingImageReaderSpi(denial);
        IIORegistry registry = IIORegistry.getDefaultInstance();
        registry.registerServiceProvider(provider);
        try {
            try (TemporaryResources tmp = new TemporaryResources();
                 TikaInputStream original = TikaInputStream.get(image)) {
                SecurityException thrown =
                        assertThrows(SecurityException.class,
                                () -> TesseractOCRParser.prepareOcrInput(
                                        original, tmp, 2000,
                                        new Metadata()));
                assertSame(denial, thrown);
            }
        } finally {
            registry.deregisterServiceProvider(provider);
        }
    }

    @Test
    public void testSubsamplingKeepsIntermediateWithinPixelBudget() {
        int subsampling =
                TesseractOCRParser.calculateSourceSubsampling(6000, 4000, 4_000_000, 1000);

        assertTrue(subsampling > 1);
        long decodedPixels =
                (long) divideCeiling(6000, subsampling) * divideCeiling(4000, subsampling);
        assertTrue(decodedPixels <= 4_000_000);
    }

    @Test
    public void testPreparedReplacementOwnsAndClosesStream() throws Exception {
        Path image = writeImage("owned.png", 300, 180);
        try (TemporaryResources tmp = new TemporaryResources();
             TikaInputStream original = TikaInputStream.get(image)) {
            TesseractOCRParser.PreparedOcrInput prepared =
                    TesseractOCRParser.prepareOcrInput(original, tmp, 100, new Metadata());
            TikaInputStream replacement = prepared.stream();
            assertNotSame(original, replacement);
            assertTrue(replacement.read() >= 0);

            prepared.close();

            assertThrows(IOException.class, replacement::read);
        }
    }

    @Test
    public void testCleanupFailureClosesAbandonedOwnedReplacement()
            throws Exception {
        Path replacementPath = tempDir.resolve("abandoned-replacement.bin");
        Files.writeString(replacementPath, "replacement", UTF_8);
        TikaInputStream replacement = TikaInputStream.get(replacementPath);
        TesseractOCRParser.PreparedOcrInput prepared =
                TesseractOCRParser.PreparedOcrInput.owned(replacement);

        TesseractOCRParser.closePreparedAfterCleanupFailure(
                prepared,
                new IllegalStateException(
                        "simulated image reader cleanup failure"));

        assertThrows(IOException.class, replacement::read);
    }

    private Path writeImage(String name, int width, int height) throws IOException {
        Path path = tempDir.resolve(name);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        assertTrue(ImageIO.write(image, "png", path.toFile()));
        return path;
    }

    private static int divideCeiling(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static byte[] pngWithDimensions(int width, int height) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeLong(0x89504e470d0a1a0aL);
            ByteArrayOutputStream ihdrBytes = new ByteArrayOutputStream();
            try (DataOutputStream ihdr = new DataOutputStream(ihdrBytes)) {
                ihdr.writeInt(width);
                ihdr.writeInt(height);
                ihdr.writeByte(8);
                ihdr.writeByte(2);
                ihdr.writeByte(0);
                ihdr.writeByte(0);
                ihdr.writeByte(0);
            }
            writePngChunk(out, "IHDR", ihdrBytes.toByteArray());
            writePngChunk(out, "IEND", new byte[0]);
        }
        return bytes.toByteArray();
    }

    private static void writePngChunk(DataOutputStream out, String type, byte[] data)
            throws IOException {
        byte[] typeBytes = type.getBytes(UTF_8);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeInt(data.length);
        out.write(typeBytes);
        out.write(data);
        out.writeInt((int) crc.getValue());
    }

    private static final class ThrowingImageReaderSpi extends ImageReaderSpi {

        private ThrowingImageReaderSpi() {
            super("Apache Tika", "1.0",
                    new String[]{"runtime-reader"},
                    new String[]{"img"},
                    new String[]{"application/x-runtime-reader"},
                    ThrowingImageReader.class.getName(),
                    new Class<?>[]{ImageInputStream.class},
                    null, false, null, null, null, null,
                    false, null, null, null, null);
        }

        @Override
        public boolean canDecodeInput(Object source) {
            return source instanceof ImageInputStream;
        }

        @Override
        public ImageReader createReaderInstance(Object extension) {
            return new ThrowingImageReader(this);
        }

        @Override
        public String getDescription(Locale locale) {
            return "ImageReader that simulates an unchecked decoder failure";
        }
    }

    private static final class ThrowingImageReader extends ImageReader {

        private ThrowingImageReader(ImageReaderSpi provider) {
            super(provider);
        }

        @Override
        public int getNumImages(boolean allowSearch) {
            return 1;
        }

        @Override
        public int getWidth(int imageIndex) {
            throw new IllegalArgumentException(
                    "simulated malformed image reader failure");
        }

        @Override
        public int getHeight(int imageIndex) {
            return 1;
        }

        @Override
        public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) {
            return Collections.emptyIterator();
        }

        @Override
        public IIOMetadata getStreamMetadata() {
            return null;
        }

        @Override
        public IIOMetadata getImageMetadata(int imageIndex) {
            return null;
        }

        @Override
        public BufferedImage read(int imageIndex, ImageReadParam param) {
            return null;
        }
    }

    private static final class CleanupThrowingImageReaderSpi
            extends ImageReaderSpi {

        private final RuntimeException cleanupFailure;

        private CleanupThrowingImageReaderSpi(
                RuntimeException cleanupFailure) {
            super("Apache Tika", "1.0",
                    new String[]{"cleanup-reader"},
                    new String[]{"img"},
                    new String[]{"application/x-cleanup-reader"},
                    CleanupThrowingImageReader.class.getName(),
                    new Class<?>[]{ImageInputStream.class},
                    null, false, null, null, null, null,
                    false, null, null, null, null);
            this.cleanupFailure = cleanupFailure;
        }

        @Override
        public boolean canDecodeInput(Object source) throws IOException {
            if (!(source instanceof ImageInputStream input)) {
                return false;
            }
            long position = input.getStreamPosition();
            try {
                return input.read() == 0x7e;
            } finally {
                input.seek(position);
            }
        }

        @Override
        public ImageReader createReaderInstance(Object extension) {
            return new CleanupThrowingImageReader(
                    this, cleanupFailure);
        }

        @Override
        public String getDescription(Locale locale) {
            return "ImageReader that simulates cleanup failure";
        }
    }

    private static final class CleanupThrowingImageReader
            extends ImageReader {

        private final RuntimeException cleanupFailure;

        private CleanupThrowingImageReader(
                ImageReaderSpi provider,
                RuntimeException cleanupFailure) {
            super(provider);
            this.cleanupFailure = cleanupFailure;
        }

        @Override
        public int getNumImages(boolean allowSearch) {
            return 1;
        }

        @Override
        public int getWidth(int imageIndex) {
            return 1;
        }

        @Override
        public int getHeight(int imageIndex) {
            return 1;
        }

        @Override
        public ImageTypeSpecifier getRawImageType(int imageIndex) {
            return ImageTypeSpecifier.createFromBufferedImageType(
                    BufferedImage.TYPE_INT_RGB);
        }

        @Override
        public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) {
            return Collections.singleton(
                    getRawImageType(imageIndex)).iterator();
        }

        @Override
        public IIOMetadata getStreamMetadata() {
            return null;
        }

        @Override
        public IIOMetadata getImageMetadata(int imageIndex) {
            return null;
        }

        @Override
        public BufferedImage read(int imageIndex, ImageReadParam param) {
            throw new AssertionError(
                    "safe borrowed input must not be decoded");
        }

        @Override
        public void dispose() {
            throw cleanupFailure;
        }
    }

    private static final class MultiFrameImageReaderSpi
            extends ImageReaderSpi {

        private MultiFrameImageReaderSpi() {
            super("Apache Tika", "1.0",
                    new String[]{"multiframe-reader"},
                    new String[]{"img"},
                    new String[]{"application/x-multiframe-reader"},
                    MultiFrameImageReader.class.getName(),
                    new Class<?>[]{ImageInputStream.class},
                    null, false, null, null, null, null,
                    false, null, null, null, null);
        }

        @Override
        public boolean canDecodeInput(Object source) throws IOException {
            if (!(source instanceof ImageInputStream input)) {
                return false;
            }
            long position = input.getStreamPosition();
            try {
                return input.read() == 0x7d;
            } finally {
                input.seek(position);
            }
        }

        @Override
        public ImageReader createReaderInstance(Object extension) {
            return new MultiFrameImageReader(this);
        }

        @Override
        public String getDescription(Locale locale) {
            return "Two-frame ImageReader with an oversized second frame";
        }
    }

    private static final class MultiFrameImageReader
            extends ImageReader {

        private MultiFrameImageReader(ImageReaderSpi provider) {
            super(provider);
        }

        @Override
        public int getNumImages(boolean allowSearch) {
            return 2;
        }

        @Override
        public int getWidth(int imageIndex) {
            return imageIndex == 0 ? 1 : 200_000;
        }

        @Override
        public int getHeight(int imageIndex) {
            return imageIndex == 0 ? 1 : 200_000;
        }

        @Override
        public ImageTypeSpecifier getRawImageType(int imageIndex) {
            return ImageTypeSpecifier.createFromBufferedImageType(
                    BufferedImage.TYPE_INT_RGB);
        }

        @Override
        public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) {
            return Collections.singleton(
                    getRawImageType(imageIndex)).iterator();
        }

        @Override
        public IIOMetadata getStreamMetadata() {
            return null;
        }

        @Override
        public IIOMetadata getImageMetadata(int imageIndex) {
            return null;
        }

        @Override
        public BufferedImage read(int imageIndex, ImageReadParam param) {
            throw new AssertionError(
                    "unsafe multiframe input must not be decoded");
        }
    }
}
