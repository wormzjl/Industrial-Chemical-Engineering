package com.wormzjl.icheme.world.level.block.entity;

import com.wormzjl.icheme.registry.ModBlockEntities;
import com.wormzjl.icheme.runtime.fluid.*;
import com.wormzjl.icheme.science.equipment.LineGeometry;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.world.inventory.FluidDeviceMenu;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.*;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

import java.util.Arrays;

/**
 * Loaded presentation binding only. No nitrogen, fluid amount, energy, or clock is reconstructed from this TE.
 * Loading and identity binding only mark the device for its presentation bucket; the view arrives with that
 * bucket. The block update this entity sends carries its identity and its drawn geometry, nothing else.
 *
 * <p>Drawn geometry (batch 2026-10-15-custom-equipment-rendering, owner request 2026-10-16): the line bore of a pipe or
 * filter, and for each face the outside diameter of the line met there, as {@link PipeSizes#code} values (0: nothing
 * connects). It is static geometry, not process data: the server refreshes it when the block loads, when a neighbour
 * changes and when a topology commit applies an edit (the engine's schedule), and the update tag carries it like a
 * vessel cell's shape.
 */
public final class FluidDeviceBlockEntity extends BlockEntity implements com.wormzjl.icheme.runtime.instrument.Instrument.Holder, FluidView.Receiver {
    /** Accessed only by the client-thread lifecycle hooks and renderer, including in an integrated server. */
    private static final java.util.Set<FluidDeviceBlockEntity> CLIENT_GLASSES=java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    public static java.util.List<FluidDeviceBlockEntity> clientSightGlasses(){return java.util.List.copyOf(CLIENT_GLASSES);}
    /** Client: the pipes with an open end, which the pipe laying tool marks while it is held. Client thread only; weak, as above. */
    private static final java.util.Set<FluidDeviceBlockEntity> CLIENT_OPEN_ENDS=java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    public static java.util.List<FluidDeviceBlockEntity> clientOpenEnds(){return java.util.List.copyOf(CLIENT_OPEN_ENDS);}
    private void trackOpenEnds(){if(level!=null&&level.isClientSide()){if(openEnds()!=0)CLIENT_OPEN_ENDS.add(this);else CLIENT_OPEN_ENDS.remove(this);}}
    /**
     * The faces where this pipe's line ends open: the blind-flanged end of a dead end (straight on from its one
     * connection, or both ends along its facing when it has none), and a laid pipe's joints that nothing meets any more.
     * A line laid to such a face joins it (plan 4.2). 0 for every other device.
     */
    public int openEnds() {
        if(!(getBlockState().getBlock() instanceof FluidDeviceBlock block)||block.kind()!=TopologyCompiler.Kind.PIPE)return 0;
        int connected=FluidDeviceBlock.connectionMask(getBlockState());
        int dangling=joints==PhysicalFluidTopology.ALL_FACES?0:joints&~connected;
        int dead=connected==0?3<<(getBlockState().getValue(FluidDeviceBlock.FACING).ordinal()&~1):Integer.bitCount(connected)==1?1<<(Integer.numberOfTrailingZeros(connected)^1):0;
        return dangling|dead;
    }
    @Override public void setBlockState(BlockState state) {
        boolean before=FluidDeviceBlock.hasSightGlass(getBlockState());super.setBlockState(state);
        if(level!=null&&level.isClientSide()) {
            if(FluidDeviceBlock.hasSightGlass(state))CLIENT_GLASSES.add(this);else CLIENT_GLASSES.remove(this);
            if(before&&!FluidDeviceBlock.hasSightGlass(state))com.wormzjl.icheme.client.SightGlassRenderer.forget(worldPosition);
            trackOpenEnds();
        }
    }
    /** Model data read while the chunk is meshed. */
    public static final ModelProperty<Drawn> DRAWN = new ModelProperty<>();
    /**
     * What the model needs beyond the block state: the bore, the line met at each face (seven bits each), the faces whose
     * joint is flanged (a bit each: a line meeting equipment or a vessel nozzle), whether this pipe ends a spool, and where
     * the line met at each face leaves the face's centre ({@link LineGeometry.Key#offsets}; a transition pipe's), and the
     * line's primary paint (its pipe) and secondary paint (its flanges), each
     * {@link com.wormzjl.icheme.science.equipment.Paint#BARE} when it has none.
     */
    public record Drawn(double bore, long faces, int flanged, boolean spool, long offsets, int paint, int secondary) {
        public int face(Direction direction) {
            return (int) (faces >>> (7 * direction.ordinal()) & 127);
        }

