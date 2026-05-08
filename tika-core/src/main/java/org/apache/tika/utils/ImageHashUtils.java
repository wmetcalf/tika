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
package org.apache.tika.utils;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;

import org.apache.tika.metadata.ImageHash;
import org.apache.tika.metadata.Metadata;

/**
 * Computes perceptual image hashes (phash and colorhash) from a
 * {@link BufferedImage} and sets them on a {@link Metadata} instance.
 *
 * <p>The algorithms produce values compatible with Python {@code imagehash}
 * {@code phash()} and {@code colorhash(binbits=4)}.  This class is in
 * tika-core so that parser modules outside tika-parser-image-module (e.g.
 * tika-parser-microsoft-module, tika-parser-xml-module) can call it for
 * vector/container formats that are rasterized at parse time (EMF, WMF, SVG,
 * HEIF).</p>
 */
public final class ImageHashUtils {

    private static final int PHASH_SIZE = 32;
    private static final int PHASH_LOW_FREQ_SIZE = 8;
    private static final int PHASH_PRESIZE = 256;
    private static final int COLOR_HASH_BINBITS = 4;
    private static final double[][] DCT_COS = initDctCos();

    private ImageHashUtils() {
    }

    /**
     * Compute phash and colorhash for {@code image} and set them on
     * {@code metadata}.  All exceptions are swallowed so that a hash failure
     * never aborts a parse.
     */
    public static void setHashes(BufferedImage image, Metadata metadata) {
        if (image == null || metadata == null) {
            return;
        }
        try {
            metadata.set(ImageHash.PHASH, computePhash(image));
        } catch (Exception e) {
            // non-fatal
        }
        try {
            metadata.set(ImageHash.COLORHASH, computeColorHash(image));
        } catch (Exception e) {
            // non-fatal
        }
    }

    /**
     * Compute the phash string for the given image.
     */
    public static String computePhash(BufferedImage img) {
        if (img.getWidth() > PHASH_PRESIZE || img.getHeight() > PHASH_PRESIZE) {
            double scale = Math.min((double) PHASH_PRESIZE / img.getWidth(),
                    (double) PHASH_PRESIZE / img.getHeight());
            int width = Math.max(PHASH_SIZE, (int) (img.getWidth() * scale));
            int height = Math.max(PHASH_SIZE, (int) (img.getHeight() * scale));
            BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = resized.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(img, 0, 0, width, height, null);
            graphics.dispose();
            img = resized;
        }

        int srcW = img.getWidth();
        int srcH = img.getHeight();
        double[] gray = new double[srcW * srcH];
        for (int y = 0; y < srcH; y++) {
            for (int x = 0; x < srcW; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                gray[y * srcW + x] = (r * 19595 + g * 38470 + b * 7471 + 32768) >> 16;
            }
        }

        double[] pixels = lanczosResize(gray, srcW, srcH, PHASH_SIZE, PHASH_SIZE);
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = Math.round(pixels[i]);
        }

        double[] tmp = new double[PHASH_SIZE * PHASH_SIZE];
        for (int col = 0; col < PHASH_SIZE; col++) {
            for (int k = 0; k < PHASH_SIZE; k++) {
                double value = 0.0;
                double[] cos = DCT_COS[k];
                for (int row = 0; row < PHASH_SIZE; row++) {
                    value += pixels[row * PHASH_SIZE + col] * cos[row];
                }
                tmp[k * PHASH_SIZE + col] = 2.0 * value;
            }
        }

        double[] lowfreq = new double[PHASH_LOW_FREQ_SIZE * PHASH_LOW_FREQ_SIZE];
        for (int row = 0; row < PHASH_LOW_FREQ_SIZE; row++) {
            for (int k = 0; k < PHASH_LOW_FREQ_SIZE; k++) {
                double value = 0.0;
                double[] cos = DCT_COS[k];
                for (int col = 0; col < PHASH_SIZE; col++) {
                    value += tmp[row * PHASH_SIZE + col] * cos[col];
                }
                lowfreq[row * PHASH_LOW_FREQ_SIZE + k] = 2.0 * value;
            }
        }

