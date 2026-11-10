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

    @Test void theRadiusIsCappedAndOnlyTheNearest256Arrive() {
        for (int i = 0; i < 300; i++) place(OVERWORLD, 5 * i, 0, Paint.DEFAULT);
        place(OVERWORLD, 5000, 0, Paint.DEFAULT);
        place(NETHER, 0, 0, Paint.DEFAULT);
        assertEquals(Silhouettes.MAXIMUM_RADIUS, Silhouettes.radius(100_000));
        assertEquals(0, Silhouettes.radius(-5));
        var batch = silhouettes.watch(player, at(0, 0), 100_000, true);
        assertTrue(batch.reset());
        assertEquals(Silhouettes.MAXIMUM_STRUCTURES, batch.placed().size(), "the 256 nearest");
        for (int i = 0; i < Silhouettes.MAXIMUM_STRUCTURES; i++) assertEquals(new BlockPos(5 * i, 64, 0), batch.placed().get(i).origin(), "nearest first");
        // Only registry facts: each placement is the record the registry holds, unchanged.
        for (var p : batch.placed()) assertSame(registry.get(OVERWORLD).get(p.origin()), p);
        var near = silhouettes.watch(player, at(0, 0), 12, true);
        assertEquals(3, near.placed().size(), "within 12 blocks: the boxes at 0, 5 and 10");
        assertTrue(near.placed().stream().noneMatch(p -> p.origin().getX() == 5000));
    }

    @Test void placementsRepaintsAndRemovalsArePushedAtTheCadence() {
        var a = place(OVERWORLD, 0, 0, Paint.DEFAULT);
        var b = place(OVERWORLD, 40, 0, Paint.DEFAULT);
        assertEquals(2, silhouettes.watch(player, at(0, 0), 512, true).placed().size());
        assertEquals(Map.of(), silhouettes.publish(u -> at(0, 0)), "nothing changed, nobody moved: nothing is sent");
        var c = place(OVERWORLD, 80, 0, Paint.DEFAULT);
        var repainted = place(OVERWORLD, 40, 0, RED);
        registry.get(OVERWORLD).remove(a.origin());
        silhouettes.changed(OVERWORLD);
        var sent = silhouettes.publish(u -> at(0, 0)).get(player);
        assertFalse(sent.reset());
        assertEquals(Set.of(c, repainted), Set.copyOf(sent.placed()), "the new one and the repainted one");
        assertEquals(List.of(a.origin()), sent.removed(), "the removed one");
        assertNotEquals(b, repainted);
        // A walk away past the radius removes what fell out of it.
        var walked = silhouettes.publish(u -> at(10_000, 0)).get(player);
        assertEquals(Set.of(b.origin(), c.origin()), Set.copyOf(walked.removed()));
        assertEquals(List.of(), walked.placed());
    }

    @Test void aDimensionChangeOrALogoutEndsTheSubscription() {
        place(OVERWORLD, 0, 0, Paint.DEFAULT);
        silhouettes.watch(player, at(0, 0), 512, true);
        assertTrue(silhouettes.subscribed(player));
        place(OVERWORLD, 30, 0, Paint.DEFAULT);
        assertEquals(Map.of(), silhouettes.publish(u -> new Silhouettes.Stand(NETHER, 0, 0)), "it changed dimension: nothing is sent");
        assertFalse(silhouettes.subscribed(player));
        silhouettes.watch(player, at(0, 0), 512, true);
        assertEquals(Map.of(), silhouettes.publish(u -> null), "it left");
        assertFalse(silhouettes.subscribed(player));
        silhouettes.watch(player, at(0, 0), 512, true);
        silhouettes.forget(player);
        assertTrue(silhouettes.idle());
        assertNull(silhouettes.watch(player, at(0, 0), 512, false), "an unsubscribe sends nothing");
        assertFalse(silhouettes.subscribed(player));
    }
}