        public boolean flanged(Direction direction) {
            return (flanged >> direction.ordinal() & 1) != 0;
        }
    }
    /** Bit of a face byte marking a flanged joint; the low seven bits hold the size code. */
    private static final int FLANGED = 128;
    private long identity;
    private final com.wormzjl.icheme.runtime.instrument.Instrument.Metadata instruments=new com.wormzjl.icheme.runtime.instrument.Instrument.Metadata();
    @Override public com.wormzjl.icheme.runtime.instrument.Instrument.Metadata instruments(){return instruments;}
    private FluidView lastView;
    private double bore = .05;
    private BlockState drawnState;
    private final byte[] faces = new byte[6];
    /** Drawn state beside {@link #faces}: the off-centre point of the line met at each face, as {@link LineGeometry.Key#offsets}. */
    private long offsets;
    /**
     * The faces this pipe may connect through (batch 2026-10-26-pipe-laying-system), a copy of its registration's joints:
     * drawing reads it, and a block that has lost its registration (the D4 reset) registers again with it. Saved only
     * when it is not every face, so a hand-placed pipe saves as before.
     */
    private int joints = PhysicalFluidTopology.ALL_FACES;
    /**
     * The paint of a laid line (batch 2026-10-26-pipe-laying-system), 0xRRGGBB, or bare steel: a fact the player chose
     * with the laying tool, kept on the block as a vessel's paint is kept on its cells. Saved only when painted, so a
     * pipe saved before paint existed reads bare, as it looked. This is the primary paint, the pipe's.
     */
    private int paint = com.wormzjl.icheme.science.equipment.Paint.BARE;
    /**
     * The secondary paint, the flanges' (owner request 2026-10-26: primary and secondary, as a vessel has). Saved only when
     * it differs from {@link #paint}, so a pipe saved before it existed reads with its flanges in its paint, as it looked.
     */
    private int secondary = com.wormzjl.icheme.science.equipment.Paint.BARE;
    public FluidDeviceBlockEntity(BlockPos pos,BlockState state){super(ModBlockEntities.FLUID_DEVICE.get(),pos,state);}
    /** The line's primary paint, its pipe's: 0xRRGGBB, or {@link com.wormzjl.icheme.science.equipment.Paint#BARE}. */
    public int paint(){return paint;}
    /** The line's secondary paint, its flanges': 0xRRGGBB, or {@link com.wormzjl.icheme.science.equipment.Paint#BARE}. */
    public int secondary(){return secondary;}
    /** Server: paints this pipe (the laying tool's paints at a lay) and redraws it. */
    public void paint(int primary,int secondary){
        if(!com.wormzjl.icheme.science.equipment.Paint.line(primary)||!com.wormzjl.icheme.science.equipment.Paint.line(secondary))throw new IllegalArgumentException("A line's paint is 0xRRGGBB or bare");
        if(paint==primary&&this.secondary==secondary)return;paint=primary;this.secondary=secondary;sync();
    }
    @Override public long fluidIdentity(){return identity;}
    public TopologyCompiler.Kind kind(){return ((FluidDeviceBlock)getBlockState().getBlock()).kind();}
    /** The bore the line has at this block: a pipe's or filter's own, else that of a pipe it meets (0 when none). */
    public double bore(){return bore;}
    /** The faces this pipe may connect through; every face for a hand-placed pipe and every other device. */
    public int joints(){return joints;}
    public void bindIdentity(long id) {
        if(id<=0)throw new IllegalStateException("Invalid fluid identity");
        if(level instanceof ServerLevel serverLevel) {
            var world=FluidWorldAuthority.find(serverLevel.getServer()).orElseThrow();
            var p=new PhysicalFluidTopology.Position(level.dimension().location().toString(),worldPosition.getX(),worldPosition.getY(),worldPosition.getZ());
            var record=world.at(p).orElseThrow();
            if(record.device().id()!=id)throw new IllegalStateException("Fluid identity belongs to a different position");
            bore=record.device().geometry().diameter();joints=record.device().joints();
            computeFaces();
        }
        identity=id;com.wormzjl.icheme.runtime.instrument.InstrumentHosts.loaded(this);setChanged();
        if(level instanceof ServerLevel serverLevel){FluidWorldAuthority.find(serverLevel.getServer()).ifPresent(w->w.deviceLoaded(id));level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),2);}
    }
    @Override public void onLoad() {
        super.onLoad();com.wormzjl.icheme.runtime.instrument.InstrumentHosts.loaded(this);
        if(level!=null&&level.isClientSide()&&FluidDeviceBlock.hasSightGlass(getBlockState())){com.wormzjl.icheme.client.SightGlassRenderer.forget(worldPosition);CLIENT_GLASSES.add(this);}
        trackOpenEnds();
        if(!(level instanceof ServerLevel serverLevel))return;
        FluidWorldAuthority.find(serverLevel.getServer()).ifPresent(world->{
            var p=new PhysicalFluidTopology.Position(level.dimension().location().toString(),worldPosition.getX(),worldPosition.getY(),worldPosition.getZ());
            world.at(p).ifPresent(record->{
                if(identity!=record.device().id())bindIdentity(record.device().id());else world.deviceLoaded(identity);
                // Drawn geometry: onLoad runs once the chunk is in the level (Level.tickBlockEntities), so its loaded
                // neighbours are readable; a neighbour loaded before this block drew no line here, so both sides refresh.
                // Neighbours in unloaded chunks keep their saved joints (FluidDeviceBlock.connectedState).
                refreshGeometry(record.device().geometry().diameter(),true);
            });
        });
    }
    /**
     * Server: the registered bore after a topology commit (or a load), and the faces it changes here and next door. Each
     * neighbour's faces depend on this block's bore only, so the refresh stops one block away.
     */
    public void refreshGeometry(double registeredBore,boolean neighbours) {
        if(!(level instanceof ServerLevel serverLevel))return;
        // Attachment presence follows accepted player input immediately. Lever state still follows commits.
        var world=FluidWorldAuthority.find(serverLevel.getServer()).orElse(null);
        var projected=world==null?null:world.registrations().get(identity);
        var committed=world==null?null:world.committedDevice(identity).orElse(null);
        boolean jointsChanged=false;
        if(projected!=null&&projected.device().kind()==TopologyCompiler.Kind.PIPE){
            var d=projected.device();var state=getBlockState();
            // The joints follow accepted player input at once, as a fitting does: the connected faces below read them.
            if(joints!=d.joints()){joints=d.joints();jointsChanged=true;neighbours=true;}
            var updated=state.setValue(FluidDeviceBlock.PRESSURE_CONTROL,d.control() instanceof com.wormzjl.icheme.science.fluid.network.FlowControl.Valve)
                    .setValue(FluidDeviceBlock.INLINE_FILTER,d.inlineFilter())
                    .setValue(FluidDeviceBlock.INLINE_MOVER,d.control() instanceof com.wormzjl.icheme.science.fluid.network.FlowControl.Pump?FluidDeviceBlock.InlineMover.PUMP:d.control() instanceof com.wormzjl.icheme.science.fluid.network.FlowControl.Compressor?FluidDeviceBlock.InlineMover.COMPRESSOR:FluidDeviceBlock.InlineMover.NONE);
            if(d.cutoff()==PhysicalFluidTopology.Cutoff.NONE)updated=updated.setValue(FluidDeviceBlock.SHUTOFF,FluidDeviceBlock.Shutoff.NONE);
            else if(committed!=null&&committed.cutoff()!=PhysicalFluidTopology.Cutoff.NONE)
                updated=updated.setValue(FluidDeviceBlock.SHUTOFF,FluidDeviceBlock.Shutoff.of(committed.cutoff()));
            if(d.inlineFilter()||d.control() instanceof com.wormzjl.icheme.science.fluid.network.FlowControl.Mover||d.control() instanceof com.wormzjl.icheme.science.fluid.network.FlowControl.Valve||d.cutoff()!=PhysicalFluidTopology.Cutoff.NONE)
                updated=updated.setValue(FluidDeviceBlock.FACING,Direction.valueOf((d.cutoff()!=PhysicalFluidTopology.Cutoff.NONE&&committed!=null&&committed.cutoff()!=PhysicalFluidTopology.Cutoff.NONE?committed:d).facing().name()));
            updated=FluidDeviceBlock.connectedState(updated,level,worldPosition);
            if(updated!=state){level.setBlock(worldPosition,updated,net.minecraft.world.level.block.Block.UPDATE_ALL);neighbours=true;}
        }
        boolean changed=bore!=registeredBore;bore=registeredBore;
        boolean appearanceChanged=drawnState!=getBlockState();drawnState=getBlockState();
        // Static fitting changes need a model-data refresh even on an isolated pipe with unchanged bore/faces.
        if(refreshFaces()||changed||appearanceChanged||jointsChanged)sync();
        if(changed||neighbours)for(var direction:Direction.values()) {
            var at=worldPosition.relative(direction);
            if(!level.isLoaded(at))continue;
            if(level.getBlockEntity(at) instanceof FluidDeviceBlockEntity neighbour){if(neighbour.refreshFaces())neighbour.sync();}
            else if(level.getBlockEntity(at) instanceof PipeRackBlockEntity rack)rack.redraw();
            else EquipmentStructure.refreshNozzlesAt(level,at);
        }
    }
    /** Server: recompute the faces from the neighbours; true when they changed. */
    public boolean refreshFaces(){var before=faces.clone();long offsetsBefore=offsets;computeFaces();return !Arrays.equals(before,faces)||offsets!=offsetsBefore;}
    /** A transition pipe (plan 6): a line met off a face's centre. Its run is not straight, so it takes no fitting or gauge. */
    public boolean transition(){return offsets!=0;}
    public void refreshFacesAndSync(){if(refreshFaces())sync();}
    private void sync(){setChanged();if(level!=null)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),2);}
    private void computeFaces() {
        if(level==null)return;
        boolean pipe=kind()==TopologyCompiler.Kind.PIPE;
        offsets=0;
        for(var direction:Direction.values()) {
            int code=0;boolean flanged=false;
            if(getBlockState().getValue(FluidDeviceBlock.connection(direction))&&level.isLoaded(worldPosition.relative(direction))) {
                var neighbour=level.getBlockEntity(worldPosition.relative(direction));
                if(neighbour instanceof FluidDeviceBlockEntity other) {
                    boolean otherPipe=other.kind()==TopologyCompiler.Kind.PIPE;
                    // A pipe meets the next pipe's size (for its reducer), welded except at accessory fittings. At equipment both sides flange at the
                    // pipe's size, whatever it is (owner request 2026-10-21: nozzles and their flanges follow the pipe
                    // everywhere); two devices bolt together at the larger of their sizes.
                    if(pipe&&otherPipe){code=PipeSizes.code(PipeSizes.outside(other.bore));flanged=FluidDeviceBlock.hasBoundaryFitting(getBlockState(),bore)||FluidDeviceBlock.hasBoundaryFitting(other.getBlockState(),other.bore);}
                    else{code=PipeSizes.code(PipeSizes.outside(pipe?bore:otherPipe?other.bore:Math.max(bore,other.bore)));flanged=true;}
                } else if(pipe&&neighbour instanceof PipeRackBlockEntity rack) {
                    // A lane is welded to the pipe that continues it, at the lane's size (plan 5.2: no flanges at racks).
                    var lane=rack.laneTowards(direction.getOpposite());
                    if(lane!=null){
                        code=PipeSizes.code(PipeSizes.outside(lane.bore()));
                        // The pipe meets the lane at the lane's point on the shared face, whose in-plane axes are this face's too.
                        var at=com.wormzjl.icheme.science.equipment.RackGeometry.steps(rack.point(lane,direction.getOpposite().ordinal()));
                        offsets|=LineGeometry.Key.offsets(direction.ordinal(),at[0],at[1]);
                    }
                } else if(pipe&&neighbour instanceof EquipmentShellBlockEntity shell) {
                    var nozzle=shell.nozzleFacing(direction.getOpposite());
                    // The vessel's flange is the pipe's size, its neck too (owner decision 2026-10-21: a 1 m pipe has a 1 m root): the pipe's flange matches it.
                    if(nozzle!=null){
                        code=PipeSizes.code(PipeSizes.outside(bore));flanged=true;
                    }
                }
            }
            faces[direction.ordinal()]=(byte)(code|(flanged?FLANGED:0));
        }
    }
    @Override public void onChunkUnloaded(){super.onChunkUnloaded();released();}
    @Override public void setRemoved(){super.setRemoved();com.wormzjl.icheme.runtime.instrument.InstrumentHosts.unloaded(this);if(level!=null&&level.isClientSide())com.wormzjl.icheme.client.InstrumentRenderer.forget(worldPosition);released();}
    /** Unloaded or removed: publications stop marking this device. */
    private void released() {
        com.wormzjl.icheme.runtime.instrument.InstrumentHosts.unloaded(this);
        if(level!=null&&level.isClientSide())com.wormzjl.icheme.client.InstrumentRenderer.forget(worldPosition);
        if(level!=null&&level.isClientSide()){CLIENT_GLASSES.remove(this);CLIENT_OPEN_ENDS.remove(this);com.wormzjl.icheme.client.SightGlassRenderer.forget(worldPosition);}
        if(identity!=0&&level instanceof ServerLevel serverLevel&&serverLevel.getServer().isSameThread())FluidWorldAuthority.find(serverLevel.getServer()).ifPresent(world->world.deviceUnloaded(identity));
    }
    /** The engine's bucket hands over this device's view (the authority checked the identity); the block entity keeps it for server-side readers. */
    @Override public void acceptFluidView(FluidView view){lastView=view;}
    public FluidView lastView(){return lastView;}
    public void open(ServerPlayer player,boolean debug) {
        if(identity==0){player.displayClientMessage(Component.translatable("gui.icheme.fluid.waiting_identity"),true);return;}
        player.openMenu(new MenuProvider() {
            public Component getDisplayName(){return Component.translatable(FluidDeviceBlock.hasPump(getBlockState())?"item.icheme.fluid_pump":FluidDeviceBlock.hasCompressor(getBlockState())?"item.icheme.fluid_compressor":FluidDeviceBlock.hasFilter(getBlockState())?"item.icheme.inline_filter":FluidDeviceBlock.hasPressureValve(getBlockState())?"item.icheme.pressure_control_valve":getBlockState().getBlock().getDescriptionId());}
            public AbstractContainerMenu createMenu(int containerId,Inventory inventory,Player player){return new FluidDeviceMenu(containerId,inventory,worldPosition,identity,debug,FluidDeviceMenu.REACH);}
        },buffer->{buffer.writeBlockPos(worldPosition);buffer.writeLong(identity);buffer.writeBoolean(debug);buffer.writeDouble(FluidDeviceMenu.REACH);});
        // Replay only after the opening packet and server menu exist, preserving packet order.
        if(player.containerMenu instanceof FluidDeviceMenu menu&&menu.identity()==identity)
            FluidWorldAuthority.find(player.server).ifPresent(world->world.replayOnOpen(menu));
    }
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider registries){super.saveAdditional(tag,registries);instruments.write(tag);tag.putLong("FluidIdentity",identity);writeLine(joints,paint,secondary,tag);}
    /** Writes a pipe's line facts: its joints when not every face, its paint when painted, its secondary paint when it differs. */
    public static CompoundTag writeLine(int joints,int paint,int secondary,CompoundTag tag){
        if(joints!=PhysicalFluidTopology.ALL_FACES)tag.putByte("Joints",(byte)joints);
        return writePaints(paint,secondary,tag);
    }
    /** Writes a line's paints, as a pipe and a rack lane save them: its paint when painted, its secondary paint when it differs. */
    public static CompoundTag writePaints(int paint,int secondary,CompoundTag tag){
        if(paint!=com.wormzjl.icheme.science.equipment.Paint.BARE)tag.putInt("Paint",paint);
        if(secondary!=paint)tag.putInt("Secondary",secondary);
        return tag;
    }
    /** A pipe's saved joints. Absent: every face, as every pipe connected before joints existed. A value past the six faces is not a joint set. */
    public static int readJoints(CompoundTag tag){int saved=tag.contains("Joints")?tag.getByte("Joints")&255:PhysicalFluidTopology.ALL_FACES;return (saved&~PhysicalFluidTopology.ALL_FACES)==0?saved:PhysicalFluidTopology.ALL_FACES;}
    /** A pipe's saved paint. Absent: bare, as every pipe looked before paint existed. A value that is no colour is repaired to bare and logged. */
    public static int readPaint(CompoundTag tag){return readPaint(tag,"pipe_compat");}
    /** {@link #readPaint(CompoundTag)}, a repair logged under {@code log}: a pipe's and a rack lane's paint read alike. */
    public static int readPaint(CompoundTag tag,String log){
        if(!tag.contains("Paint"))return com.wormzjl.icheme.science.equipment.Paint.BARE;
        int saved=tag.contains("Paint",net.minecraft.nbt.Tag.TAG_INT)?tag.getInt("Paint"):Integer.MIN_VALUE;
        if((saved&~0xFFFFFF)==0)return saved;
        com.wormzjl.icheme.IChemE.LOGGER.warn("{} repair=paint ({}) -> bare",log,tag.get("Paint"));
        return com.wormzjl.icheme.science.equipment.Paint.BARE;
    }
    /**
     * A pipe's saved secondary paint. Absent: {@code paint}, as the flanges looked before the secondary paint existed. A
     * value that is neither a colour nor bare is repaired to {@code paint} and logged.
     */
    public static int readSecondary(CompoundTag tag,int paint){return readSecondary(tag,paint,"pipe_compat");}
    /** {@link #readSecondary(CompoundTag, int)}, a repair logged under {@code log}. */
    public static int readSecondary(CompoundTag tag,int paint,String log){
        if(!tag.contains("Secondary"))return paint;
        int saved=tag.contains("Secondary",net.minecraft.nbt.Tag.TAG_INT)?tag.getInt("Secondary"):Integer.MIN_VALUE;
        if(com.wormzjl.icheme.science.equipment.Paint.line(saved))return saved;
        com.wormzjl.icheme.IChemE.LOGGER.warn("{} repair=secondary ({}) -> paint",log,tag.get("Secondary"));
        return paint;
    }
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider registries){
        super.loadAdditional(tag,registries);instruments.read(tag);identity=tag.getLong("FluidIdentity");lastView=null;
        joints=readJoints(tag);paint=readPaint(tag);secondary=readSecondary(tag,paint);
        if(tag.contains("Bore")){double read=tag.getDouble("Bore");if(read>0&&read<=com.wormzjl.icheme.science.equipment.PipeSizes.MAX_BORE)bore=read;}
        if(tag.contains("Faces")){var read=tag.getByteArray("Faces");if(read.length==6)for(int i=0;i<6;i++){int v=read[i]&255;faces[i]=(byte)(Math.min(64,v&127)|v&FLANGED);}}
        // Drawn state from the update tag only: ten bits a face, the bits past the six faces cleared.
        offsets=tag.getLong("Offsets")&(1L<<60)-1;
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries){var tag=new CompoundTag();instruments.write(tag);tag.putLong("FluidIdentity",identity);tag.putDouble("Bore",bore);tag.putByteArray("Faces",faces.clone());if(offsets!=0)tag.putLong("Offsets",offsets);writeLine(joints,paint,secondary,tag);return tag;}
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
    /** Drawn geometry that arrives after the block was meshed: refresh the model data and rebuild the section. */
    @Override public void onDataPacket(net.minecraft.network.Connection connection,ClientboundBlockEntityDataPacket packet,HolderLookup.Provider registries) {
        super.onDataPacket(connection,packet,registries);com.wormzjl.icheme.runtime.instrument.InstrumentHosts.loaded(this);requestModelDataUpdate();trackOpenEnds();
        if(level!=null)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),0);
    }
    @Override public ModelData getModelData() {
        long packed=0;int flanged=0;
        for(int i=0;i<6;i++){packed|=(long)(faces[i]&127)<<(7*i);if((faces[i]&FLANGED)!=0)flanged|=1<<i;}
        return ModelData.of(DRAWN,new Drawn(bore,packed,flanged,spoolEnd(),offsets,paint,secondary));
    }
    /** A straight pipe whose coordinate along its run is a multiple of {@link LineGeometry#SPOOL}: it carries the flange pair between spools. */
    private boolean spoolEnd() {
        var state=getBlockState();if(!(state.getBlock() instanceof FluidDeviceBlock block)||block.kind()!=TopologyCompiler.Kind.PIPE)return false;
        for(var axis:Direction.Axis.values()) {
            var negative=Direction.fromAxisAndDirection(axis,Direction.AxisDirection.NEGATIVE);
            if(state.getValue(FluidDeviceBlock.connection(negative))&&state.getValue(FluidDeviceBlock.connection(negative.getOpposite())))
                return Math.floorMod(worldPosition.get(axis),LineGeometry.SPOOL)==0;
        }
        return false;
    }
}
