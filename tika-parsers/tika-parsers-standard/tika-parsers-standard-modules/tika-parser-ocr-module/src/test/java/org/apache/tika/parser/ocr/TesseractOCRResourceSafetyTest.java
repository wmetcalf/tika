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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.apache.tika.io.TemporaryResources;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;

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
    public void testZeroDisablesImagePreparationAndBorrowsInput() throws Exception {
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
}
