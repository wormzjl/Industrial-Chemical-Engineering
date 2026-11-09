package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.runtime.fluid.ShaftTrains;
import com.wormzjl.icheme.runtime.fluid.ShaftUnit;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.MachineDuty;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.science.machine.CreateCoupling;
import com.wormzjl.icheme.science.machine.ShaftNetwork;
import com.wormzjl.icheme.science.machine.ShaftTrain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import java.util.ArrayList;
import java.util.List;

/**
 * The world side of the shaft trains' parts (batch 2026-11-03-rotating-machinery, W3, build plan section 5): what a shaft
 * block, a creative motor or a structure with shaft ends queues when it is placed, rebuilt or removed. Every change is a
 * queued {@link ShaftTrains.Event}; nothing here reads or moves a train.
 */
public final class ShaftParts {
    /** How far a pre-W3 shaft line is walked from a machine's coupling to adopt its blocks (reading A44). */
    static final int ADOPT_WALK = 256;

    private ShaftParts() {
    }

    private static PhysicalFluidTopology.Position position(Level level, BlockPos at) {
        return new PhysicalFluidTopology.Position(level.dimension().location().toString(), at.getX(), at.getY(), at.getZ());
    }

    private static void queue(Level level, ShaftTrains.Event event) {
        if (level instanceof ServerLevel server) FluidWorldAuthority.find(server.getServer()).ifPresent(world -> world.shafts().queue(event));
    }

    /** A shaft block at {@code at} on {@code axis}. */
    public static void shaft(Level level, BlockPos at, Direction.Axis axis) {
        var cell = position(level, at);
        int a = axis.ordinal();
        queue(level, new ShaftTrains.Put(cell, ShaftNetwork.Kind.SHAFT, id -> ShaftUnit.shaft(id, cell, a)));
    }

    /** A creative motor at {@code at}, its shaft leaving its {@code facing} side. */
    public static void creative(Level level, BlockPos at, Direction facing) {
        var cell = position(level, at);
        var end = new ShaftUnit.End(cell, facing.getStepX(), facing.getStepY(), facing.getStepZ(), true);
        queue(level, new ShaftTrains.Put(cell, ShaftNetwork.Kind.CREATIVE_MOTOR, id -> new ShaftUnit(id, ShaftNetwork.Kind.CREATIVE_MOTOR, cell, 0, List.of(end), 0,
                0, ShaftTrain.CREATIVE_SPEED, 0, MachineDuty.Drive.VARIABLE, ShaftUnit.Settings.DEFAULT, ShaftTrain.Trip.NONE, null)));
    }

    /**
     * A Create coupling at {@code at}, its industrial shaft leaving its {@code shaftSide} (W6b, D40): a driver with no
     * settings, its design speed Create's highest, {@link CreateCoupling#DESIGN_SPEED}, which an auto gearbox behind it matches.
     */
    public static void coupling(Level level, BlockPos at, Direction shaftSide) {
        var cell = position(level, at);
        var end = new ShaftUnit.End(cell, shaftSide.getStepX(), shaftSide.getStepY(), shaftSide.getStepZ(), true);
        queue(level, new ShaftTrains.Put(cell, ShaftNetwork.Kind.CREATE_COUPLING, id -> new ShaftUnit(id, ShaftNetwork.Kind.CREATE_COUPLING, cell, 0, List.of(end), 0,
                0, CreateCoupling.DESIGN_SPEED, 0, MachineDuty.Drive.VARIABLE, ShaftUnit.Settings.DEFAULT, ShaftTrain.Trip.NONE, null)));
    }

    /** What the coupling at {@code at} reads of its Create network: |rpm| and the stress it may take, su (W6b; a queued engine event). */
    public static void createInput(Level level, BlockPos at, double rpm, double stress) {
        queue(level, new ShaftTrains.CreateInput(position(level, at), rpm, stress));
    }

