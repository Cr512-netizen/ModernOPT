// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt.render;

import com.mojang.blaze3d.platform.NativeImage;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Numerical image-quality metrics for the benchmark screenshots.
 *
 * <p>All metrics are computed on the world area only: the top of the screen (sky/clouds) and the
 * bottom (hotbar, hand, chat) are excluded, so an upscaler comparison is not dominated by static
 * HUD elements. The central ROI is {@code x=8..92 %, y=32..82 %}; all values are normalized to
 * 0..1 where applicable.</p>
 *
 * <ul>
 *   <li>{@code sharpness} - mean central-difference luma gradient magnitude.</li>
 *   <li>{@code highFrequency} - mean absolute difference from a 3x3 box blur.</li>
 *   <li>{@code laplacian} - mean absolute 4-neighbour Laplacian.</li>
 *   <li>{@code contrast} - luma standard deviation.</li>
 *   <li>{@code qualityMae}, {@code qualityRmse}, {@code psnr} - error against the native reference.</li>
 *   <li>{@code ssim} - mean 8x8-window SSIM against the native reference.</li>
 *   <li>{@code sharpnessRatio} / {@code highFrequencyRatio} - relative to the reference.</li>
 * </ul>
 *
 * <p>Higher sharpness/high-frequency values are not automatically better: Nearest-neighbour
 * scores high while looking blocky. Use the reference SSIM/MAE together with the sharpness ratio;
 * a good upscaler keeps a high ratio without a large SSIM loss.</p>
 */
public final class ImageQuality {

    private static final int BLOCK = 8;
    private static final double SSIM_C1 = 0.01 * 0.01;
    private static final double SSIM_C2 = 0.03 * 0.03;

    private ImageQuality() {
    }

    /** Returns an empty map when the image cannot be read. */
    public static Map<String, Object> analyze(Path imagePath, Path referencePath) {
        Map<String, Object> result = new LinkedHashMap<>();
        try (InputStream input = Files.newInputStream(imagePath);
                NativeImage image = NativeImage.read(input)) {
            int width = image.getWidth();
            int height = image.getHeight();
            int x0 = Math.max(1, (int) Math.round(width * 0.08));
            int x1 = Math.min(width - 2, (int) Math.round(width * 0.92));
            int y0 = Math.max(1, (int) Math.round(height * 0.32));
            int y1 = Math.min(height - 2, (int) Math.round(height * 0.82));
            if (x1 <= x0 + 2 || y1 <= y0 + 2) {
                return result;
            }

            int[] luma = luma(image, width, height);
            double currentSharpness = sharpness(luma, width, x0, x1, y0, y1);
            double currentHighFrequency = highFrequency(luma, width, x0, x1, y0, y1);
            result.put("sharpness", round(currentSharpness, 5));
            result.put("highFrequency", round(currentHighFrequency, 5));
            result.put("laplacian", round(laplacian(luma, width, x0, x1, y0, y1), 5));
            result.put("contrast", round(contrast(luma, width, x0, x1, y0, y1), 5));

            if (referencePath == null || !Files.isRegularFile(referencePath)) {
                return result;
            }
            try (InputStream refInput = Files.newInputStream(referencePath);
                    NativeImage reference = NativeImage.read(refInput)) {
                if (reference.getWidth() != width || reference.getHeight() != height) {
                    return result;
                }
                int[] refLuma = luma(reference, width, height);
                double refSharpness = sharpness(refLuma, width, x0, x1, y0, y1);
                double refHighFrequency = highFrequency(refLuma, width, x0, x1, y0, y1);
                double[] errors = errors(luma, refLuma, width, x0, x1, y0, y1);
                result.put("qualityMae", round(errors[0], 5));
                result.put("qualityRmse", round(errors[1], 5));
                result.put("psnr", round(errors[2], 3));
                result.put("ssim", round(ssim(luma, refLuma, width, x0, x1, y0, y1), 5));
                result.put("referenceSharpness", round(refSharpness, 5));
                result.put("referenceHighFrequency", round(refHighFrequency, 5));
                result.put("sharpnessRatio", round(refSharpness <= 1.0e-9 ? 0.0
                        : currentSharpness / refSharpness, 4));
                result.put("highFrequencyRatio", round(refHighFrequency <= 1.0e-9 ? 0.0
                        : currentHighFrequency / refHighFrequency, 4));
            }
        } catch (Throwable ignored) {
            // Missing/corrupt screenshot or unsupported image: leave the metrics absent.
        }
        return result;
    }

