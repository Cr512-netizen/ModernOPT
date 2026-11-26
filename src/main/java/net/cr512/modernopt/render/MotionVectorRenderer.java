// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.cr512.modernopt.ModernOPT;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Per-entity screen-space motion vectors for MSR2.
 *
 * <p>Minecraft does not provide a velocity buffer, so this class reconstructs one at the source
 * resolution. While entities are rendered, their projected bounding box and screen-space centre
 * are recorded. At the end of the world pass a small GL pass rasterises those boxes into an
 * RGBA16F texture: RGB carries {@code previousUv - currentUv}, and the blue channel is the
 * coverage mask. The temporal compute shader uses the vector for entity pixels and falls back to
 * the camera reprojection for the rest of the scene.</p>
 *
 * <p>The box is an approximation: Minecraft does not expose per-vertex skinning or per-object
 * previous transforms for arbitrary renderers. The depth range of the box prevents the vector
 * from being applied to visible geometry behind the entity, and the temporal shader still applies
 * its depth test and reactive mask. Particles and transparent entities that do not write depth
 * remain camera-reprojected.</p>
 */
public final class MotionVectorRenderer {

    private static final int MAX_ENTITIES = 512;
    private static final int FLOATS_PER_VERTEX = 6; // position xy, velocity xy, depth range xy
    private static final int VERTICES_PER_QUAD = 6;
    private static final int STRIDE = FLOATS_PER_VERTEX * Float.BYTES;

    /** A projected entity box and its screen-space motion, reused between frames. */
    private static final class Record {
        float minX;
        float minY;
        float maxX;
        float maxY;
        float velocityX;
        float velocityY;
        float depthMin;
        float depthMax;
        float centerDepth;
    }

    /** Previous centre for one entity id, reused between frames. */
    private static final class Track {
        long lastFrame = Long.MIN_VALUE;
        float previousU;
        float previousV;
        boolean hasPrevious;
        double worldX;
        double worldY;
        double worldZ;
        boolean hasWorld;
        int recordSlot = -1;
        long recordFrame = -1L;
    }

    private static final Comparator<Record> FAR_TO_NEAR =
            (a, b) -> Float.compare(b.centerDepth, a.centerDepth);

    private final Int2ObjectOpenHashMap<Track> tracks = new Int2ObjectOpenHashMap<>();
    private final Record[] records = new Record[MAX_ENTITIES];
    private final FloatBuffer vertices = MemoryUtil.memAllocFloat(
            MAX_ENTITIES * VERTICES_PER_QUAD * FLOATS_PER_VERTEX);
    private final Vector4f scratch = new Vector4f();

    private final int program;
    private final int vao;
    private final int vbo;
    private final int depthTextureUniform;

    private HalfFloatTarget target;
    private int recordCount;
    private long frameIndex;
    private int viewWidth = 1;
    private int viewHeight = 1;
    private boolean logged;
    private boolean loggedMotion;
    private boolean disabled;

    public MotionVectorRenderer() throws IOException {
        RenderSystem.assertOnRenderThreadOrInit();
        for (int i = 0; i < MAX_ENTITIES; i++) {
            records[i] = new Record();
        }
        program = compileProgram();

        int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        int previousBuffer = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
        try {
            vao = GL30C.glGenVertexArrays();
            vbo = GL15C.glGenBuffers();
            GL30C.glBindVertexArray(vao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vbo);
            GL20C.glEnableVertexAttribArray(0);
            GL20C.glVertexAttribPointer(0, 2, GL11C.GL_FLOAT, false, STRIDE, 0L);
            GL20C.glEnableVertexAttribArray(1);
            GL20C.glVertexAttribPointer(1, 2, GL11C.GL_FLOAT, false, STRIDE, 2L * Float.BYTES);
            GL20C.glEnableVertexAttribArray(2);
            GL20C.glVertexAttribPointer(2, 2, GL11C.GL_FLOAT, false, STRIDE, 4L * Float.BYTES);
            depthTextureUniform = GL20C.glGetUniformLocation(program, "DepthTexture");
        } finally {
            GL30C.glBindVertexArray(previousVao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, previousBuffer);
        }
    }

    /** Starts a new world frame. Call before entities are rendered. */
    public void beginFrame(long index, int width, int height) {
        frameIndex = index;
        recordCount = 0;
        if (width > 0 && height > 0) {
            viewWidth = width;
            viewHeight = height;
        }
    }

