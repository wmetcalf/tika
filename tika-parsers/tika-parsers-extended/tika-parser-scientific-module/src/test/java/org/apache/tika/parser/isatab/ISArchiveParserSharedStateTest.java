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
package org.apache.tika.parser.isatab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.apache.tika.TikaTest;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

/**
 * {@link ISArchiveParser} resolved its archive directory once and kept it. A parser instance is
 * shared -- DefaultParser holds one and every thread parses through it -- so every document after
 * the first was described using the FIRST document's archive.
 */
public class ISArchiveParserSharedStateTest extends TikaTest {

    private static final String STUDY = "s_BII-S-1.txt";

    /** Copies the shipped archive and rewrites its identifier so the two are distinguishable. */
    private static Path archive(Path parent, String name, String identifier) throws Exception {
        Path src = Path.of(ISArchiveParserSharedStateTest.class
                .getResource("/test-documents/testISATab_BII-I-1/").toURI());
        Path dest = Files.createDirectories(parent.resolve(name));
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes attrs)
                    throws IOException {
                Files.copy(f, dest.resolve(src.relativize(f).toString()));
                return FileVisitResult.CONTINUE;
            }
        });
        Path investigation = dest.resolve("i_investigation.txt");
        Files.writeString(investigation,
                Files.readString(investigation, StandardCharsets.UTF_8)
                        .replace("\"BII-I-1\"", '"' + identifier + '"'),
                StandardCharsets.UTF_8);
        return dest;
    }

    private static Metadata parse(ISArchiveParser parser, Path archiveDir) throws Exception {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, STUDY);
        try (TikaInputStream tis = TikaInputStream.get(archiveDir.resolve(STUDY))) {
            parser.parse(tis, new BodyContentHandler(), metadata, new ParseContext());
        }
        return metadata;
    }

    /**
     * The no-arg parser derives its archive directory from the input. That derived value was
     * stored in an instance field and never reset, so the second document was described from the
     * first document's directory -- wrong investigation and wrong assay files, with no exception.
     */
    @Test
    public void oneParserInstanceDoesNotReuseTheFirstDocumentsArchive(@TempDir Path tmp)
            throws Exception {
        Path first = archive(tmp, "first", "ARCHIVE-A");
        Path second = archive(tmp, "second", "ARCHIVE-B");

        ISArchiveParser parser = new ISArchiveParser();

        assertEquals("ARCHIVE-A", parse(parser, first).get("Investigation Identifier"),
                "the first document should be described by its own archive");
        assertEquals("ARCHIVE-B", parse(parser, second).get("Investigation Identifier"),
                "the second document was described using the FIRST document's archive; the "
                        + "resolved location is living on the shared parser instance");
    }

    /** A directory supplied at construction is configuration and must still be honoured. */
    @Test
    public void anExplicitlyConfiguredDirectoryStillWins(@TempDir Path tmp) throws Exception {
        Path configured = archive(tmp, "configured", "ARCHIVE-CONFIGURED");
        Path other = archive(tmp, "other", "ARCHIVE-OTHER");

        ISArchiveParser parser = new ISArchiveParser(configured.toString());

        assertEquals("ARCHIVE-CONFIGURED", parse(parser, other).get("Investigation Identifier"),
                "the configured directory must override the input's own directory");
    }
}
