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
        /**
         * The mover a pipe of hydraulic kind {@code kind} carries ({@link PhysicalFluidTopology.Device#hydraulicKind}): the
         * engine's own classification, whatever law it runs (batch 2026-11-09-drawn-connections: an in-line machine is a
         * pump or a compressor as much as the legacy laws are).
         */
        public static InlineMover of(TopologyCompiler.Kind kind){return switch(kind){case PUMP->PUMP;case COMPRESSOR->COMPRESSOR;default->NONE;};}
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
     * The block drawn joined through the faces its registration joins (batch 2026-11-09-drawn-connections): {@link
     * FluidWorldAuthority#drawnFaces}, the network's own as-built rule, so what is drawn bolted together is what the network
     * joins. Mechanical joints stay drawn when a valve closes or a generator switches off. The state comes back as it is on
     * a client, off the server thread and where nothing is registered yet (the registration draws it). No block is read, so
     * no chunk loads (review 2026-10-26-chunk-loading-review, D1): a neighbour in a chunk that is not loaded is known by its
     * registration.
     */
    public static BlockState connectedState(BlockState state,LevelReader level,BlockPos position) {
        if(!(level instanceof ServerLevel server))return state;
        var world=authority(server);if(world==null)return state;
        int faces=world.drawnFaces(position(server,position));
        return faces<0?state:withFaces(state,faces);
    }
    /** {@code state} drawn joined through exactly {@code faces}, a bit per {@link Direction} ordinal. */
    public static BlockState withFaces(BlockState state,int faces){for(var direction:Direction.values())state=state.setValue(CONNECTIONS.get(direction),(faces>>direction.ordinal()&1)!=0);return state;}
    /** The level's fluid authority on the server thread; null off it or before the world opened it. */
    private static @Nullable FluidWorldAuthority authority(ServerLevel level){return level.getServer().isSameThread()?FluidWorldAuthority.find(level.getServer()).orElse(null):null;}
    private static PhysicalFluidTopology.Position position(Level level,BlockPos position){return new PhysicalFluidTopology.Position(level.dimension().location().toString(),position.getX(),position.getY(),position.getZ());}
    @Override public RenderShape getRenderShape(BlockState state){return RenderShape.MODEL;}
    /**
     * A device's shape: its block less a texel at the sides. A full cube would make the chunk renderer light every flat
     * face inside the block from the neighbour it faces (vanilla takes such a block's axis-aligned quads as lying on its
     * faces), so a pump's underside would take the ground's darkness.
     */
    private static final net.minecraft.world.phys.shapes.VoxelShape DEVICE=Block.box(1,0,1,15,16,15);
    /** A pipe's shape follows its drawn outside diameter (at least 3/16 block across, so a thin line can be aimed at); devices nearly fill their block. */
    @Override protected net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState state,BlockGetter level,BlockPos position,net.minecraft.world.phys.shapes.CollisionContext context) {
        if(kind!=TopologyCompiler.Kind.PIPE)return LinePieces.shape(level,position,DEVICE);
        double bore=level.getBlockEntity(position) instanceof FluidDeviceBlockEntity entity?entity.bore():.05;
        int code=com.wormzjl.icheme.science.equipment.PipeSizes.code(com.wormzjl.icheme.science.equipment.PipeSizes.outside(bore)),mask=0;
        for(var direction:Direction.values())if(state.getValue(CONNECTIONS.get(direction)))mask|=1<<direction.ordinal();
        // Unconnected, it lies along its facing as drawn. A dead end likewise runs to a blind flange at the other face.
        if(mask==0)mask=3<<(state.getValue(FACING).ordinal()&~1);
        else if(Integer.bitCount(mask)==1)mask|=1<<(Integer.numberOfTrailingZeros(mask)^1);
        long offsets=level.getBlockEntity(position) instanceof FluidDeviceBlockEntity entity?entity.lineOffsets():0;
        return LinePieces.shape(level,position,LineShapes.shape(mask,state.getValue(FACING).ordinal(),code,offsets));
    }
    @Override public @Nullable BlockEntity newBlockEntity(BlockPos position,BlockState state){return new FluidDeviceBlockEntity(position,state);}
    /** Only pipes and valves may stand in a vertical line (owner decision 2026-10-16); every other device faces a horizontal direction. */
    public static boolean vertical(TopologyCompiler.Kind kind){return kind==TopologyCompiler.Kind.PIPE||kind==TopologyCompiler.Kind.VALVE;}
    /**
     * Facing from the look, and drawn joined where the network will join the block once {@link #onPlace} registers it
     * (batch 2026-11-09-drawn-connections, {@link FluidWorldAuthority#placedFaces}). A client draws no joint until the
     * server's state arrives: it has no registry, and the drawing has no rule of its own.
     */
    @Override public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        var facing=vertical(kind)?context.getNearestLookingDirection().getOpposite():context.getHorizontalDirection().getOpposite();
        var state=defaultBlockState().setValue(FACING,facing);
        if(!(context.getLevel() instanceof ServerLevel server))return state;
        var world=authority(server);
        return world==null?state:withFaces(state,world.placedFaces(position(server,context.getClickedPos()),kind,PhysicalFluidTopology.Direction.valueOf(facing.name())));
    }
    public static BooleanProperty connection(Direction direction){return CONNECTIONS.get(direction);}
    /** Whether a line may meet this block through {@code direction}: a fitting or a filter takes lines only along its axis (the laying tool's ends). */
    static boolean face(BlockState state,Direction direction) {
        if(hasAxialAccessory(state)&&state.getValue(FACING).getAxis()!=direction.getAxis())return false;
        var block=(FluidDeviceBlock)state.getBlock();return block.kind!=TopologyCompiler.Kind.VALVE&&block.kind!=TopologyCompiler.Kind.FILTER||state.getValue(FACING).getAxis()==direction.getAxis();
    }
    @Override protected BlockState updateShape(BlockState state,Direction direction,BlockState neighbor,LevelAccessor level,BlockPos position,BlockPos neighborPosition) {
        // A neighbour placed or broken has registered or removed itself by now (onPlace, onRemove run before the shape
        // updates), so every face is drawn from the network's rule; a client keeps its state until the server's arrives.
        var connected=connectedState(state,level,position);
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
    /**
     * Server thread: the blocks being placed or replaced stand for registrations that already exist, so placing one registers
     * nothing and replacing one, or a rack cell ({@link PipeRackBlock}), removes no registration and recovers nothing. The
     * laying tool sets it for a line it registered itself ({@link LineLaying}), the re-alignment for a block it sets back
     * ({@link RegistryRealignment}).
     */
    static boolean registered;
    /** Runs {@code change} with {@link #registered} set: block edits that follow the registry, never edit it. */
    public static void quietly(Runnable change){boolean was=registered;registered=true;try{change.run();}finally{registered=was;}}
    /** Whether a block edit now follows the registry ({@link #quietly}): a shaft part block queues no part for it (W5c C1). */
    public static boolean quiet(){return registered;}
    /**
     * The block state registered device {@code d} stands as (registry first, batch 2026-11-03-save-corruption,
     * REGISTRY_FIRST_PLAN.md 4.3): its kind's block with its facing and fittings, the inverse of what {@link #onPlace} and
     * a chunk load register from a block. No connection and no sight glass: the placer connects it, and accessories
     * belong to the registration. Null for a device no block of this class stands for: a structure's, a rack lane, a
     * column's port, a heat side.
     */
    public static @Nullable BlockState stateFor(PhysicalFluidTopology.Device d) {
        if(!d.ports().isEmpty()||d.position().lane()!=0)return null;
        var block=switch(d.kind()){
            case RESERVOIR->com.wormzjl.icheme.registry.ModBlocks.FLUID_RESERVOIR.get();case PIPE->com.wormzjl.icheme.registry.ModBlocks.FLUID_PIPE.get();
            case GENERATOR->com.wormzjl.icheme.registry.ModBlocks.FLUID_GENERATOR.get();case VOID->com.wormzjl.icheme.registry.ModBlocks.FLUID_VOID.get();
            case FILTER->com.wormzjl.icheme.registry.ModBlocks.INLINE_FILTER.get();default->null;};
        return block==null?null:fittings(block.defaultBlockState(),d);
    }
    /**
     * {@code state} with {@code d}'s facing and, on a pipe, its fittings: cutoff, pressure valve, filter, in-line mover.
     * Connections and sight glass are kept. The fittings are the engine's classification of the registration
     * ({@link #fitted}), so an in-line machine stands as the pump or compressor it is, whatever law it runs.
     */
    public static BlockState fittings(BlockState state,PhysicalFluidTopology.Device d) {
        state=state.setValue(FACING,Direction.valueOf(d.facing().name()));
        return state.hasProperty(INLINE_MOVER)?fitted(state,d).setValue(SHUTOFF,Shutoff.of(d.cutoff())):state;
    }
    /**
     * A pipe {@code state} with the pressure valve, filter and mover that registration {@code d} compiles as
     * ({@link PhysicalFluidTopology.Device#hydraulicKind}): what the network does there is what the block draws (batch
     * 2026-11-09-drawn-connections). The cutoff and facing are left to the caller.
     */
    public static BlockState fitted(BlockState state,PhysicalFluidTopology.Device d) {
        var hydraulic=d.hydraulicKind();
        return state.setValue(PRESSURE_CONTROL,hydraulic==TopologyCompiler.Kind.VALVE).setValue(INLINE_FILTER,hydraulic==TopologyCompiler.Kind.FILTER)
                .setValue(INLINE_MOVER,InlineMover.of(hydraulic));
    }
    @Override protected void onPlace(BlockState state,Level level,BlockPos position,BlockState previous,boolean moved) {
        super.onPlace(state,level,position,previous,moved);if(!(level instanceof ServerLevel serverLevel)||registered)return;
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
        if(!registered&&state.getBlock()!=replacement.getBlock()&&level instanceof ServerLevel serverLevel)FluidWorldAuthority.find(serverLevel.getServer()).ifPresent(world->{
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
