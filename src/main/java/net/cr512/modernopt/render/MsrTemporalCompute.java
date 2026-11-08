package net.cr512.modernopt.render;

import net.cr512.modernopt.ModernOPT;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Raw OpenGL 4.3 compute program for the MSR 2.0 temporal pass.
 *
 * <p>Minecraft's {@link net.minecraft.client.renderer.ShaderInstance} can only wrap graphics
 * programs (vertex + fragment), so {@code glDispatchCompute} on the fragment temporal shader is
 * invalid. This helper compiles {@code assets/modernopt/shaders/core/msr_temporal.comp}
 * directly, owns its uniform locations, and exposes the small subset of setters the pipeline
 * needs. It is only created when the driver reports GL 4.3; otherwise {@link RenderPipeline}
 * keeps using the fragment temporal fallback.</p>
 */
public final class MsrTemporalCompute {

    private final int program;
    private final Map<String, Integer> uniformLocations = new HashMap<>();

    private MsrTemporalCompute(int program) {
        this.program = program;
    }

    public static MsrTemporalCompute load(ResourceProvider resources) throws IOException {
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
                ModernOPT.MOD_ID, "shaders/core/msr_temporal.comp");
        Resource resource = resources.getResource(location).orElseThrow(
                () -> new IOException("missing MSR compute shader " + location));
        String source;
        try (InputStream input = resource.open()) {
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        return compile(source);
    }

    private static MsrTemporalCompute compile(String source) throws IOException {
        int shader = GL20C.glCreateShader(GL43C.GL_COMPUTE_SHADER);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11C.GL_FALSE) {
            String log = GL20C.glGetShaderInfoLog(shader);
            GL20C.glDeleteShader(shader);
            throw new IOException("MSR temporal compute shader failed to compile:\n" + log);
        }

        int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, shader);
        GL20C.glLinkProgram(program);
        GL20C.glDeleteShader(shader);
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
            String log = GL20C.glGetProgramInfoLog(program);
            GL20C.glDeleteProgram(program);
            throw new IOException("MSR temporal compute program failed to link:\n" + log);
        }
        return new MsrTemporalCompute(program);
    }

    public void bind() {
        GL20C.glUseProgram(program);
    }

    public void unbind() {
        GL20C.glUseProgram(0);
    }

    public void delete() {
        GL20C.glDeleteProgram(program);
    }

    private int location(String name) {
        return uniformLocations.computeIfAbsent(name, n -> GL20C.glGetUniformLocation(program, n));
    }

    public void setInt(String name, int value) {
        int location = location(name);
        if (location >= 0) {
            GL20C.glUniform1i(location, value);
        }
    }

    public void setFloat(String name, float value) {
        int location = location(name);
        if (location >= 0) {
            GL20C.glUniform1f(location, value);
        }
    }

    public void setVec2(String name, float x, float y) {
        int location = location(name);
        if (location >= 0) {
            GL20C.glUniform2f(location, x, y);
        }
    }

    public void setVec3(String name, float x, float y, float z) {
        int location = location(name);
        if (location >= 0) {
            GL20C.glUniform3f(location, x, y, z);
        }
    }

    /** Scratch array: one upload per frame, never retained. */
    private static final float[] MATRIX_SCRATCH = new float[16];

    public void setMatrix4(String name, Matrix4f matrix) {
        int location = location(name);
        if (location < 0) {
            return;
        }
        matrix.get(MATRIX_SCRATCH);
        GL20C.glUniformMatrix4fv(location, false, MATRIX_SCRATCH);
    }

    /** Binds a texture as a write-only image for {@code imageStore}. */
    public void bindImage(int unit, int texture, int format) {
        GL42C.glBindImageTexture(unit, texture, 0, false, 0, GL15C.GL_WRITE_ONLY, format);
    }

    /** Detaches an image unit, so a destroyed texture can never stay bound. */
    public static void unbindImage(int unit, int format) {
        GL42C.glBindImageTexture(unit, 0, 0, false, 0, GL15C.GL_WRITE_ONLY, format);
    }
}
