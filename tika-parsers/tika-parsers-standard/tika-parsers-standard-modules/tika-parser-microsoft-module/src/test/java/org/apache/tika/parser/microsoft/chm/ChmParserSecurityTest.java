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
package org.apache.tika.parser.microsoft.chm;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.TikaTest;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.metadata.writefilter.MetadataWriteLimiterFactory;
import org.apache.tika.metadata.writefilter.StandardMetadataLimiterFactory;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

public class ChmParserSecurityTest extends TikaTest {

    private static final String CHM_FIXTURE = "/test-documents/testChm.chm";

    @Test
    public void testEmbeddedSecurityExceptionPropagates() {
        SecurityException failure =
                new SecurityException("simulated CHM embedded security boundary");

        SecurityException thrown = assertThrows(SecurityException.class,
                () -> parseWithEmbeddedException(failure));

        assertSame(failure, thrown);
    }

    @Test
    public void testEmbeddedWriteLimitPropagates() {
        WriteLimitReachedException failure = new WriteLimitReachedException(7);

        WriteLimitReachedException thrown = assertThrows(WriteLimitReachedException.class,
                () -> parseWithEmbeddedException(failure));

        assertSame(failure, thrown);
    }

    @Test
    public void testOrdinaryEmbeddedFailureMarksAnalysisIncomplete() throws Exception {
        Metadata metadata = parseWithEmbeddedException(
                new IOException("simulated ordinary CHM embedded failure"));

        assertTrue(Arrays.stream(metadata.getValues(
                        TikaCoreProperties.TIKA_META_EXCEPTION_WARNING))
                .anyMatch(value -> value.startsWith("CHM entry analysis incomplete") &&
                        value.endsWith("IOException")));
        assertNotNull(metadata.get("ExploitClass"));
    }

    @Test
    public void testEmbeddedMetadataUsesContextLimiter() throws Exception {
        ParseContext context = new ParseContext();
        StandardMetadataLimiterFactory factory = new StandardMetadataLimiterFactory();
        factory.setIncludeFields(Set.of("allowed"));
        context.set(MetadataWriteLimiterFactory.class, factory);
        AtomicBoolean limiterApplied = new AtomicBoolean();
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                metadata.set("not-allowed", "probe");
                limiterApplied.set(metadata.get("not-allowed") == null);
                return false;
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata metadata, ParseContext parseContext,
                                      boolean outputHtml) {
                throw new AssertionError("embedded parsing should be disabled");
            }
        });

        parse(context);

        assertTrue(limiterApplied.get(),
                "fork-created CHM embedded metadata must inherit the context limiter");
    }

    private Metadata parseWithEmbeddedException(Exception failure) throws Exception {
        ParseContext context = new ParseContext();
        AtomicBoolean pendingFailure = new AtomicBoolean(true);
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                return pendingFailure.get();
            }

            @Override
            public void parseEmbedded(TikaInputStream stream, ContentHandler handler,
                                      Metadata metadata, ParseContext parseContext,
                                      boolean outputHtml) throws IOException, SAXException {
                if (!pendingFailure.compareAndSet(true, false)) {
                    throw new AssertionError("test failure must be thrown exactly once");
                }
                if (failure instanceof IOException ioException) {
                    throw ioException;
                }
                if (failure instanceof SAXException saxException) {
                    throw saxException;
                }
                if (failure instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new AssertionError("unsupported test exception", failure);
            }
        });
        return parse(context);
    }

    private Metadata parse(ParseContext context) throws Exception {
        Metadata metadata = new Metadata();
        try (TikaInputStream stream = getResourceAsStream(CHM_FIXTURE)) {
            new ChmParser().parse(
                    stream, new BodyContentHandler(-1), metadata, context);
        }
        return metadata;
    }
}
