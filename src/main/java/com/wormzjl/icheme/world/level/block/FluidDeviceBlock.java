package com.wormzjl.icheme.world.level.block;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.wormzjl.icheme.runtime.fluid.*;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** Gameplay adapter only: authoritative inventory is never stored in this block or its block entity. */
public sealed class FluidDeviceBlock extends BaseEntityBlock {
    public static final MapCodec<FluidDeviceBlock> CODEC=RecordCodecBuilder.mapCodec(instance->instance.group(
            Codec.STRING.xmap(TopologyCompiler.Kind::valueOf,TopologyCompiler.Kind::name).fieldOf("device_kind").forGetter(block->block.kind),propertiesCodec()).apply(instance,FluidDeviceBlock::create));
    public static final DirectionProperty FACING=BlockStateProperties.FACING;
    /** Static pipe accessory, with no hydraulic meaning. Changing it keeps this block and its entity. */
    public static final BooleanProperty SIGHT_GLASS=BooleanProperty.create("sight_glass");
    public static final EnumProperty<Direction.Axis> SIGHT_GLASS_AXIS=EnumProperty.create("sight_glass_axis",Direction.Axis.class);
    public enum InlineMover implements net.minecraft.util.StringRepresentable {
        NONE,PUMP,COMPRESSOR;
        @Override public String getSerializedName(){return name().toLowerCase(java.util.Locale.ROOT);}
        public TopologyCompiler.Kind kind(){return this==NONE?TopologyCompiler.Kind.PIPE:TopologyCompiler.Kind.valueOf(name());}
    }
    public static final EnumProperty<InlineMover> INLINE_MOVER=EnumProperty.create("inline_mover",InlineMover.class);
    public static final BooleanProperty INLINE_FILTER=BooleanProperty.create("inline_filter");
    public static final BooleanProperty PRESSURE_CONTROL=BooleanProperty.create("pressure_control");
    /** Committed lever position; a newly installed accessory initially draws open. */
    public enum Shutoff implements net.minecraft.util.StringRepresentable {
        NONE(PhysicalFluidTopology.Cutoff.NONE),OPEN(PhysicalFluidTopology.Cutoff.OPEN),CLOSED(PhysicalFluidTopology.Cutoff.CLOSED),CHECK(PhysicalFluidTopology.Cutoff.CHECK);
        private final PhysicalFluidTopology.Cutoff cutoff;
        Shutoff(PhysicalFluidTopology.Cutoff cutoff){this.cutoff=cutoff;}
        public PhysicalFluidTopology.Cutoff cutoff(){return cutoff;}
        @Override public String getSerializedName(){return name().toLowerCase(java.util.Locale.ROOT);}
        public static Shutoff of(PhysicalFluidTopology.Cutoff cutoff){return switch(cutoff){case NONE->NONE;case OPEN->OPEN;case CLOSED->CLOSED;case CHECK->CHECK;};}
    }
    public static final EnumProperty<Shutoff> SHUTOFF=EnumProperty.create("shutoff",Shutoff.class);
    private static final java.util.Map<Direction,BooleanProperty> CONNECTIONS=java.util.Map.of(
            Direction.DOWN,BlockStateProperties.DOWN,Direction.UP,BlockStateProperties.UP,Direction.NORTH,BlockStateProperties.NORTH,
            Direction.SOUTH,BlockStateProperties.SOUTH,Direction.WEST,BlockStateProperties.WEST,Direction.EAST,BlockStateProperties.EAST);
    private final TopologyCompiler.Kind kind;
    /** Only pipes carry cutoff blockstates; other devices retain their existing state space. */
    public static FluidDeviceBlock create(TopologyCompiler.Kind kind,BlockBehaviour.Properties properties){return kind==TopologyCompiler.Kind.PIPE?new Pipe(properties):new FluidDeviceBlock(kind,properties);}
    private FluidDeviceBlock(TopologyCompiler.Kind kind,BlockBehaviour.Properties properties) {
        super(properties);this.kind=kind;var state=stateDefinition.any().setValue(FACING,Direction.NORTH).setValue(SIGHT_GLASS,false).setValue(SIGHT_GLASS_AXIS,Direction.Axis.Z);
        if(kind==TopologyCompiler.Kind.PIPE)state=state.setValue(SHUTOFF,Shutoff.NONE).setValue(PRESSURE_CONTROL,false).setValue(INLINE_FILTER,false).setValue(INLINE_MOVER,InlineMover.NONE);
        for(var property:CONNECTIONS.values())state=state.setValue(property,false);registerDefaultState(state);
    }
    private static final class Pipe extends FluidDeviceBlock {
        private Pipe(BlockBehaviour.Properties properties){super(TopologyCompiler.Kind.PIPE,properties);}
        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){super.createBlockStateDefinition(builder);builder.add(SHUTOFF,PRESSURE_CONTROL,INLINE_FILTER,INLINE_MOVER);}
    }
    public TopologyCompiler.Kind kind(){return kind;}
    @Override public MapCodec<FluidDeviceBlock> codec(){return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){builder.add(FACING,SIGHT_GLASS,SIGHT_GLASS_AXIS);for(var property:CONNECTIONS.values())builder.add(property);}
    /** Connected faces in Minecraft Direction order, shared by installation and model geometry. */
    public static int connectionMask(BlockState state){int mask=0;for(var direction:Direction.values())if(state.getValue(CONNECTIONS.get(direction)))mask|=1<<direction.ordinal();return mask;}
    public static boolean supportsSightGlass(BlockState state) {
        if(!(state.getBlock() instanceof FluidDeviceBlock block)||block.kind!=TopologyCompiler.Kind.PIPE)return false;
        return com.wormzjl.icheme.science.equipment.SightGlassGeometry.supports(connectionMask(state));
    }
    public static boolean hasSightGlass(BlockState state){return supportsSightGlass(state)&&state.getValue(SIGHT_GLASS);}
    public static boolean hasPressureValve(BlockState state){return state.getBlock() instanceof FluidDeviceBlock block&&block.kind==TopologyCompiler.Kind.PIPE&&state.getValue(PRESSURE_CONTROL);}
    public static boolean hasFilter(BlockState s){return s.hasProperty(INLINE_FILTER)&&s.getValue(INLINE_FILTER);}
    public static boolean hasMover(BlockState s){return s.hasProperty(INLINE_MOVER)&&s.getValue(INLINE_MOVER)!=InlineMover.NONE;}
    public static boolean hasPump(BlockState s){return hasMover(s)&&s.getValue(INLINE_MOVER)==InlineMover.PUMP;}
    public static boolean hasCompressor(BlockState s){return hasMover(s)&&s.getValue(INLINE_MOVER)==InlineMover.COMPRESSOR;}
    public static boolean hasAxialAccessory(BlockState s){return hasPipeValve(s)||hasFilter(s)||hasMover(s);}
    public static boolean hasShutoff(BlockState state){return hasPipeValve(state)&&(state.getValue(SHUTOFF)==Shutoff.OPEN||state.getValue(SHUTOFF)==Shutoff.CLOSED);}
    public static boolean hasPipeValve(BlockState state){return state.getBlock() instanceof FluidDeviceBlock block&&block.kind==TopologyCompiler.Kind.PIPE&&(state.getValue(SHUTOFF)!=Shutoff.NONE||state.getValue(PRESSURE_CONTROL));}
    public static PhysicalFluidTopology.Cutoff cutoff(BlockState state){return state.getBlock() instanceof FluidDeviceBlock block&&block.kind==TopologyCompiler.Kind.PIPE?state.getValue(SHUTOFF).cutoff():PhysicalFluidTopology.Cutoff.NONE;}
    public static boolean hasFitting(BlockState state){return hasSightGlass(state)||hasAxialAccessory(state);}
    /** Small movers carry compact internal joints; larger movers flange at the block faces. */
    public static boolean hasBoundaryFitting(BlockState state,double bore){return hasFitting(state)&&(!hasMover(state)||com.wormzjl.icheme.science.equipment.InlineMoverGeometry.boundaryJoints(com.wormzjl.icheme.science.equipment.PipeSizes.code(com.wormzjl.icheme.science.equipment.PipeSizes.outside(bore))));}
    /**
     * Mechanical joints stay connected when a valve closes hydraulically. A face whose neighbour lies in a chunk that is not
     * loaded keeps its saved joint: on a server, reading that neighbour would load its chunk on the main thread, and a
     * pipe on the loaded area's edge would load the next chunk on each load, along the whole run (review
     * 2026-10-26-chunk-loading-review, D1). The neighbour's own load settles both sides.
     */
    public static BlockState connectedState(BlockState state,LevelReader level,BlockPos position) {
        for(var direction:Direction.values()){var at=position.relative(direction);if(!level.hasChunkAt(at))continue;state=state.setValue(CONNECTIONS.get(direction),connects(state,level,at,level.getBlockState(at),direction));}
        return state;
    }
    @Override public RenderShape getRenderShape(BlockState state){return RenderShape.MODEL;}
    /** Pipe shapes by drawn outside code and connected faces. */
    private static final java.util.Map<Integer,net.minecraft.world.phys.shapes.VoxelShape> PIPE_SHAPES=new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * A device's shape: its block less a texel at the sides. A full cube would make the chunk renderer light every flat
     * face inside the block from the neighbour it faces (vanilla takes such a block's axis-aligned quads as lying on its
     * faces), so a pump's underside would take the ground's darkness.
     */
    private static final net.minecraft.world.phys.shapes.VoxelShape DEVICE=Block.box(1,0,1,15,16,15);
    /** A pipe's shape follows its drawn outside diameter (at least 3/16 block across, so a thin line can be aimed at); devices nearly fill their block. */
    @Override protected net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState state,BlockGetter level,BlockPos position,net.minecraft.world.phys.shapes.CollisionContext context) {
        if(kind!=TopologyCompiler.Kind.PIPE)return DEVICE;
        double bore=level.getBlockEntity(position) instanceof FluidDeviceBlockEntity entity?entity.bore():.05;
        int code=com.wormzjl.icheme.science.equipment.PipeSizes.code(com.wormzjl.icheme.science.equipment.PipeSizes.outside(bore)),mask=0;
        for(var direction:Direction.values())if(state.getValue(CONNECTIONS.get(direction)))mask|=1<<direction.ordinal();
        // Unconnected, it lies along its facing as drawn. A dead end likewise runs to a blind flange at the other face.
        if(mask==0)mask=3<<(state.getValue(FACING).ordinal()&~1);
        else if(Integer.bitCount(mask)==1)mask|=1<<(Integer.numberOfTrailingZeros(mask)^1);
        return PIPE_SHAPES.computeIfAbsent(code<<6|mask,FluidDeviceBlock::pipeShape);
    }
    private static net.minecraft.world.phys.shapes.VoxelShape pipeShape(int key) {
        // At most a texel short of the block on each side: a full cube changes how its flat faces are lit (see DEVICE).
        double h=Math.min(7.5,Math.max(com.wormzjl.icheme.science.equipment.PipeSizes.outsideOf(key>>6)/2,3/32.0)*16),lo=8-h,hi=8+h;
        var shape=Block.box(lo,lo,lo,hi,hi,hi);
        for(var direction:Direction.values())if((key>>direction.ordinal()&1)!=0) {
            var arm=switch(direction){case DOWN->Block.box(lo,0,lo,hi,8,hi);case UP->Block.box(lo,8,lo,hi,16,hi);case NORTH->Block.box(lo,lo,0,hi,hi,8);
                case SOUTH->Block.box(lo,lo,8,hi,hi,16);case WEST->Block.box(0,lo,lo,8,hi,hi);case EAST->Block.box(8,lo,lo,16,hi,hi);};
            shape=net.minecraft.world.phys.shapes.Shapes.or(shape,arm);
        }
        return shape.optimize();
    }
    @Override public @Nullable BlockEntity newBlockEntity(BlockPos position,BlockState state){return new FluidDeviceBlockEntity(position,state);}
    /** Only pipes and valves may stand in a vertical line (owner decision 2026-10-16); every other device faces a horizontal direction. */
    public static boolean vertical(TopologyCompiler.Kind kind){return kind==TopologyCompiler.Kind.PIPE||kind==TopologyCompiler.Kind.VALVE;}
    @Override public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        var facing=vertical(kind)?context.getNearestLookingDirection().getOpposite():context.getHorizontalDirection().getOpposite();
        var state=defaultBlockState().setValue(FACING,facing);
        for(var direction:Direction.values()){var at=context.getClickedPos().relative(direction);state=state.setValue(CONNECTIONS.get(direction),connects(state,context.getLevel(),at,context.getLevel().getBlockState(at),direction));}
        return state;
    }
    public static BooleanProperty connection(Direction direction){return CONNECTIONS.get(direction);}
    private static boolean boundary(TopologyCompiler.Kind kind){return kind==TopologyCompiler.Kind.RESERVOIR||kind==TopologyCompiler.Kind.GENERATOR||kind==TopologyCompiler.Kind.VOID;}
    static boolean face(BlockState state,Direction direction) {
        if(hasAxialAccessory(state)&&state.getValue(FACING).getAxis()!=direction.getAxis())return false;
        var block=(FluidDeviceBlock)state.getBlock();return block.kind!=TopologyCompiler.Kind.VALVE&&block.kind!=TopologyCompiler.Kind.FILTER||state.getValue(FACING).getAxis()==direction.getAxis();
    }
    /**
     * The faces the fluid block at {@code position} may connect through (batch 2026-10-26-pipe-laying-system): a laid
     * pipe's joints from its block entity; every face for any other block, and for a block not yet placed.
     */
    public static int joints(BlockGetter level,BlockPos position){return level.getBlockEntity(position) instanceof FluidDeviceBlockEntity entity?entity.joints():PhysicalFluidTopology.ALL_FACES;}
    private static boolean joint(BlockGetter level,BlockPos position,Direction direction){return (joints(level,position)>>direction.ordinal()&1)!=0;}
    /**
     * Whether a pipe of this state and {@code joints} may meet a rack lane (plan 6, as {@link PhysicalFluidTopology.Device#transitionPipe}):
     * laid, of at most two joints, without fittings, so its run can bend to the lane's off-centre point.
     */
    public static boolean transition(BlockState state,int joints){return joints!=PhysicalFluidTopology.ALL_FACES&&Integer.bitCount(joints)<=2&&!hasFitting(state);}
    /**
     * Whether this block meets its neighbour towards {@code direction}: another device by the rules above, or, for a pipe,
     * a vessel nozzle facing it or a rack lane with a joint towards it (plan 5.2) where it is a transition pipe (see
     * {@link #transition}). Each side must have a joint on the shared face, so a laid line never joins a block it was only
     * laid beside.
     */
    private static boolean connects(BlockState state,BlockGetter level,BlockPos neighborPosition,BlockState neighbor,Direction direction) {
        var position=neighborPosition.relative(direction.getOpposite());
        if(!joint(level,position,direction))return false;
        if(neighbor.getBlock() instanceof FluidDeviceBlock other)return !(boundary(((FluidDeviceBlock)state.getBlock()).kind)&&boundary(other.kind))&&face(state,direction)&&face(neighbor,direction.getOpposite())&&joint(level,neighborPosition,direction.getOpposite());
        if(((FluidDeviceBlock)state.getBlock()).kind!=TopologyCompiler.Kind.PIPE||!face(state,direction))return false;
        if(neighbor.getBlock() instanceof PipeRackBlock)return transition(state,joints(level,position))&&level.getBlockEntity(neighborPosition) instanceof com.wormzjl.icheme.world.level.block.entity.PipeRackBlockEntity rack&&rack.laneTowards(direction.getOpposite())!=null;
        return neighbor.getBlock() instanceof EquipmentShellBlock&&level.getBlockEntity(neighborPosition) instanceof com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity shell&&shell.nozzleFacing(direction.getOpposite())!=null;
    }
    @Override protected BlockState updateShape(BlockState state,Direction direction,BlockState neighbor,LevelAccessor level,BlockPos position,BlockPos neighborPosition) {
        var connected=state.setValue(CONNECTIONS.get(direction),connects(state,level,neighborPosition,neighbor,direction));
        // Defer removal until the new connection state is installed. Several neighbour updates share one scheduled tick.
        if(level instanceof ServerLevel&&kind==TopologyCompiler.Kind.PIPE&&connected.getValue(SIGHT_GLASS)&&!supportsSightGlass(connected))level.scheduleTick(position,this,1);
        // The drawn faces follow the new connections once the state is in the world.
        if(level instanceof ServerLevel server)server.getServer().execute(()->{if(server.getBlockEntity(position) instanceof FluidDeviceBlockEntity entity)entity.refreshFacesAndSync();});
        return super.updateShape(connected,direction,neighbor,level,position,neighborPosition);
    }
    @Override protected void tick(BlockState state,ServerLevel level,BlockPos position,net.minecraft.util.RandomSource random) {
        if(kind!=TopologyCompiler.Kind.PIPE||!state.getValue(SIGHT_GLASS)||supportsSightGlass(state))return;
        if(level.setBlock(position,state.setValue(SIGHT_GLASS,false),Block.UPDATE_ALL)) {
            if(level.getBlockEntity(position) instanceof FluidDeviceBlockEntity entity)entity.refreshGeometry(entity.bore(),true);
            if(level.getBlockEntity(position) instanceof FluidDeviceBlockEntity entity)FluidWorldAuthority.find(level.getServer()).ifPresent(w->w.instruments().recoverLegacy(entity.fluidIdentity(),level,position));
        }
    }
    /** Server thread: the laying tool is placing the blocks of a line it registered itself ({@link PipeLaying}). */
    static boolean laying;
    @Override protected void onPlace(BlockState state,Level level,BlockPos position,BlockState previous,boolean moved) {
        super.onPlace(state,level,position,previous,moved);if(!(level instanceof ServerLevel serverLevel)||laying)return;
        FluidWorldAuthority.find(serverLevel.getServer()).ifPresent(world->{
            var p=new PhysicalFluidTopology.Position(level.dimension().location().toString(),position.getX(),position.getY(),position.getZ());
            if(previous.getBlock()!=this) {
                var record=world.place(p,kind,PhysicalFluidTopology.Direction.valueOf(state.getValue(FACING).name()),cutoff(state),hasPressureValve(state),hasFilter(state),hasMover(state)?state.getValue(INLINE_MOVER).kind():TopologyCompiler.Kind.PIPE);
                if(level.getBlockEntity(position) instanceof FluidDeviceBlockEntity entity)entity.bindIdentity(record.device().id());
            } else if(!hasAxialAccessory(state)&&!hasAxialAccessory(previous)&&previous.getValue(FACING)!=state.getValue(FACING)) {
                world.at(p).ifPresent(old->{var d=old.device();world.edit(d.id(),old.revision(),d.withFacing(PhysicalFluidTopology.Direction.valueOf(state.getValue(FACING).name())),old.spec());});
            }
        });
    }
    @Override public BlockState playerWillDestroy(Level level,BlockPos pos,BlockState state,Player player){if(level.getBlockEntity(pos) instanceof com.wormzjl.icheme.runtime.instrument.Instrument.Holder h)h.instruments().recoverOnBreak(!player.isCreative());return super.playerWillDestroy(level,pos,state,player);}
    @Override protected void onRemove(BlockState state,Level level,BlockPos position,BlockState replacement,boolean moved) {
        if(state.getBlock()!=replacement.getBlock()&&level instanceof ServerLevel serverLevel)FluidWorldAuthority.find(serverLevel.getServer()).ifPresent(world->{
            var p=new PhysicalFluidTopology.Position(level.dimension().location().toString(),position.getX(),position.getY(),position.getZ());world.at(p).ifPresent(r->{boolean drops=!(level.getBlockEntity(position) instanceof com.wormzjl.icheme.runtime.instrument.Instrument.Holder h)||h.instruments().recoverOnBreak();world.instruments().recover(r.device().id(),level,position,com.wormzjl.icheme.runtime.instrument.InstrumentHosts.legacy(state),drops);world.remove(r.device().id());});
        });
        super.onRemove(state,level,position,replacement,moved);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos position,Player player,BlockHitResult hit) {
        if(hasPressureValve(state)||hasFilter(state)||hasMover(state)) {
            if(player instanceof ServerPlayer serverPlayer&&level.getBlockEntity(position) instanceof FluidDeviceBlockEntity entity)entity.open(serverPlayer,false);
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        if(hasPipeValve(state)&&player.getMainHandItem().isEmpty()&&player.getOffhandItem().isEmpty()) {
            if(!player.mayBuild()||player.isSpectator()||!level.mayInteract(player,position)||!player.mayUseItemAt(position,hit.getDirection(),ItemStack.EMPTY))return InteractionResult.FAIL;
            if(player instanceof ServerPlayer serverPlayer&&level.getBlockEntity(position) instanceof FluidDeviceBlockEntity entity)
                FluidWorldAuthority.find(serverPlayer.server).ifPresent(world->world.togglePipeCutoff(entity.fluidIdentity()));
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        if(kind==TopologyCompiler.Kind.PIPE&&!(level.getBlockEntity(position) instanceof FluidDeviceBlockEntity pipe&&!pipe.instruments().sensors().isEmpty()))return InteractionResult.PASS;
        if(player instanceof ServerPlayer serverPlayer&&level.getBlockEntity(position) instanceof FluidDeviceBlockEntity entity)entity.open(serverPlayer,false);
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack,BlockState state,Level level,BlockPos position,Player player,InteractionHand hand,BlockHitResult hit) {
        if(stack.is(com.wormzjl.icheme.registry.ModItems.FLUID_DEBUGGER.get())) {
            if(player instanceof ServerPlayer serverPlayer&&level.getBlockEntity(position) instanceof FluidDeviceBlockEntity entity)entity.open(serverPlayer,true);
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
}
