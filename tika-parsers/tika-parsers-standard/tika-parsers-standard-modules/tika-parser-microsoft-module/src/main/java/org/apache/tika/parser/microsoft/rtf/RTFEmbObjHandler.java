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
package org.apache.tika.parser.microsoft.rtf;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.UnsynchronizedByteArrayOutputStream;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.TikaMemoryLimitException;
import org.apache.tika.extractor.EmbeddedDocumentUtil;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Office;
import org.apache.tika.metadata.RTFMetadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.microsoft.OfficeLinkMetadataUtil;
import org.apache.tika.sax.EmbeddedContentHandler;
import org.apache.tika.sax.XHTMLBalancingHandler;

/**
 * This class buffers data from embedded objects and pictures.
 * <p/>
 * <p/>
 * <p/>
 * When the parser has finished an object or picture and called
 * {@link #handleCompletedObject()}, this will write the object
 * to the {@link #handler}.
 * <p/>
 * <p/>
 * <p/>
 * This (in combination with TextExtractor) expects basically a flat parse.  It will pull out
 * all pict whether they are tied to objdata or are intended
 * to be standalone.
 * <p/>
 * <p/>
 * This tries to pull metadata around a pict that is encoded
 * with {sp {sn} {sv}} types of data.  This information
 * sometimes contains the name and even full file path of the original file.
 */
class RTFEmbObjHandler {

    private static final String EMPTY_STRING = "";
    private final ContentHandler handler;
    private final ParseContext context;
    private final EmbeddedDocumentUtil embeddedDocumentUtil;
    private final UnsynchronizedByteArrayOutputStream os;
    private final int memoryLimitInKb;
    private final Metadata parentMetadata;

    private boolean isPictBitmap = false;
    //high hex cached for writing hexpair chars (data)
    private int hi = -1;
    private int thumbCount = 0;
    //don't need atomic, do need mutable
    private AtomicInteger unknownFilenameCount = new AtomicInteger();
    private boolean inObject = false;
    private String sv = EMPTY_STRING;
    private String sn = EMPTY_STRING;
    private StringBuilder sb = new StringBuilder();
    private Metadata metadata;
    private EMB_STATE state = EMB_STATE.NADA;

    // Fix 2: last-occurrence \objdata wins.
    // Malware RTF documents sometimes contain decoy \objdata blocks before the real
    // payload.  MS Word processes only the last \objdata occurrence.  We buffer the
    // accumulated bytes from each completed \objdata group here instead of immediately
    // extracting them; the previous buffer is discarded on each new \objdata start.
    // After the full document has been tokenised, flushLastObjData() is called once to
    // extract the surviving (last) block.
    private byte[] lastObjDataBytes = null;
    private Metadata lastObjDataMeta = null;

    // Obfuscation-detection counters/flags — reset per \object group in flushLastObjData().
    private int decoyCount = 0;
    private boolean hexEscapeInObjdata = false;
    private boolean unicodeInObjdata = false;

    protected RTFEmbObjHandler(ContentHandler handler, Metadata metadata, ParseContext context,
                               int memoryLimitInKb) {
        this.handler = handler;
        this.context = context;
        this.embeddedDocumentUtil = new EmbeddedDocumentUtil(context);
        os = UnsynchronizedByteArrayOutputStream.builder().get();
        this.memoryLimitInKb = memoryLimitInKb;
        this.parentMetadata = metadata;
    }

    protected void startPict() {
        state = EMB_STATE.PICT;
        metadata = Metadata.newInstance(context);
    }

    protected void startObjData() {
        // Discard any previously buffered \objdata bytes: the new occurrence wins
        // (last-occurrence semantics matching MS Word behaviour, Fix 2).
        if (lastObjDataBytes != null) {
            decoyCount++;
        } else {
            decoyCount = 0;  // first \objdata in this group; clear any residual from an exception path
        }
        lastObjDataBytes = null;
        lastObjDataMeta = null;
        hexEscapeInObjdata = false;
        unicodeInObjdata = false;
        state = EMB_STATE.OBJDATA;
        metadata = Metadata.newInstance(context);
    }

    protected void startSN() {
        sb.setLength(0);
        sb.append(RTFMetadata.RTF_PICT_META_PREFIX);
    }

    protected void endSN() {
        sn = sb.toString();
    }

    protected void startSV() {
        sb.setLength(0);
    }

    protected void endSV() {
        sv = sb.toString();
    }

    //end metadata pair
    protected void endSP() {
        metadata.add(sn, sv);
    }

    protected boolean getInObject() {
        return inObject;
    }

    protected void setInObject(boolean v) {
        inObject = v;
    }

    protected void writeMetadataChar(char c) {
        sb.append(c);
    }

