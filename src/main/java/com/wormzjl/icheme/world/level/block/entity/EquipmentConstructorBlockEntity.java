package com.wormzjl.icheme.world.level.block.entity;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModBlockEntities;
import com.wormzjl.icheme.science.equipment.ColumnDesign;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.MachineDuty;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.ShapeRepair;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.science.equipment.VesselPlacement;
import com.wormzjl.icheme.world.level.block.EquipmentConstructorBlock;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The design an equipment constructor builds: the vessel's shape and where it stands relative to the constructor.
 * Never ticks. The design is synced to clients so that looking at the constructor shows the vessel's ghost.
 */
public final class EquipmentConstructorBlockEntity extends BlockEntity {
    /**
     * A vessel relative to its constructor: diameter, tangent length, which way it lies, its support height and where it
     * stands, and a drum's side connections; a column's height follows its trays instead of the tangent length; a unit
     * reads diameter, length and support as its own sizes ({@link ProcessUnit}). Like the placement, the form only becomes
     * a world direction with the constructor's facing. The drum's length and connections, the column's trays and the air
     * cooler's bank are all kept, so changing the form loses none of them; so is the boot, which only a lying drum carries.
     * The paint colours whatever is built. {@code outlet}: the quarter turns from the front a bottom outlet leaving through
     * a skirt takes (owner request 2026-10-25), valid for the form ({@link EquipmentShape#outlets}): a standing drum's or
     * a trickle-bed reactor's; 0 for every other form.
     *
     * <p>{@code column}: the column design imported from a column calculator (batch 2026-10-26-multi-machine-framework,
     * plan section 4.5), or null. A column is built from it only: its diameter, trays and components; the skirt, the
     * placement and the paint stay the player's. Without one, the column form shows {@code trays} at {@code diameter} (a
     * design saved before imports were required) and builds nothing.
     *
     * <p>{@code turns}: the column's side nozzles the player turned (finding F3, owner decision 2026-10-27), applied to
     * whichever trays the column shows; every other side nozzle takes its side by itself. Kept across an import and a
     * form switch; a turn for a nozzle the column does not have changes nothing.
     */
    public record Design(double diameter, int tangentLength, VesselPlacement.Form form, int support, VesselPlacement placement, ColumnTrays trays,
                         List<EquipmentShape.Connection> connections, Paint paint, ProcessUnit.Bank bank, boolean boot, int outlet,
                         @org.jetbrains.annotations.Nullable ColumnDesign column, List<ColumnTrays.Turn> turns, com.wormzjl.icheme.science.equipment.TowerBank towerBank, String name,
                         MachineDuty duty, com.wormzjl.icheme.science.equipment.ExchangerType exchangerType) {
        public Design(double diameter, int tangentLength, VesselPlacement.Form form, int support, VesselPlacement placement, ColumnTrays trays,
                         List<EquipmentShape.Connection> connections, Paint paint, ProcessUnit.Bank bank, boolean boot, int outlet,
                         @org.jetbrains.annotations.Nullable ColumnDesign column, List<ColumnTrays.Turn> turns, com.wormzjl.icheme.science.equipment.TowerBank towerBank, String name,
                         MachineDuty duty) {
            this(diameter,tangentLength,form,support,placement,trays,connections,paint,bank,boot,outlet,column,turns,towerBank,name,duty,com.wormzjl.icheme.science.equipment.ExchangerType.STANDARD);
        }

        /** A design with no name: built by hand, its structure starts unnamed (N1). */
        public Design(double diameter, int tangentLength, VesselPlacement.Form form, int support, VesselPlacement placement, ColumnTrays trays,
                      List<EquipmentShape.Connection> connections, Paint paint, ProcessUnit.Bank bank, boolean boot, int outlet,
                      @org.jetbrains.annotations.Nullable ColumnDesign column, List<ColumnTrays.Turn> turns, com.wormzjl.icheme.science.equipment.TowerBank towerBank, MachineDuty duty) {
            this(diameter, tangentLength, form, support, placement, trays, connections, paint, bank, boot, outlet, column, turns, towerBank, com.wormzjl.icheme.science.equipment.DeviceName.NONE, duty);
        }

        /** A design with no name whose machine takes the design point its sizes give ({@link UnitDesign#duty}). */
        public Design(double diameter, int tangentLength, VesselPlacement.Form form, int support, VesselPlacement placement, ColumnTrays trays,
                      List<EquipmentShape.Connection> connections, Paint paint, ProcessUnit.Bank bank, boolean boot, int outlet,
                      @org.jetbrains.annotations.Nullable ColumnDesign column, List<ColumnTrays.Turn> turns, com.wormzjl.icheme.science.equipment.TowerBank towerBank) {
            this(diameter, tangentLength, form, support, placement, trays, connections, paint, bank, boot, outlet, column, turns, towerBank, MachineDuty.NONE);
        }

        /** This design whose structure is built named {@code value} (cleaned; empty: unnamed). */
        public Design withName(String value) {
            return new Design(diameter,tangentLength,form,support,placement,trays,connections,paint,bank,boot,outlet,column,turns,towerBank,value,duty, exchangerType);
        }
        public Design withExchangerType(com.wormzjl.icheme.science.equipment.ExchangerType value) {
            return new Design(diameter,tangentLength,form,support,placement,trays,connections,paint,bank,boot,outlet,column,turns,towerBank,name,duty,value);
        }

        public Design withTowerBank(com.wormzjl.icheme.science.equipment.TowerBank value) {
            return new Design(diameter,tangentLength,form,support,placement,trays,connections,paint,bank,boot,outlet,column,turns,value,name,duty, exchangerType);
        }
        /** A new column's trays: twenty, fed in the middle. */
        public static final ColumnTrays TRAYS = ColumnTrays.simple(20, 10);
        /**
         * A 2 m drum, 4 m between tangent lines, on a 2 m skirt, right in front of its constructor, with a new drum's side
         * connection, its bottom outlet to the front, in the built-in paint. This baseline remains fixed; new constructors
         * use the interior sizes from {@link #fresh}.
         */
        public static final Design DEFAULT = new Design(2, 4, VesselPlacement.Form.VERTICAL, EquipmentShape.defaultSupport(2, Orientation.VERTICAL),
                VesselPlacement.DEFAULT, TRAYS, EquipmentShape.defaultConnections(2, 4, Orientation.VERTICAL, EquipmentShape.defaultSupport(2, Orientation.VERTICAL), false),
                Paint.DEFAULT, ProcessUnit.Bank.ONE, false, 0, null);

        /** A design without an imported column. */
        public Design(double diameter, int tangentLength, VesselPlacement.Form form, int support, VesselPlacement placement, ColumnTrays trays,
                      List<EquipmentShape.Connection> connections, Paint paint, ProcessUnit.Bank bank, boolean boot, int outlet) {
            this(diameter, tangentLength, form, support, placement, trays, connections, paint, bank, boot, outlet, null, List.of(), com.wormzjl.icheme.science.equipment.TowerBank.ONE);
        }

        /** A design whose column's nozzles all take their sides by themselves. */
        public Design(double diameter, int tangentLength, VesselPlacement.Form form, int support, VesselPlacement placement, ColumnTrays trays,
                      List<EquipmentShape.Connection> connections, Paint paint, ProcessUnit.Bank bank, boolean boot, int outlet,
                      @org.jetbrains.annotations.Nullable ColumnDesign column) {
            this(diameter, tangentLength, form, support, placement, trays, connections, paint, bank, boot, outlet, column, List.of(), com.wormzjl.icheme.science.equipment.TowerBank.ONE);
        }

        /** A new constructor starts inside the size ranges, in the config's primary and secondary colours. */
        public static Design fresh() {
            var d = DEFAULT;
            var sizes = com.wormzjl.icheme.science.equipment.ConstructorDefaults.sizes(d.form);
            int support = EquipmentShape.defaultSupport(sizes.diameter(), d.form.orientation());
            return new Design(sizes.diameter(), sizes.length(), d.form, support, d.placement, d.trays,
                    EquipmentShape.defaultConnections(sizes.diameter(), sizes.length(), d.form.orientation(), support, d.boot),
                    IChemE.defaultPaint(), d.bank, d.boot, d.outlet);
        }

        public Design {
            Objects.requireNonNull(exchangerType);
            Objects.requireNonNull(form, "form");
            Objects.requireNonNull(placement, "placement");
            Objects.requireNonNull(trays, "trays");
            Objects.requireNonNull(paint, "paint");
            Objects.requireNonNull(bank, "bank");Objects.requireNonNull(towerBank,"towerBank");
            name = com.wormzjl.icheme.science.equipment.DeviceName.clean(name);
            connections = List.copyOf(connections);
            turns = List.copyOf(turns);
            // A machine's design point (shape fields 8): the one given, else the one its sizes give; its sizes then
            // follow it. Another form has none.
            if (form.designed() && UnitDesign.of(form.kind()).hasDuty()) {
                var design = UnitDesign.of(form.kind());
                if (duty == null || duty.equals(MachineDuty.NONE)) duty = design.duty(diameter, tangentLength);
                diameter = design.size(duty);
                tangentLength = design.length(duty);
            } else duty = MachineDuty.NONE;
            // Validates the shape from the parameters (a compact constructor assigns the fields after its body); the
            // facing does not change what is valid.
            shape(diameter, tangentLength, form, support, trays, connections, bank, boot, outlet, column, turns, duty, EquipmentShape.Heading.NORTH);
        }

        public EquipmentShape shape(Direction facing) {
            return shape(diameter, tangentLength, form, support, trays, connections, bank, boot, outlet, column, turns, duty, form.front(facing.getStepX(), facing.getStepZ())).withTowerBank(form.kind()==EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER?towerBank:com.wormzjl.icheme.science.equipment.TowerBank.ONE).withExchangerType(form.kind()==EquipmentShape.Kind.EXCHANGER?exchangerType:com.wormzjl.icheme.science.equipment.ExchangerType.STANDARD);
        }

        /** This design with the column's side nozzles turned as {@code next}. */
        public Design withTurns(List<ColumnTrays.Turn> next) {
            return new Design(diameter, tangentLength, form, support, placement, trays, connections, paint, bank, boot, outlet, column, next, towerBank, name, duty, exchangerType);
        }

        /** Whether this design builds: anything but a column without an imported design. */
        public boolean buildable() {
            return !form.column() || column != null;
        }

        /**
         * This design with the column {@code imported}: the column form, its diameter, the skirt at least the column's
         * least, the bottoms to the front; the placement, the paint and the other forms' fields kept.
         */
        public Design imported(ColumnDesign imported) {
            double d = imported.diameter();
            return new Design(d, tangentLength, VesselPlacement.Form.COLUMN, Math.max(support, EquipmentShape.minimumSkirt(d)), placement, trays, connections, paint,
                    bank, boot, 0, imported, turns,towerBank, name, duty, exchangerType);
        }

        /**
         * This design with {@code sent} on it, a design Send to block stored (batch 2026-11-02-process-simulator, S5): a column imported as a
         * calculator's design is ({@link #imported}); anything else at its form, sizes and support as sized, an air cooler's bank, a drum's boot
         * with its standard connections, its bottom outlet. The placement, the paint, the imported column, the turns and the other forms'
         * fields are kept; a lying drum laid crosswise stays crosswise.
         */
        public Design landed(com.wormzjl.icheme.science.equipment.SentDesign sent) {
            if (sent.column() != null) return imported(sent.column());
            var shape = sent.shape();
            var kind = shape.kind();
            VesselPlacement.Form to = null;
            if (kind == EquipmentShape.Kind.DRUM)
                to = shape.orientation() == Orientation.VERTICAL ? VesselPlacement.Form.VERTICAL : form == VesselPlacement.Form.CROSSWISE ? form : VesselPlacement.Form.LENGTHWISE;
            else for (var f : VesselPlacement.Form.values()) if (f.kind() == kind) to = f;
            if (to == null) throw new IllegalArgumentException("No form builds a " + kind);
            int length = (int) Math.round(shape.tangentLength());
            boolean drum = kind == EquipmentShape.Kind.DRUM;
            var fitted = drum ? EquipmentShape.defaultConnections(shape.diameter(), length, shape.orientation(), shape.support(), shape.boot(), shape.outlet()) : connections;
            return new Design(shape.diameter(), length, to, shape.support(), placement, trays, fitted, paint, kind == EquipmentShape.Kind.AIR_COOLER ? shape.bank() : bank,
                    drum ? shape.boot() : boot, shape.outlet(), column, turns, kind == EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER ? shape.towerBank() : towerBank, name, shape.duty(), kind == EquipmentShape.Kind.EXCHANGER ? shape.exchangerType() : exchangerType);
        }

        /** {@code edited}, a design the player sent, with this design's imported column: only an import sets it. */
        public Design keepingColumn(Design edited) {
            return new Design(edited.diameter, edited.tangentLength, edited.form, edited.support, edited.placement, edited.trays, edited.connections, edited.paint,
                    edited.bank, edited.boot, edited.outlet, column, edited.turns,edited.towerBank, name, edited.duty, edited.exchangerType);
        }

        /** The shape; an outlet the form does not turn to is refused ({@link EquipmentShape#outlets}); a machine carries its design point. */
        private static EquipmentShape shape(double diameter, int tangentLength, VesselPlacement.Form form, int support, ColumnTrays trays,
                                            List<EquipmentShape.Connection> connections, ProcessUnit.Bank bank, boolean boot, int outlet,
                                            @org.jetbrains.annotations.Nullable ColumnDesign column, List<ColumnTrays.Turn> turns, MachineDuty duty, EquipmentShape.Heading front) {
            if (form.designed()) {
                var shape = EquipmentShape.designed(form.kind(), diameter, tangentLength, front, support, form.kind() == EquipmentShape.Kind.AIR_COOLER ? bank : ProcessUnit.Bank.ONE, outlet);
                return UnitDesign.of(form.kind()).hasDuty() ? shape.withDuty(duty) : shape;
            }
            if (form.column()) {
                if (outlet != 0) throw new IllegalArgumentException("A column's bottoms leave to the front");
                return column != null ? EquipmentShape.column(column.diameter(), column.trays().withTurns(turns), front, support)
                        : EquipmentShape.column(diameter, trays.withTurns(turns), front, support);
            }
            return EquipmentShape.drum(diameter, tangentLength, form.orientation(), front, support, connections, boot && form.orientation().horizontal(), outlet);
        }
    }

