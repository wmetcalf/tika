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
package org.apache.tika.parser.image;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.InputStream;
import java.util.Base64;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.xml.sax.ContentHandler;

import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

/**
 * Regression test for the fork's image-hashing path in {@link AbstractImageParser}.
 * The non-OCR branch of {@code parse()} runs extractMetadata / addImageHashMetadata
 * (which calls {@code ImageIO.read}) / addBarcodeMetadata WITHOUT a try/catch, while
 * the OCR branch wraps the identical calls and rethrows as {@link TikaException}. A
 * malformed/adversarial image whose reader throws an unchecked exception must NOT
 * escape {@code parse()} as a raw {@link RuntimeException} — that violates the Tika
 * parser contract. This reproduces the leak by having extractMetadata throw an
 * unchecked exception (as a real malformed-image reader would deep inside ImageIO).
 */
public class ImageHashContractTest {

    // 1x1 PNG so prepareBarcodePathLookup has a real image to stage before the throw.
    private static final byte[] ONE_PX_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNk"
                    + "+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    /** Non-OCR image parser whose metadata extraction throws unchecked, like a
     *  malformed image tripping an ImageIO reader plugin. */
    static class ThrowingImageParser extends AbstractImageParser {
        @Override
        public Set<MediaType> getSupportedTypes(ParseContext context) {
            return Set.of(MediaType.image("x-contract-test"));
        }

        @Override
        void extractMetadata(InputStream is, ContentHandler contentHandler,
                             Metadata metadata, ParseContext context) {
            throw new ArrayIndexOutOfBoundsException(
                    "simulated malformed-image reader failure");
        }
    }

    @Test
    public void nonOcrBranchWrapsUncheckedAsTikaException() throws Exception {
        ThrowingImageParser parser = new ThrowingImageParser();
        parser.setImageHashingEnabled(true);
        // No Content-Type set -> ocrMediaType is null -> the non-OCR branch is taken.
        Metadata metadata = new Metadata();
        try (TikaInputStream tis = TikaInputStream.get(ONE_PX_PNG)) {
            try {
                parser.parse(tis, new BodyContentHandler(-1), metadata, new ParseContext());
                fail("expected the parser to surface the failure");
            } catch (TikaException e) {
                // contract upheld: unchecked failure wrapped as TikaException
            } catch (RuntimeException e) {
                fail("AbstractImageParser leaked a raw " + e.getClass().getName()
                        + " from the non-OCR image-hashing branch (Tika contract "
                        + "requires TikaException): " + e);
            }
        }
    }
}