    /**
     * Fix 3a: Reset the hex-nibble accumulator.
     * Called after a {@code \binN} skip inside an {@code \objdata} stream so that
     * the next hex pair starts on a fresh byte boundary.
     */
    protected void resetHexNibble() {
        hi = -1;
    }

    /**
     * Fix 3b: Write a pre-decoded byte directly into the OLE output stream.
     * Used when a {@code \'HH} RTF hex escape appears inside an {@code \objdata}
     * stream: the decoded byte is written as a complete OLE byte, and any pending
     * nibble (hi) is discarded so the next raw hex character starts a fresh pair.
     * This matches MS Word's behaviour — it does not mix {@code \'HH} nibbles with
     * the surrounding hex stream.
     */
    protected void writeDecodedByte(int b) throws IOException, TikaException {
        if (state != EMB_STATE.OBJDATA) {
            return;
        }
        if (os.size() >= (long) memoryLimitInKb * 1024) {
            throw new TikaMemoryLimitException(os.size() + 1, (long) memoryLimitInKb * 1024);
        }
        os.write(b);
        hexEscapeInObjdata = true;
        hi = -1;
    }

    /** Called by TextExtractor when a RTF unicode escape (backslash-u) is seen inside {@code \objdata}. */
    protected void markUnicodeInObjdata() {
        unicodeInObjdata = true;
    }

    protected void setPictBitmap(boolean isPictBitmap) {
        this.isPictBitmap = isPictBitmap;
    }

    protected void writeHexChar(int b) throws IOException, TikaException {
        //if not hexchar, ignore
        //white space is common
        if (TextExtractor.isHexChar(b)) {
            if (hi == -1) {
                hi = 16 * TextExtractor.hexValue(b);
            } else {
                long sum = hi + TextExtractor.hexValue(b);
                if (sum > Integer.MAX_VALUE || sum < 0) {
                    throw new IOException("hex char to byte overflow");
                }

                if (os.size() >= (long) memoryLimitInKb * 1024) {
                    throw new TikaMemoryLimitException(os.size() + 1, (long) memoryLimitInKb * 1024);
                }
                os.write((int) sum);

                hi = -1;
            }
            return;
        }
        if (b == -1) {
            throw new TikaException("hit end of stream before finishing byte pair");
        }
    }

    protected void writeBytes(InputStream is, int len) throws IOException, TikaException {
        if (len < 0) {
            throw new TikaException("Requesting I read < 0 bytes ?!");
        }
        if (len > (long) memoryLimitInKb * 1024) {
            throw new TikaMemoryLimitException(len, ((long) memoryLimitInKb * 1024));
        }

        byte[] bytes = new byte[len];
        IOUtils.readFully(is, bytes);
        os.write(bytes);
    }

