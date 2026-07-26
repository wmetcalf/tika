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
package org.apache.tika.parser.xml;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;

import org.apache.batik.anim.dom.SAXSVGDocumentFactory;
import org.apache.batik.bridge.ExternalResourceSecurity;
import org.apache.batik.bridge.NoLoadExternalResourceSecurity;
import org.apache.batik.bridge.NoLoadScriptSecurity;
import org.apache.batik.bridge.ScriptSecurity;
import org.apache.batik.bridge.UserAgent;
import org.apache.batik.transcoder.SVGAbstractTranscoder;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.ImageTranscoder;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.apache.batik.util.SVGConstants;
import org.apache.batik.util.XMLResourceDescriptor;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import org.apache.tika.annotation.TikaComponent;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentUtil;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.ImageHash;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.EmbeddedContentHandler;
import org.apache.tika.sax.TaggedContentHandler;
import org.apache.tika.sax.TextContentHandler;
import org.apache.tika.sax.XHTMLContentHandler;
import org.apache.tika.utils.ImageHashUtils;
import org.apache.tika.utils.XMLReaderUtils;

/**
 * XML parser.
 */
@TikaComponent(spi = false)
public class XMLParser implements Parser {

    /**
     * Serial version UID
     */
    private static final long serialVersionUID = -6028836725280212837L;