    /**
     * A Create coupling's block entity as the engine's world side sees it (W6b): it takes the stress impact the engine
     * sends, su per rpm, on the engine's schedule. Implemented in {@code compat/create} only, so nothing here names Create.
     */
    public interface Coupling {
        void stressImpact(double suPerRpm);
        default void output(double rpm,double watts) {}
        default double nativeSpeed(){return 0;}
    }

    /** The part at {@code at} (a block, or a structure's origin) leaves. */
    public static void remove(Level level, BlockPos at) {
        queue(level, new ShaftTrains.Remove(position(level, at)));
    }

    /** A structure's part kind, or null for one with no shaft. */
    static ShaftNetwork.Kind kind(EquipmentShape.Kind kind) {
        if (!UnitDesign.shafted(kind)) return null;
        return switch (kind) {
            case MOTOR -> ShaftNetwork.Kind.MOTOR;
            case GENERATOR -> ShaftNetwork.Kind.GENERATOR;
            case GEARBOX -> ShaftNetwork.Kind.GEARBOX;
            case STEAM_TURBINE -> ShaftNetwork.Kind.STEAM_TURBINE;
            default -> ShaftNetwork.Kind.CASING;
        };
    }

    /**
     * A whole structure with shaft ends at {@code origin}: its unit (its ends' world cells, its rating, for a casing or a
     * turbine its fluid registration {@code fluid}), and the shaft blocks and creative motor already standing on its
     * couplings' lines (reading A44: placed before W3, they never queued themselves).
     */
    public static void structure(ServerLevel level, BlockPos origin, EquipmentShape shape, long fluid) {
        var kind = kind(shape.kind());
        if (kind == null) return;
        if ((kind == ShaftNetwork.Kind.CASING || kind == ShaftNetwork.Kind.STEAM_TURBINE) && fluid == 0) return;
        var ends = new ArrayList<ShaftUnit.End>();
        int outputs = 0;
        for (var s : ShapeRasterizer.shafts(shape)) {
            boolean output = s.role() == EquipmentShape.ShaftEnd.Role.DRIVER_OUTPUT;
            outputs += output ? 1 : 0;
            ends.add(new ShaftUnit.End(position(level, origin.offset(s.cell().x(), s.cell().y(), s.cell().z())), s.dx(), s.dy(), s.dz(), output));
        }
        if (ends.isEmpty() || ends.size() > ShaftUnit.MAXIMUM_ENDS || kind == ShaftNetwork.Kind.GEARBOX && (ends.size() != 2 || outputs != 1)) return;
        var duty = shape.duty();
        int poles = kind == ShaftNetwork.Kind.MOTOR || kind == ShaftNetwork.Kind.GENERATOR ? duty.poles() == 0 ? 2 : duty.poles() : 0;
        double rated = UnitDesign.ratedPower(shape), speed = UnitDesign.designSpeed(shape);
        var cell = position(level, origin);
        var drive = duty.drive();
        queue(level, new ShaftTrains.Put(cell, kind, id -> new ShaftUnit(id, kind, cell, 0, ends, fluid, rated, speed, poles, drive, ShaftUnit.Settings.placed(kind), ShaftTrain.Trip.NONE, null)));
        for (var s : ShapeRasterizer.shafts(shape)) adopt(level, origin.offset(s.cell().x(), s.cell().y(), s.cell().z()), Direction.fromDelta(s.dx(), s.dy(), s.dz()));
    }

    /** Queues the shaft blocks on the line leaving {@code from} toward {@code direction}, and a creative motor that ends it. */
    static void adopt(ServerLevel level, BlockPos from, Direction direction) {
        if (direction == null) return;
        var at = from.relative(direction);
        for (int step = 0; step < ADOPT_WALK && level.hasChunkAt(at); step++, at = at.relative(direction)) {
            var state = level.getBlockState(at);
            if (state.getBlock() instanceof ShaftBlock && state.getValue(ShaftBlock.AXIS) == direction.getAxis()) {
                shaft(level, at, direction.getAxis());
                continue;
            }
            if (state.getBlock() instanceof CreativeMotorBlock && state.getValue(CreativeMotorBlock.FACING) == direction.getOpposite()) creative(level, at, direction.getOpposite());
            return;
        }
    }
}
