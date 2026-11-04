package com.wormzjl.icheme.world.item;

import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PumpHealth;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
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
 * A pump repair kit (batch 2026-11-03-rotating-machinery W10b, owner decision D34): used on any cell of a pump casing or on
 * an in-line pump's pipe block, it queues a repair ({@link PumpHealth.Repair}) that applies on the engine's tick drain. The
 * kit is consumed only when the repair is accepted (none in creative mode); a pump at full health, or a block that is no
 * pump, refuses it with a chat line.
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
        if (id <= 0) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer server) || !(context.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
        var pos = context.getClickedPos();
        if (!server.mayBuild() || !level.mayInteract(server, pos) || !server.canInteractWithBlock(pos, 1.0)) return InteractionResult.FAIL;
        var world = FluidWorldAuthority.find(server.server).orElse(null);
        if (world == null) return InteractionResult.FAIL;
        world.health().queue(new PumpHealth.Repair(id, () -> take(server), outcome -> reply(server, outcome)));
        return InteractionResult.CONSUME;
    }

    /** The pump device behind the block at {@code pos}: a casing's fluid cell or an in-line pump's pipe block; 0 for none. */
    private static long pumpAt(net.minecraft.world.level.Level level, BlockPos pos) {
        var entity = level.getBlockEntity(pos);
        if (entity instanceof EquipmentShellBlockEntity cell) {
            var shape = cell.shape();
            var origin = cell.origin();
            if (shape == null || origin == null || shape.kind() != com.wormzjl.icheme.science.equipment.EquipmentShape.Kind.PUMP) return 0;
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

    private static void reply(ServerPlayer player, PumpHealth.Outcome outcome) {
        if (player.isRemoved() || player.hasDisconnected() || player.connection == null) return;
        String key = switch (outcome) {
            case REPAIRED -> "chat.icheme.repair_kit.repaired";
            case FULL -> "chat.icheme.repair_kit.full";
            case NOT_A_PUMP -> "chat.icheme.repair_kit.not_a_pump";
            case NO_KIT -> "chat.icheme.repair_kit.no_kit";
        };
        player.displayClientMessage(Component.translatable(key), false);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.icheme.repair_kit"));
    }
}
