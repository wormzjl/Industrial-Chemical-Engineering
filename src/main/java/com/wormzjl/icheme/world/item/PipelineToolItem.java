package com.wormzjl.icheme.world.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.runtime.fluid.PhysicalRegistry;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.fluid.network.PipeResistance;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.world.inventory.PipelineToolMenu;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.Positions;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/** Item interactions read static registrations and queue edits; they never inspect or publish process state. */
public final class PipelineToolItem extends Item {
    public PipelineToolItem(Properties properties) {
        super(properties.stacksTo(1).component(ModItems.PIPELINE_TOOL_SETTINGS.get(), Settings.DEFAULT));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown()) return InteractionResultHolder.pass(stack);
        return new InteractionResultHolder<>(configure(player, hand), stack);
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        var player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        var level = context.getLevel();
        var pos = context.getClickedPos();
        // Sneaking resizes a pipe's whole line; anywhere else it opens the tool's menu.
        boolean line = player.isShiftKeyDown();
        if (!(level.getBlockState(pos).getBlock() instanceof FluidDeviceBlock block)
                || block.kind() != TopologyCompiler.Kind.PIPE) return line ? configure(player, context.getHand()) : InteractionResult.PASS;
        var settings = stack.get(ModItems.PIPELINE_TOOL_SETTINGS.get());
        if (settings == null || !player.mayBuild() || player.isSpectator() || !level.mayInteract(player, pos))
            return InteractionResult.FAIL;
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof FluidDeviceBlockEntity entity) {
            FluidWorldAuthority.find(serverPlayer.server).ifPresent(world -> {
                var position = Positions.of(level, pos);
                world.at(position).filter(record -> record.device().id() == entity.fluidIdentity()
                        && record.device().kind() == TopologyCompiler.Kind.PIPE).ifPresent(record -> {
                    var device = record.device();
                    if (line) {
                        // A pipe beside a nozzle takes at most what the nozzle takes (NozzleBore); a rack lane meets no nozzle.
                        int pipes = world.resizeLine(device.id(), settings.diameter(), d -> d.position().lane() > 0 ? PipeSizes.MAX_BORE
                                : EquipmentShellBlockEntity.largestBoreBeside(level, new BlockPos(d.position().x(), d.position().y(), d.position().z()), d.joints()));
                        player.displayClientMessage(pipes == PhysicalRegistry.LANE_TOO_NARROW ? Component.translatable("item.icheme.pipeline_tool.lane_too_narrow", Double.toString(settings.diameter()))
                                : pipes == PhysicalRegistry.NOZZLE_TOO_SMALL ? Component.translatable("item.icheme.pipeline_tool.nozzle_too_small", Double.toString(settings.diameter()))
                                : pipes < 0 ? Component.translatable("item.icheme.pipeline_tool.line_too_long", FluidWorldAuthority.LINE_LIMIT)
                                : Component.translatable("item.icheme.pipeline_tool.line_resized", pipes, Double.toString(settings.diameter())), true);
                        return;
                    }
                    var geometry = device.geometry();
                    if (geometry.diameter() == settings.diameter()) return;
                    if (settings.diameter() > EquipmentShellBlockEntity.largestBoreBeside(level, pos, entity.joints()) + 1e-9) {
                        player.displayClientMessage(Component.translatable("item.icheme.pipeline_tool.nozzle_too_small", Double.toString(settings.diameter())), true);
                        return;
                    }
                    var resized = new PipeResistance.Geometry(geometry.length(), settings.diameter(), geometry.roughness(), geometry.minorLoss());
                    world.edit(device.id(), record.revision(), device.withGeometry(resized), record.spec());
                });
            });
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    private static InteractionResult configure(Player player, InteractionHand hand) {
        if (player.isSpectator() || player.getItemInHand(hand).get(ModItems.PIPELINE_TOOL_SETTINGS.get()) == null)
            return InteractionResult.FAIL;
        if (player instanceof ServerPlayer serverPlayer) PipelineToolMenu.open(serverPlayer, hand);
        return InteractionResult.sidedSuccess(player.level().isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        var settings = stack.get(ModItems.PIPELINE_TOOL_SETTINGS.get());
        if (settings != null) tooltip.add(Component.translatable("item.icheme.pipeline_tool.target", Double.toString(settings.diameter()))
                .withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("item.icheme.pipeline_tool.configure").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.icheme.pipeline_tool.apply").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.icheme.pipeline_tool.apply_line").withStyle(ChatFormatting.GRAY));
    }

    /** The tool's internal diameter in metres; the same bounds govern storage, wire and editor input. */
    public record Settings(double diameter) {
        public static final double MIN_DIAMETER = .001;
        public static final double MAX_DIAMETER = PipeSizes.MAX_BORE;
        public static final Settings DEFAULT = new Settings(.05);
        public static final Codec<Settings> CODEC = Codec.DOUBLE.comapFlatMap(
                value -> valid(value) ? DataResult.success(new Settings(value))
                        : DataResult.error(() -> "Pipeline diameter must be finite and between 0.001 and " + MAX_DIAMETER + " m"),
                Settings::diameter);
        public static final StreamCodec<ByteBuf, Settings> STREAM_CODEC = ByteBufCodecs.DOUBLE.map(
                Settings::new, Settings::diameter);

        public Settings {
            if (!valid(diameter)) throw new IllegalArgumentException("Pipeline diameter must be finite and between 0.001 and " + MAX_DIAMETER + " m");
        }

        public static boolean valid(double diameter) {
            return Double.isFinite(diameter) && diameter >= MIN_DIAMETER && diameter <= MAX_DIAMETER;
        }
    }
}