    /**
     * Constructors loaded on the client, for their ghosts (owner request 2026-10-16: shown without aiming at the
     * constructor). Render thread only; weak, so an unloaded level frees them even without {@link #setRemoved}.
     */
    private static final Set<EquipmentConstructorBlockEntity> CLIENT_LOADED = Collections.newSetFromMap(new WeakHashMap<>());

    private Design design = Design.fresh();

    public EquipmentConstructorBlockEntity(BlockPos position, BlockState state) {
        super(ModBlockEntities.EQUIPMENT_CONSTRUCTOR.get(), position, state);
    }

    /** Client: the constructors loaded in the client's level. */
    public static List<EquipmentConstructorBlockEntity> clientLoaded() {
        return List.copyOf(CLIENT_LOADED);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide) CLIENT_LOADED.add(this);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        CLIENT_LOADED.remove(this);
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        CLIENT_LOADED.remove(this);
    }

    public Design design() {
        return design;
    }

    public Direction facing() {
        return getBlockState().getValue(EquipmentConstructorBlock.FACING);
    }

    /** Server: store a design the player chose and show it to every client that sees the constructor. */
    public void apply(Design design) {
        if (design.equals(this.design)) return;
        this.design = design;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    /** The vessel this constructor would build for a design: its orientation and origin follow the constructor's facing. */
    public EquipmentStructure.Vessel vessel(Design design) {
        var facing = facing();
        var shape = design.shape(facing);
        int[] origin = design.placement().origin(worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(),
                facing.getStepX(), facing.getStepZ(), shape.sizeX(), shape.sizeZ());
        return new EquipmentStructure.Vessel(new BlockPos(origin[0], origin[1], origin[2]), shape);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        write(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        design = Design.fresh();
        if (!tag.contains("Diameter")) return;
        try {
            design = readDesign(tag, design, repair -> IChemE.LOGGER.warn("equipment_compat constructor at={} repair={}", worldPosition.toShortString(), repair));
        } catch (IllegalArgumentException invalid) {
            IChemE.LOGGER.error("Equipment constructor at {} has a design its repair cannot read and proposes the default: {}", worldPosition, invalid.getMessage());
        }
    }

    /**
     * A saved design read onto the current rules (batch 2026-10-26-backward-compat): its shape through {@link ShapeRepair},
     * its placement and paint kept where valid; a form this build no longer has proposes the default shape with the
     * saved placement and paint, as {@code fresh} (what a new constructor proposes) has them otherwise. The shape is read
     * from the fields its form builds with ({@link Design#shape}); the other forms' fields (a drum's side connections and
     * boot, the air cooler's bank, the trays) are kept as saved, as a form switch keeps them (finding F2, batch
     * 2026-10-26-multi-machine-framework), and so are the column's turned nozzles (shape fields 3). Each change goes to
     * {@code repairs}.
     */
    public static Design readDesign(CompoundTag tag, Design fresh, java.util.function.Consumer<String> repairs) {
        var design=readBaseDesign(tag,fresh,repairs).withName(savedName(tag,repairs));
        if(tag.contains("ExchangerType"))design=design.withExchangerType(com.wormzjl.icheme.science.equipment.ExchangerType.valueOf(tag.getString("ExchangerType")));
        if(!tag.contains("TowerBank"))return design;
        try{return design.withTowerBank(com.wormzjl.icheme.science.equipment.TowerBank.decode(tag.getIntArray("TowerBank")));}
        catch(IllegalArgumentException invalid){repairs.accept("tower bank -> one cell: "+invalid.getMessage());return design;}
    }
    /** The design's saved name (N1): absent reads unnamed, as a constructor saved before names; one that breaks the rule is cleaned, a repair. */
    private static String savedName(CompoundTag tag, java.util.function.Consumer<String> repairs) {
        if (!tag.contains(DeviceNames.KEY)) return com.wormzjl.icheme.science.equipment.DeviceName.NONE;
        String saved = tag.getString(DeviceNames.KEY), cleaned = com.wormzjl.icheme.science.equipment.DeviceName.clean(saved);
        if (!tag.contains(DeviceNames.KEY, net.minecraft.nbt.Tag.TAG_STRING) || !cleaned.equals(saved)) repairs.accept("name (" + tag.get(DeviceNames.KEY) + ") -> " + cleaned);
        return cleaned;
    }
    private static Design readBaseDesign(CompoundTag tag, Design fresh, java.util.function.Consumer<String> repairs) {
        var placement = fresh.placement();
        int[] offsets = tag.getIntArray("Offsets");
        try {
            if (offsets.length != 3) throw new IllegalArgumentException("Offsets must hold forward, right and up");
            placement = new VesselPlacement(offsets[0], offsets[1], offsets[2]);
        } catch (IllegalArgumentException invalid) {
            repairs.accept("placement (" + invalid.getMessage() + ") -> default");
        }
        var paint = fresh.paint();
        try {
            paint = Paint.decode(tag.getIntArray("Paint"));
        } catch (IllegalArgumentException invalid) {
            repairs.accept("paint (" + invalid.getMessage() + ") -> default");
        }
        var turns = ShapeRepair.turns(ColumnDesignTags.savedTurns(tag, "Turns"), repairs);
        var form = ShapeRepair.form(tag.getString("Form")).orElse(null);
        if (form == null) {
            repairs.accept("form " + tag.getString("Form") + " -> " + fresh.form().name().toLowerCase(Locale.ROOT) + " (this build no longer has it)");
            return new Design(fresh.diameter(), fresh.tangentLength(), fresh.form(), fresh.support(), placement, fresh.trays(), fresh.connections(), paint, fresh.bank(),
                    fresh.boot(), fresh.outlet(), null, turns, fresh.towerBank());
        }
        var trays = Design.TRAYS;
        if (tag.contains("Trays"))
            try {
                trays = ColumnTrays.decode(tag.getIntArray("Trays"));
            } catch (IllegalArgumentException invalid) {
                repairs.accept("trays (" + invalid.getMessage() + ") -> " + Design.TRAYS.trays() + " trays");
            }
        // The shape from what its form builds with, as Design.shape: only a lying drum shows the boot.
        boolean drum = form.kind() == EquipmentShape.Kind.DRUM, lying = drum && form.orientation().horizontal(), boot = tag.getBoolean("Boot");
        int[] savedConnections = tag.contains("Connections") ? tag.getIntArray("Connections") : null, savedBank = tag.contains("Bank") ? tag.getIntArray("Bank") : null;
        var read = ShapeRepair.read(new ShapeRepair.Saved(form.kind().name(), "north", form.orientation().name(), tag.getDouble("Diameter"), tag.getInt("TangentLength"),
                tag.getInt("Support"), trays.encode(), savedConnections, boot && lying, tag.getInt("Outlet"), savedBank, null, List.of(), null,
                EquipmentShellBlockEntity.savedDuty(tag)));
        read.repairs().forEach(repairs);
        var shape = read.shape();
        // A column's own length follows its trays; the design keeps the drum length it was saved with for another form.
        int length = form.column() ? (int) Math.max(1, Math.min(EquipmentShape.MAX_TANGENT_LENGTH, tag.getInt("TangentLength"))) : (int) shape.tangentLength();
        // What the other forms own is kept as saved: this form's shape does not carry it (finding F2).
        var connections = shape.connections();
        if (!drum && savedConnections != null)
            try {
                connections = EquipmentShape.Connection.decode(savedConnections);
            } catch (IllegalArgumentException invalid) {
                repairs.accept("connections (" + invalid.getMessage() + ") -> none");
            }
        var bank = shape.bank();
        if (form.kind() != EquipmentShape.Kind.AIR_COOLER && savedBank != null)
            try {
                bank = ProcessUnit.Bank.decode(savedBank);
            } catch (IllegalArgumentException invalid) {
                repairs.accept("bank (" + invalid.getMessage() + ") -> one bay");
            }
        var design = new Design(shape.diameter(), length, form, shape.support(), placement, shape.trays() != null ? shape.trays() : trays, connections, paint,
                bank, lying ? shape.boot() : boot, shape.outlet(), null, turns, com.wormzjl.icheme.science.equipment.TowerBank.ONE, shape.duty());
        if (!tag.contains("Column")) return design;
        // The imported column (shape fields 2): its own checks, then the skirt at least its least.
        try {
            if (!tag.contains("Column", net.minecraft.nbt.Tag.TAG_COMPOUND)) throw new IllegalArgumentException("not a compound");
            var column = ColumnDesignTags.read(tag.getCompound("Column"));
            int skirt = Math.max(design.support(), EquipmentShape.minimumSkirt(column.diameter()));
            if (form.column() && skirt != design.support()) repairs.accept("support " + design.support() + " -> " + skirt);
            return new Design(design.diameter(), design.tangentLength(), form, form.column() ? skirt : design.support(), placement, design.trays(), design.connections(),
                    paint, design.bank(), design.boot(), design.outlet(), column, turns, design.towerBank(), design.duty());
        } catch (IllegalArgumentException invalid) {
            repairs.accept("column design (" + invalid.getMessage() + ") -> none");
            return design;
        }
    }

    private void write(CompoundTag tag) {
        writeDesign(tag, design);
    }

    /**
     * A design's tags, which {@link #readDesign} reads: its fields by name; an imported column (shape fields 2) under
     * {@code Column}; the turned nozzles (shape fields 3) under {@code Turns}; a machine's design point (shape fields 8)
     * under {@code Duty}, as a shell saves it.
     */
    public static void writeDesign(CompoundTag tag, Design design) {
        tag.putString("ExchangerType",design.exchangerType().name());
        tag.putDouble("Diameter", design.diameter());
        tag.putInt("TangentLength", design.tangentLength());
        tag.putString("Form", design.form().name().toLowerCase(Locale.ROOT));
        tag.putInt("Support", design.support());
        var p = design.placement();
        tag.putIntArray("Offsets", new int[]{p.forward(), p.right(), p.up()});
        tag.putIntArray("Trays", design.trays().encode());
        tag.putIntArray("Connections", EquipmentShape.Connection.encode(design.connections()));
        tag.putIntArray("Paint", design.paint().encode());
        tag.putIntArray("Bank", design.bank().encode());
        tag.putIntArray("TowerBank",design.towerBank().encode());
        tag.putBoolean("Boot", design.boot());
        tag.putInt("Outlet", design.outlet());
        if (design.column() != null) tag.put("Column", ColumnDesignTags.write(design.column()));
        if (!design.turns().isEmpty()) tag.put("Turns", ColumnDesignTags.writeTurns(design.turns()));
        DeviceNames.write(tag, design.name());
        if (!design.duty().equals(MachineDuty.NONE)) tag.put("Duty", EquipmentShellBlockEntity.writeDuty(design.duty()));
    }

    /** Server: the process simulator nearest this constructor within {@code reach} blocks, whose designs its Import lists; empty when there is none. A read: nothing is solved. */
    public java.util.Optional<ProcessSimulatorBlockEntity> simulator(int reach) {
        if (level == null) return java.util.Optional.empty();
        ProcessSimulatorBlockEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (var at : BlockPos.betweenClosed(worldPosition.offset(-reach, -reach, -reach), worldPosition.offset(reach, reach, reach))) {
            if (!level.isLoaded(at) || !(level.getBlockEntity(at) instanceof ProcessSimulatorBlockEntity simulator)) continue;
            double distance = at.distSqr(worldPosition);
            if (distance < best) {
                best = distance;
                nearest = simulator;
            }
        }
        return java.util.Optional.ofNullable(nearest);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        var tag = new CompoundTag();
        write(tag);
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