    private static final Set<MediaType> SUPPORTED_TYPES = Collections.unmodifiableSet(
            new HashSet<>(
                    Arrays.asList(MediaType.application("xml"), MediaType.image("svg+xml"))));

    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return SUPPORTED_TYPES;
    }

    public void parse(TikaInputStream tis, ContentHandler handler, Metadata metadata,
                      ParseContext context) throws IOException, SAXException, TikaException {
        if (metadata.get(Metadata.CONTENT_TYPE) == null) {
            metadata.set(Metadata.CONTENT_TYPE, "application/xml");
        }

        boolean isSvg = "image/svg+xml".equals(metadata.get(Metadata.CONTENT_TYPE));
        Path svgPath = isSvg ? tis.getPath() : null;

        final XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata, context);
        xhtml.startDocument();
        xhtml.startElement("p");

        TaggedContentHandler tagged = new TaggedContentHandler(handler);
        tis.setCloseShield();
        boolean parsedSuccessfully = false;
        try {
            XMLReaderUtils.parseSAX(tis,
                            new EmbeddedContentHandler(
                                    getContentHandler(tagged, metadata, context)),
                    context);
            parsedSuccessfully = true;
        } catch (SAXException e) {
            tagged.throwIfCauseOf(e);
            throw new TikaException("XML parse error", e);
        } finally {
            tis.removeCloseShield();
            if (svgPath != null && parsedSuccessfully) {
                trySvgOcr(svgPath, xhtml, metadata, context);
            }
            xhtml.endElement("p");
            xhtml.endDocument();
        }
    }

    // Skip rasterization for SVGs above this size to avoid OOM in Batik.
    // Data URI images are stripped before rendering so the main crash vector is handled;
    // 10 MB covers legitimate AI-generated art, data visualisations, and complex diagrams.
    private static final long SVG_RASTER_MAX_BYTES = 10L * 1024 * 1024; // 10 MB

    /**
     * Rasterize {@code svgPath} to PNG bytes at the given max dimension.
     * The input is parsed into Batik's SVG DOM with Tika's hardened XML reader,
     * and Batik is configured to reject scripts and external resources.
     */
    private static byte[] rasterizeSvg(Path svgPath, float maxDim) throws Exception {
        String uri = svgPath.toUri().toString();
        XMLReader xmlReader = XMLReaderUtils.getXMLReader();
        // SAXDocumentFactory reconstructs namespaces from xmlns attributes and
        // therefore requires namespace-prefix attributes in addition to namespace URIs.
        xmlReader.setFeature("http://xml.org/sax/features/namespace-prefixes", true);
        SAXSVGDocumentFactory documentFactory = new SAXSVGDocumentFactory(
                XMLResourceDescriptor.getXMLParserClassName());
        Document document = documentFactory.createDocument(
                SVGConstants.SVG_NAMESPACE_URI, SVGConstants.SVG_SVG_TAG, uri, xmlReader);
        sanitizeSvgDocument(document);

        PNGTranscoder transcoder = new SecurePngTranscoder();
        transcoder.addTranscodingHint(ImageTranscoder.KEY_MAX_WIDTH, maxDim);
        transcoder.addTranscodingHint(ImageTranscoder.KEY_MAX_HEIGHT, maxDim);
        transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_EXECUTE_ONLOAD, Boolean.FALSE);
        transcoder.addTranscodingHint(
                SVGAbstractTranscoder.KEY_ALLOW_EXTERNAL_RESOURCES, Boolean.FALSE);
        transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_ALLOWED_SCRIPT_TYPES, "");

        TranscoderInput input = new TranscoderInput(document);
        input.setURI(uri);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transcoder.transcode(input, new TranscoderOutput(out));
        byte[] bytes = out.toByteArray();
        if (bytes.length == 0) {
            throw new IOException("Batik produced an empty SVG raster");
        }
        return bytes;
    }

    private static void sanitizeSvgDocument(Document document) {
        removeSvgElements(document.getElementsByTagNameNS(
                SVGConstants.SVG_NAMESPACE_URI, SVGConstants.SVG_IMAGE_TAG));
        sanitizeSvgReferences(document.getElementsByTagNameNS(
                SVGConstants.SVG_NAMESPACE_URI, SVGConstants.SVG_USE_TAG));
    }

    private static void removeSvgElements(NodeList elements) {
        for (int i = elements.getLength() - 1; i >= 0; i--) {
            Element element = (Element) elements.item(i);
            element.getParentNode().removeChild(element);
        }
    }

    private static void sanitizeSvgReferences(NodeList elements) {
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            String href = element.getAttributeNS(SVGConstants.XLINK_NAMESPACE_URI,
                    SVGConstants.XLINK_HREF_ATTRIBUTE);
            if (href.isEmpty()) {
                href = element.getAttribute("href");
            }
            element.removeAttribute("href");
            element.removeAttributeNS(SVGConstants.XLINK_NAMESPACE_URI,
                    SVGConstants.XLINK_HREF_ATTRIBUTE);
            if (href.startsWith("#")) {
                element.setAttributeNS(SVGConstants.XLINK_NAMESPACE_URI,
                        "xlink:" + SVGConstants.XLINK_HREF_ATTRIBUTE, href);
            } else {
                element.setAttributeNS(SVGConstants.XLINK_NAMESPACE_URI,
                        "xlink:" + SVGConstants.XLINK_HREF_ATTRIBUTE, "");
            }
        }
    }

    private static final class SecurePngTranscoder extends PNGTranscoder {

        @Override
        protected UserAgent createUserAgent() {
            return new SVGAbstractTranscoderUserAgent() {
                @Override
                public ExternalResourceSecurity getExternalResourceSecurity(
                        org.apache.batik.util.ParsedURL resourceUrl,
                        org.apache.batik.util.ParsedURL documentUrl) {
                    return new NoLoadExternalResourceSecurity();
                }

                @Override
                public ScriptSecurity getScriptSecurity(String scriptType,
                        org.apache.batik.util.ParsedURL scriptUrl,
                        org.apache.batik.util.ParsedURL documentUrl) {
                    return new NoLoadScriptSecurity(scriptType);
                }
            };
        }
    }

    private static void trySvgOcr(Path svgPath, XHTMLContentHandler xhtml,
                                   Metadata metadata, ParseContext context) {
        try {
            // Guard against huge SVGs (e.g. embedded base64 images) that OOM the JVM
            if (Files.size(svgPath) > SVG_RASTER_MAX_BYTES) {
                return;
            }

            // Only rasterize in the primary parse pass (where EmbeddedLimits is set by
            // ParserRunner). EmbeddedFileExtractor's second pass does not set EmbeddedLimits,
            // so this guard prevents rasterizing the same SVG a second time unnecessarily.
            if (context.get(org.apache.tika.config.EmbeddedLimits.class) == null) {
                return;
            }

            // Full-resolution raster for OCR and phash (1200px max).
            // Ordinary render failures fall through to the lower-res phash
            // fallback. Resource failures are recorded but not retried.
            Throwable rasterFailure = null;
            boolean unsafeToRetry = false;
            byte[] pngBytes = null;
            try {
                pngBytes = rasterizeSvg(svgPath, 1200f);
            } catch (Exception | LinkageError | StackOverflowError | OutOfMemoryError e) {
                rasterFailure = e;
                unsafeToRetry =
                        e instanceof StackOverflowError || e instanceof OutOfMemoryError;
            }
            if (pngBytes != null) {
                // Phash/colorhash from the full-res render
                try {
                    BufferedImage raster = ImageIO.read(new ByteArrayInputStream(pngBytes));
                    ImageHashUtils.setHashes(raster, metadata);
                } catch (Exception e) {
                    rasterFailure = e;
                }

                // OCR dispatch — only if Tesseract (or equivalent) is configured
                org.apache.tika.parser.Parser ocrParser =
                    EmbeddedDocumentUtil.getStatelessParser(context);
                org.apache.tika.mime.MediaType pngOcrType =
                    org.apache.tika.mime.MediaType.image("ocr-png");
                if (ocrParser != null
                        && ocrParser.getSupportedTypes(context).contains(pngOcrType)) {
                    try (org.apache.tika.io.TikaInputStream pngStream =
                            org.apache.tika.io.TikaInputStream.get(pngBytes)) {
                        Metadata ocrMeta = Metadata.newInstance(context);
                        ocrMeta.set(TikaCoreProperties.CONTENT_TYPE_PARSER_OVERRIDE,
                            pngOcrType.toString());
                        ocrMeta.set(Metadata.CONTENT_TYPE, "image/png");
                        ocrParser.parse(pngStream,
                            new org.apache.tika.sax.EmbeddedContentHandler(
                                new BodyContentHandler(xhtml)),
                            ocrMeta, context);
                    } catch (Exception ignored) { }
                }
            }

            // Fallback: if phash still unset (1200px rasterization failed), try 512px.
            // This keeps phash in the Tika fork for complex/large SVGs where high-res
            // Batik rendering fails, rather than delegating to the application layer.
            if (metadata.get(ImageHash.PHASH) == null && !unsafeToRetry) {
                byte[] fallback = null;
                try {
                    fallback = rasterizeSvg(svgPath, 512f);
                } catch (Exception | LinkageError | StackOverflowError | OutOfMemoryError e) {
                    rasterFailure = e;
                }
                if (fallback != null) {
                    try {
                        BufferedImage img = ImageIO.read(new ByteArrayInputStream(fallback));
                        ImageHashUtils.setHashes(img, metadata);
                    } catch (Exception e) {
                        rasterFailure = e;
                    }
                }
            }

            if (metadata.get(ImageHash.PHASH) == null) {
                addSvgRasterWarning(metadata, rasterFailure);
            }
        } catch (Exception | LinkageError | StackOverflowError | OutOfMemoryError e) {
            addSvgRasterWarning(metadata, e);
        }
    }

    private static void addSvgRasterWarning(Metadata metadata, Throwable failure) {
        StringBuilder warning = new StringBuilder("SVG raster enrichment failed");
        if (failure != null) {
            warning.append(": ").append(failure.getClass().getSimpleName());
            if (failure.getMessage() != null && !failure.getMessage().isBlank()) {
                warning.append(": ").append(failure.getMessage());
            }
        }
        metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING, warning.toString());
    }

    protected ContentHandler getContentHandler(ContentHandler handler, Metadata metadata,
                                               ParseContext context) {
        if ("image/svg+xml".equals(metadata.get(Metadata.CONTENT_TYPE))) {
            return new SvgEnrichingHandler(new TextContentHandler(handler, true), metadata);
        }
        return new TextContentHandler(handler, true);
    }

    /**
     * SAX ContentHandler that wraps a TextContentHandler for SVG content and extracts
     * security-relevant features into metadata: foreignObject presence, event handler
     * attributes, external href/src references, and zero-width characters.
     */
    private static final class SvgEnrichingHandler implements ContentHandler {

        private static final String XLINK_NS = "http://www.w3.org/1999/xlink";
        private static final int MAX_SECURITY_REFERENCES = 4_096;
        private static final int MAX_SECURITY_REFERENCE_CHARS = 1024 * 1024;
        private static final String SECURITY_REFERENCE_LIMIT_WARNING =
                "SVG security-reference limit reached; additional external "
                        + "references were skipped";

        private static final Set<String> EVENT_ATTRS;
        static {
            EVENT_ATTRS = new HashSet<>(Arrays.asList(
                "onclick", "onload", "onerror", "onmouseover", "onmouseenter",
                "onmouseleave", "onmouseout", "onmousedown", "onmouseup", "ondblclick",
                "onfocus", "onblur", "onsubmit", "oninput", "onkeydown", "onkeyup",
                "onkeypress", "onbegin", "onend", "onunload", "onabort"
            ));
        }

        // Zero-width / invisible characters to detect
        private static final String ZERO_WIDTH_CHARS = "​‌‍⁠﻿­";

        private final ContentHandler delegate;
        private final Metadata metadata;
        private int securityReferenceCount;
        private int securityReferenceChars;
        private boolean securityReferenceLimitExceeded;

        SvgEnrichingHandler(ContentHandler delegate, Metadata metadata) {
            this.delegate = delegate;
            this.metadata = metadata;
        }

        private static String getHref(Attributes attrs) {
            String v = attrs.getValue("href");
            if (v != null) {
                return v;
            }
            v = attrs.getValue(XLINK_NS, "href");
            if (v != null) {
                return v;
            }
            return attrs.getValue("xlink:href");
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts)
                throws SAXException {
            String local = localName != null && !localName.isEmpty() ? localName : qName;

            // Feature 1: foreignObject detection
            if ("foreignObject".equals(local)) {
                metadata.set("svg:hasForeignObject", "true");
            }

            // Feature 2: event handler attribute extraction
            int n = atts.getLength();
            for (int i = 0; i < n; i++) {
                String attrName = atts.getLocalName(i);
                if (attrName == null || attrName.isEmpty()) {
                    attrName = atts.getQName(i);
                }
                if (EVENT_ATTRS.contains(attrName)) {
                    metadata.set("svg:hasEventHandlers", "true");
                    String val = atts.getValue(i);
                    if (val != null && !val.isEmpty()) {
                        char[] chars = val.toCharArray();
                        delegate.characters(chars, 0, chars.length);
                    }
                }
            }

            // Feature 3: external href/src extraction
            if ("use".equals(local)) {
                String href = getHref(atts);
                if (href != null && !href.isEmpty() && !href.startsWith("#")) {
                    addSecurityReference("svg:externalUseRef", href);
                }
            } else if ("a".equals(local)) {
                String href = getHref(atts);
                if (href != null && !href.isEmpty() && !href.startsWith("#")) {
                    addSecurityReference("svg:link", href);
                }
            } else if ("script".equals(local)) {
                String src = getHref(atts);
                if (src == null || src.isEmpty()) {
                    src = atts.getValue("src");
                }
                if (src != null && !src.isEmpty()) {
                    addSecurityReference("svg:externalScript", src);
                }
            }

            delegate.startElement(uri, localName, qName, atts);
        }

        private void addSecurityReference(String field, String value) {
            if (securityReferenceCount >= MAX_SECURITY_REFERENCES
                    || value.length()
                    > MAX_SECURITY_REFERENCE_CHARS - securityReferenceChars) {
                markSecurityReferenceLimitExceeded();
                return;
            }
            securityReferenceCount++;
            securityReferenceChars += value.length();
            metadata.add(field, value);
        }

        private void markSecurityReferenceLimitExceeded() {
            if (securityReferenceLimitExceeded) {
                return;
            }
            securityReferenceLimitExceeded = true;
            metadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                    SECURITY_REFERENCE_LIMIT_WARNING);
            if (metadata.get("ExploitClass") == null) {
                metadata.set("ExploitClass",
                        "SVG security-reference extraction incomplete; external "
                                + "executable content may be hidden");
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            // Feature 4: zero-width character detection
            for (int i = start; i < start + length; i++) {
                if (ZERO_WIDTH_CHARS.indexOf(ch[i]) >= 0) {
                    metadata.set("svg:hasZeroWidthChars", "true");
                    break;
                }
            }
            delegate.characters(ch, start, length);
        }

        @Override
        public void setDocumentLocator(Locator locator) {
            delegate.setDocumentLocator(locator);
        }

        @Override
        public void startDocument() throws SAXException {
            delegate.startDocument();
        }

        @Override
        public void endDocument() throws SAXException {
            delegate.endDocument();
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) throws SAXException {
            delegate.startPrefixMapping(prefix, uri);
        }

        @Override
        public void endPrefixMapping(String prefix) throws SAXException {
            delegate.endPrefixMapping(prefix);
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            delegate.endElement(uri, localName, qName);
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
            delegate.ignorableWhitespace(ch, start, length);
        }

        @Override
        public void processingInstruction(String target, String data) throws SAXException {
            delegate.processingInstruction(target, data);
        }

        @Override
        public void skippedEntity(String name) throws SAXException {
            delegate.skippedEntity(name);
        }
    }
}
