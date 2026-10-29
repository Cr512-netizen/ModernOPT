package net.cr512.modernopt.render;

/**
 * MSR 2.0 Motion Vector Collector.
 *
 * Collects entity motion vectors for temporal reprojection without GC allocations.
 * Uses a pre-allocated flat object pool and SSBO for GPU transfer.
 * <p>
 * Critical: NO WeakHashMap or hash tables - all GC-free to avoid micro-freezes.
 */
public final class MotionVectorCollector {

    /** Maximum number of tracked entities. */
    private static final int MAX_ENTITIES = 1024;

    /** Entity motion data packed for SSBO upload. */
    private static final class EntityMotion {
        int entityId;
        float screenX;
        float screenY;
        float deltaX;
        float deltaY;
        boolean active;
    }

    /** Pre-allocated object pool - no allocations during gameplay. */
    private final EntityMotion[] pool = new EntityMotion[MAX_ENTITIES];

    /** SSBO buffer ID for GPU transfer. */
    private int ssboId = 0;

    /** Buffer size in bytes. */
    private static final int STRIDE = 24; // 4 bytes * 6 floats/ints per entity

    /** Whether compute shader path is available (GL 4.3+). */
    private final boolean computeAvailable;

    /** Previous frame screen positions for delta calculation. */
    private final float[] prevScreenX = new float[MAX_ENTITIES];
    private final float[] prevScreenY = new float[MAX_ENTITIES];

    private int entityCount = 0;

    public MotionVectorCollector(boolean computeAvailable) {
        this.computeAvailable = computeAvailable;
        for (int i = 0; i < MAX_ENTITIES; i++) {
            pool[i] = new EntityMotion();
        }
    }

    /**
     * Reset per-frame state. Call at start of each frame.
     */
    public void beginFrame() {
        entityCount = 0;
    }

    /**
     * Record motion for an entity. Call from entity render mixins.
     *
     * @param entityId   Unique entity ID
     * @param screenX    Current screen X (0..1)
     * @param screenY    Current screen Y (0..1)
     */
    public void recordMotion(int entityId, float screenX, float screenY) {
        if (entityCount >= MAX_ENTITIES) return;

        EntityMotion motion = pool[entityCount];
        motion.entityId = entityId;
        motion.screenX = screenX;
        motion.screenY = screenY;
        motion.active = true;

        // Calculate delta from previous frame
        motion.deltaX = screenX - prevScreenX[entityId % MAX_ENTITIES];
        motion.deltaY = screenY - prevScreenY[entityId % MAX_ENTITIES];

        // Store for next frame
        prevScreenX[entityId % MAX_ENTITIES] = screenX;
        prevScreenY[entityId % MAX_ENTITIES] = screenY;

        entityCount++;
    }

    /**
     * Upload motion data to SSBO. Call once per frame before compute dispatch.
     */
    public void uploadToSsbo() {
        if (!computeAvailable || entityCount == 0) return;

        if (ssboId == 0) {
            ssboId = org.lwjgl.opengl.GL15.glGenBuffers();
        }

        org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL31.GL_SHADER_STORAGE_BUFFER, ssboId);
        org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL31.GL_SHADER_STORAGE_BUFFER,
                MAX_ENTITIES * STRIDE, org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW);

        // Map buffer and write entity data
        java.nio.FloatBuffer buffer = org.lwjgl.system.MemoryUtil.memAllocFloat(MAX_ENTITIES * 6);
        try {
            for (int i = 0; i < MAX_ENTITIES; i++) {
                if (i < entityCount) {
                    EntityMotion m = pool[i];
                    buffer.put(m.entityId);
                    buffer.put(m.screenX);
                    buffer.put(m.screenY);
                    buffer.put(m.deltaX);
                    buffer.put(m.deltaY);
                    buffer.put(m.active ? 1.0f : 0.0f);
                } else {
                    // Inactive entity - zero out
                    buffer.put(0);
                    buffer.put(0.0f);
                    buffer.put(0.0f);
                    buffer.put(0.0f);
                    buffer.put(0.0f);
                    buffer.put(0.0f);
                }
            }
            buffer.flip();
            org.lwjgl.opengl.GL15.glBufferSubData(org.lwjgl.opengl.GL31.GL_SHADER_STORAGE_BUFFER,
                    0, buffer);
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(buffer);
        }

        org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL31.GL_SHADER_STORAGE_BUFFER, 0);
    }

    /**
     * Bind SSBO for compute shader. Call before dispatch.
     */
    public void bindSsbo(int bindingPoint) {
        if (computeAvailable && ssboId != 0) {
            org.lwjgl.opengl.GL31.glBindBufferBase(org.lwjgl.opengl.GL31.GL_SHADER_STORAGE_BUFFER,
                    bindingPoint, ssboId);
        }
    }

    public int getEntityCount() {
        return entityCount;
    }

    public boolean isComputeAvailable() {
        return computeAvailable;
    }

    /** Clean up resources. */
    public void dispose() {
        if (ssboId != 0) {
            org.lwjgl.opengl.GL15.glDeleteBuffers(ssboId);
            ssboId = 0;
        }
    }
}
