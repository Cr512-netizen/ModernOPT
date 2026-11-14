package net.cr512.modernopt.render;

import net.cr512.modernopt.ModernOPT;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.lwjgl.opengl.GL33C;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bench-only stage timeline for the world pass.
 *
 * <p>{@link GpuProfiler} already times the whole world section and a few known sub-passes, but a
 * multi millisecond gap remains between "world" and the sum of the sub-passes under some
 * conditions. This profiler puts a GPU timestamp on every {@link RenderLevelStageEvent} boundary
 * and reports the resulting intervals, which localises the missing time to a single vanilla stage
 * (entities, block entities, particles, weather, translucent terrain, ...).</p>
 *
 * <p>Queries are ring buffered over three frames, so reading them never waits on the GPU.</p>
 */
@EventBusSubscriber(modid = ModernOPT.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class RenderStageProfiler {

    private static final boolean ENABLED = Boolean.getBoolean("modernopt.bench");
    private static final int SLOTS = 3;
    private static final int MAX_MARKS = 16;

    private static final int[][] queries = new int[SLOTS][MAX_MARKS];
    private static final String[][] names = new String[SLOTS][MAX_MARKS];
    private static final int[] marks = new int[SLOTS];
    private static int frame;
    private static long lastLogNanos = System.nanoTime();

    private static final Map<String, Double> accumMs = new LinkedHashMap<>();
    private static final Map<String, Integer> samples = new LinkedHashMap<>();

    private RenderStageProfiler() {
    }

    @SubscribeEvent
    public static void onStage(RenderLevelStageEvent event) {
        if (!ENABLED) {
            return;
        }
        int slot = frame % SLOTS;
        int index = marks[slot];
        if (index >= MAX_MARKS) {
            return;
        }
        int query = queries[slot][index];
        if (query == 0) {
            query = GL33C.glGenQueries();
            queries[slot][index] = query;
        }
        names[slot][index] = event.getStage().toString();
        marks[slot]++;
        GL33C.glQueryCounter(query, GL33C.GL_TIMESTAMP);

        // AFTER_LEVEL is the last stage of the frame: close the slot and read the frame that ended
        // two frames ago, whose queries are guaranteed to be resolved.
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            marks[slot] = index + 1;
            frame++;
            readResolvedSlot((frame + SLOTS - 2) % SLOTS);
            marks[(frame + SLOTS - 2) % SLOTS] = 0;
        }
    }

    private static void readResolvedSlot(int slot) {
        int count = marks[slot];
        if (count < 2) {
            return;
        }
        long previous = 0L;
        for (int i = 0; i < count; i++) {
            long stamp = GL33C.glGetQueryObjectui64(queries[slot][i], GL33C.GL_QUERY_RESULT);
            if (i > 0 && stamp > previous) {
                String name = names[slot][i];
                double delta = (stamp - previous) / 1_000_000.0;
                accumMs.merge(name, delta, Double::sum);
                samples.merge(name, 1, Integer::sum);
            }
            previous = stamp;
        }
    }

    /** Logs averaged stage intervals roughly every two seconds. */
    public static void maybeLog() {
        if (!ENABLED) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastLogNanos < 2_000_000_000L) {
            return;
        }
        lastLogNanos = now;
        if (accumMs.isEmpty()) {
            return;
        }
        StringBuilder line = new StringBuilder("[BENCH][STAGE]");
        for (Map.Entry<String, Double> entry : accumMs.entrySet()) {
            int count = samples.getOrDefault(entry.getKey(), 0);
            double average = count == 0 ? 0 : entry.getValue() / count;
            line.append(' ').append(entry.getKey()).append('=')
                    .append(String.format("%.2f", average));
        }
        ModernOPT.LOG.info(line.toString());
        accumMs.clear();
        samples.clear();
    }
}