    /**
     * Call this when the objdata/pict has completed
     *
     * @throws IOException
     * @throws SAXException
     * @throws TikaException
     */
    protected void handleCompletedObject() throws IOException, SAXException, TikaException {

        byte[] bytes = os.toByteArray();
        if (state == EMB_STATE.OBJDATA) {
            // Fix 2: buffer instead of extracting immediately.  A new \objdata start
            // (startObjData) will discard these bytes; only the last occurrence is
            // extracted via flushLastObjData() after the document is fully parsed.
            lastObjDataBytes = bytes;
            lastObjDataMeta = metadata;
        } else if (state == EMB_STATE.PICT) {
            String filePath = metadata.get(RTFMetadata.RTF_PICT_META_PREFIX + "wzDescription");
            if (filePath != null && filePath.length() > 0) {
                metadata.set(TikaCoreProperties.EMBEDDED_RELATIONSHIP_ID, filePath);
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, FilenameUtils.getName(filePath));
                metadata.set(TikaCoreProperties.ORIGINAL_RESOURCE_NAME, filePath);
            }
            metadata.set(RTFMetadata.THUMBNAIL, Boolean.toString(inObject));
            if (isPictBitmap) {
                metadata.set(
                        TikaCoreProperties.CONTENT_TYPE_PARSER_OVERRIDE, "image/x-rtf-raw-bitmap");
            }
            extractObj(bytes, handler, metadata);

        } else if (state == EMB_STATE.NADA) {
            //swallow...no start for pict or embed?!
        }
        reset();
    }

    /**
     * Fix 2: Extract the last accumulated \objdata block.
     * Called by TextExtractor once the entire document has been parsed so that
     * only the final \objdata occurrence (the real payload, not any decoys) is
     * processed.  Non-fatal: exceptions are recorded in metadata.
     */
    protected void flushLastObjData() throws IOException, SAXException, TikaException {
        if (lastObjDataBytes == null) {
            decoyCount = 0;
            hexEscapeInObjdata = false;
            unicodeInObjdata = false;
            return;
        }
        byte[] bytes = lastObjDataBytes;
        Metadata savedMeta = lastObjDataMeta;
        lastObjDataBytes = null;
        lastObjDataMeta = null;

        // Stamp obfuscation signals onto the surviving object's metadata.
        if (decoyCount > 0) {
            savedMeta.set(RTFMetadata.EMB_OBJDATA_DECOY_COUNT, decoyCount);
        }
        if (hexEscapeInObjdata) {
            savedMeta.set(RTFMetadata.EMB_HEX_ESCAPE_IN_OBJDATA, true);
        }
        if (unicodeInObjdata) {
            savedMeta.set(RTFMetadata.EMB_UNICODE_IN_OBJDATA, true);
        }

        // Reset for the next \object group.
        decoyCount = 0;
        hexEscapeInObjdata = false;
        unicodeInObjdata = false;

        RTFObjDataParser objParser = new RTFObjDataParser(memoryLimitInKb);
        try {
            byte[] objBytes = objParser.parse(bytes, savedMeta, unknownFilenameCount);
            if (objParser.isLinkedObject()) {
                surfaceLinkedObject(savedMeta);
            }
            extractObj(objBytes, handler, savedMeta);
        } catch (IOException e) {
            EmbeddedDocumentUtil.recordException(e, savedMeta);
        }
    }

    private void surfaceLinkedObject(Metadata linkedMetadata) {
        String topic = linkedMetadata.get(RTFMetadata.EMB_TOPIC);
        if (topic == null || topic.isBlank()) {
            return;
        }
        parentMetadata.set(Office.HAS_LINKED_OLE_OBJECTS, true);
        OfficeLinkMetadataUtil.addLink(parentMetadata, "linked_ole_object", topic,
                linkedMetadata.get(RTFMetadata.EMB_ITEM), null, "rtf", "ole1",
                linkedMetadata.get(RTFMetadata.EMB_CLASS), "", "", "external_url");
    }

    private void extractObj(byte[] bytes, ContentHandler handler, Metadata metadata)
            throws SAXException, IOException, TikaException {

        if (bytes == null) {
            return;
        }

        metadata.set(Metadata.CONTENT_LENGTH, Integer.toString(bytes.length));

        if (embeddedDocumentUtil.shouldParseEmbedded(metadata)) {
            try (TikaInputStream tis = TikaInputStream.get(bytes)) {
                if (metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY) == null) {
                    String extension = embeddedDocumentUtil.getExtension(tis, metadata);
                    if (inObject && state == EMB_STATE.PICT) {
                        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY,
                                EmbeddedDocumentUtil.EmbeddedResourcePrefix.THUMBNAIL.getPrefix()
                                        + "-" + thumbCount++ + extension);
                        metadata.set(RTFMetadata.THUMBNAIL, "true");
                    } else {
                        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY,
                                EmbeddedDocumentUtil.EmbeddedResourcePrefix.EMBEDDED.getPrefix()
                                        + "-" + unknownFilenameCount.getAndIncrement()
                                        + extension);
                    }
                    metadata.set(TikaCoreProperties.RESOURCE_NAME_EXTENSION_INFERRED, true);
                }
                // Wrap the outer handler in a balancing handler so that if
                // the embedded parser aborts mid-element (leaving <p>/<b>/etc
                // open on the wire), we can drain those before propagating.
                // Without this, the outer RTF parser's subsequent
                // </b>/</p>/</body> sequence trips StrictXHTMLValidator on
                // unbalanced nesting and surfaces as a misleading error at
                // endDocument. Same shape as the SXWPF / Epub catch arms.
                XHTMLBalancingHandler balancer = new XHTMLBalancingHandler(handler);
                try {
                    embeddedDocumentUtil
                            .parseEmbedded(tis, new EmbeddedContentHandler(balancer), metadata,
                                    true);
                } catch (IOException e) {
                    balancer.drainOpenElements();
                    EmbeddedDocumentUtil.recordEmbeddedStreamException(e, metadata);
                } catch (SAXException e) {
                    balancer.drainOpenElements();
                    EmbeddedDocumentUtil.recordException(e, metadata);
                }
            }
        }
    }

    /**
     * reset state after each object.
     * Do not reset unknown file number.
     */
    protected void reset() {
        state = EMB_STATE.NADA;
        os.reset();
        metadata = Metadata.newInstance(context);
        hi = -1;
        sv = EMPTY_STRING;
        sn = EMPTY_STRING;
        sb.setLength(0);
        isPictBitmap = false;
    }

    private enum EMB_STATE {
        PICT, //recording pict data
        OBJDATA, //recording objdata
        NADA
    }
}
