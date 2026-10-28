package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import java.util.*;

/** Pure adapter from registered block identities to scientific islands; never reads chunks or block entities. */
public final class PhysicalFluidTopology {
    private PhysicalFluidTopology() {}
    public enum Direction {
        DOWN(0,-1,0),UP(0,1,0),NORTH(0,0,-1),SOUTH(0,0,1),WEST(-1,0,0),EAST(1,0,0);
        final int x,y,z;
        Direction(int x,int y,int z){this.x=x;this.y=y;this.z=z;}
    }
    /**
     * A device's place: its block, and for a pipe in a rack its lane (batch 2026-10-26-pipe-laying-system, plan 5.1): 0
     * for the block itself, else one more than the lane's option in its cell, 1 to {@link #RACK_PLACES} (RACK_LAYERS_PLAN I1:
     * a tier lane's first position and side level, or a crossing level). Lanes in one rack cell take different options, so
     * each has its own place.
     */
    public record Position(String dimension,int x,int y,int z,int lane) {
        public Position(String dimension,int x,int y,int z){this(dimension,x,y,z,0);}
        public Position {Objects.requireNonNull(dimension);if(dimension.isBlank())throw new IllegalArgumentException("Missing dimension");if(lane<0||lane>PORT_LANE)throw new IllegalArgumentException("Invalid rack lane "+lane);}
        Position offset(Direction d){return new Position(dimension,Math.addExact(x,d.x),Math.addExact(y,d.y),Math.addExact(z,d.z),lane);}
        public Position withLane(int value){return value==lane?this:new Position(dimension,x,y,z,value);}
    }
    /** A rack lane's places (options) in its cell, and its widest width in positions (RACK_LAYERS_PLAN, RD10). */
    public static final int RACK_PLACES=14,RACK_WIDTH=4;
    /**
     * The place of a running column's port in its cell (batch 2026-10-28-column-operation, plan 4.2): one past the rack
     * lanes. A port stands in the cell inside a boundary valve, beside the suspended device there, and meets the valve
     * alone ({@link #across}).
     */
    public static final int PORT_LANE=RACK_PLACES+1;
    /** Whether a rack lane of number lane (option + 1) may be slots positions wide: a tier lane within its four positions, a crossing one wide. */
    public static boolean laneFits(int lane,int slots){return lane>=1&&lane<=RACK_PLACES&&slots>=1&&slots<=RACK_WIDTH&&(lane<=12?(lane-1)%4+slots<=4:slots==1);}
    /** A reservoir's phase outlet, a mover's explicitly directed process connection, or a heat side's inlet or outlet. Stable checkpoint codes. */
    public enum ConnectionRole {
        VESSEL(0),SUCTION(1),DISCHARGE(2),INLET(3),OUTLET(4);
        private final int code;
        ConnectionRole(int code){this.code=code;}
        public int code(){return code;}
        public static ConnectionRole fromCode(int code){return switch(code){case 0->VESSEL;case 1->SUCTION;case 2->DISCHARGE;case 3->INLET;case 4->OUTLET;default->throw new IllegalArgumentException("Unknown connection role "+code);};}
    }
    /** A multi-cell device's nozzle: the face of {@code cell} where a pipe meets it, its phase class and connection role. */
    public record Port(Position cell,Direction face,PassiveNetwork.PhasePort port,ConnectionRole role) {
        public Port(Position cell,Direction face,PassiveNetwork.PhasePort port){this(cell,face,port,ConnectionRole.VESSEL);}
        public Port{Objects.requireNonNull(cell);Objects.requireNonNull(face);Objects.requireNonNull(port);Objects.requireNonNull(role);}
    }
    /** Where a device meets the next block: one of its cells and the direction out of it. */
    public record Contact(Position cell,Direction direction) {}
    /** An installed pipe cutoff. Codes are part of the checkpoint, independent of enum ordering. */
    public enum Cutoff {
        NONE(0),OPEN(1),CLOSED(2),CHECK(3);
        private final int code;
        Cutoff(int code){this.code=code;}
        public int code(){return code;}
        public Cutoff toggled(){return switch(this){case OPEN->CLOSED;case CLOSED->OPEN;case CHECK->CHECK;case NONE->throw new IllegalStateException("No pipe cutoff installed");};}
        public static Cutoff fromCode(int code){return switch(code){case 0->NONE;case 1->OPEN;case 2->CLOSED;case 3->CHECK;default->throw new IllegalArgumentException("Unknown pipe cutoff "+code);};}
    }
    public static FlowControl.Mover defaultMover(TopologyCompiler.Kind kind){return switch(kind){case PUMP->new FlowControl.Pump(.01,500000);case COMPRESSOR->new FlowControl.Compressor(.05,3);default->throw new IllegalArgumentException("Not a mover");};}
    /** The most ports a vessel registers. */
    public static final int MAXIMUM_PORTS=64;
    /** Every face, in {@link Direction} order: the joints of a hand-placed pipe and of every device that is not a laid pipe. */
    public static final int ALL_FACES=63;
    /**
     * A registered device. One block occupies {@code position} and meets its neighbours through its faces; a vessel with
     * {@code ports} occupies its node cell {@code position} (where its level head is measured from, its lowest inside
     * cell, or a mover's suction cell) and its ports' cells, and meets pipes only at its ports.
     *
     * <p>{@code joints} (batch 2026-10-26-pipe-laying-system): the faces, a bit per {@link Direction} ordinal, a pipe may
     * connect through. A laid pipe has the faces its path uses, so a line laid beside another does not join it; every other
     * device has {@link #ALL_FACES}, the behaviour before the field existed.</p>
     *
     * <p>{@code slots}: a rack lane's width in positions; 0 for every other device. A lane is a plain laid pipe with at most
     * two joints: a rack carries no junction, fitting or gauge (plan 5.2). {@code points}: where a lane meets each of its
     * joint faces, ten bits per face as the transition pipes' offsets pack them (two signed five-bit steps of 1/32 m along
     * the face's in-plane axes); 0 for every other device. Two lanes in neighbouring cells meet where their points on the
     * shared face coincide (RACK_LAYERS_PLAN I2), whatever their racks' facings.</p>
     */
    public record Device(long id,Position position,TopologyCompiler.Kind kind,Direction facing,
                         PipeResistance.Geometry geometry,FlowControl control,List<Port> ports,boolean generatorEnabled,Cutoff cutoff,boolean inlineFilter,int joints,int slots,long points,HeatUnit heat,
                         long suspendedBy) {
        /** A device no running column holds ({@code suspendedBy} 0). */
        public Device(long id,Position position,TopologyCompiler.Kind kind,Direction facing,PipeResistance.Geometry geometry,FlowControl control,List<Port> ports,boolean generatorEnabled,Cutoff cutoff,boolean inlineFilter,int joints,int slots,long points,HeatUnit heat){this(id,position,kind,facing,geometry,control,ports,generatorEnabled,cutoff,inlineFilter,joints,slots,points,heat,0);}
        /** A device without a heat unit: every kind but {@link TopologyCompiler.Kind#HEAT_SIDE} and a kettle's shell. */
        public Device(long id,Position position,TopologyCompiler.Kind kind,Direction facing,PipeResistance.Geometry geometry,FlowControl control,List<Port> ports,boolean generatorEnabled,Cutoff cutoff,boolean inlineFilter,int joints,int slots,long points){this(id,position,kind,facing,geometry,control,ports,generatorEnabled,cutoff,inlineFilter,joints,slots,points,null);}
        public Device(long id,Position position,TopologyCompiler.Kind kind,Direction facing,PipeResistance.Geometry geometry,FlowControl control,List<Port> ports,boolean generatorEnabled,Cutoff cutoff,boolean inlineFilter,int joints){this(id,position,kind,facing,geometry,control,ports,generatorEnabled,cutoff,inlineFilter,joints,0,0);}
        public Device(long id,Position position,TopologyCompiler.Kind kind,Direction facing,PipeResistance.Geometry geometry,FlowControl control,List<Port> ports,boolean generatorEnabled,Cutoff cutoff,boolean inlineFilter){this(id,position,kind,facing,geometry,control,ports,generatorEnabled,cutoff,inlineFilter,ALL_FACES);}
        public Device(long id,Position position,TopologyCompiler.Kind kind,Direction facing,PipeResistance.Geometry geometry,FlowControl control,List<Port> ports,boolean generatorEnabled,Cutoff cutoff){this(id,position,kind,facing,geometry,control,ports,generatorEnabled,cutoff,false);}
        public Device(long id,Position position,TopologyCompiler.Kind kind,Direction facing,PipeResistance.Geometry geometry,FlowControl control,List<Port> ports,boolean generatorEnabled){this(id,position,kind,facing,geometry,control,ports,generatorEnabled,Cutoff.NONE);}
        /** Authoring convenience; world placement explicitly creates generators switched off. */
        public Device(long id,Position position,TopologyCompiler.Kind kind,Direction facing,PipeResistance.Geometry geometry,FlowControl control,List<Port> ports){this(id,position,kind,facing,geometry,control,ports,true);}
        public Device(long id,Position position,TopologyCompiler.Kind kind,Direction facing,PipeResistance.Geometry geometry,FlowControl control){this(id,position,kind,facing,geometry,control,List.of());}
        public Device {
            if(id<=0)throw new IllegalArgumentException("Physical identities must be positive");
            Objects.requireNonNull(position);Objects.requireNonNull(kind);Objects.requireNonNull(facing);Objects.requireNonNull(geometry);Objects.requireNonNull(control);
            Objects.requireNonNull(cutoff);
            if((joints&~ALL_FACES)!=0)throw new IllegalArgumentException("Invalid pipe joints "+joints);
            if(joints!=ALL_FACES&&(kind!=TopologyCompiler.Kind.PIPE||!ports.isEmpty()))throw new IllegalArgumentException("Only a laid pipe has explicit joints");
            boolean onPortLane=position.lane()==PORT_LANE;
            if(position.lane()==0||onPortLane?slots!=0||points!=0:!laneFits(position.lane(),slots)||(points>>>60)!=0)throw new IllegalArgumentException("Invalid rack lane "+position.lane()+" of "+slots+" positions");
            if(suspendedBy<0||onPortLane&&suspendedBy!=0)throw new IllegalArgumentException("Invalid column suspension");
            // A column's port (plan 4.2): a void or a generator meeting its valve through one face of its own cell.
            if(onPortLane!=(TopologyCompiler.fixed(kind)&&!ports.isEmpty())
                    ||onPortLane&&(ports.size()!=1||!ports.getFirst().cell.equals(position)||ports.getFirst().port!=PassiveNetwork.PhasePort.BULK||ports.getFirst().role!=ConnectionRole.VESSEL))
                throw new IllegalArgumentException("A column port is a void or a generator with one port at its own cell, and only a port has ports of that kind");
            if(slots>0&&(joints==ALL_FACES||Integer.bitCount(joints)>2||cutoff!=Cutoff.NONE||inlineFilter||!(control instanceof FlowControl.Passive)))
                throw new IllegalArgumentException("A rack lane is a plain laid pipe with at most two joints");
            if(inlineFilter&&(kind!=TopologyCompiler.Kind.PIPE||cutoff!=Cutoff.NONE||!(control instanceof FlowControl.Passive)))throw new IllegalArgumentException("Invalid pipe filter accessory");
            if(kind!=TopologyCompiler.Kind.PIPE&&cutoff!=Cutoff.NONE)throw new IllegalArgumentException("Only a pipe has a cutoff accessory");
            boolean valid=switch(kind) {case PUMP->control instanceof FlowControl.Pump;case COMPRESSOR->control instanceof FlowControl.Compressor;case VALVE->control instanceof FlowControl.Valve;case PIPE->control instanceof FlowControl.Passive||control instanceof FlowControl.Valve||control instanceof FlowControl.Mover;default->control instanceof FlowControl.Passive;};
            if(!valid)throw new IllegalArgumentException("Device/control mismatch");
            if(control instanceof FlowControl.Valve&&cutoff!=Cutoff.NONE)throw new IllegalArgumentException("A pressure valve cannot carry a cutoff accessory");
            if(kind==TopologyCompiler.Kind.PIPE&&control instanceof FlowControl.Mover&&(cutoff!=Cutoff.NONE||facing.y!=0))throw new IllegalArgumentException("An inline mover needs a horizontal pipe without a cutoff");
            if(kind!=TopologyCompiler.Kind.GENERATOR&&!generatorEnabled)throw new IllegalArgumentException("Only a generator has a source switch");
            ports=List.copyOf(ports);
            // The one-block pump and compressor are gone (batch 2026-10-28-old-mover-removal): a mover is either constructed,
            // with ports, or in-line, a pipe carrying its control.
            if(ports.isEmpty()&&(kind==TopologyCompiler.Kind.PUMP||kind==TopologyCompiler.Kind.COMPRESSOR))throw new IllegalArgumentException("A pump or compressor without ports is an in-line mover: a pipe carrying its control");
            if(ports.isEmpty()&&control instanceof FlowControl.Mover mover&&!mover.enabled())throw new IllegalArgumentException("Only constructed movers have a power switch");
            if(ports.size()>MAXIMUM_PORTS)throw new IllegalArgumentException("Too many device ports: "+ports.size());
            var seen=new HashSet<Contact>();
            int suction=0,discharge=0;
            // A kettle's shell is a reservoir whose heat unit names its tube side (batch 2026-10-27-heat-exchanger-design, W7).
            boolean pool=kind==TopologyCompiler.Kind.RESERVOIR&&heat!=null;
            if(pool&&(heat.unit()!=com.wormzjl.icheme.science.equipment.HeatSides.Unit.KETTLE||heat.side()!=com.wormzjl.icheme.science.equipment.HeatSides.Side.SHELL||heat.partner()==0||heat.partner()==id))
                throw new IllegalArgumentException("A reservoir's heat unit is a kettle's shell, partnered with its tubes");
            if(!pool&&(kind==TopologyCompiler.Kind.HEAT_SIDE)!=(heat!=null))throw new IllegalArgumentException("A heat side, and only a heat side or a kettle's shell, carries a heat unit");
            if(kind==TopologyCompiler.Kind.HEAT_SIDE&&heat.unit()==com.wormzjl.icheme.science.equipment.HeatSides.Unit.KETTLE&&heat.side()!=com.wormzjl.icheme.science.equipment.HeatSides.Side.TUBE)
                throw new IllegalArgumentException("A kettle's heat side is its tubes");
            if(kind==TopologyCompiler.Kind.HEAT_SIDE) {
                // A side's node is its first inlet's cell, as a mover's is its suction cell (batch 2026-10-27-heat-exchanger-design).
                int inlets=0,outlets=0;
                for(var port:ports) {
                    if(!port.cell.dimension().equals(position.dimension())||!seen.add(new Contact(port.cell,port.face))||port.port!=PassiveNetwork.PhasePort.BULK)throw new IllegalArgumentException("Invalid heat side port");
                    switch(port.role){case INLET->{if(inlets++==0&&!port.cell.equals(position))throw new IllegalArgumentException("A heat side's node must be its first inlet's cell");}case OUTLET->outlets++;default->throw new IllegalArgumentException("A heat side needs inlet and outlet ports");}
                }
                if(inlets==0||outlets==0)throw new IllegalArgumentException("A heat side needs an inlet and an outlet");
                if(heat.partner()==id)throw new IllegalArgumentException("A heat side cannot be its own partner");
            } else for(var port:ports) {
                if(!port.cell.dimension().equals(position.dimension())||!seen.add(new Contact(port.cell,port.face)))throw new IllegalArgumentException("Invalid device port");
                if(port.role==ConnectionRole.INLET||port.role==ConnectionRole.OUTLET)throw new IllegalArgumentException("Only a heat side has inlet and outlet ports");
                if(kind==TopologyCompiler.Kind.RESERVOIR) {
                    if(port.role!=ConnectionRole.VESSEL)throw new IllegalArgumentException("A reservoir needs vessel ports");
                } else if(kind==TopologyCompiler.Kind.PUMP||kind==TopologyCompiler.Kind.COMPRESSOR) {
                    if(port.port!=PassiveNetwork.PhasePort.BULK)throw new IllegalArgumentException("A mover needs bulk ports");
                    switch(port.role) {
                        case SUCTION->{suction++;if(!port.cell.equals(position))throw new IllegalArgumentException("A mover's node must be its suction cell");}
                        case DISCHARGE->discharge++;
                        case VESSEL->throw new IllegalArgumentException("A mover needs suction and discharge ports");
                    }
                } else if(!onPortLane)throw new IllegalArgumentException("Only reservoirs, pumps, compressors, heat sides and column ports have ports");
            }
            if(!ports.isEmpty()&&kind!=TopologyCompiler.Kind.RESERVOIR&&kind!=TopologyCompiler.Kind.HEAT_SIDE&&!onPortLane&&(ports.size()!=2||suction!=1||discharge!=1))
                throw new IllegalArgumentException("A mover needs exactly one suction and one discharge port");
        }
        boolean boundary(){return kind==TopologyCompiler.Kind.RESERVOIR||kind==TopologyCompiler.Kind.GENERATOR||kind==TopologyCompiler.Kind.VOID;}
        /** Includes an installed filter without changing the mechanical host identity. */
        public boolean filter(){return inlineFilter||kind==TopologyCompiler.Kind.FILTER;}
        /** Mechanical pipes compile through the existing valve or filter law when fitted. */
        public TopologyCompiler.Kind hydraulicKind(){
            if(inlineFilter)return TopologyCompiler.Kind.FILTER;
            if(kind==TopologyCompiler.Kind.PIPE)return switch(control){case FlowControl.Pump p->TopologyCompiler.Kind.PUMP;case FlowControl.Compressor c->TopologyCompiler.Kind.COMPRESSOR;case FlowControl.Valve v->TopologyCompiler.Kind.VALVE;default->kind;};
            return kind;
        }
        boolean actuator(){var hydraulic=hydraulicKind();return hydraulic==TopologyCompiler.Kind.PUMP||hydraulic==TopologyCompiler.Kind.COMPRESSOR||hydraulic==TopologyCompiler.Kind.VALVE;}
        /** Copy edits preserve accessories, ports and the independent generator switch. */
        public Device withGeometry(PipeResistance.Geometry value){return new Device(id,position,kind,facing,value,control,ports,generatorEnabled,cutoff,inlineFilter,joints,slots,points,heat,suspendedBy);}
        public Device withFacing(Direction value){return new Device(id,position,kind,value,geometry,control,ports,generatorEnabled,cutoff,inlineFilter,joints,slots,points,heat,suspendedBy);}
        public Device withControl(FlowControl value){return new Device(id,position,kind,facing,geometry,value,ports,generatorEnabled,cutoff,inlineFilter,joints,slots,points,heat,suspendedBy);}
        public Device withCutoff(Direction axis,Cutoff value){return new Device(id,position,kind,axis,geometry,control,ports,generatorEnabled,value,inlineFilter,joints,slots,points,heat,suspendedBy);}
        public Device withInlineFilter(Direction axis,boolean installed){return new Device(id,position,kind,axis,geometry,control,ports,generatorEnabled,cutoff,installed,joints,slots,points,heat,suspendedBy);}
        /** The same device standing at {@code value} with {@code nozzles}: a layout refresh rebuilding a vessel or a machine (decision D7). */
        public Device withPlacement(Position value,List<Port> nozzles){return new Device(id,value,kind,facing,geometry,control,nozzles,generatorEnabled,cutoff,inlineFilter,joints,slots,points,heat,suspendedBy);}
        /** The same pipe with another joint set: a line laid to one of its faces (a tee or an extension). */
        public Device withJoints(int value){return new Device(id,position,kind,facing,geometry,control,ports,generatorEnabled,cutoff,inlineFilter,value,slots,points,heat,suspendedBy);}
        public Device withGeneratorEnabled(boolean value){return new Device(id,position,kind,facing,geometry,control,ports,value,cutoff,inlineFilter,joints,slots,points,heat,suspendedBy);}
        /** The same heat side with {@code value}: a control or transit edit, or a partner found (batch 2026-10-27-heat-exchanger-design). */
        public Device withHeat(HeatUnit value){return new Device(id,position,kind,facing,geometry,control,ports,generatorEnabled,cutoff,inlineFilter,joints,slots,points,value,suspendedBy);}
        /** The same device held by the running column at {@code master} (its origin; 0: released). */
        public Device withSuspendedBy(long master){return new Device(id,position,kind,facing,geometry,control,ports,generatorEnabled,cutoff,inlineFilter,joints,slots,points,heat,master);}
        /**
         * Whether a running column holds this device (batch 2026-10-28-column-operation, plan 4.1): it stands where it was,
         * with what it holds, and joins nothing until the column stops; the column's ports take its place at the valves.
         */
        public boolean suspended(){return suspendedBy!=0;}
        /** Whether this is a running column's port (plan 4.2). */
        public boolean port(){return position.lane()==PORT_LANE;}
        /** Whether a pipe may connect through {@code direction} at all, whatever its fitting or switch. */
        public boolean jointAt(Direction direction){return (joints>>direction.ordinal()&1)!=0;}
        /** A lane's point on face direction (see points). */
        public int point(Direction direction){return (int)(points>>>10*direction.ordinal()&1023);}
        /** Every cell the registry finds this device at: its own, and its ports'. */
        public Set<Position> cells() {
            var cells=new LinkedHashSet<Position>();cells.add(position);for(var port:ports)cells.add(port.cell);return cells;
        }
        /** Where it meets the next block: each face it connects through, or a vessel's ports. */
        public List<Contact> contacts() {
            var contacts=new ArrayList<Contact>(ports.isEmpty()?6:ports.size());
            if(ports.isEmpty()){for(var direction:Direction.values())if(connects(direction))contacts.add(new Contact(position,direction));}
            else for(var port:ports)contacts.add(new Contact(port.cell,port.face));
            return contacts;
        }
        /** Whether it meets the next block out of {@code cell} towards {@code outward}. */
        boolean meets(Position cell,Direction outward) {
            if(ports.isEmpty())return cell.equals(position)&&connects(outward);
            for(var port:ports)if(port.face==outward&&port.cell.equals(cell))return true;
            return false;
        }
        /** Static fit, independent of power/cutoff state or whether the neighbouring chunk is loaded. */
        boolean mechanicallyMeets(Position cell,Direction outward) {
            if(ports.isEmpty())return cell.equals(position)&&mechanicallyConnects(outward);
            for(var port:ports)if(port.face==outward&&port.cell.equals(cell))return true;
            return false;
        }
        /**
         * Step 1 of the network reduction stage, connectivity (owner decision D20: only pipes connect): this device, out of
         * its cell {@code from} towards {@code direction}, and {@code other} in the next cell are joined when each meets
         * the other there and at least one of them is a pipe block. Two pieces of equipment side by side (a tank, generator,
         * void, pump, compressor, valve or filter against another) exchange nothing.
         */
        boolean joins(Device other,Position from,Direction direction){return joins(other,from,direction,false);}
        /** As {@link #joins}, as built: whatever its valves', cutoffs' or switches' state (a pipe line, {@link #line}). */
        boolean mechanicallyJoins(Device other,Position from,Direction direction){return joins(other,from,direction,true);}
        private boolean joins(Device other,Position from,Direction direction,boolean built) {
            // A device a running column holds joins nothing (plan 4.1): the column stands in its place. As built, it still does.
            // A port stands in for the pipe block inside its valve (plan 4.2), so it joins the valve as that pipe did.
            if(!built&&(suspended()||other.suspended()))return false;
            // Across a rack face (see across) the other device stands on another lane of the next cell: it meets there.
            var to=from.offset(direction);if(to.lane()!=other.position.lane())to=other.position;
            var back=opposite(direction);
            return (built?mechanicallyMeets(from,direction)&&other.mechanicallyMeets(to,back):meets(from,direction)&&other.meets(to,back))
                    &&(kind==TopologyCompiler.Kind.PIPE||other.kind==TopologyCompiler.Kind.PIPE||port()||other.port())&&lanesMeet(other,direction);
        }
        /**
         * Two lanes meet where their points on the shared face coincide. A lane meets a plain cell only through a transition pipe there (plan
         * 5.2 and 6): a laid plain pipe of at most two joints and no fitting, whose run can bend to the lane's off-centre
         * point.
         */
        private boolean lanesMeet(Device other,Direction direction) {
            if(slots==0&&other.slots==0)return true;
            if(slots>0&&other.slots>0)return point(direction)==other.point(opposite(direction));
            return (slots==0?this:other).transitionPipe();
        }
        /** A pipe that may meet a rack lane: laid, of at most two joints, plain and without fittings. */
        public boolean transitionPipe(){return kind==TopologyCompiler.Kind.PIPE&&ports.isEmpty()&&slots==0&&joints!=ALL_FACES&&Integer.bitCount(joints)<=2&&!actuator()&&!filter()&&cutoff==Cutoff.NONE;}
        /** A generator or a void (owner decision D17): islands meet at it without being joined by it. */
        boolean fixed(){return TopologyCompiler.fixed(kind);}
        /** Off closes a generator's hydraulic contacts; its registered identity and fixed source specification stay put. */
        boolean connects(Direction direction){return generatorEnabled&&cutoff!=Cutoff.CLOSED&&mechanicallyConnects(direction);}
        /** Fitting joints and bore inheritance remain axial even when hydraulic contacts are closed. */
        public boolean mechanicallyConnects(Direction direction){return jointAt(direction)&&(!actuator()&&!filter()&&cutoff==Cutoff.NONE||direction.x*facing.x+direction.y*facing.y+direction.z*facing.z!=0);}
        /** The driven side is the actual discharge nozzle, independent of the node's position and the model's heading. */
        boolean outletAt(Position cell,Direction face) {
            if(kind==TopologyCompiler.Kind.HEAT_SIDE){for(var port:ports)if(port.cell.equals(cell)&&port.face==face)return port.role==ConnectionRole.OUTLET;return false;}
            if(!actuator())return false;
            if(ports.isEmpty())return cell.equals(position)&&face==facing;
            for(var port:ports)if(port.cell.equals(cell)&&port.face==face)return port.role==ConnectionRole.DISCHARGE;
            return false;
        }
        /**
         * The port a pipe end met out of {@code cell} towards {@code face} compiles to (plan 4.1, default A1 of the
         * phase-ports batch): a vessel's port its declared class; a one-block tank's {@code UP} face the top outlet
         * (VAPOR), {@code DOWN} the bottom outlet (LIQUID), a horizontal face the side outlet (BULK); every other end
         * (generator, void, junction, actuator, filter) BULK.
         */
        PassiveNetwork.PhasePort portAt(Position cell,Direction face) {
            if(kind!=TopologyCompiler.Kind.RESERVOIR)return PassiveNetwork.PhasePort.BULK;
            for(var port:ports)if(port.face==face&&port.cell.equals(cell))return port.port;
            return face==Direction.UP?PassiveNetwork.PhasePort.VAPOR:face==Direction.DOWN?PassiveNetwork.PhasePort.LIQUID:PassiveNetwork.PhasePort.BULK;
        }
    }
    /** The local world direction of graph-forward transport; null for synthetic or non-straight mappings. */
    public record View(int islandIndex,long pipeId,boolean forward,double tap,Direction flowDirection) {
        public View(int islandIndex,long pipeId,boolean forward){this(islandIndex,pipeId,forward,.5,null);}
        public View(int islandIndex,long pipeId,boolean forward,double tap){this(islandIndex,pipeId,forward,tap,null);}
    }
    public record Island(Set<Long> physicalIds,PassiveNetwork graph,Optional<String> error) {
        public Island {physicalIds=Set.copyOf(physicalIds);Objects.requireNonNull(graph);Objects.requireNonNull(error);}
    }
    /**
     * {@code tankFaces}: for every tank (RESERVOIR device id), the face each of its compiled connections leaves it by
     * (compiled pipe id to the direction from the tank to its neighbour), the face its port was compiled from; the tank
     * page names its connections with it.
     */
    public record Compiled(List<Island> islands,Map<Long,List<View>> pipeViews,Map<Long,String> diagnostics,Map<Long,Map<Long,Direction>> tankFaces) {
        public Compiled {
            islands=List.copyOf(islands);var copied=new HashMap<Long,List<View>>();pipeViews.forEach((id,views)->copied.put(id,List.copyOf(views)));pipeViews=Map.copyOf(copied);diagnostics=Map.copyOf(diagnostics);
            var faces=new HashMap<Long,Map<Long,Direction>>();tankFaces.forEach((id,byPipe)->faces.put(id,Map.copyOf(byPipe)));tankFaces=Map.copyOf(faces);
        }
    }
    /**
     * What {@code from} meets out of {@code cell} towards {@code direction} (batch 2026-10-26-pipe-laying-system, plan 7):
     * for a lane, the plain device in the next cell or the lane there whose point on the shared face is its own; for any
     * other device, the device in the next cell; else, across a rack face, the lane of a rack cell a plain pipe meets, the
     * one jointed back through this face (at most one, plan 5.2). Null when none; {@link Device#joins} decides whether the
     * two join. Where no lane is near, it is the plain lookup of the next cell.
     */
    static <R> R across(java.util.function.Function<Position,R> at,java.util.function.Function<R,Device> device,Device from,Position cell,Direction direction) {
        var next=cell.offset(direction);var back=opposite(direction);
        if(next.lane()!=0) {
            // A lane: the plain device in the next cell, or the lane there whose point on the shared face is this lane's.
            var plain=at.apply(next.withLane(0));if(plain!=null)return plain;
            int point=from.point(direction);
            for(int lane=1;lane<=RACK_PLACES;lane++){var candidate=at.apply(next.withLane(lane));if(candidate!=null&&device.apply(candidate).jointAt(back)&&device.apply(candidate).point(back)==point)return candidate;}
            return null;
        }
        // A running column's port stands in the cell inside its valve, beside the suspended device there (plan 4.2): it is
        // what that cell meets. Joins decide the rest (a port meets through its one face; a suspended device joins nothing).
        var port=at.apply(next.withLane(PORT_LANE));
        if(port!=null)return port;
        var found=at.apply(next);
        if(found!=null)return found;
        if(from.kind!=TopologyCompiler.Kind.PIPE||!from.ports.isEmpty())return null;
        for(int lane=1;lane<=RACK_PLACES;lane++){var candidate=at.apply(next.withLane(lane));if(candidate!=null&&device.apply(candidate).jointAt(back))return candidate;}
        return null;
    }
    private record Point(double x,double y,double z) {}
    public static long filterIdentity(long physicalId){return Long.MIN_VALUE+physicalId;}
    /**
     * {@code node} as its device compiles it: a kettle's shell (W7) carries its pool, keyed as a side would be
     * ({@link #sideIdentity}), partnered with its tubes' connection, over the bundle's surface. A pool the stock already
     * carries with the same facts keeps its slice inputs; any other is compiled idle, at the contents' temperature, and its
     * first slice plans it. Every other node as it is, a pool it carries dropped.
     */
    private static PassiveNetwork.Reservoir pooled(Device device,PassiveNetwork.Reservoir node) {
        var held=node.inventory().pool();
        if(device==null||device.heat==null||device.kind!=TopologyCompiler.Kind.RESERVOIR)
            return held==null?node:new PassiveNetwork.Reservoir(node.id(),node.elevation(),node.state(),node.kind(),node.inventory().withPool(null));
        double shell=node.inventory().geometry().diameter(),bundle=com.wormzjl.icheme.science.equipment.HeatSides.kettleBundle(shell);
        var pool=com.wormzjl.icheme.science.fluid.network.Pool.idle(sideIdentity(device.id),sideIdentity(device.heat.partner()),device.heat.area(),(shell-bundle)/2,bundle,
                com.wormzjl.icheme.science.equipment.HeatSides.EXCHANGER_CELLS,node.state().temperature());
        if(held!=null&&held.shape().equals(pool.shape()))return node;
        return new PassiveNetwork.Reservoir(node.id(),node.elevation(),node.state(),node.kind(),node.inventory().withPool(pool));
    }
    /** The compiled pipe of heat side {@code physicalId}: its passage between its inlet node and its outlet junction. */
    public static long sideIdentity(long physicalId){return Long.MIN_VALUE/2+physicalId;}
    public static Compiled compile(Collection<Device> devices,Map<Long,PassiveNetwork.Reservoir> boundaryStates) {
        return compile(devices,boundaryStates,Map.of(),boundaryStates.values().stream().findFirst().map(PassiveNetwork.Reservoir::state).orElse(null));
    }
    public static Compiled compile(Collection<Device> devices,Map<Long,PassiveNetwork.Reservoir> boundaryStates,Map<Long,InlineFilter> filterStock,com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.State idleSeed) {return compile(devices,boundaryStates,filterStock,idleSeed,Set.of());}
    public static Compiled compile(Collection<Device> devices,Map<Long,PassiveNetwork.Reservoir> boundaryStates,Map<Long,InlineFilter> filterStock,com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.State idleSeed,Set<Long> flowmeters) {
        var physical=new TreeMap<Long,Device>();var positions=new HashMap<Position,Device>();var points=new HashMap<Long,Point>();
        var nodes=new ArrayList<TopologyCompiler.Node>();var links=new ArrayList<TopologyCompiler.Link>();var linkOwner=new HashMap<Long,Long>();
        for(var d:devices) {
            if(physical.putIfAbsent(d.id,d)!=null)throw new IllegalArgumentException("Duplicate physical identity or position");
            for(var cell:d.cells())if(positions.putIfAbsent(cell,d)!=null)throw new IllegalArgumentException("Duplicate physical identity or position");
            var p=d.position;nodes.add(new TopologyCompiler.Node(d.id,d.hydraulicKind(),p.y));points.put(d.id,new Point(p.x,p.y,p.z));
            if(d.boundary()) {
                var state=boundaryStates.get(d.id);
                var expected=switch(d.kind){case RESERVOIR->PassiveNetwork.NodeKind.RESERVOIR;case GENERATOR->PassiveNetwork.NodeKind.GENERATOR;case VOID->PassiveNetwork.NodeKind.VOID;default->throw new AssertionError();};
                if(state==null||state.id()!=d.id||state.kind()!=expected||state.elevation()!=p.y)throw new IllegalArgumentException("Missing/incompatible authoritative boundary state");
            }
        }
        long virtual=-1,linkId=1;
        // The face of every link with a tank (RESERVOIR) end, as the direction from the tank to its neighbour, and the port
        // a pipe end at that tank compiles to (Device.portAt). Generators and voids stay BULK.
        var tankFaces=new HashMap<Long,Direction>();var tankPorts=new HashMap<Long,PassiveNetwork.PhasePort>();var outletLinks=new HashSet<Long>();
        var linkDirections=new HashMap<Long,Direction>();
        for(var d:physical.values())for(var contact:d.contacts()) {
            var direction=contact.direction();var next=contact.cell().offset(direction);
            var other=across(positions::get,x->x,d,contact.cell(),direction);
            if(other==null||other.id<=d.id||!d.joins(other,contact.cell(),direction))continue;
            if(next.lane()!=other.position.lane())next=other.position;
            if(d.boundary()||other.boundary()) {
                var owner=d.boundary()?other:d;links.add(new TopologyCompiler.Link(linkId,d.id,other.id,scaled(owner.geometry,.5)));
                linkDirections.put(linkId,direction);
                if(d.kind==TopologyCompiler.Kind.RESERVOIR){tankFaces.put(linkId,direction);tankPorts.put(linkId,d.portAt(contact.cell(),direction));}
                else if(other.kind==TopologyCompiler.Kind.RESERVOIR){tankFaces.put(linkId,opposite(direction));tankPorts.put(linkId,other.portAt(next,opposite(direction)));}
                if(d.outletAt(contact.cell(),direction)||other.outletAt(next,opposite(direction)))outletLinks.add(linkId);
                linkOwner.put(linkId++,owner.id);
            } else {
                long middle=virtual--;var a=points.get(d.id);var b=points.get(other.id);var p=new Point((a.x+b.x)/2,(a.y+b.y)/2,(a.z+b.z)/2);
                points.put(middle,p);nodes.add(new TopologyCompiler.Node(middle,TopologyCompiler.Kind.PIPE,p.y));
                links.add(new TopologyCompiler.Link(linkId,d.id,middle,half(d,other)));linkDirections.put(linkId,direction);if(d.outletAt(contact.cell(),direction))outletLinks.add(linkId);linkOwner.put(linkId++,d.id);
                links.add(new TopologyCompiler.Link(linkId,middle,other.id,half(other,d)));linkDirections.put(linkId,direction);if(other.outletAt(next,opposite(direction)))outletLinks.add(linkId);linkOwner.put(linkId++,other.id);
            }
        }
        var checks=new HashSet<Long>();for(var d:physical.values())if(d.cutoff==Cutoff.CHECK)checks.add(d.id);
        var compiled=TopologyCompiler.compile(nodes,links,checks);var byLink=new HashMap<Long,TopologyCompiler.Link>();for(var link:links)byLink.put(link.id(),link);
        var diagnostics=new HashMap<Long,String>();
        for(long link:compiled.deadEndLinks())diagnostics.put(linkOwner.get(link),"NO FLOW: dead-end pipe");
        for(long link:compiled.unanchoredLinks())diagnostics.put(linkOwner.get(link),"NO FLOW: no reservoir or boundary");
        for(long pump:compiled.invalidPumpCycles())diagnostics.put(pump,"ERROR: pump needs two ports without a zero-storage bypass");
        var groups=new ArrayList<>(compiled.islands());var included=new HashSet<Long>();groups.forEach(included::addAll);
        for(var d:physical.values())if(d.boundary()&&!included.contains(d.id))groups.add(Set.of(d.id));
        var retained=new HashMap<Long,TopologyCompiler.Node>();for(var node:compiled.retainedNodes())retained.put(node.id(),node);
        var islands=new ArrayList<Island>();var views=new HashMap<Long,List<View>>();var pipeIdentities=new HashSet<Long>();var faces=new HashMap<Long,Map<Long,Direction>>();
        // Groups share only fixed boundaries (a generator or a void belongs to every group that reaches it, owner decision
        // D17), so each run belongs to the group of its first node that is not a fixed boundary: an end, or else the node
        // its first segment leads to (a run between two fixed boundaries passes through at least one pipe block, since two
        // boundaries side by side never connect). Bucketed once, in run order, so assembling the islands reads every run once.
        var groupOf=new HashMap<Long,Integer>();for(int g=0;g<groups.size();g++)for(long id:groups.get(g))if(!fixedBoundary(physical.get(id)))groupOf.put(id,g);
        var runsOf=new ArrayList<List<TopologyCompiler.Run>>(groups.size());for(int g=0;g<groups.size();g++)runsOf.add(new ArrayList<>());
        for(var run:compiled.runs()) {
            long owner=!fixedBoundary(physical.get(run.first()))?run.first():!fixedBoundary(physical.get(run.second()))?run.second():other(byLink.get(run.segments().getFirst().linkId()),run.first());
            Integer g=groupOf.get(owner);if(g!=null)runsOf.get(g).add(run);
        }
        for(int group=0;group<groups.size();group++) {
            var members=groups.get(group);
            var boundaries=members.stream().filter(boundaryStates::containsKey).sorted().toList();
            if(boundaries.isEmpty()){for(long id:members)if(physical.containsKey(id))diagnostics.put(id,"NO FLOW: no reservoir or boundary");continue;}
            // A junction is built with the island's first boundary state as a placeholder; its start
            // guess is decided where it is minted, the island's fixed point (PassiveNetwork.mintJunctions,
            // decision D54), and never with any boundary's stock.
            // Solids are stock: carrying them over mints the generator's whole charge of particles on
            // every junction the compiler creates, on every topology edit, including the junction on
            // the far side of a filter edge, where no connection can ever deliver them. See
            // documentation/FULL_TANK_SOLIDS_EVENT.md.
            var boundarySeed=boundaryStates.get(boundaries.getFirst()).state();
            var seed=boundarySeed.solids().empty()?boundarySeed:boundarySeed.withSolids(com.wormzjl.icheme.science.fluid.state.SolidInventory.EMPTY);
            var reservoirs=new ArrayList<PassiveNetwork.Reservoir>();var indices=new HashMap<Long,Integer>();
            for(long id:new TreeSet<>(members))if(retained.containsKey(id)) {
                indices.put(id,reservoirs.size());var node=retained.get(id);var boundary=boundaryStates.get(id);
                reservoirs.add(boundary!=null?pooled(physical.get(id),boundary):new PassiveNetwork.Reservoir(id,node.elevation(),seed,PassiveNetwork.NodeKind.JUNCTION));
            }
            var pipes=new ArrayList<PassiveNetwork.Pipe>();int islandIndex=islands.size();var positiveFilterEdges=new HashMap<Long,Set<Long>>();
            for(var run:runsOf.get(group)) {
                // A passive return to the same zero-holdup node has no driving pressure or owned stock.
                if(run.first()==run.second()){for(var segment:run.segments())diagnostics.put(linkOwner.get(segment.linkId()),"NO FLOW: passive return loop");continue;}
                var start=physical.get(run.first());var end=physical.get(run.second());
                var startLink=byLink.get(run.segments().getFirst().linkId());var endLink=byLink.get(run.segments().getLast().linkId());
                boolean fromStart=start!=null&&start.actuator()&&outletLinks.contains(startLink.id()),fromEnd=end!=null&&end.actuator()&&outletLinks.contains(endLink.id());
                var startPort=endPort(start,startLink,tankPorts);var endPort=endPort(end,endLink,tankPorts);
                var sections=run.segments().stream().map(TopologyCompiler.Segment::geometry).toList();
                int a=indices.get(run.first()),b=indices.get(run.second());
                long pipeId=runIdentity(run,linkOwner,0);if(!pipeIdentities.add(pipeId))throw new IllegalStateException("Compiled pipe identity collision");
                if(fromStart&&fromEnd) {
                    int middle=reservoirs.size();reservoirs.add(new PassiveNetwork.Reservoir(virtual--,.5*(reservoirs.get(a).elevation()+reservoirs.get(b).elevation()),seed,PassiveNetwork.NodeKind.JUNCTION));
                    var halves=sections.stream().map(g->scaled(g,.5)).toList();
                    pipes.add(new PassiveNetwork.Pipe(pipeId,a,middle,halves,control(start,compiled.invalidPumpCycles())).withPorts(startPort,PassiveNetwork.PhasePort.BULK));mapViews(views,run,linkOwner,linkDirections,physical,islandIndex,pipeId,true,true);
                    pipeId=runIdentity(run,linkOwner,1);if(!pipeIdentities.add(pipeId))throw new IllegalStateException("Compiled pipe identity collision");
                    pipes.add(new PassiveNetwork.Pipe(pipeId,b,middle,halves,control(end,compiled.invalidPumpCycles())).withPorts(endPort,PassiveNetwork.PhasePort.BULK));mapViews(views,run,linkOwner,linkDirections,physical,islandIndex,pipeId,false,true);
                } else {
                    // The ports follow the ends through the swap: a run driven from its end is stated from b to a.
                    pipes.add(new PassiveNetwork.Pipe(pipeId,fromEnd?b:a,fromEnd?a:b,sections,fromStart?control(start,compiled.invalidPumpCycles()):fromEnd?control(end,compiled.invalidPumpCycles()):new FlowControl.Passive())
                            .withPorts(fromEnd?endPort:startPort,fromEnd?startPort:endPort)
                            .withForbiddenDirections(checkRestrictions(start,end,startLink,endLink,points,fromEnd)));
                    mapViews(views,run,linkOwner,linkDirections,physical,islandIndex,pipeId,!fromEnd);
                    // A tank is never an actuator, so only a run with at most one driven end has a tank end.
                    if(start!=null&&start.kind==TopologyCompiler.Kind.RESERVOIR)faces.computeIfAbsent(start.id,k->new HashMap<>()).put(pipeId,tankFaces.get(startLink.id()));
                    if(end!=null&&end.kind==TopologyCompiler.Kind.RESERVOIR)faces.computeIfAbsent(end.id,k->new HashMap<>()).put(pipeId,tankFaces.get(endLink.id()));
                    if(start!=null&&start.filter()&&positiveSide(start,points.get(other(startLink,run.first()))))positiveFilterEdges.computeIfAbsent(start.id,k->new HashSet<>()).add(pipeId);
                    if(end!=null&&end.filter()&&positiveSide(end,points.get(other(endLink,run.second()))))positiveFilterEdges.computeIfAbsent(end.id,k->new HashSet<>()).add(pipeId);
                    // A heat side's outlet runs leave from its outlet junction, as a filter's positive side does.
                    if(start!=null&&start.kind==TopologyCompiler.Kind.HEAT_SIDE&&outletLinks.contains(startLink.id()))positiveFilterEdges.computeIfAbsent(start.id,k->new HashSet<>()).add(pipeId);
                    if(end!=null&&end.kind==TopologyCompiler.Kind.HEAT_SIDE&&outletLinks.contains(endLink.id()))positiveFilterEdges.computeIfAbsent(end.id,k->new HashSet<>()).add(pipeId);
                }
            }
            // A filter's cake and a heat side's passage each own a pipe between the device's node and a junction of their own
            // (batch 2026-10-27-heat-exchanger-design: the side's junction stands at its outlet).
            for(long id:new TreeSet<>(members))if(physical.containsKey(id)&&(physical.get(id).filter()||physical.get(id).kind==TopologyCompiler.Kind.HEAT_SIDE)){
                int negative=indices.get(id),positive=reservoirs.size();var device=physical.get(id);
                boolean side=device.kind==TopologyCompiler.Kind.HEAT_SIDE;
                double elevation=side?device.ports.stream().filter(p->p.role==ConnectionRole.OUTLET).findFirst().orElseThrow().cell.y:device.position.y;
                reservoirs.add(new PassiveNetwork.Reservoir(virtual--,elevation,seed,PassiveNetwork.NodeKind.JUNCTION));
                var positiveEdges=positiveFilterEdges.getOrDefault(id,Set.of());
                for(int i=0;i<pipes.size();i++){var p=pipes.get(i);if(positiveEdges.contains(p.id()))pipes.set(i,new PassiveNetwork.Pipe(p.id(),p.first()==negative?positive:p.first(),p.second()==negative?positive:p.second(),p.sections(),p.control(),p.blockedDirections(),p.filter(),p.firstPort(),p.secondPort(),p.forbiddenDirections()));}
                long identity=side?sideIdentity(id):filterIdentity(id);
                // The side's heat law (W5: an air cooler against its air) rides on its passage; a side waiting for its partner has none.
                pipes.add(side?new PassiveNetwork.Pipe(identity,negative,positive,device.geometry).withHeat(device.heat.heatSide()):new PassiveNetwork.Pipe(identity,negative,positive,device.geometry).withFilter(filterStock.getOrDefault(id,InlineFilter.empty())));
                views.put(id,List.of(new View(islandIndex,identity,true)));
            }
            var ids=new TreeSet<Long>();for(long id:members)if(physical.containsKey(id))ids.add(id);
            Optional<String> error=ids.stream().filter(compiled.invalidPumpCycles()::contains).findFirst().map(id->diagnostics.get(id));
            for(int e=0;e<pipes.size();e++){var pipe=pipes.get(e);if(pipe.control() instanceof FlowControl.Valve){var actuator=physical.get(reservoirs.get(pipe.first()).id());if(actuator!=null)pipes.set(e,pipe.withActuatorArea(actuator.geometry().area()));}}
            for(int p=0;p<pipes.size();p++){
                var pipe=pipes.get(p);var measurementPoints=new ArrayList<PassiveNetwork.Pipe.ObservationPoint>();
                for(long host:flowmeters){var device=physical.get(host);if(device==null)continue;
                    if(device.actuator()&&reservoirs.get(pipe.first()).id()==host&&pipe.control() instanceof FlowControl.Valve){
                        measurementPoints.add(new PassiveNetwork.Pipe.ObservationPoint(host,0,device.position().y(),false));continue;
                    }
                    if(device.actuator())continue;
                    for(var view:views.getOrDefault(host,List.of()))if(view.islandIndex()==islandIndex&&view.pipeId()==pipe.id()&&Double.isFinite(view.tap()))
                        measurementPoints.add(new PassiveNetwork.Pipe.ObservationPoint(host,view.tap(),device.position().y(),!view.forward()));
                }
                if(!measurementPoints.isEmpty())pipes.set(p,pipe.withObservationPoints(measurementPoints));
            }
            islands.add(new Island(ids,new PassiveNetwork(reservoirs,pipes),error));
        }
        for(var d:physical.values())if(d.kind==TopologyCompiler.Kind.PIPE&&!views.containsKey(d.id))diagnostics.putIfAbsent(d.id,"NO FLOW: unconnected pipe");
        // An unconnected filter still owns its cake. Equal fixed property ports have exactly zero flow
        // and no finite fluid ownership; they keep the filter's simulation clock available for later edits. A filter on a
        // dead-end branch (owner decision D18, NetworkReduction step 2) is one of them: its cake stays, frozen, until a
        // topology event gives its branch a destination.
        for(var device:physical.values())if(device.filter()&&!views.containsKey(device.id)){
            boolean deadEnd=diagnostics.containsKey(device.id);
            if(idleSeed==null)throw new IllegalArgumentException("Disconnected filter needs a zero-flow property seed");
            long identity=filterIdentity(device.id);int island=islands.size();double y=device.position.y;
            var dormant=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(virtual--,y,idleSeed,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(virtual--,y,idleSeed,PassiveNetwork.NodeKind.VOID)),
                    List.of(new PassiveNetwork.Pipe(identity,0,1,device.geometry).withFilter(filterStock.getOrDefault(device.id,InlineFilter.empty())).withBlockedDirections(3)));
            islands.add(new Island(Set.of(device.id),dormant,Optional.empty()));views.put(device.id,List.of(new View(island,identity,true)));diagnostics.put(device.id,deadEnd?"NO FLOW: dead-end filter":"NO FLOW: disconnected filter");
        }
        return new Compiled(islands,views,diagnostics,faces);
    }
    /** Restrictions are in the run's traversal order, then swapped with a discharge-driven end. */
    private static int checkRestrictions(Device start,Device end,TopologyCompiler.Link first,TopologyCompiler.Link last,Map<Long,Point> points,boolean reversed) {
        int mask=0;
        if(start!=null&&start.cutoff==Cutoff.CHECK)mask|=positiveSide(start,points.get(other(first,start.id)))?2:1;
        if(end!=null&&end.cutoff==Cutoff.CHECK)mask|=positiveSide(end,points.get(other(last,end.id)))?1:2;
        return reversed?((mask&1)<<1)|((mask&2)>>1):mask;
    }
    private static long other(TopologyCompiler.Link link,long id){return link.first()==id?link.second():link.first();}
    /**
     * The port a run end compiles to: at a tank, the port its link leaves the tank by ({@link Device#portAt}); every other
     * end BULK. A run of one link from a junction into a tank has that link at both ends; only the tank's end is a port.
     */
    private static PassiveNetwork.PhasePort endPort(Device end,TopologyCompiler.Link link,Map<Long,PassiveNetwork.PhasePort> tankPorts) {
        return end!=null&&end.kind==TopologyCompiler.Kind.RESERVOIR?tankPorts.getOrDefault(link.id(),PassiveNetwork.PhasePort.BULK):PassiveNetwork.PhasePort.BULK;
    }
    /** A generator or a void: a boundary whose state nothing in the network changes, shared by every island that reaches it. */
    static boolean fixedBoundary(Device device){return device!=null&&device.fixed();}
    static Direction opposite(Direction d){return switch(d){case DOWN->Direction.UP;case UP->Direction.DOWN;case NORTH->Direction.SOUTH;case SOUTH->Direction.NORTH;case WEST->Direction.EAST;case EAST->Direction.WEST;};}
    /**
     * The pipe line through pipe {@code start} (the pipeline tool's sneak-use), as a plant's line runs from one item of
     * equipment to the next: the pipes its runs reach through mechanical joints, its valves open or closed. A tee belongs
     * to the run it continues: the line goes straight through a junction (a pipe of three or more joints) and stops before
     * one it would enter from the side, so a branch is a line of its own. An inline pump or compressor is a machine between
     * two lines and belongs to neither. From a junction the line takes each straight run through it. A rack lane is a
     * pipe like any other: the line meets its neighbours as the network does ({@link #across}, {@link Device#joins}), so
     * it runs along its lane and through a transition pipe, and parallel lanes stay apart. Returns the pipes in walk
     * order, {@code start} first, or null when there are more than {@code limit}.
     */
    static List<Device> line(java.util.function.Function<Position,Device> at,Device start,int limit) {
        var line=new ArrayList<Device>(List.of(start));
        if(start.control() instanceof FlowControl.Mover)return line;
        var seen=new HashSet<>(Set.of(start.id()));
        // Each step: the next device of the line and the direction the line goes on in to reach it.
        var steps=new ArrayDeque<Map.Entry<Device,Direction>>();
        var first=joints(at,start);
        for(var e:first.entrySet())if(first.size()<3||first.containsKey(opposite(e.getKey())))steps.add(Map.entry(e.getValue(),e.getKey()));
        while(!steps.isEmpty()) {
            var step=steps.poll();var next=step.getKey();var d=step.getValue();
            if(next.kind!=TopologyCompiler.Kind.PIPE||next.control() instanceof FlowControl.Mover||seen.contains(next.id()))continue;
            var joints=joints(at,next);boolean junction=joints.size()>2;
            if(junction&&!joints.containsKey(d))continue;
            seen.add(next.id());line.add(next);if(line.size()>limit)return null;
            if(junction)steps.add(Map.entry(joints.get(d),d));
            else for(var e:joints.entrySet())if(e.getKey()!=opposite(d))steps.add(Map.entry(e.getValue(),e.getKey()));
        }
        return line;
    }
    /** The neighbours pipe {@code pipe} meets mechanically, by face, whatever its valves' or switches' state. */
    private static Map<Direction,Device> joints(java.util.function.Function<Position,Device> at,Device pipe) {
        var joints=new EnumMap<Direction,Device>(Direction.class);
        for(var d:Direction.values()) {
            if(!pipe.mechanicallyConnects(d))continue;
            var other=across(at,x->x,pipe,pipe.position(),d);
            if(other==null||!pipe.mechanicallyJoins(other,pipe.position(),d))continue;
            // Each must meet the other: a plain pipe meets one lane across a rack face (across), not every lane jointed to it.
            var back=across(at,x->x,other,other.ports().isEmpty()?other.position():pipe.position().offset(d),opposite(d));
            if(back!=null&&back.id()==pipe.id())joints.put(d,other);
        }
        return joints;
    }
    /** Independent edits must not renumber a different island's last-interval debug history. */
    private static long runIdentity(TopologyCompiler.Run run,Map<Long,Long> owners,int part) {
        long hash=0xcbf29ce484222325L;hash=(hash^run.first())*0x100000001b3L;hash=(hash^run.second())*0x100000001b3L;
        for(var segment:run.segments())hash=(hash^owners.get(segment.linkId()))*0x100000001b3L;
        hash=(hash^part)*0x100000001b3L;return (hash&Long.MAX_VALUE)==0?1:hash&Long.MAX_VALUE;
    }
    private static FlowControl control(Device device,Set<Long> invalidPumps) {
        if(device.control instanceof FlowControl.Pump pump&&invalidPumps.contains(device.id))return new FlowControl.Pump(0,pump.maximumAddedPressure());
        if(device.control instanceof FlowControl.Compressor compressor&&invalidPumps.contains(device.id))return new FlowControl.Compressor(0,compressor.maximumPressureRatio());
        return device.control;
    }
    private static boolean positiveSide(Device device,Point neighbor){var p=device.position;var d=device.facing;return (neighbor.x-p.x)*d.x+(neighbor.y-p.y)*d.y+(neighbor.z-p.z)*d.z>0;}
    /**
     * A device's half of its link to {@code line}: half its own geometry, except that a pump, compressor or valve takes the
     * bore and roughness of the pipe it is joined to (owner decision 2026-10-16, batch 2026-10-15-custom-equipment-rendering:
     * their nozzles match the line, as on a plant). It keeps its own length and minor loss. Equal bores give the old doubles.
     */
    private static PipeResistance.Geometry half(Device device,Device line) {
        var g=device.geometry;
        // A heat side's geometry is its passage, which its own pipe carries ({@link #sideIdentity}); its nozzles are 1 m stubs
        // at the line's bore, as an actuator's are.
        if(device.kind==TopologyCompiler.Kind.HEAT_SIDE)g=new PipeResistance.Geometry(1,line.geometry.diameter(),line.geometry.roughness(),0);
        if(device.kind!=TopologyCompiler.Kind.PIPE&&device.actuator()&&line.kind==TopologyCompiler.Kind.PIPE)g=new PipeResistance.Geometry(g.length(),line.geometry.diameter(),line.geometry.roughness(),g.minorLoss());
        return scaled(g,.5);
    }
    private static PipeResistance.Geometry scaled(PipeResistance.Geometry g,double fraction){return new PipeResistance.Geometry(g.length()*fraction,g.diameter(),g.roughness(),g.minorLoss()*fraction,g.channels());}
    private static void mapViews(Map<Long,List<View>> views,TopologyCompiler.Run run,Map<Long,Long> owners,Map<Long,Direction> linkDirections,Map<Long,Device> physical,int island,long pipe,boolean forward) {mapViews(views,run,owners,linkDirections,physical,island,pipe,forward,false);}
    private static void mapViews(Map<Long,List<View>> views,TopologyCompiler.Run run,Map<Long,Long> owners,Map<Long,Direction> linkDirections,Map<Long,Device> physical,int island,long pipe,boolean forward,boolean split) {
        double total=run.segments().stream().mapToDouble(s->s.geometry().length()).sum(),cursor=0;var spans=new LinkedHashMap<Long,double[]>();
        var directions=new HashMap<Long,Direction>();
        for(var segment:run.segments()){
            long id=owners.get(segment.linkId());double start=cursor;cursor+=segment.geometry().length();var span=spans.get(id);if(span==null)spans.put(id,new double[]{start,cursor});else span[1]=cursor;
            var direction=linkDirections.get(segment.linkId());if(segment.forward()!=forward)direction=opposite(direction);
            // A corner has two local tangents and cannot carry a sight glass. Keep one mapping without inventing a direction.
            if(!directions.containsKey(id))directions.put(id,direction);else if(directions.get(id)!=direction)directions.put(id,null);
        }
        for(var segment:run.segments()) {
            long id=owners.get(segment.linkId());if(physical.get(id).kind!=TopologyCompiler.Kind.PIPE)continue;
            var span=spans.get(id);double tap=(span[0]+span[1])/(2*total);if(!forward)tap=1-tap;if(split)tap=tap<=.5?tap*2:Double.NaN;var entry=new View(island,pipe,forward,tap,directions.get(id));var list=views.computeIfAbsent(id,ignored->new ArrayList<>());if(!list.contains(entry))list.add(entry);
        }
    }
}