    /**
     * Records one entity. The coordinates are Minecraft's camera-relative render position, not
     * world coordinates, because {@code currentView} is the rotation-only view matrix used by the
     * world pass.
     */
    public void record(Entity entity, double x, double y, double z,
            Matrix4f view, Matrix4f projection) {
        if (recordCount >= MAX_ENTITIES || entity == null || entity.isInvisible()) {
            return;
        }
        int id = entity.getId();
        if (entity.getBbWidth() * entity.getBbHeight() < 0.5) {
            // Dropped items, arrows and XP orbs are handled by the reactive mask. Their tiny
            // boxes would otherwise apply a velocity to the ground behind them.
            return;
        }
        Track track = tracks.get(id);
        if (track != null && track.lastFrame == frameIndex) {
            // RenderDispatcher can be called more than once for the same entity in a frame
            // (layers/outline). The first projection is enough.
            return;
        }

        AABB box = entity.getBoundingBox();
        double ex = entity.getX();
        double ey = entity.getY();
        double ez = entity.getZ();
        double minWorldX = x + (box.minX - ex);
        double maxWorldX = x + (box.maxX - ex);
        double minWorldY = y + (box.minY - ey);
        double maxWorldY = y + (box.maxY - ey);
        double minWorldZ = z + (box.minZ - ez);
        double maxWorldZ = z + (box.maxZ - ez);

        float minNdcX = Float.POSITIVE_INFINITY;
        float minNdcY = Float.POSITIVE_INFINITY;
        float maxNdcX = Float.NEGATIVE_INFINITY;
        float maxNdcY = Float.NEGATIVE_INFINITY;
        float minDepth = Float.POSITIVE_INFINITY;
        float maxDepth = Float.NEGATIVE_INFINITY;

        for (int corner = 0; corner < 8; corner++) {
            double cx = (corner & 1) == 0 ? minWorldX : maxWorldX;
            double cy = (corner & 2) == 0 ? minWorldY : maxWorldY;
            double cz = (corner & 4) == 0 ? minWorldZ : maxWorldZ;
            if (!project(cx, cy, cz, view, projection)) {
                // A box crossing the near plane produces meaningless NDC values. Skipping the
                // whole entity is safer than covering the screen with a bogus quad.
                return;
            }
            float ndcX = scratch.x / scratch.w;
            float ndcY = scratch.y / scratch.w;
            float depth = (scratch.z / scratch.w) * 0.5F + 0.5F;
            minNdcX = Math.min(minNdcX, ndcX);
            minNdcY = Math.min(minNdcY, ndcY);
            maxNdcX = Math.max(maxNdcX, ndcX);
            maxNdcY = Math.max(maxNdcY, ndcY);
            minDepth = Math.min(minDepth, depth);
            maxDepth = Math.max(maxDepth, depth);
        }

        if (maxNdcX < -1.0F || minNdcX > 1.0F || maxNdcY < -1.0F || minNdcY > 1.0F) {
            return;
        }

        // Expand by roughly one source pixel so the quad also covers the jittered rasterisation.
        float padX = 2.0F / Math.max(1, viewWidth);
        float padY = 2.0F / Math.max(1, viewHeight);
        minNdcX = Math.max(-1.0F, minNdcX - padX);
        maxNdcX = Math.min(1.0F, maxNdcX + padX);
        minNdcY = Math.max(-1.0F, minNdcY - padY);
        maxNdcY = Math.min(1.0F, maxNdcY + padY);
        if (maxNdcX - minNdcX < 2.0F / 4096.0F || maxNdcY - minNdcY < 2.0F / 4096.0F) {
            return;
        }

        double centerX = x + ((box.minX + box.maxX) * 0.5 - ex);
        double centerY = y + ((box.minY + box.maxY) * 0.5 - ey);
        double centerZ = z + ((box.minZ + box.maxZ) * 0.5 - ez);
        float currentU = (minNdcX + maxNdcX) * 0.5F;
        float currentV = (minNdcY + maxNdcY) * 0.5F;
        if (project(centerX, centerY, centerZ, view, projection)) {
            currentU = (scratch.x / scratch.w) * 0.5F + 0.5F;
            currentV = (scratch.y / scratch.w) * 0.5F + 0.5F;
        }

        if (track == null) {
            track = new Track();
            tracks.put(id, track);
        }

        boolean moving = false;
        if (track.hasWorld) {
            double dx = entity.getX() - track.worldX;
            double dy = entity.getY() - track.worldY;
            double dz = entity.getZ() - track.worldZ;
            moving = dx * dx + dy * dy + dz * dz > 2.5e-5; // ~0.005 blocks
        }
        track.worldX = entity.getX();
        track.worldY = entity.getY();
        track.worldZ = entity.getZ();
        track.hasWorld = true;

        float velocityX = 0.0F;
        float velocityY = 0.0F;
        if (track.hasPrevious && track.lastFrame == frameIndex - 1) {
            velocityX = track.previousU - currentU;
            velocityY = track.previousV - currentV;
        }
        track.previousU = currentU;
        track.previousV = currentV;
        track.hasPrevious = true;
        track.lastFrame = frameIndex;

        if (!moving) {
            // A world-static entity is already reprojected exactly by the camera matrices.
            // Generating a box vector for it would only risk moving the background around its
            // silhouette.
            return;
        }

        int slot;
        if (track.recordFrame == frameIndex && track.recordSlot >= 0) {
            slot = track.recordSlot;
        } else {
            slot = recordCount++;
            track.recordSlot = slot;
            track.recordFrame = frameIndex;
        }
        Record record = records[slot];
        record.minX = minNdcX;
        record.minY = minNdcY;
        record.maxX = maxNdcX;
        record.maxY = maxNdcY;
        record.velocityX = velocityX;
        record.velocityY = velocityY;
        float depthPad = Math.max(0.01F, (maxDepth - minDepth) * 0.5F);
        record.depthMin = Math.max(0.0F, minDepth - depthPad);
        record.depthMax = Math.min(1.0F, maxDepth + depthPad);
        record.centerDepth = (record.depthMin + record.depthMax) * 0.5F;

        if ((frameIndex & 1023L) == 0L && tracks.size() > 2048) {
            tracks.int2ObjectEntrySet().removeIf(entry ->
                    entry.getValue().lastFrame < frameIndex - 600L);
        }
    }

