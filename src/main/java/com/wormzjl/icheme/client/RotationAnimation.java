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
import net.minecraft.core.Direction;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Client-owned speed snapshots and continuous angles. Chunk workers only submit immutable shaft positions. */
@EventBusSubscriber(modid = IChemE.MOD_ID, value = Dist.CLIENT)
public final class RotationAnimation {
    private static final Set<BlockPos> DISCOVERED = ConcurrentHashMap.newKeySet();
    private static final Set<BlockPos> SHAFTS = new HashSet<>(), WATCHED = new HashSet<>();
    private static final Map<Endpoint, Spin> SPINS = new HashMap<>();
    private static final Map<BlockPos, RotationNetwork.State> STATES = new HashMap<>();
    private static ClientLevel world;
    private static int age;

    private static final class Spin {
        final VisualRotation.Clock clock;
        double rpm;
        boolean published;

        Spin() {
            clock = new VisualRotation.Clock();
        }

        Spin(Spin previous) {
            clock = previous.clock.copy();
            rpm = previous.rpm;
            published = previous.published;
        }
    }

    /** A loose shaft, a machine's single coupling, or one of a gearbox's two separate lines. */
    private record Endpoint(BlockPos position, boolean output) {
        private Endpoint {
            position = position.immutable();
        }
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
        STATES.put(state.position(), state);
        update(SPINS.computeIfAbsent(new Endpoint(state.position(), false), p -> new Spin()), state.rpm(), level.getGameTime() / 20.0);
        var output = SPINS.get(new Endpoint(state.position(), true));
        if (output != null) update(output, state.outputRpm(), level.getGameTime() / 20.0);
    }

    private static void update(Spin spin, double rpm, double seconds) {
        spin.clock.at(seconds, MachineAnimation.running() ? spin.rpm : 0);
        spin.rpm = rpm;
        spin.published = true;
    }

    /** Unknown speeds stand still; a stop freezes the current angle rather than returning to an initial pose. */
    public static double turns(BlockPos position, double seconds) {
        var spin = SPINS.get(new Endpoint(position, false));
        return spin == null ? 0 : spin.clock.at(seconds, MachineAnimation.running() ? spin.rpm : 0);
    }

    /** Choose a gearbox part's nearest coupling so its two ends follow their own operating rpm. */
    public static double turns(EquipmentShape shape, BlockPos origin, ProcessUnit.Motion motion, double seconds) {
        if (shape.kind() != EquipmentShape.Kind.GEARBOX) return turns(origin, seconds);
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
        var spin = SPINS.get(new Endpoint(origin, output));
        return spin == null ? 0 : spin.clock.at(seconds, MachineAnimation.running() ? spin.rpm : 0);
    }

