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
package org.apache.tika.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.apache.tika.TikaLoaderHelper;
import org.apache.tika.TikaTest;
import org.apache.tika.config.loader.TikaLoader;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;

public class AutoDetectParserConfigTest extends TikaTest {

    @Test
    public void testConfiguringEmbeddedDocExtractor() throws Exception {
        TikaLoader noNamesLoader = TikaLoaderHelper.getLoader("tika-config-no-names.json");
        Parser p = noNamesLoader.loadAutoDetectParser();
        ParseContext noNamesContext = noNamesLoader.loadParseContext();
        String xml = getXML("testPPT_EmbeddedPDF.pptx", p, new Metadata(), noNamesContext).xml;
        assertNotContained("<h1>image3.jpg</h1>", xml);

        TikaLoader withNamesLoader = TikaLoaderHelper.getLoader("tika-config-with-names.json");
        p = withNamesLoader.loadAutoDetectParser();
        ParseContext withNamesContext = withNamesLoader.loadParseContext();
        xml = getXML("testPPT_EmbeddedPDF.pptx", p, new Metadata(), withNamesContext).xml;
        assertContains("<h1>image3.jpg</h1>", xml);
    }

    @Test
    public void testContentHandlerDecoratorFactory() throws Exception {
        Parser p = TikaLoaderHelper.getLoader("tika-config-upcasing-custom-handler-decorator.json").loadAutoDetectParser();
        List<Metadata> metadataList = getRecursiveMetadata("testPPT_EmbeddedPDF.pptx", p);
        Metadata pdfMetadata1 = metadataList.get(4);
        assertContains("APACHE TIKA", pdfMetadata1.get(TikaCoreProperties.TIKA_CONTENT));
        Metadata pdfMetadata2 = metadataList.get(5);
        assertContains("HELLO WORLD", pdfMetadata2.get(TikaCoreProperties.TIKA_CONTENT));
    }

    @Test
    public void testRecursiveContentHandlerDecoratorFactory() throws Exception {
        Parser p = TikaLoaderHelper.getLoader("tika-config-doubling-custom-handler-decorator.json").loadAutoDetectParser();
        List<Metadata> metadataList = getRecursiveMetadata("testPPT_EmbeddedPDF.pptx", p);
        assertContainsCount("IMAGE2.EMF",
                metadataList.get(0).get(TikaCoreProperties.TIKA_CONTENT), 2);
        assertContainsCount("15.9.2007 11:02",
                metadataList.get(4).get(TikaCoreProperties.TIKA_CONTENT), 2);
        assertContainsCount("HELLO WORLD",
                metadataList.get(5).get(TikaCoreProperties.TIKA_CONTENT), 4);
    }

    @Test
    public void testXMLContentHandlerDecoratorFactory() throws Exception {
        //test to make sure that the decorator is only applied once for
        //legacy (e.g. not RecursiveParserWrapperHandler) parsing

        Parser p = TikaLoaderHelper.getLoader("tika-config-doubling-custom-handler-decorator.json").loadAutoDetectParser();
        String txt = getXML("testPPT_EmbeddedPDF.pptx", p).xml;
        assertContainsCount("THE APACHE TIKA PROJECT WAS FORMALLY", txt, 2);
        assertContainsCount("15.9.2007 11:02", txt, 2);
    }

    @Test
    public void testWriteFilter() throws Exception {
        TikaLoader loader = TikaLoaderHelper.getLoader("tika-config-write-filter.json");
        Parser p = loader.loadAutoDetectParser();
        ParseContext parseContext = loader.loadParseContext();
        Metadata metadata = Metadata.newInstance(parseContext);
        List<Metadata> metadataList = getRecursiveMetadata("testPPT_EmbeddedPDF.pptx", p,
                metadata, parseContext, true);
        for (Metadata m : metadataList) {
            for (String k : m.names()) {
                assertTrue(k.startsWith("X-TIKA:") || k.startsWith("access_permission:")
                        || k.startsWith("Content-") || k.equals("dc:creator"),
                        "unexpected key: " + k);
            }
        }
    }

    @Test
    public void testDigests() throws Exception {
        //test to make sure that the decorator is only applied once for
        //legacy (e.g. not RecursiveParserWrapperHandler) parsing
        TikaLoader loader = TikaLoaderHelper.getLoader("tika-config-digests.json");
        Parser p = loader.loadAutoDetectParser();
        ParseContext context = loader.loadParseContext();
        List<Metadata> metadataList = getRecursiveMetadata("testPPT_EmbeddedPDF.pptx", p, context);
        // SHA256 with BASE32 encoding includes encoding in the key
        assertEquals("SO67W5OGGMOFPMFQTHTNL5YU5EQXWPMNEPU7HKOZX2ULHRQICRZA====",
                metadataList.get(0).get("X-TIKA:digest:SHA256:BASE32"));

        assertEquals("a16f14215ebbfa47bd995e799f03cb18",
                metadataList.get(0).get("X-TIKA:digest:MD5"));

        assertEquals("Q7D3RFV6DNGZ4BQIS6UKNWX4CDIKPIGDU2D7ADBUDVOBYSZHF7FQ====",
                metadataList.get(6).get("X-TIKA:digest:SHA256:BASE32"));
        assertEquals("90a8b249a6d6b6cb127c59e01cef3aaa",
                metadataList.get(6).get("X-TIKA:digest:MD5"));
    }