    /**
     * Rasterises the recorded boxes into an RGBA16F texture at the source resolution.
     *
     * @return the colour texture id, or {@code 0} when motion vectors are unavailable
     */
    public int render(int depthTexture, int width, int height) {
        if (disabled || recordCount == 0 || width <= 0 || height <= 0 || depthTexture == 0) {
            return 0;
        }
        try {
            if (target == null || target.width != width || target.height != height) {
                if (target != null) {
                    target.destroyBuffers();
                }
                target = new HalfFloatTarget(width, height);
            }

            ShaderLibrary.beginPassState();
            int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
            int previousBuffer = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
            try {
                target.bindWrite(true);
                GlStateManager._clearColor(0.0F, 0.0F, 0.0F, 0.0F);
                GlStateManager._clear(GL11C.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);

                if (recordCount > 0) {
                    Arrays.sort(records, 0, recordCount, FAR_TO_NEAR);
                    buildVertices();

                    GL20C.glUseProgram(program);
                    GL20C.glUniform1i(depthTextureUniform, 0);
                    GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
                    GlStateManager._bindTexture(depthTexture);

                    GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vbo);
                    GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, vertices, GL15C.GL_STREAM_DRAW);
                    GL30C.glBindVertexArray(vao);
                    GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, recordCount * VERTICES_PER_QUAD);
                }

