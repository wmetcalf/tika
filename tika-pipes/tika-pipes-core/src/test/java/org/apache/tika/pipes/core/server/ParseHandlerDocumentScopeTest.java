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
package org.apache.tika.pipes.core.server;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;

import org.apache.tika.detect.Detector;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ParseRecord;
import org.apache.tika.parser.RecursiveParserWrapper;
import org.apache.tika.pipes.api.FetchEmitTuple;
import org.apache.tika.pipes.api.ParseMode;
import org.apache.tika.pipes.api.emitter.EmitKey;
import org.apache.tika.pipes.api.fetcher.FetchKey;
import org.apache.tika.sax.BasicContentHandlerFactory;

/**
 * ParseHandler must claim the document BEFORE preprocessing.
 *
 * <p>{@code preParse} runs detection, and a Detector reached from the ParseContext can write to
 * the ParseRecord. If the parser's own beginDocument is the FIRST claim, it sees an unclaimed
 * depth-zero record, reads it as a new document and resets it -- discarding whatever
 * preprocessing just recorded. Preprocessing also sets SkipContainerDocumentDigest, so the digest
 * half would not run again to regenerate anything.
 *
 * <p>This test exists because the fix previously had NO test that went red when reverted: the
 * unit test named for it exercised ParseRecord directly and never touched ParseHandler, so
 * deleting the claim from this class broke nothing outside the Docker-gated integration suites.
 */
public class ParseHandlerDocumentScopeTest {

    private static final String MARKER = "recorded during preprocessing";

    private ParseHandler handler(Detector preprocessingDetector) {
        AutoDetectParser autoDetect = new AutoDetectParser();
        return new ParseHandler(preprocessingDetector, new ArrayBlockingQueue<>(4),
                new CountDownLatch(0), autoDetect, new RecursiveParserWrapper(autoDetect),
                new BasicContentHandlerFactory(BasicContentHandlerFactory.HANDLER_TYPE.TEXT, -1),
                ParseMode.RMETA);
    }

    /** A detector that records a diagnostic during preprocessing, as a real one may. */
    private Detector recordingDetector() {
        return (stream, metadata, context) -> {
            ParseRecord record = context.get(ParseRecord.class);
            if (record != null) {
                record.addWarning(MARKER);
            }
            return MediaType.OCTET_STREAM;
        };
    }

    private static FetchEmitTuple tuple() {
        return new FetchEmitTuple("test-id", new FetchKey("fs", "test"), new EmitKey("fs", "test"));
    }

    @Test
    public void parseConcatenatedKeepsDiagnosticsRecordedDuringPreprocessing() throws Exception {
        ParseContext context = new ParseContext();
        ParseRecord record = ParseRecord.newInstance(context);
        context.set(ParseRecord.class, record);

        try (TikaInputStream tis = TikaInputStream.get(new byte[] {1, 2, 3})) {
            handler(recordingDetector()).parseConcatenated(tuple(),
                    new BasicContentHandlerFactory(BasicContentHandlerFactory.HANDLER_TYPE.TEXT, -1),
                    tis, new Metadata(), context);
        }

        assertTrue(record.getWarnings().stream().anyMatch(w -> w.startsWith(MARKER)),
                "a diagnostic recorded during preprocessing was discarded: the parser's own "
                        + "beginDocument claimed first, read the record as a new document and "
                        + "reset it. ParseHandler must claim before preParse.");
    }

    @Test
    public void parseRecursiveKeepsDiagnosticsRecordedDuringPreprocessing() throws Exception {
        ParseContext context = new ParseContext();
        ParseRecord record = ParseRecord.newInstance(context);
        context.set(ParseRecord.class, record);

        try (TikaInputStream tis = TikaInputStream.get(new byte[] {1, 2, 3})) {
            handler(recordingDetector()).parseRecursive(tuple(),
                    new BasicContentHandlerFactory(BasicContentHandlerFactory.HANDLER_TYPE.TEXT, -1),
                    tis, new Metadata(), context);
        }

        assertTrue(record.getWarnings().stream().anyMatch(w -> w.startsWith(MARKER)),
                "parseRecursive discarded a diagnostic recorded during preprocessing");
    }
}