    /** Visual connectivity only: adjacent, coaxial shaft blocks share an angle, including the coupling at either end. */
    private static void alignClocks(Map<BlockPos, EquipmentShape> machines) {
        var parents = new HashMap<BlockPos, BlockPos>();
        var axes = new HashMap<BlockPos, Direction.Axis>();
        for (var position : SHAFTS) {
            parents.put(position, position);
            axes.put(position, world.getBlockState(position).getValue(ShaftBlock.AXIS));
        }
        for (var entry : axes.entrySet()) {
            var next = entry.getKey().relative(Direction.fromAxisAndDirection(entry.getValue(), Direction.AxisDirection.POSITIVE));
            if (axes.get(next) != entry.getValue()) continue;
            var a = root(parents, entry.getKey());
            var b = root(parents, next);
            if (a.compareTo(b) < 0) parents.put(b, a); else parents.put(a, b);
        }
        var aliases = new LinkedHashMap<Endpoint, Endpoint>();
        // Existing equipment clocks take priority when a newly placed shaft joins them; the whole line then uses it.
        for (var machine : machines.entrySet()) {
            var origin = machine.getKey();
            var shape = machine.getValue();
            for (var end : ShapeRasterizer.shafts(shape)) {
                boolean output = shape.kind() == EquipmentShape.Kind.GEARBOX && end.role() == EquipmentShape.ShaftEnd.Role.DRIVER_OUTPUT;
                var endpoint = new Endpoint(origin, output);
                var block = end.block();
                var position = origin.offset(block.x(), block.y(), block.z());
                int axis = end.dx() != 0 ? 0 : end.dy() != 0 ? 1 : 2;
                var heading = axes.get(position);
                aliases.put(endpoint, heading != null && heading.ordinal() == axis ? new Endpoint(root(parents, position), false) : endpoint);
            }
        }
        for (var position : SHAFTS) aliases.put(new Endpoint(position, false), new Endpoint(root(parents, position), false));
        var lines = new HashMap<Endpoint, Spin>();
        for (var entry : aliases.entrySet()) {
            var previous = SPINS.get(entry.getKey());
            var chosen = lines.get(entry.getValue());
            if (chosen == null || !chosen.published && previous != null && previous.published)
                lines.put(entry.getValue(), previous == null ? new Spin() : previous);
        }
        // Cutting a shaft must split its clock too; both new lines retain the old angle at the cut.
        var claimed = new IdentityHashMap<Spin, Endpoint>();
        for (var entry : lines.entrySet())
            if (claimed.putIfAbsent(entry.getValue(), entry.getKey()) != null) entry.setValue(new Spin(entry.getValue()));
        var aligned = new HashMap<Endpoint, Spin>();
        aliases.forEach((endpoint, line) -> aligned.put(endpoint, lines.get(line)));
        SPINS.clear();
        SPINS.putAll(aligned);
    }

    private static BlockPos root(Map<BlockPos, BlockPos> parents, BlockPos position) {
        var at = position;
        while (!at.equals(parents.get(at))) at = parents.get(at);
        while (!position.equals(at)) {
            var next = parents.put(position, at);
            position = next;
        }
        return at;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (world != mc.level) {
            if (mc.getConnection() != null) RotationNetwork.request(List.of(), List.copyOf(WATCHED));
            SHAFTS.clear(); WATCHED.clear(); SPINS.clear(); STATES.clear(); world = mc.level; age = 0;
        }
        if (world == null || mc.player == null || mc.getConnection() == null) return;
        for (var position : DISCOVERED)
            if (DISCOVERED.remove(position) && world.hasChunkAt(position) && world.getBlockState(position).getBlock() instanceof ShaftBlock) SHAFTS.add(position);
        if (age++ % 20 != 0) return;
        SHAFTS.removeIf(p -> !world.hasChunkAt(p) || !(world.getBlockState(p).getBlock() instanceof ShaftBlock));
        var wanted = new HashSet<>(SHAFTS);
        var machines = new TreeMap<BlockPos, EquipmentShape>();
        for (var cell : EquipmentShellBlockEntity.clientMovingCells())
            if (!cell.isRemoved() && cell.getLevel() == world && cell.shape() != null && cell.origin() != null && live(cell.shape())) machines.put(cell.origin(), cell.shape());
        wanted.addAll(machines.keySet());
        alignClocks(machines);
        var point = mc.player.position();
        double range = RotationNetwork.RANGE - 8;
        var near = wanted.stream().filter(p -> p.distToCenterSqr(point) <= range * range)
                .sorted(Comparator.comparingDouble((BlockPos p) -> p.distToCenterSqr(point)).thenComparingLong(BlockPos::asLong))
                .limit(RotationNetwork.MAXIMUM_WATCHED).toList();
        var selected = new HashSet<>(near);
        var gone = WATCHED.stream().filter(p -> !selected.contains(p)).toList();
        // Retry an unanswered watch: a freshly placed machine's queued engine registration may not exist yet.
        var added = near.stream().filter(p -> !WATCHED.contains(p) || !STATES.containsKey(p)).toList();
        WATCHED.removeAll(gone); gone.forEach(STATES::remove); WATCHED.addAll(near);
        RotationNetwork.request(added, gone);
    }
}
