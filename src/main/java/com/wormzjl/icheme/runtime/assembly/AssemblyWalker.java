package com.wormzjl.icheme.runtime.assembly;

import com.wormzjl.icheme.science.assembly.PortGraph;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * The port graph of the pipes around an assembly, read from the world as the matcher asks for it (batch
 * 2026-10-26-multi-machine-framework, plan section 6). A run starts at a nozzle's pipe block and follows joined blocks:
 * only pipes join (decision D20), a fitting passes along its axis, and a run ends at a structure's nozzle, a branch, a
 * one-block tank, generator or void (outside), an open end, or a chunk that is not loaded. The walk reads loaded chunks
 * only, never passes through a vessel or a constructed mover, and visits at most {@link #MAX_WALK} blocks. It records
 * the structural code of each block it reads, which the store compares at a chunk load.
 */
public final class AssemblyWalker implements PortGraph {
    /** The most blocks one validation reads. */
    public static final int MAX_WALK = 8192;

    /** What the walker reads of the world. */
    public interface Ground {
        /** Whether the chunk holding {@code position} is loaded; the walk never loads one. */
        boolean loaded(BlockPos position);

        /** The registered one-block fluid device at {@code position}, or null. */
        @Nullable Piece piece(BlockPos position);

        /** The bound equipment structure whose shell cell stands at {@code position}, or null. */
        @Nullable Shell shell(BlockPos position);
    }

    /**
     * A one-block fluid device as the walk reads it: a pipe (only pipes join, D20), an outside terminal, the fitting it
     * carries (null for none), its facing (a fitting's outlet), and the faces it meets its neighbours through (bit per
     * {@link Direction#ordinal()}). Settings (a valve's target, a pump's switch, a cutoff open or shut) are not part of it.
     */
    public record Piece(boolean pipe, boolean terminal, @Nullable Fitting fitting, Direction facing, int faces) {
        public boolean faces(Direction direction) {
            return (faces >> direction.ordinal() & 1) != 0;
        }

        /** Its structural code: what a chunk-load check compares. */
        public int code() {
            return (pipe ? 1 : 0) | (terminal ? 2 : 0) | (fitting == null ? 0 : fitting.ordinal() + 1) << 2 | facing.ordinal() << 5 | faces << 8;
        }
    }

    /** A structure: its origin and shape. */
    public record Shell(BlockPos origin, EquipmentShape shape) {
    }

    /** A nozzle's flange cell holds this code: its structure's kind and the nozzle's index, negative to keep it apart from a piece's. */
    public static int nozzleCode(EquipmentShape.Kind kind, int nozzle) {
        return -1 - (kind.ordinal() << 16 | nozzle);
    }

    private record Key(long origin, EquipmentShape.Role role, int ordinal) {
    }

    /** Where a run arrived at a branch, and which way it was going. */
    private record Arrival(BlockPos at, Direction travel) {
    }

    /** A neighbour a run may go on to: a block it continues through, or the end it reaches there. */
    private record Next(BlockPos position, Direction direction, @Nullable End end) {
    }

    private final Ground ground;
    private final int budget;
    private int visited;
    private boolean exhausted;
    private final Map<Long, Shell> shells = new HashMap<>();
    private final Map<Long, Unit> units = new HashMap<>();
    private final Map<Key, Run> runs = new HashMap<>();
    private final Map<Run, Arrival> arrivals = new IdentityHashMap<>();
    private final Map<Long, Integer> codes = new LinkedHashMap<>();

    public AssemblyWalker(Ground ground) {
        this(ground, MAX_WALK);
    }

    AssemblyWalker(Ground ground, int budget) {
        this.ground = ground;
        this.budget = budget;
    }

    /** The structure at {@code origin} as a unit of the graph. */
    public Unit unit(BlockPos origin, EquipmentShape shape) {
        return unit(new Shell(origin.immutable(), shape));
    }

    private Unit unit(Shell shell) {
        long origin = shell.origin().asLong();
        return units.computeIfAbsent(origin, o -> {
            shells.put(o, shell);
            var shape = shell.shape();
            return new Unit(o, shape.kind(), shape.orientation().horizontal(), shape.boot(), shape.nozzles().stream().map(EquipmentShape.Nozzle::role).toList(),
                    new Size(shape.diameter(), shape.tangentLength(), shape.bank(),shape.exchangerType()));
        });
    }

    /** The blocks read so far. */
    public int visited() {
        return visited;
    }

    /** Whether a walk stopped at {@link #MAX_WALK}: its end reads as not checked. */
    public boolean exhausted() {
        return exhausted;
    }

    /** The structural code of every block and nozzle read, by packed position. */
    public Map<Long, Integer> codes() {
        return Collections.unmodifiableMap(codes);
    }

    @Override
    public Run run(Port port) {
        var key = new Key(port.unit().origin(), port.role(), port.ordinal());
        var known = runs.get(key);
        if (known != null) return known;
        var run = Run.unconnected();
        var shell = shells.get(port.unit().origin());
        int nozzle = shell == null ? -1 : nozzle(shell.shape(), port.role(), port.ordinal());
        if (nozzle >= 0)
            for (var p : ShapeRasterizer.ports(shell.shape())) {
                if (p.nozzle() != nozzle) continue;
                var flange = shell.origin().offset(p.cell().x(), p.cell().y(), p.cell().z());
                codes.put(flange.asLong(), nozzleCode(shell.shape().kind(), nozzle));
                run = walk(flange, Direction.fromDelta(p.dx(), p.dy(), p.dz()), true);
                break;
            }
        runs.put(key, run);
        return run;
    }

    @Override
    public List<Run> branches(Junction junction, Run arrived) {
        var arrival = arrivals.get(arrived);
        if (arrival == null || arrival.at().asLong() != junction.position()) return List.of();
        var piece = ground.piece(arrival.at());
        if (piece == null) return List.of();
        var out = new ArrayList<Run>();
        for (var next : next(arrival.at(), piece, arrival.travel()))
            out.add(next.end() != null ? new Run(List.of(), next.end(), 0, List.of()) : walk(next.position().relative(next.direction().getOpposite()), next.direction(), false));
        return out;
    }

    /** The index of the {@code ordinal}th nozzle of {@code role}, or -1. */
    private static int nozzle(EquipmentShape shape, EquipmentShape.Role role, int ordinal) {
        int seen = 0;
        for (int k = 0; k < shape.nozzles().size(); k++)
            if (shape.nozzles().get(k).role() == role && seen++ == ordinal) return k;
        return -1;
    }

    /**
     * The run from {@code from} towards {@code travel}: its first block is the next one. From a nozzle ({@code nozzle})
     * only a pipe that meets the nozzle continues it; otherwise the port is unconnected.
     */
    private Run walk(BlockPos from, Direction travel, boolean nozzle) {
        var elements = new ArrayList<Element>();
        var positions = new ArrayList<Long>();
        var at = from.relative(travel);
        var t = travel;
        if (!ground.loaded(at)) return new Run(elements, new NotLoaded(at.asLong()), 0, positions);
        var piece = ground.piece(at);
        if (piece == null || nozzle && !piece.pipe() || !piece.faces(t.getOpposite())) return Run.unconnected();
        End end;
        while (true) {
            if (visited++ >= budget) {
                exhausted = true;
                end = new NotLoaded(at.asLong());
                break;
            }
            codes.put(at.asLong(), piece.code());
            if (piece.fitting() != null) elements.add(new Element(piece.fitting(), along(piece.facing(), t), at.asLong(), positions.size()));
            positions.add(at.asLong());
            var next = next(at, piece, t);
            if (next.isEmpty()) {
                end = new OpenEnd(at.asLong());
                break;
            }
            if (next.size() > 1) {
                end = new Junction(at.asLong(), next.size());
                break;
            }
            var only = next.getFirst();
            if (only.end() != null) {
                end = only.end();
                break;
            }
            at = only.position();
            t = only.direction();
            piece = ground.piece(at);
        }
        var run = new Run(elements, end, positions.size(), positions);
        if (end instanceof Junction) arrivals.put(run, new Arrival(at.immutable(), t));
        return run;
    }

    /** The neighbours a run at {@code at}, going {@code travel}, may go on to: every joined one but where it came from. */
    private List<Next> next(BlockPos at, Piece piece, Direction travel) {
        var out = new ArrayList<Next>();
        for (var d : Direction.values()) {
            if (d == travel.getOpposite() || !piece.faces(d)) continue;
            var n = at.relative(d);
            if (!ground.loaded(n)) {
                out.add(new Next(n, d, new NotLoaded(n.asLong())));
                continue;
            }
            var shell = ground.shell(n);
            if (shell != null) {
                // A structure meets a pipe only at a nozzle facing it.
                var port = piece.pipe() ? port(shell, n, d.getOpposite()) : null;
                if (port != null) out.add(new Next(n, d, new AtPort(port)));
                continue;
            }
            var other = ground.piece(n);
            if (other == null || !other.faces(d.getOpposite()) || !(piece.pipe() || other.pipe())) continue;
            out.add(new Next(n, d, other.terminal() ? new Terminal(n.asLong()) : null));
        }
        return out;
    }

    /** The port of {@code shell} whose flange face is the cell {@code at} facing {@code outward}, or null. */
    private @Nullable Port port(Shell shell, BlockPos at, Direction outward) {
        var origin = shell.origin();
        int x = at.getX() - origin.getX(), y = at.getY() - origin.getY(), z = at.getZ() - origin.getZ();
        for (var p : ShapeRasterizer.ports(shell.shape())) {
            if (p.cell().x() != x || p.cell().y() != y || p.cell().z() != z || p.dx() != outward.getStepX() || p.dy() != outward.getStepY()
                    || p.dz() != outward.getStepZ()) continue;
            var nozzles = shell.shape().nozzles();
            var role = nozzles.get(p.nozzle()).role();
            int ordinal = 0;
            for (int k = 0; k < p.nozzle(); k++) if (nozzles.get(k).role() == role) ordinal++;
            codes.put(at.asLong(), nozzleCode(shell.shape().kind(), p.nozzle()));
            return new Port(unit(shell), role, ordinal);
        }
        return null;
    }

    /** A fitting's arrow along the run: +1 when its outlet faces the way the run goes. */
    private static int along(Direction facing, Direction travel) {
        return facing == travel.getOpposite() ? -1 : 1;
    }
}
