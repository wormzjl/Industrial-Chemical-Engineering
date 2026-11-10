package com.wormzjl.icheme.client;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.client.model.FluidDeviceModel;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.science.equipment.LineGeometry;
import com.wormzjl.icheme.science.equipment.Mesh;
import com.wormzjl.icheme.science.equipment.RenderDetail;
import com.wormzjl.icheme.science.equipment.ValveGeometry;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws a hand valve's turn ({@link ValveTurns}; owner 2026-11-10: "add turning sound and animation for hand valve"):
 * the shutoff valve's lever swings its quarter turn about the stem, the diverter valve's stem and actuator case turn to
 * the next outlet. The turn starts at the click on the clicking client and at the server's block event on every client
 * in range ({@link FluidDeviceBlock#TURN_EVENT}). While it turns and waits, the valve's model leaves the part out of
 * the chunk mesh ({@link FluidDeviceBlockEntity#APART}) and this draws it once a frame through {@link MachineAnimation}'s
 * cached buffers; its own section is meshed again once at each end. At rest nothing here runs but one empty check a frame.
 *
 * <p>The diverter's actuator case is square across its axis, so it looks the same at every outlet: its turn ends with its
 * motion, and the outlet's pipes follow the block state at the commit.
 */
@EventBusSubscriber(modid = IChemE.MOD_ID, value = Dist.CLIENT)
public final class ValveTurnRenderer {
    /** How far from the camera a turn is drawn, blocks; past it the valve's state still advances. */
    private static final double RANGE = 64;
    private static final int LEVER = 1, ACTUATOR = 2;
    private static final double[] CENTRE = {.5, .5, .5};

    private record Look(LineGeometry.Key key, RenderDetail detail, ValveTurns.Kind kind) {
    }

    private static final Map<Look, List<Mesh.Polygon>> PARTS = new HashMap<>();
    private static @Nullable Level seen;

    private ValveTurnRenderer() {
    }

    /**
     * The clicking client's own turn, at once: from the position the valve shows or turns to, to the next one, as the
     * server composes rapid clicks. The server's block event then repeats it (ignored) or corrects it. Whether a turn
     * started, so the click's sound plays.
     */
    public static boolean clicked(Level level, BlockPos position, BlockState state) {
        var kind = kind(state);
        if (kind == null) return false;
        int shown = shown(kind, state);
        if (shown < 0) return false;
        int pending = ValveTurns.CLIENT.target(position.asLong());
        int from = pending >= 0 ? pending : shown;
        start(level, position, kind, shown, kind == ValveTurns.Kind.SHUTOFF ? 1 - from : (from + 1) % 3);
        return true;
    }

    /** The server's block event: valve {@code position} turns to {@code target} on this client. */
    public static void turned(Level level, BlockPos position, BlockState state, int target) {
        var kind = kind(state);
        if (kind == null) return;
        int shown = shown(kind, state);
        if (shown < 0 || target < 0 || target > (kind == ValveTurns.Kind.SHUTOFF ? 1 : 2)) return;
        start(level, position, kind, shown, target);
    }

    private static void start(Level level, BlockPos position, ValveTurns.Kind kind, int shown, int target) {
        if (level != seen) {
            ValveTurns.CLIENT.clear();
            seen = level;
        }
        if (ValveTurns.CLIENT.start(position.asLong(), kind, shown, target, level.getGameTime() / 20.0)) redraw(level, position);
    }

    /** The valve kind a hand turns at {@code state}, or null: a shutoff valve on a pipe, or a diverter valve. */
    private static ValveTurns.@Nullable Kind kind(BlockState state) {
        if (!(state.getBlock() instanceof FluidDeviceBlock block)) return null;
        if (block.kind() == TopologyCompiler.Kind.DIVERTER_VALVE) return ValveTurns.Kind.DIVERTER;
        return FluidDeviceBlock.hasShutoff(state) && !FluidDeviceBlock.hasPressureValve(state) ? ValveTurns.Kind.SHUTOFF : null;
    }

    /**
     * The position {@code state} shows: a shutoff valve's committed lever (0 open, 1 closed); a diverter valve's outlet,
     * the one of its three outlets it is drawn joined through (0 when none is).
     */
    private static int shown(ValveTurns.Kind kind, BlockState state) {
        if (kind == ValveTurns.Kind.SHUTOFF) {
            var shutoff = state.getValue(FluidDeviceBlock.SHUTOFF);
            return shutoff == FluidDeviceBlock.Shutoff.OPEN ? 0 : shutoff == FluidDeviceBlock.Shutoff.CLOSED ? 1 : -1;
        }
        var inlet = PhysicalFluidTopology.Direction.valueOf(state.getValue(FluidDeviceBlock.FACING).name());
        var outlets = PhysicalFluidTopology.diverterOutlets(inlet);
        for (int k = 0; k < outlets.size(); k++)
            if (state.getValue(FluidDeviceBlock.connection(Direction.valueOf(outlets.get(k).name())))) return k;
        return 0;
    }

    /** The valve's model data and its own section, again: its part leaves the chunk mesh or returns to it. */
    private static void redraw(Level level, BlockPos position) {
        if (level.getBlockEntity(position) instanceof FluidDeviceBlockEntity entity) entity.requestModelDataUpdate();
        Minecraft.getInstance().levelRenderer.setSectionDirty(SectionPos.blockToSectionCoord(position.getX()),
                SectionPos.blockToSectionCoord(position.getY()), SectionPos.blockToSectionCoord(position.getZ()));
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) return;
        var positions = ValveTurns.CLIENT.positions();
        if (positions.isEmpty()) return;
        var level = Minecraft.getInstance().level;
        if (level == null || level != seen) {
            ValveTurns.CLIENT.clear();
            seen = level;
            return;
        }
        if (!MachineAnimation.sprites()) return;
        double now = (level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false)) / 20.0;
        var camera = event.getCamera().getPosition();
        var detail = ClientConfig.renderDetail();
        if (PARTS.size() > 64) PARTS.clear();
        try (var draws = new MachineAnimation.Draws(event)) {
            for (long at : positions) {
                var position = BlockPos.of(at);
                if (!level.hasChunkAt(position)) {
                    ValveTurns.CLIENT.forget(at);
                    continue;
                }
                var state = level.getBlockState(position);
                var kind = kind(state);
                if (kind == null || kind != ValveTurns.CLIENT.kind(at) || !(level.getBlockEntity(position) instanceof FluidDeviceBlockEntity entity)) {
                    if (ValveTurns.CLIENT.forget(at)) redraw(level, position);
                    continue;
                }
                if (ValveTurns.CLIENT.settle(at, kind == ValveTurns.Kind.SHUTOFF ? shown(kind, state) : -1, now)) redraw(level, position);
                double angle = ValveTurns.CLIENT.angle(at, now);
                if (Double.isNaN(angle) || position.distToCenterSqr(camera) > RANGE * RANGE) continue;
                var key = FluidDeviceModel.blockKey(state, entity.getModelData());
                var look = new Look(key, detail, kind);
                var polygons = PARTS.computeIfAbsent(look, l -> l.kind() == ValveTurns.Kind.SHUTOFF
                        ? ValveGeometry.shutoffLever(l.key(), l.detail()) : LineGeometry.diverterActuator(l.key(), l.detail()));
                double[] pivot = kind == ValveTurns.Kind.SHUTOFF ? ValveGeometry.shutoffPivot(key) : CENTRE;
                double[] axis = kind == ValveTurns.Kind.SHUTOFF ? ValveGeometry.shutoffStem(key) : LineGeometry.diverterAxis(key);
                var pose = new Matrix4f(event.getModelViewMatrix())
                        .translate((float) (position.getX() - camera.x + pivot[0]), (float) (position.getY() - camera.y + pivot[1]), (float) (position.getZ() - camera.z + pivot[2]))
                        .rotate((float) angle, (float) axis[0], (float) axis[1], (float) axis[2])
                        .translate((float) -pivot[0], (float) -pivot[1], (float) -pivot[2]);
                draws.draw(look, kind == ValveTurns.Kind.SHUTOFF ? LEVER : ACTUATOR, LevelRenderer.getLightColor(level, position), polygons, pose);
            }
        }
    }
}