        double[] sorted = lowfreq.clone();
        Arrays.sort(sorted);
        double median = (sorted[31] + sorted[32]) / 2.0;
        StringBuilder sb = new StringBuilder(16);
        for (int byteIdx = 0; byteIdx < 8; byteIdx++) {
            int bv = 0;
            for (int bit = 0; bit < 8; bit++) {
                if (lowfreq[byteIdx * 8 + bit] > median) {
                    bv |= 0x80 >> bit;
                }
            }
            appendHexByte(sb, bv);
        }
        return sb.toString();
    }

    /**
     * Compute the colorhash string for the given image.
     */
    public static String computeColorHash(BufferedImage src) {
        BufferedImage img = src;
        if (src.getType() != BufferedImage.TYPE_INT_RGB) {
            img = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = img.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, src.getWidth(), src.getHeight());
            graphics.drawImage(src, 0, 0, null);
            graphics.dispose();
        }

        int width = img.getWidth();
        int height = img.getHeight();
        int n = width * height;
        int blackCount = 0;
        int grayNotBlackCount = 0;
        int colorfulCount = 0;
        int[] faintBins = new int[6];
        int[] brightBins = new int[6];
        float[] hsb = new float[3];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                int intensity = (r * 19595 + g * 38470 + b * 7471 + 32768) >> 16;

                Color.RGBtoHSB(r, g, b, hsb);
                int hVal = Math.round(hsb[0] * 255.0f);
                int sVal = Math.round(hsb[1] * 255.0f);

                if (intensity < 32) {
                    blackCount++;
                } else if (sVal < 85) {
                    grayNotBlackCount++;
                } else {
                    colorfulCount++;
                    int hueBin = Math.min(5, (int) (hVal * 6.0 / 255.0));
                    if (sVal < 170) {
                        faintBins[hueBin]++;
                    } else if (sVal > 170) {
                        brightBins[hueBin]++;
                    }
                }
            }
        }

        int maxVal = 1 << COLOR_HASH_BINBITS;
        int colorfulDenominator = Math.max(1, colorfulCount);
        int[] values = new int[14];
        values[0] = Math.min(maxVal - 1, (int) ((double) blackCount / n * maxVal));
        values[1] = Math.min(maxVal - 1, (int) ((double) grayNotBlackCount / n * maxVal));
        for (int i = 0; i < 6; i++) {
            values[2 + i] = Math.min(maxVal - 1,
                    (int) ((double) faintBins[i] * maxVal / colorfulDenominator));
            values[8 + i] = Math.min(maxVal - 1,
                    (int) ((double) brightBins[i] * maxVal / colorfulDenominator));
        }

        StringBuilder sb = new StringBuilder(14);
        int bitBuf = 0;
        int bitsInBuf = 0;
        for (int value : values) {
            for (int i = 0; i < COLOR_HASH_BINBITS; i++) {
                int divisor = 1 << (COLOR_HASH_BINBITS - i - 1);
                int modulo = 1 << (COLOR_HASH_BINBITS - i);
                int bit = (value / divisor) % modulo > 0 ? 1 : 0;
                bitBuf = (bitBuf << 1) | bit;
                bitsInBuf++;
                if (bitsInBuf == 4) {
                    sb.append(Integer.toHexString(bitBuf));
                    bitBuf = 0;
                    bitsInBuf = 0;
                }
            }
        }
        if (bitsInBuf > 0) {
            sb.append(Integer.toHexString(bitBuf << (4 - bitsInBuf)));
        }
        return sb.toString();
    }

    // ── private helpers ────────────────────────────────────────────────────

    private static double[] lanczosResize(double[] src, int srcW, int srcH, int dstW, int dstH) {
        Weight[] hWeights = createWeights(srcW, dstW);
        double[] hpass = new double[srcH * dstW];
        for (int y = 0; y < srcH; y++) {
            int srcRow = y * srcW;
            int dstRow = y * dstW;
            for (int xd = 0; xd < dstW; xd++) {
                Weight weight = hWeights[xd];
                double value = 0.0;
                for (int i = 0; i < weight.weights.length; i++) {
                    value += weight.weights[i] * src[srcRow + weight.offset + i];
                }
                hpass[dstRow + xd] = clamp255(value);
            }
        }

        Weight[] vWeights = createWeights(srcH, dstH);
        double[] result = new double[dstW * dstH];
        for (int yd = 0; yd < dstH; yd++) {
            Weight weight = vWeights[yd];
            int dstRow = yd * dstW;
            for (int xd = 0; xd < dstW; xd++) {
                double value = 0.0;
                for (int i = 0; i < weight.weights.length; i++) {
                    value += weight.weights[i] * hpass[(weight.offset + i) * dstW + xd];
                }
                result[dstRow + xd] = clamp255(value);
            }
        }
        return result;
    }

    private static Weight[] createWeights(int srcSize, int dstSize) {
        double scale = (double) srcSize / dstSize;
        double filterScale = Math.max(1.0, scale);
        double support = 3.0 * filterScale;
        Weight[] weights = new Weight[dstSize];
        for (int dst = 0; dst < dstSize; dst++) {
            double center = (dst + 0.5) * scale;
            int start = Math.max(0, (int) Math.ceil(center - support));
            int end = Math.min(srcSize - 1, (int) Math.floor(center + support));
            int len = end - start + 1;
            double[] values = new double[len];
            double sum = 0.0;
            for (int i = 0; i < len; i++) {
                double value = lanczosKernel((start + i + 0.5 - center) / filterScale);
                values[i] = value;
                sum += value;
            }
            if (sum != 0.0) {
                for (int i = 0; i < values.length; i++) {
                    values[i] /= sum;
                }
            }
            weights[dst] = new Weight(start, values);
        }
        return weights;
    }

    private static double lanczosKernel(double x) {
        double ax = Math.abs(x);
        if (ax < 3.0) {
            return sinc(ax) * sinc(ax / 3.0);
        }
        return 0.0;
    }

    private static double sinc(double x) {
        if (x == 0.0) {
            return 1.0;
        }
        double px = Math.PI * x;
        return Math.sin(px) / px;
    }

    private static double clamp255(double value) {
        return Math.max(0.0, Math.min(255.0, value));
    }

    private static double[][] initDctCos() {
        double[][] cos = new double[PHASH_SIZE][PHASH_SIZE];
        for (int k = 0; k < PHASH_SIZE; k++) {
            for (int n = 0; n < PHASH_SIZE; n++) {
                cos[k][n] = Math.cos(Math.PI * k * (2.0 * n + 1.0) / (2.0 * PHASH_SIZE));
            }
        }
        return cos;
    }

    private static void appendHexByte(StringBuilder sb, int b) {
        if (b < 0x10) {
            sb.append('0');
        }
        sb.append(Integer.toHexString(b));
    }

    private static final class Weight {
        private final int offset;
        private final double[] weights;

        private Weight(int offset, double[] weights) {
            this.offset = offset;
            this.weights = weights;
        }
    }
}
