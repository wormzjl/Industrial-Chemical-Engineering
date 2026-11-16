package com.wormzjl.icheme.world.item;

import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.MachineHealth;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

/**
 * A pump repair kit (batch 2026-11-03-rotating-machinery W10b, owner decision D34): used on any cell of a mover casing or a
 * steam turbine, or on an in-line device's pipe block, it queues a repair ({@link MachineHealth.Repair}) that applies on the
 * engine's tick drain. Whether the device wears is the health's decision there: every device with a health record (a pump,
 * a dry screw, M-02, a centrifugal compressor, N2, or a steam turbine, N4) is repaired. The kit is consumed only when the repair is accepted (none in creative mode); a pump at full health, or a
 * device with no health record ({@link MachineHealth.Outcome#NOT_A_PUMP}), refuses it with a chat line. Used on any cell of a
 * constructed vessel a blast breached (batch 2026-11-15-pollution-system, WP6b), it closes the breach first
 * ({@link FluidWorldAuthority#queueBreachRepair}), one kit taken at acceptance in the same way.
 */
public final class RepairKitItem extends Item {
    public RepairKitItem(Properties properties) {
        super(properties.stacksTo(16));
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        var player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        long id = pumpAt(context.getLevel(), context.getClickedPos());
        long[] vessels = structureAt(context.getLevel(), context.getClickedPos());
        if (id <= 0 && vessels.length == 0) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer server) || !(context.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
        var pos = context.getClickedPos();
        if (!server.mayBuild() || !level.mayInteract(server, pos) || !server.canInteractWithBlock(pos, 1.0)) return InteractionResult.FAIL;
        var world = FluidWorldAuthority.find(server.server).orElse(null);
        if (world == null) return InteractionResult.FAIL;
        // A breached vessel of the structure takes the kit first (batch 2026-11-15-pollution-system, WP6b): one kit closes its wall.
        for (long vessel : vessels)
            if (world.isBreached(vessel)) {
                world.queueBreachRepair(new MachineHealth.Repair(vessel, () -> take(server), outcome -> reply(server, outcome)));
                return InteractionResult.CONSUME;
            }
        // Else the machines' repair; a structure with no machine answers NOT_A_PUMP there.
        world.health().queue(new MachineHealth.Repair(id > 0 ? id : vessels[0], () -> take(server), outcome -> reply(server, outcome)));
        return InteractionResult.CONSUME;
    }

    /**
     * The fluid devices bound at the nodes of the constructed structure whose shell or interior cell is at {@code pos} (WP6b:
     * a breached vessel among them is what the kit repairs first); none for a block that is no structure with a fluid device,
     * or whose nodes are not loaded or not bound.
     */
    private static long[] structureAt(net.minecraft.world.level.Level level, BlockPos pos) {
        var vessel = EquipmentStructure.find(level, pos);
        if (vessel == null || !vessel.shape().fluidDevice()) return new long[0];
        return java.util.Arrays.stream(EquipmentStructure.sideIdentities(level, vessel)).filter(id -> id > 0).toArray();
    }

    /**
     * The fluid device behind the block at {@code pos}: a casing's or a turbine's fluid cell or an in-line device's pipe block;
     * 0 for none. Any mover casing or turbine (a block that holds neither keeps its own use): the queued repair answers {@code NOT_A_PUMP} where
     * the device has no health record, a reciprocating compressor for one (M-02).
     */
    private static long pumpAt(net.minecraft.world.level.Level level, BlockPos pos) {
        var entity = level.getBlockEntity(pos);
        if (entity instanceof EquipmentShellBlockEntity cell) {
            var shape = cell.shape();
            var origin = cell.origin();
            // A mover casing, or a steam turbine (N4): its fluid cell is its steam inlet's, which holds its fluid device.
            if (shape == null || origin == null || !shape.kind().mover() && shape.kind() != com.wormzjl.icheme.science.equipment.EquipmentShape.Kind.STEAM_TURBINE) return 0;
            var fluid = ShapeRasterizer.fluidCell(shape);
            if (fluid != null && level.getBlockEntity(origin.offset(fluid.x(), fluid.y(), fluid.z())) instanceof EquipmentShellBlockEntity node) return node.fluidIdentity();
            return 0;
        }
        if (entity instanceof FluidDeviceBlockEntity device) return device.fluidIdentity();
        return 0;
    }

    /** At acceptance: one kit from the player's inventory (none in creative mode); false when the player left or the kit is gone. */
    private static boolean take(ServerPlayer player) {
        if (player.isRemoved() || player.hasDisconnected()) return false;
        if (player.getAbilities().instabuild) return true;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (stack.is(ModItems.REPAIR_KIT.get())) {
                stack.shrink(1);
                inventory.setChanged();
                return true;
            }
        }
        return false;
    }

    private static void reply(ServerPlayer player, MachineHealth.Outcome outcome) {
        if (player.isRemoved() || player.hasDisconnected() || player.connection == null) return;
        String key = switch (outcome) {
            case REPAIRED -> "chat.icheme.repair_kit.repaired";
            case FULL -> "chat.icheme.repair_kit.full";
            case NOT_A_PUMP -> "chat.icheme.repair_kit.not_a_pump";
            case NO_KIT -> "chat.icheme.repair_kit.no_kit";
            case BREACH_REPAIRED -> "chat.icheme.repair_kit.breach_repaired";
        };
        player.displayClientMessage(Component.translatable(key), false);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.icheme.repair_kit"));
    }
}
