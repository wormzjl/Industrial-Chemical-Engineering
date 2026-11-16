package com.wormzjl.icheme.world.item;

import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.MachineHealth;
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
 * A repair kit (batch 2026-11-03-rotating-machinery W10b, owner decision D34; batch 2026-11-16-fouling-corrosion, owner
 * decision D8: "Keep the kit"): the repair of what the Equipment Constructor does not build. Used on an in-line pump's or
 * compressor's pipe block, or on a control valve's (its trim, plan section 8), it queues a repair
 * ({@link MachineHealth.Repair}) that applies on the engine's tick drain, accepted only while no flow passes the device
 * ({@link MachineHealth.Outcome#NOT_IDLE} otherwise). The kit is consumed only when the repair is accepted (none in creative
 * mode); a device at full health, or one with no health record ({@link MachineHealth.Outcome#NOT_A_PUMP}), refuses it with
 * a chat line. On a cell of constructed equipment (a casing, a turbine, a vessel) it is refused and kept: "Repair this with
 * the Equipment Constructor."
 */
public final class RepairKitItem extends Item {
    /** The message on a cell of constructed equipment. */
    public static final String USE_CONSTRUCTOR = "message.icheme.repair_kit.use_constructor";

    public RepairKitItem(Properties properties) {
        super(properties.stacksTo(16));
    }

    /** What a kit does on a block: refuse it for constructed equipment, queue the repair of a pipe block's device, or nothing. */
    public enum Use { CONSTRUCTOR, REPAIR, PASS }

    /**
     * The kit's rule (pure): a cell of constructed equipment ({@code structureCell}) refuses it; a pipe block bound to fluid
     * device {@code device} (above 0) queues its repair; any other block keeps its own use.
     */
    public static Use use(boolean structureCell, long device) {
        if (structureCell) return Use.CONSTRUCTOR;
        return device > 0 ? Use.REPAIR : Use.PASS;
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        var player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        var level = context.getLevel();
        var pos = context.getClickedPos();
        var entity = level.getBlockEntity(pos);
        boolean cell = entity instanceof EquipmentShellBlockEntity || EquipmentStructure.find(level, pos) != null;
        long device = entity instanceof FluidDeviceBlockEntity d ? d.fluidIdentity() : 0;
        var use = use(cell, device);
        if (use == Use.PASS) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer server) || !(level instanceof ServerLevel serverLevel)) return InteractionResult.SUCCESS;
        if (use == Use.CONSTRUCTOR) {
            if (server.connection != null) server.displayClientMessage(Component.translatable(USE_CONSTRUCTOR), true);
            return InteractionResult.FAIL;
        }
        if (!server.mayBuild() || !serverLevel.mayInteract(server, pos) || !server.canInteractWithBlock(pos, 1.0)) return InteractionResult.FAIL;
        var world = FluidWorldAuthority.find(server.server).orElse(null);
        if (world == null) return InteractionResult.FAIL;
        world.health().queue(new MachineHealth.Repair(device, () -> take(server), outcome -> reply(server, outcome)));
        return InteractionResult.CONSUME;
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

    /** The chat line of {@code outcome}: {@code chat.icheme.repair_kit.<outcome>}. */
    public static String key(MachineHealth.Outcome outcome) {
        return "chat.icheme.repair_kit." + outcome.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static void reply(ServerPlayer player, MachineHealth.Outcome outcome) {
        if (player.isRemoved() || player.hasDisconnected() || player.connection == null) return;
        player.displayClientMessage(Component.translatable(key(outcome)), false);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.icheme.repair_kit"));
    }
}
