package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Paint;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The far silhouettes' subscriptions (batch 2026-11-10-custom-shader-system, W10, plan section 4.7): a subscription is
 * answered from the registry's records alone, within its radius (2,048 blocks at most), the 256 nearest first; the
 * presentation cadence then pushes placements, repaints and removals; a dimension change or a logout ends it.
 */
class SilhouettesTest {
    private static final String OVERWORLD = "minecraft:overworld", NETHER = "minecraft:the_nether";
    private static final EquipmentShape PUMP = EquipmentShape.unit(EquipmentShape.Kind.PUMP, .25, 1, EquipmentShape.Heading.NORTH, 0);
    private static final Paint RED = new Paint(0xA72920, 0x5B686D);
    private final UUID player = UUID.randomUUID();
    /** The registry, by dimension then origin: what the source reads. */
    private final Map<String, Map<BlockPos, Silhouettes.Placement>> registry = new HashMap<>();
    private final Silhouettes silhouettes = new Silhouettes((dimension, into) -> registry.getOrDefault(dimension, Map.of()).values().forEach(into));

    private Silhouettes.Placement place(String dimension, int x, int z, Paint paint) {
        var p = new Silhouettes.Placement(new BlockPos(x, 64, z), PUMP, paint);
        registry.computeIfAbsent(dimension, k -> new LinkedHashMap<>()).put(p.origin(), p);
        silhouettes.changed(dimension);
        return p;
    }

    private static Silhouettes.Stand at(double x, double z) {
        return new Silhouettes.Stand(OVERWORLD, x, z);
    }

    /** The online clock: each request and each cadence below comes one 5 s window after the one before, unless a test sets it. */
    private long tick;

    private Silhouettes.Batch watch(Silhouettes.Stand stand, int radius, boolean on) {
        tick += Silhouettes.REQUEST_TICKS;
        return silhouettes.watch(player, stand, radius, on, tick);
    }

    private Map<UUID, Silhouettes.Batch> publish(java.util.function.Function<UUID, Silhouettes.Stand> stands) {
        tick += Silhouettes.REQUEST_TICKS;
        return silhouettes.publish(stands, tick);
    }

    /**
     * Phase 2 (fx-noise): one full set per player per 5 s at most. A second request inside the window is not answered then,
     * but the first cadence after the window ends answers it (the latest radius), and a request after the window is answered
     * at once. An unsubscribe always acts.
     */
    @Test void aPlayerGetsAtMostOneFullSetPer5Seconds() {
        place(OVERWORLD, 0, 0, Paint.DEFAULT);
        place(OVERWORLD, 100, 0, Paint.DEFAULT);
        assertNotNull(silhouettes.watch(player, at(0, 0), 512, true, 1000));
        assertNull(silhouettes.watch(player, at(0, 0), 12, true, 1020), "inside the window: nothing now");
        assertNull(silhouettes.watch(player, at(0, 0), 512, true, 1040), "still inside: the latest request waits");
        assertEquals(Map.of(), silhouettes.publish(u -> at(0, 0), 1060), "the window has not ended: nothing");
        var late = silhouettes.publish(u -> at(0, 0), 1100).get(player);
        assertNotNull(late, "the cadence after the window answers the waiting request");
        assertTrue(late.reset());
        assertEquals(2, late.placed().size(), "the latest radius, 512 blocks");
        assertNull(silhouettes.watch(player, at(0, 0), 512, true, 1150), "a new window from that answer");
        assertNull(silhouettes.watch(player, at(0, 0), 512, false, 1160), "an unsubscribe acts at once");
        assertFalse(silhouettes.subscribed(player));
        assertTrue(silhouettes.idle(), "the unsubscribe also drops the waiting request");
        assertNotNull(silhouettes.watch(player, at(0, 0), 512, true, 1200), "after the window: answered at once");
    }

    @Test void theRadiusIsCappedAndOnlyTheNearest256Arrive() {
        for (int i = 0; i < 300; i++) place(OVERWORLD, 5 * i, 0, Paint.DEFAULT);
        place(OVERWORLD, 5000, 0, Paint.DEFAULT);
        place(NETHER, 0, 0, Paint.DEFAULT);
        assertEquals(Silhouettes.MAXIMUM_RADIUS, Silhouettes.radius(100_000));
        assertEquals(0, Silhouettes.radius(-5));
        var batch = watch(at(0, 0), 100_000, true);
        assertTrue(batch.reset());
        assertEquals(Silhouettes.MAXIMUM_STRUCTURES, batch.placed().size(), "the 256 nearest");
        for (int i = 0; i < Silhouettes.MAXIMUM_STRUCTURES; i++) assertEquals(new BlockPos(5 * i, 64, 0), batch.placed().get(i).origin(), "nearest first");
        // Only registry facts: each placement is the record the registry holds, unchanged.
        for (var p : batch.placed()) assertSame(registry.get(OVERWORLD).get(p.origin()), p);
        var near = watch(at(0, 0), 12, true);
        assertEquals(3, near.placed().size(), "within 12 blocks: the boxes at 0, 5 and 10");
        assertTrue(near.placed().stream().noneMatch(p -> p.origin().getX() == 5000));
    }

    @Test void placementsRepaintsAndRemovalsArePushedAtTheCadence() {
        var a = place(OVERWORLD, 0, 0, Paint.DEFAULT);
        var b = place(OVERWORLD, 40, 0, Paint.DEFAULT);
        assertEquals(2, watch(at(0, 0), 512, true).placed().size());
        assertEquals(Map.of(), publish(u -> at(0, 0)), "nothing changed, nobody moved: nothing is sent");
        var c = place(OVERWORLD, 80, 0, Paint.DEFAULT);
        var repainted = place(OVERWORLD, 40, 0, RED);
        registry.get(OVERWORLD).remove(a.origin());
        silhouettes.changed(OVERWORLD);
        var sent = publish(u -> at(0, 0)).get(player);
        assertFalse(sent.reset());
        assertEquals(Set.of(c, repainted), Set.copyOf(sent.placed()), "the new one and the repainted one");
        assertEquals(List.of(a.origin()), sent.removed(), "the removed one");
        assertNotEquals(b, repainted);
        // A walk away past the radius removes what fell out of it.
        var walked = publish(u -> at(10_000, 0)).get(player);
        assertEquals(Set.of(b.origin(), c.origin()), Set.copyOf(walked.removed()));
        assertEquals(List.of(), walked.placed());
    }

    @Test void aDimensionChangeOrALogoutEndsTheSubscription() {
        place(OVERWORLD, 0, 0, Paint.DEFAULT);
        watch(at(0, 0), 512, true);
        assertTrue(silhouettes.subscribed(player));
        place(OVERWORLD, 30, 0, Paint.DEFAULT);
        assertEquals(Map.of(), publish(u -> new Silhouettes.Stand(NETHER, 0, 0)), "it changed dimension: nothing is sent");
        assertFalse(silhouettes.subscribed(player));
        watch(at(0, 0), 512, true);
        assertEquals(Map.of(), publish(u -> null), "it left");
        assertFalse(silhouettes.subscribed(player));
        watch(at(0, 0), 512, true);
        silhouettes.forget(player);
        assertTrue(silhouettes.idle());
        assertNull(watch(at(0, 0), 512, false), "an unsubscribe sends nothing");
        assertFalse(silhouettes.subscribed(player));
    }
}