    private static int[] luma(NativeImage image, int width, int height) {
        int[] luma = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgba = image.getPixelRGBA(x, y);
                int r = rgba & 0xFF;
                int g = (rgba >>> 8) & 0xFF;
                int b = (rgba >>> 16) & 0xFF;
                luma[y * width + x] = (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
            }
        }
        return luma;
    }

    private static double sharpness(int[] luma, int width, int x0, int x1, int y0, int y1) {
        double sum = 0.0;
        long count = 0;
        for (int y = y0; y < y1; y++) {
            int row = y * width;
            for (int x = x0; x < x1; x++) {
                double gx = (luma[row + x + 1] - luma[row + x - 1]) * 0.5;
                double gy = (luma[row + width + x] - luma[row - width + x]) * 0.5;
                sum += Math.sqrt(gx * gx + gy * gy);
                count++;
            }
        }
        return count == 0 ? 0.0 : sum / (count * 255.0);
    }

    private static double highFrequency(int[] luma, int width, int x0, int x1, int y0, int y1) {
        double sum = 0.0;
        long count = 0;
        for (int y = y0; y < y1; y++) {
            int row = y * width;
            for (int x = x0; x < x1; x++) {
                int blur = (luma[row + x]
                        + luma[row + x - 1] + luma[row + x + 1]
                        + luma[row - width + x - 1] + luma[row - width + x] + luma[row - width + x + 1]
                        + luma[row + width + x - 1] + luma[row + width + x] + luma[row + width + x + 1]) / 9;
                sum += Math.abs(luma[row + x] - blur);
                count++;
            }
        }
        return count == 0 ? 0.0 : sum / (count * 255.0);
    }

    private static double laplacian(int[] luma, int width, int x0, int x1, int y0, int y1) {
        double sum = 0.0;
        long count = 0;
        for (int y = y0; y < y1; y++) {
            int row = y * width;
            for (int x = x0; x < x1; x++) {
                int value = 4 * luma[row + x]
                        - luma[row + x - 1] - luma[row + x + 1]
                        - luma[row - width + x] - luma[row + width + x];
                sum += Math.abs(value);
                count++;
            }
        }
        return count == 0 ? 0.0 : sum / (count * 255.0);
    }

    private static double contrast(int[] luma, int width, int x0, int x1, int y0, int y1) {
        double sum = 0.0;
        double sumSq = 0.0;
        long count = 0;
        for (int y = y0; y < y1; y++) {
            int row = y * width;
            for (int x = x0; x < x1; x++) {
                double value = luma[row + x] / 255.0;
                sum += value;
                sumSq += value * value;
                count++;
            }
        }
        if (count == 0) {
            return 0.0;
        }
        double mean = sum / count;
        return Math.sqrt(Math.max(0.0, sumSq / count - mean * mean));
    }

    /** Returns {MAE, RMSE, PSNR}; luma in 0..1. */
    private static double[] errors(int[] luma, int[] reference, int width,
            int x0, int x1, int y0, int y1) {
        double sum = 0.0;
        double sumSq = 0.0;
        long count = 0;
        for (int y = y0; y < y1; y++) {
            int row = y * width;
            for (int x = x0; x < x1; x++) {
                double difference = (luma[row + x] - reference[row + x]) / 255.0;
                sum += Math.abs(difference);
                sumSq += difference * difference;
                count++;
            }
        }
        if (count == 0) {
            return new double[] {0.0, 0.0, 99.0};
        }
        double mae = sum / count;
        double rmse = Math.sqrt(sumSq / count);
        double psnr = rmse <= 1.0e-9 ? 99.0 : 20.0 * Math.log10(1.0 / rmse);
        return new double[] {mae, rmse, psnr};
    }

    private static double ssim(int[] luma, int[] reference, int width,
            int x0, int x1, int y0, int y1) {
        double total = 0.0;
        long blocks = 0;
        for (int by = y0; by + BLOCK <= y1; by += BLOCK) {
            for (int bx = x0; bx + BLOCK <= x1; bx += BLOCK) {
                double sumX = 0.0;
                double sumY = 0.0;
                double sumXX = 0.0;
                double sumYY = 0.0;
                double sumXY = 0.0;
                int count = BLOCK * BLOCK;
                for (int y = by; y < by + BLOCK; y++) {
                    int row = y * width;
                    for (int x = bx; x < bx + BLOCK; x++) {
                        double a = luma[row + x] / 255.0;
                        double b = reference[row + x] / 255.0;
                        sumX += a;
                        sumY += b;
                        sumXX += a * a;
                        sumYY += b * b;
                        sumXY += a * b;
                    }
                }
                double meanX = sumX / count;
                double meanY = sumY / count;
                double varX = Math.max(0.0, sumXX / count - meanX * meanX);
                double varY = Math.max(0.0, sumYY / count - meanY * meanY);
                double covariance = sumXY / count - meanX * meanY;
                double numerator = (2.0 * meanX * meanY + SSIM_C1) * (2.0 * covariance + SSIM_C2);
                double denominator = (meanX * meanX + meanY * meanY + SSIM_C1)
                        * (varX + varY + SSIM_C2);
                total += denominator <= 1.0e-12 ? 1.0 : numerator / denominator;
                blocks++;
            }
        }
        return blocks == 0 ? 0.0 : total / blocks;
    }

    private static double round(double value, int digits) {
        double scale = Math.pow(10.0, digits);
        return Math.round(value * scale) / scale;
    }
}
