package net.cr512.modernopt.render;

import net.cr512.modernopt.ModernOPT;
import net.cr512.modernopt.compat.ShadowMapResolution;


import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites the {@code shadowMapResolution} constant of a shader pack while its sources are being
 * preprocessed.
 *
 * <p>Iris resolves pack includes and {@code #if} blocks in {@code JcppProcessor}; the resulting
 * text is what later becomes the GLSL program. Replacing the constant here means the shader is
 * compiled with the same resolution the shadow framebuffer is created with, so texel offsets,
 * PCF taps and blur radii stay correct instead of drifting ("noisy" / "swimming" shadows).</p>
 *
 * <p>Packs that derive the size at runtime from {@code textureSize(shadowtex0, 0)} need no rewrite
 * and are left untouched.</p>
 */
public final class ShadowResolutionPatcher {

    private static final Pattern[] DECLARATIONS = {
            Pattern.compile("(const\\s+int\\s+shadowMapResolution\\s*=\\s*)(\\d+)(\\s*;)"),
            Pattern.compile("(const\\s+uint\\s+shadowMapResolution\\s*=\\s*)(\\d+)(\\s*;)"),
            Pattern.compile("(const\\s+float\\s+shadowMapResolution\\s*=\\s*)(\\d+(?:\\.\\d*)?)(\\s*;)"),
            Pattern.compile("(#define\\s+shadowMapResolution\\s+)(\\d+(?:\\.\\d*)?)(\\b)")
    };

    private static int patchedFiles;
    private static int loggedResolution = -1;

    private ShadowResolutionPatcher() {
    }

    /**
     * Rewrites every {@code shadowMapResolution} declaration in {@code source}.
     *
     * @return the patched source, or {@code null} when nothing had to change
     */
    public static String rewrite(String source) {
        if (source == null || source.isEmpty() || !ShadowMapResolution.enabled()) {
            return null;
        }
        if (!source.contains("shadowMapResolution")) {
            return null;
        }
        int resolution = ShadowMapResolution.desired();
        String result = source;
        boolean changed = false;
        for (Pattern pattern : DECLARATIONS) {
            Matcher matcher = pattern.matcher(result);
            StringBuilder out = new StringBuilder(result.length());
            boolean localChange = false;
            while (matcher.find()) {
                if (Integer.parseInt(matcher.group(2).contains(".")
                        ? matcher.group(2).substring(0, matcher.group(2).indexOf('.'))
                        : matcher.group(2)) == resolution) {
                    continue;
                }
                matcher.appendReplacement(out, Matcher.quoteReplacement(
                        matcher.group(1) + resolution + matcher.group(3)));
                localChange = true;
            }
            if (localChange) {
                matcher.appendTail(out);
                result = out.toString();
                changed = true;
            }
        }
        if (!changed) {
            return null;
        }
        patchedFiles++;
        ShadowMapResolution.notePatched(resolution);
        if (loggedResolution != resolution) {
            loggedResolution = resolution;
            ModernOPT.LOG.info("[ModernOPT] patched shadowMapResolution -> {} in shader sources ({} files so far)",
                    resolution, patchedFiles);
        }
        return result;
    }
}