                if (!logged) {
                    logged = true;
                    ModernOPT.LOG.info("[ModernOPT] MSR2 entity motion vectors active at {}x{} "
                            + "({} quads)", width, height, recordCount);
                }
                if (!loggedMotion && Boolean.getBoolean("modernopt.bench")) {
                    float maxVelocity = 0.0F;
                    for (int i = 0; i < recordCount; i++) {
                        Record record = records[i];
                        maxVelocity = Math.max(maxVelocity, (float) Math.sqrt(
                                record.velocityX * record.velocityX
                                        + record.velocityY * record.velocityY));
                    }
                    if (maxVelocity > 1.0e-5F) {
                        loggedMotion = true;
                        ModernOPT.LOG.info("[BENCH][MV] {} quads, maxVelocity={} uv",
                                recordCount, String.format(java.util.Locale.ROOT, "%.5f", maxVelocity));
                    }
                }
            } finally {
                GL30C.glBindVertexArray(previousVao);
                GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, previousBuffer);
                GL20C.glUseProgram(0);
                ShaderLibrary.endPassState();
            }
            return target.getColorTextureId();
        } catch (Throwable throwable) {
            disabled = true;
            ModernOPT.LOG.error("[ModernOPT] MSR2 motion-vector pass failed; using camera "
                    + "reprojection", throwable);
            if (target != null) {
                target.destroyBuffers();
                target = null;
            }
            return 0;
        }
    }

    private void buildVertices() {
        vertices.clear();
        for (int i = 0; i < recordCount; i++) {
            Record record = records[i];
            float minX = record.minX;
            float minY = record.minY;
            float maxX = record.maxX;
            float maxY = record.maxY;
            float vx = record.velocityX;
            float vy = record.velocityY;
            float d0 = record.depthMin;
            float d1 = record.depthMax;
            putVertex(minX, minY, vx, vy, d0, d1);
            putVertex(maxX, minY, vx, vy, d0, d1);
            putVertex(maxX, maxY, vx, vy, d0, d1);
            putVertex(minX, minY, vx, vy, d0, d1);
            putVertex(maxX, maxY, vx, vy, d0, d1);
            putVertex(minX, maxY, vx, vy, d0, d1);
        }
        vertices.flip();
    }

    private void putVertex(float x, float y, float vx, float vy, float d0, float d1) {
        vertices.put(x).put(y).put(vx).put(vy).put(d0).put(d1);
    }

    private boolean project(double x, double y, double z, Matrix4f view, Matrix4f projection) {
        scratch.set((float) x, (float) y, (float) z, 1.0F);
        scratch.mul(view);
        scratch.mul(projection);
        return scratch.w > 1.0e-4F;
    }

    public void destroy() {
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
        MemoryUtil.memFree(vertices);
        GL15C.glDeleteBuffers(vbo);
        GL30C.glDeleteVertexArrays(vao);
        GL20C.glDeleteProgram(program);
        tracks.clear();
    }

    private static int compileProgram() throws IOException {
        int vertex = compileShader(GL20C.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragment = compileShader(GL20C.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        int linked = GL20C.glCreateProgram();
        GL20C.glAttachShader(linked, vertex);
        GL20C.glAttachShader(linked, fragment);
        GL20C.glBindAttribLocation(linked, 0, "aPos");
        GL20C.glBindAttribLocation(linked, 1, "aVelocity");
        GL20C.glBindAttribLocation(linked, 2, "aDepthRange");
        GL20C.glLinkProgram(linked);
        GL20C.glDeleteShader(vertex);
        GL20C.glDeleteShader(fragment);
        if (GL20C.glGetProgrami(linked, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
            String log = GL20C.glGetProgramInfoLog(linked);
            GL20C.glDeleteProgram(linked);
            throw new IOException("MSR2 motion-vector program failed to link:\n" + log);
        }
        return linked;
    }

    private static int compileShader(int type, String source) throws IOException {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11C.GL_FALSE) {
            String log = GL20C.glGetShaderInfoLog(shader);
            GL20C.glDeleteShader(shader);
            throw new IOException("MSR2 motion-vector shader failed to compile:\n" + log);
        }
        return shader;
    }

    private static final String VERTEX_SHADER = """
            #version 150
            in vec2 aPos;
            in vec2 aVelocity;
            in vec2 aDepthRange;
            out vec2 vUv;
            out vec2 vVelocity;
            out vec2 vDepthRange;
            void main() {
                vUv = aPos * 0.5 + 0.5;
                vVelocity = aVelocity;
                vDepthRange = aDepthRange;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
            """;

    private static final String FRAGMENT_SHADER = """
            #version 150
            uniform sampler2D DepthTexture;
            in vec2 vUv;
            in vec2 vVelocity;
            in vec2 vDepthRange;
            out vec4 fragColor;
            void main() {
                float depth = texture(DepthTexture, vUv).r;
                if (depth < vDepthRange.x || depth > vDepthRange.y) {
                    discard;
                }
                // RG = screen-space velocity in UV units, B = coverage mask.
                fragColor = vec4(vVelocity, 1.0, 1.0);
            }
            """;
}