    @Test
    public void testTextAndStreamDigests() throws Exception {
        TikaLoader loader = TikaLoaderHelper.getLoader(
                "tika-config-text-and-stream-digests.json");
        Parser p = loader.loadAutoDetectParser();
        ParseContext context = loader.loadParseContext();
        List<Metadata> metadataList = getRecursiveMetadata("testPPT_EmbeddedPDF.pptx", p,
                context);

        assertDigestTriplet(metadataList.get(0), "X-TIKA:digest:",
                "a16f14215ebbfa47bd995e799f03cb18",
                "bda02354e86fc1826d9de902155e960f24ceb972",
                "93bdfb75c6331c57b0b099e6d5f714e9217b3d8d23e9f3a9d9bea8b3c6081472");
        assertDigestTriplet(metadataList.get(0), "X-TIKA:digest:text:",
                "e8593ece676bc204a5cfb007015f10f1",
                "f4e6d372198f78e6c07bcea367740deb3f4d87bf",
                "9e519f57e9aa56660ac4ed588b534d59db5258aebe222cec489f1ddac10a17f7");
        assertDigestTriplet(metadataList.get(6), "X-TIKA:digest:",
                "90a8b249a6d6b6cb127c59e01cef3aaa",
                "a83bbdfe58f30bf23ae9d6c1d1eb3e3250736868",
                "87c7b896be1b4d9e060897a8a6dafc10d0a7a0c3a687f00c341d5c1c4b272fcb");
        assertDigestTriplet(metadataList.get(6), "X-TIKA:digest:text:",
                "c1309f350564c10604998b51b93b4d36",
                "949256ecec068c777fd372606d03866a51ca14fb",
                "b286710b845b732b845a17f08191d27336b7a6a2cf52e8ba86a2b9681fc330ae");
    }

    @Test
    public void testDigestsSkipContainer() throws Exception {
        //test to make sure that the decorator is only applied once for
        //legacy (e.g. not RecursiveParserWrapperHandler) parsing
        TikaLoader loader = TikaLoaderHelper.getLoader("tika-config-digests-skip-container.json");
        Parser p = loader.loadAutoDetectParser();
        ParseContext context = loader.loadParseContext();
        List<Metadata> metadataList = getRecursiveMetadata("testPPT_EmbeddedPDF.pptx", p, context);
        // SHA256 with BASE32 encoding includes encoding in the key
        assertNull(metadataList.get(0).get("X-TIKA:digest:SHA256:BASE32"));
        assertNull(metadataList.get(0).get("X-TIKA:digest:MD5"));

        assertEquals("Q7D3RFV6DNGZ4BQIS6UKNWX4CDIKPIGDU2D7ADBUDVOBYSZHF7FQ====",
                metadataList.get(6).get("X-TIKA:digest:SHA256:BASE32"));
        assertEquals("90a8b249a6d6b6cb127c59e01cef3aaa",
                metadataList.get(6).get("X-TIKA:digest:MD5"));
    }

    @Test
    public void testDigestsEmptyParser() throws Exception {
        //TIKA-3939 -- ensure that digesting happens even with EmptyParser
        TikaLoader loader = TikaLoaderHelper.getLoader("tika-config-digests-pdf-only.json");
        Parser p = loader.loadAutoDetectParser();
        ParseContext context = loader.loadParseContext();
        List<Metadata> metadataList = getRecursiveMetadata("testPDF.pdf", p, context);
        assertEquals(1, metadataList.size());
        assertEquals("4ef0d3bdb12ba603f4caf7d2e2c6112e",
                metadataList.get(0).get("X-TIKA:digest:MD5"));
        assertEquals("org.apache.tika.parser.EmptyParser",
                metadataList.get(0).get("X-TIKA:Parsed-By"));
    }

    @Test
    public void testContainerZeroBytes() throws Exception {
        Path tmp = Files.createTempFile("tika-test", "");
        try {
            TikaLoader loader = TikaLoaderHelper.getLoader("tika-config-digests.json");
            Parser p = loader.loadAutoDetectParser();
            ParseContext context = loader.loadParseContext();
            List<Metadata> metadataList = getRecursiveMetadata(tmp, p, context, true);
            assertEquals("d41d8cd98f00b204e9800998ecf8427e",
                    metadataList.get(0).get("X-TIKA:digest:MD5"));
            assertEquals("0", metadataList.get(0).get(Metadata.CONTENT_LENGTH));
        } finally {
            Files.delete(tmp);
        }
    }

    private void assertDigestTriplet(Metadata metadata, String prefix, String md5, String sha1,
                                     String sha256) {
        assertEquals(md5, metadata.get(prefix + "MD5"));
        assertEquals(sha1, metadata.get(prefix + "SHA1"));
        assertEquals(sha256, metadata.get(prefix + "SHA256"));
    }
}
