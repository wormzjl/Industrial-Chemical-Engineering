package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.science.equipment.PipeRoute;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.equipment.RackGeometry;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import com.wormzjl.icheme.world.item.PipeLayingToolItem;
import com.wormzjl.icheme.world.level.block.entity.PipeRackBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Server: the laying tool's commands (batch 2026-10-26-pipe-laying-system, plan 4.4 and 4.5). A lay is checked whole
 * before anything changes, then registered as one ledger event and placed; a refused lay changes nothing. Both only queue
 * topology events: no solve runs and no process state is read.
 */
public final class PipeLaying {
    /** The farthest a line's start may lie from the player who lays it, in blocks. */
    public static final double START_RANGE = 64;

    private PipeLaying() {
    }

    /** Why a command did nothing, for the player; null when it was done. */
    public static @Nullable Component lay(ServerPlayer player, InteractionHand hand, PipeAnchor.Click startClick, PipeAnchor.Click endClick, List<BlockPos> path, List<Integer> options) {
        var stack = player.getItemInHand(hand);
        var settings = stack.get(ModItems.PIPE_LAYING_SETTINGS.get());
        if (!stack.is(ModItems.PIPE_LAYING_TOOL.get()) || settings == null || player.isSpectator() || !player.mayBuild()) return message("not_allowed");
        var level = player.serverLevel();
        var startClicked = startClick.clicked();
        var endClicked = endClick.clicked();
        if (!player.canInteractWithBlock(endClicked, 1) || startClicked.getCenter().distanceToSqr(player.position()) > START_RANGE * START_RANGE) return message("out_of_reach");
        if (!level.hasChunkAt(startClicked) || !level.hasChunkAt(endClicked)) return message("anchor.outside");
        double outside = PipeSizes.outside(settings.diameter());
        var start = PipeAnchor.resolve(level, startClick, outside);
        if (start.anchor() == null) return refusal(start.refusal());
        var end = PipeAnchor.resolve(level, endClick, outside);
        if (end.anchor() == null) return refusal(end.refusal());
        var refused = PipeAnchor.refuses(start.anchor(), end.anchor(), outside);
        if (refused != null) return refusal(refused);
        var cells = path.stream().map(PipeAnchor::cell).toList();
        var broken = PipeRoute.check(cells, options, start.anchor().end(), end.anchor().end(), PipeRoute.Limits.DEFAULT.cells());
        if (broken != null) return refusal(broken);
        int heading = start.anchor().leaving();
        int blocked = PipeRoute.blocked(cells, options, start.anchor().end(), heading, end.anchor().end(), outside, PipeAnchor.space(level));
        if (blocked >= 0) return message("occupied", path.get(blocked).toShortString());
        for (var cell : path) if (!level.mayInteract(player, cell)) return message("occupied", cell.toShortString());
        var world = FluidWorldAuthority.find(player.server).orElse(null);
        if (world == null) return message("not_allowed");
        String dimension = level.dimension().location().toString();
        // The laid pipes or lanes the line starts or ends at take a joint; both ends may meet the same one.
        var hosts = new HashMap<Long, Integer>();
        var edited = new ArrayList<BlockPos>();
        for (var anchor : List.of(start.anchor(), end.anchor())) {
            if (anchor.joints() < 0) continue;
            edited.add(anchor.host());
            var record = world.at(anchor.hostPosition(dimension)).orElse(null);
            if (record == null || record.device().kind() != TopologyCompiler.Kind.PIPE) return message("anchor.fitting");
            int joints = hosts.getOrDefault(record.device().id(), record.device().joints()) | anchor.joints();
            if (Integer.bitCount(joints) > (anchor.hostLane() > 0 ? 2 : 4)) return message(anchor.hostLane() > 0 ? "anchor.lane_closed" : "anchor.junction");
            hosts.put(record.device().id(), joints);
        }
        if (!player.getAbilities().instabuild && !take(player, path.size())) return message("items", path.size());
        var placement = PipeRoute.placement(cells, start.anchor().end(), heading, end.anchor().end());
        int[] joints = placement.joints(), facings = placement.facings(), arms = placement.arms();
        int width = RackGeometry.width(PipeSizes.drawn(settings.diameter()));
        // Each lane as its rack will hold it: its option, width, arms and joints; it registers with its face points, and a
        // facing that gives back its open end.
        var lanes = new ArrayList<PipeRackBlockEntity.Lane>();
        var laid = new ArrayList<FluidWorldAuthority.Laid>();
        for (int i = 0; i < path.size(); i++) {
            var at = path.get(i);
            int option = options.get(i);
            var position = new PhysicalFluidTopology.Position(dimension, at.getX(), at.getY(), at.getZ(), option + 1);
            if (option < 0) {
                lanes.add(null);
                laid.add(new FluidWorldAuthority.Laid(position, PhysicalFluidTopology.Direction.values()[facings[i]], joints[i], 0, 0));
                continue;
            }
            var rack = (PipeRackBlockEntity) level.getBlockEntity(at);
            var lane = new PipeRackBlockEntity.Lane(0, option, width, arms[i], joints[i], settings.diameter());
            lanes.add(lane);
            laid.add(new FluidWorldAuthority.Laid(position, PhysicalFluidTopology.Direction.values()[lane.facing()], joints[i], lane.width(), rack.points(lane)));
        }
        // The new pipes and lanes take the tool's paints, the pipe's and the flanges', into the registry; the pipes and
        // lanes the line starts or ends at keep theirs. The blocks copy them as they bind.
        int paint = PipeLayingToolItem.paint(stack), secondary = PipeLayingToolItem.secondary(stack);
        var records = world.lay(laid, settings.diameter(), hosts, paint, secondary);
        // The blocks and lanes bind to their queued registrations, then take their connections: every new block's joints
        // are set before any neighbour reads them, as EquipmentStructure.place sets its cells before it reconnects them.
        var racks = new ArrayList<PipeRackBlockEntity>();
        FluidDeviceBlock.laying = true;
        try {
            for (int i = 0; i < path.size(); i++) {
                var lane = lanes.get(i);
                if (lane != null) {
                    var rack = (PipeRackBlockEntity) level.getBlockEntity(path.get(i));
                    rack.addLane(new PipeRackBlockEntity.Lane(records.get(i).device().id(), lane.option(), lane.width(), lane.arms(), lane.joints(), lane.bore(), paint, secondary));
                    racks.add(rack);
                    continue;
                }
                var state = ModBlocks.FLUID_PIPE.get().defaultBlockState().setValue(FluidDeviceBlock.FACING, Direction.values()[facings[i]]);
                level.setBlock(path.get(i), state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                if (level.getBlockEntity(path.get(i)) instanceof FluidDeviceBlockEntity entity) entity.bindIdentity(records.get(i).device().id());
            }
        } finally {
            FluidDeviceBlock.laying = false;
        }
        for (var host : edited) if (level.getBlockEntity(host) instanceof PipeRackBlockEntity rack) racks.add(rack);
        // Racks take their lanes' new joints first, so the pipes next door see them when they connect.
        for (var rack : racks) rack.refresh(false);
        // An edited host pipe copies its new joints from its queued registration; any other host (a tank, a nozzle, a
        // pipe with the joint already) takes the connection through the new block's neighbour update.
        for (var host : edited)
            if (level.getBlockEntity(host) instanceof FluidDeviceBlockEntity entity) entity.refreshGeometry(entity.bore(), true);
        for (int i = 0; i < path.size(); i++)
            if (options.get(i) < 0 && level.getBlockEntity(path.get(i)) instanceof FluidDeviceBlockEntity entity) entity.refreshGeometry(settings.diameter(), true);
        for (var rack : racks) rack.refresh(true);
        return null;
    }

    /**
     * Removes one pipe block, or one lane of a rack, at once (owner decisions D6, D7), as a player breaking it would: the
     * break event, the fittings and gauges refunded through the accessory store, the pipe's drop in survival. Its
     * neighbours keep their joints towards it and become open ends.
     */
    public static @Nullable Component dismantle(ServerPlayer player, InteractionHand hand, PipeAnchor.Click click) {
        if (!player.getItemInHand(hand).is(ModItems.PIPE_LAYING_TOOL.get()) || player.isSpectator() || !player.mayBuild()) return message("not_allowed");
        var position = click.clicked();
        var level = player.serverLevel();
        if (!player.canInteractWithBlock(position, 1) || !level.hasChunkAt(position)) return message("out_of_reach");
        var state = level.getBlockState(position);
        if (level.getBlockEntity(position) instanceof PipeRackBlockEntity rack) {
            var pick = rack.pick(click.local().x, click.local().y, click.local().z);
            if (pick.lane() < 0) return message("not_a_pipe");
            // The rack block stays: no break event, only the checks any block action in this spot must pass.
            if (player.blockActionRestricted(level, position, player.gameMode.getGameModeForPlayer()) || !level.mayInteract(player, position)) return message("not_allowed");
            var world = FluidWorldAuthority.find(player.server).orElse(null);
            if (world == null) return message("not_allowed");
            var lane = rack.lanes().get(pick.lane());
            world.remove(lane.identity());
            rack.removeLane(lane.identity());
            rack.refresh(true);
            if (!player.getAbilities().instabuild) player.getInventory().placeItemBackInInventory(new ItemStack(ModItems.FLUID_PIPE.get()));
            return null;
        }
        if (!(state.getBlock() instanceof FluidDeviceBlock block) || block.kind() != TopologyCompiler.Kind.PIPE) return message("not_a_pipe");
        return player.gameMode.destroyBlock(position) ? null : message("not_allowed");
    }

    /** Takes {@code count} pipe items from the player's inventory, or none when they hold fewer. */
    private static boolean take(ServerPlayer player, int count) {
        var inventory = player.getInventory();
        int held = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++)
            if (inventory.getItem(slot).is(ModItems.FLUID_PIPE.get())) held += inventory.getItem(slot).getCount();
        if (held < count) return false;
        for (int slot = 0; slot < inventory.getContainerSize() && count > 0; slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.is(ModItems.FLUID_PIPE.get())) continue;
            int taken = Math.min(count, stack.getCount());
            stack.shrink(taken);
            count -= taken;
        }
        inventory.setChanged();
        return true;
    }

    private static String name(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    /** The player message of an anchor refusal. */
    public static Component refusal(PipeAnchor.Refusal refusal) {
        return message("anchor." + name(refusal));
    }

    /** The player message of a route refusal. */
    public static Component refusal(PipeRoute.Refusal refusal) {
        return message("route." + name(refusal));
    }

    /** What a line's end meets, for the player. */
    public static Component describe(PipeAnchor anchor) {
        return message("end." + name(anchor.kind()));
    }

    public static Component message(String key, Object... arguments) {
        return Component.translatable("message.icheme.pipe_laying." + key, arguments);
    }
}
