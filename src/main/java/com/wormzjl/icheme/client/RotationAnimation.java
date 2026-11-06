package com.wormzjl.icheme.client;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.network.RotationNetwork;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.science.equipment.VisualRotation;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.world.level.block.ShaftBlock;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Client-owned speed snapshots and continuous angles. Chunk workers only submit immutable shaft positions. */
@EventBusSubscriber(modid = IChemE.MOD_ID, value = Dist.CLIENT)
public final class RotationAnimation {
    private static final Set<BlockPos> DISCOVERED = ConcurrentHashMap.newKeySet();
    private static final Set<BlockPos> SHAFTS = new HashSet<>(), WATCHED = new HashSet<>();
    private static final Map<BlockPos, Spin> SPINS = new HashMap<>();
    private static ClientLevel world;
    private static int age;

    private static final class Spin {
        final VisualRotation.Clock clock = new VisualRotation.Clock();
        final VisualRotation.Clock outputClock = new VisualRotation.Clock();
        double rpm, outputRpm;
    }

    private RotationAnimation() {
    }

    /** Safe from chunk workers: the client tick alone reads blocks and owns all animation state. */
    public static void discover(BlockPos position) {
        DISCOVERED.add(position.immutable());
    }

    static Set<BlockPos> shafts() {
        return Collections.unmodifiableSet(SHAFTS);
    }

    /** Every machine belonging to a shaft train uses that train's published speeds. */
    public static boolean live(EquipmentShape shape) {
        return UnitDesign.shafted(shape.kind());
    }

    public static void accept(RotationNetwork.State state) {
        var level = Minecraft.getInstance().level;
        if (level == null || level != world || !WATCHED.contains(state.position())) return;
        var spin = SPINS.computeIfAbsent(state.position(), p -> new Spin());
        spin.clock.at(level.getGameTime() / 20.0, MachineAnimation.running() ? spin.rpm : 0);
        spin.outputClock.at(level.getGameTime() / 20.0, MachineAnimation.running() ? spin.outputRpm : 0);
        spin.rpm = state.rpm();
        spin.outputRpm = state.outputRpm();
    }

    /** Unknown speeds stand still; a stop freezes the current angle rather than returning to an initial pose. */
    public static double turns(BlockPos position, double seconds) {
        var spin = SPINS.get(position);
        return spin == null ? 0 : spin.clock.at(seconds, MachineAnimation.running() ? spin.rpm : 0);
    }

    /** Choose a gearbox part's nearest coupling so its two ends follow their own operating rpm. */
    public static double turns(EquipmentShape shape, BlockPos origin, ProcessUnit.Motion motion, double seconds) {
        if (shape.kind() != EquipmentShape.Kind.GEARBOX) return turns(origin, seconds);
        var spin = SPINS.get(origin);
        if (spin == null) return 0;
        boolean output = false;
        double nearest = Double.POSITIVE_INFINITY;
        var pivot = motion.pivot();
        for (var end : ShapeRasterizer.shafts(shape)) {
            double dx = pivot[0] - end.cell().x() - .5 - .5 * end.dx(), dy = pivot[1] - end.cell().y() - .5 - .5 * end.dy(),
                    dz = pivot[2] - end.cell().z() - .5 - .5 * end.dz();
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < nearest) {
                nearest = distance;
                output = end.role() == EquipmentShape.ShaftEnd.Role.DRIVER_OUTPUT;
            }
        }
        return output ? spin.outputClock.at(seconds, MachineAnimation.running() ? spin.outputRpm : 0) : turns(origin, seconds);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (world != mc.level) {
            if (mc.getConnection() != null) RotationNetwork.request(List.of(), List.copyOf(WATCHED));
            SHAFTS.clear(); WATCHED.clear(); SPINS.clear(); world = mc.level; age = 0;
        }
        if (world == null || mc.player == null || mc.getConnection() == null) return;
        for (var position : DISCOVERED)
            if (DISCOVERED.remove(position) && world.hasChunkAt(position) && world.getBlockState(position).getBlock() instanceof ShaftBlock) SHAFTS.add(position);
        if (age++ % 20 != 0) return;
        SHAFTS.removeIf(p -> !world.hasChunkAt(p) || !(world.getBlockState(p).getBlock() instanceof ShaftBlock));
        var wanted = new HashSet<>(SHAFTS);
        for (var cell : EquipmentShellBlockEntity.clientMovingCells())
            if (!cell.isRemoved() && cell.getLevel() == world && cell.shape() != null && cell.origin() != null && live(cell.shape())) wanted.add(cell.origin());
        var point = mc.player.position();
        double range = RotationNetwork.RANGE - 8;
        var near = wanted.stream().filter(p -> p.distToCenterSqr(point) <= range * range)
                .sorted(Comparator.comparingDouble((BlockPos p) -> p.distToCenterSqr(point)).thenComparingLong(BlockPos::asLong))
                .limit(RotationNetwork.MAXIMUM_WATCHED).toList();
        var selected = new HashSet<>(near);
        var gone = WATCHED.stream().filter(p -> !selected.contains(p)).toList();
        // Retry an unanswered watch: a freshly placed machine's queued engine registration may not exist yet.
        var added = near.stream().filter(p -> !WATCHED.contains(p) || !SPINS.containsKey(p)).toList();
        WATCHED.removeAll(gone); gone.forEach(SPINS::remove); WATCHED.addAll(near);
        RotationNetwork.request(added, gone);
    }
}
