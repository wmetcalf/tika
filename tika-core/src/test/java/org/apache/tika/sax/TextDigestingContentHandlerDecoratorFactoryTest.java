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
package org.apache.tika.sax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.xml.sax.ContentHandler;
import org.xml.sax.helpers.DefaultHandler;

import org.apache.tika.digest.DigestDef;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;

public class TextDigestingContentHandlerDecoratorFactoryTest {

    @Test
    public void testDigestTextCharacters() throws Exception {
        Metadata metadata = new Metadata();
        TextDigestingContentHandlerDecoratorFactory factory =
                new TextDigestingContentHandlerDecoratorFactory();
        factory.setDigests(Arrays.asList(
                new DigestDef(DigestDef.Algorithm.MD5),
                new DigestDef(DigestDef.Algorithm.SHA1),
                new DigestDef(DigestDef.Algorithm.SHA256)));

        ContentHandler handler = factory.decorate(new DefaultHandler(), metadata,
                new ParseContext());
        handler.startDocument();
        handler.characters("hello ".toCharArray(), 0, "hello ".length());
        handler.characters("world".toCharArray(), 0, "world".length());
        handler.endDocument();

        assertEquals("5eb63bbbe01eeed093cb22bb8f5acdc3",
                metadata.get("X-TIKA:digest:text:MD5"));
        assertEquals("2aae6c35c94fcfb415dbe95f408b9ce91ee846ed",
                metadata.get("X-TIKA:digest:text:SHA1"));
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe" +
                        "37a5380ee9088f7ace2efcde9",
                metadata.get("X-TIKA:digest:text:SHA256"));
        assertNull(metadata.get("X-TIKA:digest:MD5"));
    }

    @Test
    public void testDefaultDigests() throws Exception {
        Metadata metadata = new Metadata();
        TextDigestingContentHandlerDecoratorFactory factory =
                new TextDigestingContentHandlerDecoratorFactory();

        ContentHandler handler = factory.decorate(new DefaultHandler(), metadata,
                new ParseContext());
        handler.startDocument();
        handler.characters("abc".toCharArray(), 0, 3);
        handler.endDocument();

        assertEquals("900150983cd24fb0d6963f7d28e17f72",
                metadata.get("X-TIKA:digest:text:MD5"));
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d",
                metadata.get("X-TIKA:digest:text:SHA1"));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a3" +
                        "96177a9cb410ff61f20015ad",
                metadata.get("X-TIKA:digest:text:SHA256"));
    }

    @Test
    public void testSplitSurrogatePair() throws Exception {
        Metadata metadata = new Metadata();
        TextDigestingContentHandlerDecoratorFactory factory =
                new TextDigestingContentHandlerDecoratorFactory();
        factory.setDigests(Arrays.asList(new DigestDef(DigestDef.Algorithm.MD5)));

        ContentHandler handler = factory.decorate(new DefaultHandler(), metadata,
                new ParseContext());
        handler.startDocument();
        handler.characters("a\uD83D".toCharArray(), 0, 2);
        handler.characters("\uDE00b".toCharArray(), 0, 2);
        handler.endDocument();

        assertEquals("186ca4f1a2d2ac0d5381177c6719713b",
                metadata.get("X-TIKA:digest:text:MD5"));
    }

    @Test
    public void testIncludesIgnorableWhitespace() throws Exception {
        Metadata metadata = new Metadata();
        TextDigestingContentHandlerDecoratorFactory factory =
                new TextDigestingContentHandlerDecoratorFactory();
        factory.setDigests(Arrays.asList(new DigestDef(DigestDef.Algorithm.MD5)));

        ContentHandler handler = factory.decorate(new DefaultHandler(), metadata,
                new ParseContext());
        handler.startDocument();
        handler.characters("a".toCharArray(), 0, 1);
        handler.ignorableWhitespace(" ".toCharArray(), 0, 1);
        handler.characters("b".toCharArray(), 0, 1);
        handler.endDocument();

        assertEquals("0cc9cd4dd26c5137b675a0d819cb9ab0",
                metadata.get("X-TIKA:digest:text:MD5"));
    }
}
