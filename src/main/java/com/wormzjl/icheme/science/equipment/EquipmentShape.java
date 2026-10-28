package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.math.DeterministicMath;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A process vessel on the block grid (1 block = 1 m): a cylindrical shell of the given diameter and tangent-to-tangent
 * length between two 2:1 semi-ellipsoidal heads (depth D/4 each), standing or lying on its supports, with its nozzles.
 * The shell is drawn as a regular N-gon prism whose facets are at most about 1/3 m wide (a column's at most 1/4 m), and
 * each head as N-gon bands at the same angular step (owner decisions D3 and D5 of batch
 * 2026-10-15-custom-equipment-rendering: diameters in 0.5 m steps, lengths in whole metres; refined 2026-10-16).
 *
 * <p>Two kinds (owner request 2026-10-16). A <b>drum</b> stands on a skirt or lies on two saddles (owner decisions
 * 2026-10-15) and is a fluid reservoir whose nozzles are its ports. Two are fixed: the vapour outlet (a standing drum's at
 * the top head's centre, a lying drum's on top near its front end) and the liquid outlet (a skirted drum's bottom outlet,
 * whose line drops from the bottom head inside the skirt and leaves through its skirt port towards the front, owner
 * report 2026-10-16, or the side the player turns it to, {@link #outlet}, owner request 2026-10-25; without room under
 * the head a nozzle just above the bottom tangent line facing the front; a lying drum's low on its front head, or with a
 * boot underneath between the boot and the front head, {@link #bootFor}, owner request 2026-10-25). A lying drum's
 * outlets all leave at its front end, its feed enters at the far end (owner decision 2026-10-25). The rest are the
 * player's side {@link Connection}s, up to {@link #MAX_CONNECTIONS}
 * (owner request 2026-10-16), a new drum's being its former standard inlet. A <b>column</b> is a tall vertical vessel
 * laid out by its trays ({@link ColumnTrays}), insulated and clad, with its platforms, ladders, manways and process
 * nozzles at fixed sides ({@link ColumnStructure}); the player places its condenser, drum, reboiler and pumps and runs
 * the pipes (owner decisions 2026-10-16). A <b>unit</b> (heat exchanger, kettle reboiler, air cooler, steam ejector;
 * owner request 2026-10-16; centrifugal pump, reciprocating compressor, vertical and cabin heaters, owner request
 * 2026-10-17) is built from its parts, not as a vessel of revolution ({@link ProcessUnit}): {@code diameter},
 * {@code tangentLength} and {@code support} are its own sizes there. Pumps and both compressor designs are fluid movers;
 * other columns and units are drawn only. Every nozzle takes the connected pipe's size.
 *
 * <p>Supports. A vertical vessel stands on a skirt: a cylinder of the shell diameter from the ground to the bottom tangent
 * line, {@code support} metres high (0 = none: the vessel rests on its lower head, the debug command's bare form). The
 * skirt is a {@link #SKIRT_WALL} plate showing its inside (the bottom head, a column's bare under its cladding, the outlet
 * line, a floor plate) through its outlet's port and three slots. The skirt stands on a base ring projecting
 * {@link #BASE_RING} beyond it, with {@link #anchorChairs()} anchor-bolt chairs. A horizontal vessel lies on two saddles centred {@link #SADDLE_POSITION} ×
 * T/T from each tangent line, cradling the bottom {@link #SADDLE_CONTACT_DEGREES}° of the shell; {@code support}
 * is the height of the shell's box above the ground.
 *
 * <p>Frames. The vessel is built in its own frame, axis along +y, in cells of a box whose width is odd so the axis runs
 * through cell centres: then every nozzle's centre line does too and its flange face lies on a block face, where a pipe
 * meets it. Vertical: frame x and z are box-aligned before the turn, and the front (where the liquid outlet and its skirt
 * port face unless turned) is frame -z. Horizontal: frame x is up, frame y the axis, frame z across; the front head is the
 * frame's y = 0 end. {@link #transform()} turns the frame by quarter turns so the front faces {@link #front()} and shifts
 * the box to start at the origin.
 *
 * <p>{@code outlet}: the quarter turns from the front a bottom outlet leaving through a skirt takes, an index into
 * {@link #SIDES} (owner request 2026-10-25: "make drum/reactor bottom outlet rotatable"): a skirted drum's on any side, a
 * trickle-bed reactor's effluent on the sides its design leaves free ({@link #outlets}); 0 for every other shape.
 */
public record EquipmentShape(Kind kind, double diameter, double tangentLength, Orientation orientation, Heading front, int support,
                             @Nullable ColumnTrays trays, List<Connection> connections, ProcessUnit.Bank bank, boolean boot, int outlet) {
    public static final double MIN_DIAMETER = .5, MAX_DIAMETER = 10, MAX_COLUMN_DIAMETER = 15, MAX_TANGENT_LENGTH = 64;
    public static final int MAX_SUPPORT = 16;
    /**
     * Saddles: centred this fraction of T/T from each tangent line (owner decision 2026-10-25 from a reflux drum's drawing,
     * Ø900 x 4000 mm on saddles 500 mm inside its tangent lines), this wide along the axis (m), over this arc of the shell.
     */
    public static final double SADDLE_POSITION = .125, SADDLE_WIDTH = .375, SADDLE_CONTACT_DEGREES = 120;
    /**
     * Support parts, m, at one to two texels (1/16 m) so they read at block scale: thin plates (saddle top and base plates,
     * skirt base ring), the saddle's web and rib thickness, the base ring's projection beyond the skirt, and the width and
     * height of an anchor chair.
     */
    public static final double PLATE = 1 / 16.0, SADDLE_WEB = .125, SADDLE_RIB = .125, BASE_RING = .125, CHAIR = .25;
    /** A nozzle's flange face stands at least this far beyond the vessel surface (photo survey: 150-300 mm), on the next block face. */
    public static final double STAND_OUT = .15;
    /**
     * The row a skirted vessel's bottom outlet line leaves the skirt on, through the centre of its skirt port: the lowest
     * cell centre, where a pipe's largest line clears the base plate and its flange touches grade (owner decisions
     * 2026-10-21: the hole takes the pipe's size; 2026-10-25: pipes at most 0.8 m, a lower skirt). The hole's clearance
     * narrows over the base plate ({@link #portRadius}).
     */
    public static final double SKIRT_OUTLET = .5;
    /**
     * A skirt port, m: the hole in the skirt plate is the line's outside this much round it, at every pipe size, and a
     * collar this wide rings it (owner requests 2026-10-16: the port scales with the line; 2026-10-21: "Skirt hole needs
     * to adapt to the size of the pipe to max 1m").
     */
    public static final double PORT_CLEARANCE = .05, PORT_COLLAR = 1 / 32.0;
    /** How far under the bottom head's pole a skirt's outlet line's elbow (bend radius 1 D) tops out, m. */
    public static final double ELBOW_CLEARANCE = .05;

    /**
     * A skirt port's hole round a line of outside {@code line}, radius: the line's half and {@link #PORT_CLEARANCE}, the
     * clearance narrowing so the hole stays 5 mm over the base plate (a line near a pipe's largest on
     * {@link #SKIRT_OUTLET}); its collar may reach over the base ring there.
     */
    public static double portRadius(double line) {
        return Math.min(line / 2 + PORT_CLEARANCE, SKIRT_OUTLET - PLATE - .005);
    }
    /** A drum's skirt plate, m (a texel), drawn on both faces: its inside shows through the opening and the slots. */
    public static final double SKIRT_WALL = 1 / 16.0;
    /** The most side connections a drum takes: with its two fixed outlets, every nozzle's size fits in a cell key. */
    public static final int MAX_CONNECTIONS = 6;
    /**
     * The frame directions a side connection faces, as {axis, sign}: -z, +x, +z, -x. A standing drum's front (towards
     * the constructor), left, back and right; a lying drum's sides and its top (+x) and bottom (-x). A bottom outlet's
     * {@link #outlet} turns are quarter turns through them from the front.
     */
    static final int[][] SIDES = {{2, -1}, {0, 1}, {2, 1}, {0, -1}};
    /** The number of sides a connection may face. */
    public static final int CONNECTION_SIDES = SIDES.length;

    /** The frame direction of {@link #SIDES}[{@code side}], {axis, sign}: a connection's, or a bottom outlet turned that many quarter turns. */
    public static int[] side(int side) {
        return SIDES[side].clone();
    }
    /** A column's insulation under its aluminium cladding, m (photo survey: 50-150 mm); a drum is bare. */
    public static final double INSULATION = .1;
    /**
     * Outside area of one 2:1 head over D²: half an oblate spheroid of semi-axes D/2 and D/4 (eccentricity √3/2),
     * π (1/4 + ln(7 + 4√3) / (16√3)) = 1.0840, the datasheet value.
     */
    public static final double HEAD_AREA_FACTOR = Math.PI * (.25 + DeterministicMath.log(7 + 4 * Math.sqrt(3)) / (16 * Math.sqrt(3)));

    public enum Kind {
        DRUM, COLUMN,
        // Storage vessels (owner request 2026-10-21, batch 2026-10-21-floating-roof-tanks): reservoirs built from their parts.
        TANK, SPHERE,
        EXCHANGER, KETTLE, AIR_COOLER, EJECTOR, PUMP, COMPRESSOR, VERTICAL_HEATER, CABIN_HEATER,
        // New units (owner request 2026-10-18, NEW_UNITS_PLAN.md).
        STIRRED_REACTOR, ADSORBER, TRICKLE_BED_REACTOR, EBULLATED_BED_REACTOR, CENTRIFUGAL_COMPRESSOR, STEAM_TURBINE, CONDENSING_TURBINE, CYCLONE,
        FCC_CONVERTER, DELAYED_COKER, FLUIDIZED_BED_BOILER, FLUIDIZED_BED_GASIFIER, GRATE_BOILER, FIXED_BED_GASIFIER;

        /** A standalone unit, built from its parts ({@link ProcessUnit}); a drum and a column are vessels of revolution. */
        public boolean unit() {
            return ordinal() >= EXCHANGER.ordinal();
        }

        /**
         * A floating-roof tank or a storage sphere: a fluid reservoir as a drum is, but drawn from its parts by its design
         * ({@link UnitDesign}), with fixed ports and no side connections.
         */
        public boolean storage() {
            return this == TANK || this == SPHERE;
        }

        /** Drawn from its parts by a {@link UnitDesign}: a storage vessel or a unit. */
        public boolean designed() {
            return ordinal() >= TANK.ordinal();
        }

        /** A fluid reservoir: one node, a port per nozzle, its design volume and level height. */
        public boolean reservoir() {
            return this == DRUM || storage();
        }

        /** A self-powered fluid mover, with one suction and one discharge nozzle. */
        public boolean mover() {
            return this == PUMP || this == COMPRESSOR || this == CENTRIFUGAL_COMPRESSOR;
        }
    }

    public enum Orientation {
        VERTICAL, HORIZONTAL;

        public boolean horizontal() {
            return this == HORIZONTAL;
        }
    }

    /** A horizontal world direction: north is -z, east +x. */
    public enum Heading {
        NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);

        public final int dx, dz;

        Heading(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        public static Heading of(int dx, int dz) {
            for (var h : values()) if (h.dx == dx && h.dz == dz) return h;
            throw new IllegalArgumentException("Not a horizontal direction: " + dx + ", " + dz);
        }

        /** Quarter turns counter-clockwise seen from above from {@code base} to this heading. */
        int turnsFrom(Heading base) {
            // Counter-clockwise from above: north, west, south, east.
            int[] order = {0, 3, 2, 1};
            return Math.floorMod(order[ordinal()] - order[base.ordinal()], 4);
        }

        public Heading counterClockwise() {
            return switch (this) {
                case NORTH -> WEST;
                case WEST -> SOUTH;
                case SOUTH -> EAST;
                case EAST -> NORTH;
            };
        }
    }

    /**
     * What a nozzle is for, and the fluid port its position makes it (owner decision 2026-10-16: fixed class by position):
     * a drum's, a column's, then a unit's (tube side and shell side of an exchanger or a kettle's channel, an air cooler's
     * process line, an ejector's suction, motive steam and discharge, a pump's or a compressor's suction and discharge, a
     * heater's convection and radiant coils' inlets and outlets and its fuel gas).
     */
    public enum Role {
        VAPOUR_OUTLET(PortClass.VAPOUR), SIDE(PortClass.BULK), LIQUID_OUTLET(PortClass.LIQUID), BOOT_DRAIN(PortClass.LIQUID), FEED(PortClass.BULK), REFLUX(PortClass.BULK),
        DRAW(PortClass.BULK), PUMPAROUND_DRAW(PortClass.BULK), RETURN(PortClass.BULK), STEAM(PortClass.BULK), BOTTOMS(PortClass.LIQUID),
        VAPOUR_RETURN(PortClass.BULK), TUBE_INLET(PortClass.BULK), TUBE_OUTLET(PortClass.BULK), SHELL_INLET(PortClass.BULK), SHELL_OUTLET(PortClass.BULK),
        PROCESS_INLET(PortClass.BULK), PROCESS_OUTLET(PortClass.BULK), SUCTION(PortClass.BULK), MOTIVE(PortClass.BULK), DISCHARGE(PortClass.BULK), FUEL(PortClass.BULK),
        CONVECTION_INLET(PortClass.BULK), CONVECTION_OUTLET(PortClass.BULK), RADIANT_INLET(PortClass.BULK), RADIANT_OUTLET(PortClass.BULK),
        // The new units' roles (2026-10-18, NEW_UNITS_PLAN.md): bottom outlets draw the dense phase (liquid, solids) first, top
        // outlets and vents the vapour.
        PRODUCT(PortClass.BULK), NITROGEN(PortClass.BULK), COOLANT_INLET(PortClass.BULK), COOLANT_OUTLET(PortClass.BULK), REGENERATION_INLET(PortClass.BULK),
        REGENERATION_OUTLET(PortClass.BULK), HYDROGEN(PortClass.BULK), EFFLUENT(PortClass.BULK), QUENCH(PortClass.BULK), CATALYST_INLET(PortClass.BULK), AIR(PortClass.BULK),
        OXIDANT(PortClass.BULK), WATER(PortClass.BULK), CUTTING_WATER(PortClass.BULK), BLOWDOWN(PortClass.BULK), SOLIDS_INLET(PortClass.BULK), FEEDWATER(PortClass.BULK),
        VENT(PortClass.VAPOUR), EXHAUST(PortClass.VAPOUR), FLUE_GAS(PortClass.VAPOUR), RELIEF(PortClass.VAPOUR), SYNGAS(PortClass.VAPOUR),
        DRAIN(PortClass.LIQUID), CONDENSATE(PortClass.LIQUID), CATALYST_OUTLET(PortClass.LIQUID), SOLIDS_OUTLET(PortClass.LIQUID),
        // A floating-roof tank: centered front/back bottom nozzles, and the front overflow at the fill height.
        BOTTOM(PortClass.LIQUID), OVERFLOW(PortClass.OVERFLOW);

        public final PortClass port;

        Role(PortClass port) {
            this.port = port;
        }
    }

    /**
     * The phase a nozzle draws first: the top outlet's vapour, a bottom outlet's liquid, or the bulk; a floating-roof
     * tank's overflow draws only the liquid held above its fill height (plan 2026-10-21-floating-roof-tanks, section 3.1).
     */
    public enum PortClass {
        VAPOUR, BULK, LIQUID, OVERFLOW
    }

    /**
     * A nozzle in the vessel frame: its neck runs from {@code base} (inside the vessel, under its surface) along
     * {@code direction} (a frame axis) for {@code length} metres to its flange face, which lies on a block face. Its
     * flange and outer end take the connected pipe's size, whatever it is, so a pipe never steps at the flange (owner
     * decision 2026-10-16: resizing starts at the port-to-vessel connection); unconnected it shows {@code defaultOd} under
     * a blind flange. The neck takes the connected pipe's size too, whatever it is, from {@code base} to the flange face
     * (owner decision 2026-10-21: a 1 m pipe has a 1 m root): {@code base} lies under the vessel's surface deep enough for
     * a neck of a pipe's largest ({@link PipeSizes#MAX_OUTSIDE}). A line coming down from the vessel ({@code riser} > 0, a
     * skirted drum's bottom outlet) rises to frame height {@code riser} a pipe's largest behind {@code base}, turning into
     * the neck through an elbow of bend radius 1 D of the line drawn and a straight run.
     */
    public record Nozzle(Role role, double[] base, int axis, int sign, double length, double defaultOd, double riser) {
        public double[] direction() {
            var d = new double[3];
            d[axis] = sign;
            return d;
        }

        /** The centre of the flange face, in the vessel frame. */
        public double[] face() {
            var f = base.clone();
            f[axis] += sign * length;
            return f;
        }

        /** The block beyond the flange face, where the connected pipe stands, in frame cells. */
        public int[] pipeBlock() {
            var f = face();
            var block = new int[3];
            for (int i = 0; i < 3; i++) block[i] = (int) Math.floor(f[i] + (i == axis ? .5 * sign : 0));
            return block;
        }

        /**
         * Whether this nozzle's pipe block and {@code other}'s touch, by a face, an edge or a corner. Pipes in blocks that
         * share a face join, so every pipe keeps one free block between it and the next (owner rule 2026-10-17).
         */
        public boolean crowds(Nozzle other) {
            int[] p = pipeBlock(), q = other.pipeBlock();
            return Math.abs(p[0] - q[0]) < 2 && Math.abs(p[1] - q[1]) < 2 && Math.abs(p[2] - q[2]) < 2;
        }
    }

    /**
     * A side connection the player places on a drum's shell (owner request 2026-10-16): a radial nozzle facing frame
     * direction {@link #SIDES}[{@code side}], its centre line at frame height {@code station} + 0.5 along the vessel's
     * axis and {@code offset} whole cells across it, along the frame axis that is neither the vessel's nor its own (a
     * hillside nozzle when not 0). Its ranges follow the drum ({@link #stations}, {@link #maximumOffset}).
     */
    public record Connection(int side, int station, int offset) {
        public Connection {
            if (side < 0 || side >= SIDES.length) throw new IllegalArgumentException("A connection faces side 0 to 3, not " + side);
        }

        public int axis() {
            return SIDES[side][0];
        }

        public int sign() {
            return SIDES[side][1];
        }

        /** The frame axis the offset runs along. */
        public int across() {
            return 2 - axis();
        }

        /** Encoded for NBT and the network: side, station and offset per connection. */
        public static int[] encode(List<Connection> connections) {
            var out = new int[3 * connections.size()];
            for (int i = 0; i < connections.size(); i++) {
                var c = connections.get(i);
                out[3 * i] = c.side;
                out[3 * i + 1] = c.station;
                out[3 * i + 2] = c.offset;
            }
            return out;
        }

        /** @throws IllegalArgumentException for a malformed array or side; the ranges are checked with the shape */
        public static List<Connection> decode(int[] encoded) {
            if (encoded.length % 3 != 0 || encoded.length > 3 * MAX_CONNECTIONS)
                throw new IllegalArgumentException("Connections need three numbers each, at most " + MAX_CONNECTIONS);
            var out = new ArrayList<Connection>(encoded.length / 3);
            for (int i = 0; i < encoded.length; i += 3) out.add(new Connection(encoded[i], encoded[i + 1], encoded[i + 2]));
            return List.copyOf(out);
        }
    }

    /**
     * A side connection that cannot be built as placed: connection {@code connection} meets {@code other}, another
     * connection's index or one of {@link #VAPOUR}, {@link #LIQUID}, {@link #SADDLE}, {@link #GROUND}, {@link #MANWAY},
     * {@link #BOOT}.
     */
    public record Clash(int connection, int other) {
        public static final int VAPOUR = -1, LIQUID = -2, SADDLE = -3, GROUND = -4, MANWAY = -5, BOOT = -6;
    }

    /** A bare vertical drum resting on its lower head, front to the north: the debug command's form. */
    public EquipmentShape(double diameter, double tangentLength) {
        this(Kind.DRUM, diameter, tangentLength, Orientation.VERTICAL, Heading.NORTH, 0, null, defaultConnections(diameter, tangentLength, Orientation.VERTICAL, 0, false), ProcessUnit.Bank.ONE, false, 0);
    }

    /** A drum with a new drum's side connection ({@link #defaultConnections}). */
    public static EquipmentShape drum(double diameter, double tangentLength, Orientation orientation, Heading front, int support) {
        return drum(diameter, tangentLength, orientation, front, support, defaultConnections(diameter, tangentLength, orientation, support, false));
    }

    public static EquipmentShape drum(double diameter, double tangentLength, Orientation orientation, Heading front, int support, List<Connection> connections) {
        return drum(diameter, tangentLength, orientation, front, support, connections, false);
    }

    /** A drum; a lying one with a water {@code boot} under its front end ({@link #bootPlace}). */
    public static EquipmentShape drum(double diameter, double tangentLength, Orientation orientation, Heading front, int support, List<Connection> connections, boolean boot) {
        return drum(diameter, tangentLength, orientation, front, support, connections, boot, 0);
    }

    /** A drum as {@link #drum(double, double, Orientation, Heading, int, List, boolean)}, a skirted one's bottom outlet turned {@code outlet} quarter turns from the front. */
    public static EquipmentShape drum(double diameter, double tangentLength, Orientation orientation, Heading front, int support, List<Connection> connections, boolean boot,
                                      int outlet) {
        return new EquipmentShape(Kind.DRUM, diameter, tangentLength, orientation, front, support, null, connections, ProcessUnit.Bank.ONE, boot, outlet);
    }

    /** This drum with {@code connections} instead of its own. */
    public EquipmentShape withConnections(List<Connection> connections) {
        return new EquipmentShape(kind, diameter, tangentLength, orientation, front, support, trays, connections, bank, boot, outlet);
    }

    /** A column: its bottoms leave through its skirt to the front (the owner leaves columns alone, 2026-10-25). */
    public static EquipmentShape column(double diameter, ColumnTrays trays, Heading front, int skirt) {
        return new EquipmentShape(Kind.COLUMN, diameter, trays.tangentLength(diameter), Orientation.VERTICAL, front, skirt, trays, List.of(), ProcessUnit.Bank.ONE, false, 0);
    }

    /** A standalone unit of kind {@code kind}, its sizes as {@link ProcessUnit} reads them; it lies or stands as its kind does. */
    public static EquipmentShape unit(Kind kind, double diameter, double tangentLength, Heading front, int support) {
        return unit(kind, diameter, tangentLength, front, support, ProcessUnit.Bank.ONE);
    }

    /** A unit as {@link #unit(Kind, double, double, Heading, int)}; an air cooler built as a bank of bays. */
    public static EquipmentShape unit(Kind kind, double diameter, double tangentLength, Heading front, int support, ProcessUnit.Bank bank) {
        return unit(kind, diameter, tangentLength, front, support, bank, 0);
    }

    /** A unit as {@link #unit(Kind, double, double, Heading, int, ProcessUnit.Bank)}, its bottom outlet turned {@code outlet} quarter turns from the front where its design turns it. */
    public static EquipmentShape unit(Kind kind, double diameter, double tangentLength, Heading front, int support, ProcessUnit.Bank bank, int outlet) {
        if (!kind.unit()) throw new IllegalArgumentException(kind + " is not a unit");
        return new EquipmentShape(kind, diameter, tangentLength, ProcessUnit.orientation(kind), front, support, null, List.of(), bank, false, outlet);
    }

    /**
     * A storage vessel (owner request 2026-10-21): a floating-roof tank of {@code diameter} and {@code shell} height, or a
     * storage sphere of {@code diameter} standing {@code support} metres clear of the ground (its {@code shell} is 1).
     */
    public static EquipmentShape storage(Kind kind, double diameter, double shell, Heading front, int support) {
        if (!kind.storage()) throw new IllegalArgumentException(kind + " is not a storage vessel");
        return new EquipmentShape(kind, diameter, shell, ProcessUnit.orientation(kind), front, support, null, List.of(), ProcessUnit.Bank.ONE, false, 0);
    }

    /** A shape of any designed kind ({@link Kind#designed()}): a storage vessel, or a unit in {@code bank} (an air cooler's). */
    public static EquipmentShape designed(Kind kind, double diameter, double length, Heading front, int support, ProcessUnit.Bank bank) {
        return designed(kind, diameter, length, front, support, bank, 0);
    }

    /** A designed shape as {@link #designed(Kind, double, double, Heading, int, ProcessUnit.Bank)}, its bottom outlet turned {@code outlet} quarter turns ({@link #outlets}). */
    public static EquipmentShape designed(Kind kind, double diameter, double length, Heading front, int support, ProcessUnit.Bank bank, int outlet) {
        if (!kind.designed()) throw new IllegalArgumentException(kind + " is not drawn from a design");
        return new EquipmentShape(kind, diameter, length, ProcessUnit.orientation(kind), front, support, null, List.of(), kind.storage() ? ProcessUnit.Bank.ONE : bank, false, outlet);
    }

    /**
     * A new drum's side connections: its former standard inlet, a standing drum's in the upper shell (0.4 D below the
     * top tangent line) facing the right, a lying drum's on top near its far end, away from the outlets at the front
     * (owner decision 2026-10-25), when the drum is long enough for it beside the vapour outlet.
     */
    public static List<Connection> defaultConnections(double diameter, double tangentLength, Orientation orientation, int support, boolean boot) {
        return defaultConnections(diameter, tangentLength, orientation, support, boot, 0);
    }

    /** A new drum's side connections ({@link #defaultConnections(double, double, Orientation, int, boolean)}) fitted round its bottom outlet turned {@code outlet}. */
    public static List<Connection> defaultConnections(double diameter, double tangentLength, Orientation orientation, int support, boolean boot, int outlet) {
        var drum = new EquipmentShape(Kind.DRUM, diameter, tangentLength, orientation, Heading.NORTH, support, null, List.of(), ProcessUnit.Bank.ONE, boot, outlet);
        double bT = orientation.horizontal() || support == 0 ? diameter / 4 : support, tT = bT + tangentLength;
        if (orientation.horizontal()) {
            double near = Math.ceil(bT) + .5, far = Math.floor(tT - 1) + .5;
            return far - near >= 1.5 ? fit(List.of(new Connection(1, (int) Math.floor(far), 0)), drum) : List.of();
        }
        double y = Math.floor(Math.max(bT + .25, tT - .4 * diameter)) + .5;
        if (y > tT - .1) y -= 1;
        return y > bT + .1 ? fit(List.of(new Connection(3, (int) Math.floor(y), 0)), drum) : List.of();
    }

    /**
     * The sides a connection prefers, best first: a standing drum's facing the constructor's right, left, the back, then
     * the front; a lying drum's on top, at the sides, then underneath.
     */
    public static int[] preferredSides(Orientation orientation) {
        return orientation.horizontal() ? new int[]{1, 0, 2, 3} : new int[]{3, 1, 2, 0};
    }

    /**
     * Side connections fitted to a drum's design (owner report 2026-10-16: the default lying drum's connection met a
     * saddle, "the system needs to adapt the pipe location for different design"). In order, each keeps its place
     * (within the drum's ranges) where it meets nothing; otherwise it moves to the nearest station, on its own side first
     * and then the {@link #preferredSides}, centred across, where it meets neither the outlets, the saddles, the ground
     * nor the connections before it. Where no place is free it stays at its own, and the drum's clashes say so; a shell
     * with no station for any drops them.
     */
    public static List<Connection> fit(List<Connection> wanted, EquipmentShape drum) {
        int[] range = stations(drum.diameter, drum.tangentLength, drum.orientation, drum.support);
        if (range[0] > range[1]) return List.of();
        var out = new ArrayList<Connection>(wanted.size());
        for (var w : wanted) out.add(place(out, w, drum));
        return List.copyOf(out);
    }

    /**
     * Connection {@code wanted} placed after {@code placed} (which stay as they are), as {@link #fit} places each: where
     * it is if it meets nothing there, else at the nearest free station on its own side, then on the preferred sides in
     * turn, else where it is. The drum must have a station for it.
     */
    public static Connection place(List<Connection> placed, Connection wanted, EquipmentShape drum) {
        int[] range = stations(drum.diameter, drum.tangentLength, drum.orientation, drum.support);
        int across = maximumOffset(drum.diameter);
        var own = new Connection(wanted.side(), Math.clamp(wanted.station(), range[0], range[1]), Math.clamp(wanted.offset(), -across, across));
        if (free(placed, own, drum)) return own;
        var sides = new ArrayList<Integer>(List.of(wanted.side()));
        for (int side : preferredSides(drum.orientation)) if (side != wanted.side()) sides.add(side);
        for (int side : sides)
            for (int step = 0; step <= range[1] - range[0]; step++)
                for (int station : new int[]{own.station() - step, own.station() + step}) {
                    if (station < range[0] || station > range[1]) continue;
                    var candidate = new Connection(side, station, 0);
                    if (free(placed, candidate, drum)) return candidate;
                }
        return own;
    }

    /** Whether {@code candidate}, added after {@code placed}, meets nothing on the drum. */
    private static boolean free(List<Connection> placed, Connection candidate, EquipmentShape drum) {
        var trial = new ArrayList<>(placed);
        trial.add(candidate);
        int index = placed.size();
        for (var clash : drum.withConnections(trial).clashes())
            if (clash.connection() == index) return false;
        return true;
    }

    public EquipmentShape {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(orientation, "orientation");
        Objects.requireNonNull(front, "front");
        Objects.requireNonNull(bank, "bank");
        connections = List.copyOf(connections);
        if (outlet < 0 || outlet >= SIDES.length) throw new IllegalArgumentException("A bottom outlet turns 0 to 3 quarter turns from the front, not " + outlet);
        if (kind.designed()) {
            // A storage vessel's ports are fixed (owner decision 2026-10-21): it takes no side connections, as a unit takes none.
            if (trays != null || !connections.isEmpty()) throw new IllegalArgumentException("A " + kind.name().toLowerCase(java.util.Locale.ROOT)
                    + " has no trays and no side connections");
            ProcessUnit.validate(kind, diameter, tangentLength, orientation, support, bank);
            if (boot) throw new IllegalArgumentException("Only a lying drum has a boot");
        } else {
            double maximum = kind == Kind.COLUMN ? MAX_COLUMN_DIAMETER : MAX_DIAMETER;
            if (!(diameter >= MIN_DIAMETER && diameter <= maximum) || diameter * 2 != Math.rint(diameter * 2))
                throw new IllegalArgumentException("Diameter must be 0.5 to " + (int) maximum + " m in 0.5 m steps, not " + diameter);
            if (!(tangentLength >= 1 && tangentLength <= MAX_TANGENT_LENGTH) || tangentLength != Math.rint(tangentLength))
                throw new IllegalArgumentException("Tangent length must be 1 to 64 whole metres, not " + tangentLength);
            if (kind == Kind.COLUMN) {
                if (trays == null || orientation.horizontal()) throw new IllegalArgumentException("A column stands, laid out by its trays");
                if (tangentLength != trays.tangentLength(diameter)) throw new IllegalArgumentException("A column's length follows its trays");
                if (support < minimumSkirt(diameter) || support > MAX_SUPPORT)
                    throw new IllegalArgumentException("Skirt height must be " + minimumSkirt(diameter) + " to " + MAX_SUPPORT + " m, not " + support);
            } else if (trays != null) throw new IllegalArgumentException("A drum has no trays");
            if (orientation.horizontal() && (support < 1 || support > MAX_SUPPORT))
                throw new IllegalArgumentException("Saddle height must be 1 to " + MAX_SUPPORT + " m, not " + support);
            if (!orientation.horizontal() && support != 0 && (support < minimumSkirt(diameter) || support > MAX_SUPPORT))
                throw new IllegalArgumentException("Skirt height must be 0 (none) or " + minimumSkirt(diameter) + " to " + MAX_SUPPORT + " m, not " + support);
            if (!bank.equals(ProcessUnit.Bank.ONE)) throw new IllegalArgumentException("Only air coolers are built in banks");
            if (boot) {
                if (kind != Kind.DRUM || !orientation.horizontal()) throw new IllegalArgumentException("Only a lying drum has a boot");
                if (bootFor(diameter, tangentLength) == null)
                    throw new IllegalArgumentException("A " + tangentLength + " m drum has no room for a boot and its liquid outlet between or beyond its saddles");
                if (support < minimumBootSupport(diameter, tangentLength))
                    throw new IllegalArgumentException("A drum with a boot stands at least " + minimumBootSupport(diameter, tangentLength) + " m up, not " + support);
            }
            if (kind == Kind.COLUMN && !connections.isEmpty()) throw new IllegalArgumentException("A column's nozzles follow its trays");
            if (connections.size() > MAX_CONNECTIONS) throw new IllegalArgumentException("A drum takes at most " + MAX_CONNECTIONS + " side connections");
            int[] range = stations(diameter, tangentLength, orientation, support);
            int across = maximumOffset(diameter);
            for (var c : connections) {
                if (c.station() < range[0] || c.station() > range[1])
                    throw new IllegalArgumentException("A side connection stands at row " + range[0] + " to " + range[1] + ", not " + c.station());
                if (Math.abs(c.offset()) > across) throw new IllegalArgumentException("A side connection's offset is at most " + across + ", not " + c.offset());
            }
        }
        if ((outlets(kind, diameter, tangentLength, orientation, support) >> outlet & 1) == 0)
            throw new IllegalArgumentException("This " + kind.name().toLowerCase(java.util.Locale.ROOT) + "'s bottom outlet does not turn " + outlet + " quarter turns from the front");
    }

    /**
     * The quarter turns from the front a bottom outlet leaving horizontally through a skirt may take, as bits (bit q:
     * {@link #SIDES}[q]; the front, bit 0, always), owner request 2026-10-25: "make drum/reactor bottom outlet rotatable".
     * A skirted drum's port, its slots and its chairs turn together, so every side; a column's stays at the front (the
     * owner leaves columns alone); a lying or bare drum has no skirt port; a designed shape's as its design says
     * ({@link UnitDesign#outlets}). The ranges must already hold.
     */
    public static int outlets(Kind kind, double diameter, double length, Orientation orientation, int support) {
        if (kind.designed()) return UnitDesign.of(kind).outlets(diameter, (int) length, support);
        return kind == Kind.DRUM && !orientation.horizontal() && support > 0 ? (1 << SIDES.length) - 1 : 1;
    }

    /** Whether this shape's bottom outlet may face more than the front: the constructor shows its control. */
    public boolean outletTurns() {
        return Integer.bitCount(outlets(kind, diameter, tangentLength, orientation, support)) > 1;
    }

    /**
     * A lying drum's water boot (owner request 2026-10-16; photo survey EQUIPMENT_DETAILS_PHOTO_SURVEY.md, section A): a
     * vertical cylinder of {@code diameter} under the shell's bottom on the drum's axis, its 2:1 bottom head's tangent
     * {@code length} under the shell, centred at frame height {@code station} along the drum. It hangs from the shell with
     * no support of its own. Its drum's liquid outlet stands underneath at frame height {@code outlet}, between the boot
     * and the front head (owner decision 2026-10-25), or, where {@code outlet} is NaN, low on the front head as a drum's
     * without a boot. {@code largest}: the largest bore that outlet takes, {@link PipeSizes#MAX_BORE} unless its flange
     * must stay clear of the saddle ({@link #bootFor}).
     */
    public record Boot(double station, double diameter, double length, double outlet, double largest) {
    }

    /**
     * The boot a lying drum of {@code diameter} and {@code tangentLength} takes, and where its liquid outlet stands, or
     * null where they have no room. A three-phase drum (owner decision 2026-10-25; Arnold and Stewart's three-phase
     * separator, a reflux drum's general arrangement, photo survey A): the feed enters at the far end, free water settles
     * into the boot at the outlet end, the front, and the oil leaves behind an overflow baffle beyond it, through a nozzle
     * facing down on the bottom centre line between the boot and the front head. The boot: a third of the drum's
     * diameter, at least 0.3, 0.4 or 0.5 m (under 0.9, 1.4 m and above) and at most half of it; 0.9 m or 0.6 D deep, the
     * longer, less what lifts its drain's face a block (owner request 2026-10-25: saddles a block lower; 0.02 to 0.97 m,
     * so at least 0.4 m deep) and what its drain's neck needs at a pipe's largest ({@link #bootDrop}, up to 0.07 m);
     * on the first cell centre inboard of the front saddle that keeps its shell 0.05 m clear of
     * it (their welds apart; owner report 2026-10-25 with a reflux drum's drawing: the boot leans to the outlet end, its
     * centre a quarter of T/T from the tangent line, so not a whole block further in for 0.1 m), and as clear of the far
     * saddle. The outlet on the last cell centre outboard of the front saddle that keeps its largest pipe's
     * flange as clear of it (survey A: bottom nozzles clear the saddle by 0.15 m); the saddle between them keeps their
     * pipes a free block apart (owner rule 2026-10-17). The outlet may stand on the head's bottom near the tangent line,
     * its centre at most 0.6 of the head's depth beyond it, where the head's bottom is within 0.2 R of the shell's, and
     * its largest neck short of the head's apex. Where no such cell keeps a pipe's largest flange clear, the outlet
     * stands on the cell centre next to the saddle's block column and takes a smaller largest bore, in
     * {@link NozzleBore#STEP}s, whose flange stays as clear (owner decision 2026-10-28, option A of batch
     * 2026-10-28-drum-outlet-placement: a 2 x 6 drum's oil left mid-height from its front head, on the axis row). Without
     * that room it stays low on the front head (owner request 2026-10-25: every drum from 6 m long takes a boot). Each
     * pipe drops through a block column clear of the saddles'
     * blocks. A drum whose saddles cannot keep the boot clear has none, nor one whose oil has room neither beyond the
     * saddle nor on its front head (a 0.5 m drum under 9 m).
     */
    public static @Nullable Boot bootFor(double diameter, double tangentLength) {
        double low = diameter < .9 ? .3 : diameter < 1.4 ? .4 : .5, size = Math.min(diameter / 2, Math.max(low, diameter / 3));
        // The drain's face stands STAND_OUT under the boot's apex on a block face (minimumBootSupport): the boot stops
        // short of the block line its preferred depth would reach under that face (a micrometre short, so the face's
        // floor lands on that line in floating point), and shorter by what a pipe's largest neck needs beyond that
        // (bootDrop): the boot gives way, the saddles stay.
        double shell = lyingAxis(diameter) - diameter / 2, reach = shell - size / 4 - STAND_OUT;
        double length = Math.max(.4, reach - Math.floor(reach - Math.max(.9, .6 * diameter)) - 1 - 1e-6 - (bootDrop(size) - size / 4 - STAND_OUT));
        double od = outletLargest(diameter), flange = PipeSizes.flange(od) / 2;
        double bT = diameter / 4, tT = bT + tangentLength, saddle = SADDLE_WIDTH / 2 + .15, clear = SADDLE_WIDTH / 2 + .05 + size / 2;
        double near = bT + SADDLE_POSITION * tangentLength, far = tT - SADDLE_POSITION * tangentLength;
        // Each pipe drops through a block column clear of the saddles' blocks.
        double half = SADDLE_WIDTH / 2;
        double beside = Math.floor(near - half) - .5, outlet = Math.min(Math.floor(near - saddle - flange - .5) + .5, beside);
        double station = Math.max(Math.ceil(near + clear - .5) + .5, Math.floor(near + half - 1e-9) + 1.5);
        if (station > Math.min(far - clear + 1e-9, Math.floor(far - half) - .5)) return null;
        double largest = PipeSizes.MAX_BORE;
        boolean under = bT - outlet <= .6 * bT + 1e-9 && outlet > od / 2;
        if (!under && bT - beside <= .6 * bT + 1e-9) {
            // The cell beside the saddle's column, its largest flange (1.25 OD + 0.085 m, OD 1.1 bore) as clear of the saddle.
            double room = near - saddle - beside, bore = Math.floor((2 * room - .085) / 1.25 / 1.1 / NozzleBore.STEP + 1e-9) * NozzleBore.STEP;
            if (bore >= NozzleBore.STEP && beside > PipeSizes.outside(bore) / 2) {
                outlet = beside;
                largest = bore;
                under = true;
            }
        }
        if (!under && headOutletLargest(diameter) <= .05) return null;
        return new Boot(station, size, length, under ? outlet : Double.NaN, under ? largest : PipeSizes.MAX_BORE);
    }

    /**
     * How far under its bottom tangent line a boot {@code size} across sets its drain's face at least, m: STAND_OUT under
     * its head's apex, and a unit's neck ({@link ProcessUnit#NECK}) under where a pipe's largest leaves the head (owner
     * decision 2026-10-21: a 0.8 m pipe has a 0.8 m root). On a boot from about 1.1 m across the head is flat enough
     * under that neck that STAND_OUT alone left its hub and flange in the head (batch 2026-10-21-floating-roof-tanks,
     * 2026-10-25: 0.21 m of neck on an 8 m drum's boot).
     */
    private static double bootDrop(double size) {
        double depth = size / 4, edge = PipeSizes.MAX_OUTSIDE / size;
        return Math.max(depth + STAND_OUT, depth * Math.sqrt(Math.max(0, 1 - edge * edge)) + ProcessUnit.NECK);
    }

    /** A lying drum's axis height in its frame: the middle of its box's odd count of cells across ({@link #width}). */
    private static double lyingAxis(double diameter) {
        int cells = (int) Math.ceil(diameter);
        return (cells % 2 == 0 ? cells + 1 : cells) / 2.0;
    }

    /** Frame height of a lying drum's liquid outlet low on its front head: the lowest cell centre at least 0.3 m above the shell's bottom. */
    private static double headOutletHeight(double diameter) {
        return Math.ceil(lyingAxis(diameter) - diameter / 2 + .3 - .5) + .5;
    }

    /** That outlet's largest pipe, its edge within 0.85 of the radius across; at most 0.05 m (none) on a drum too small for it. */
    private static double headOutletLargest(double diameter) {
        return Math.min(outletLargest(diameter), 2 * (.85 * diameter / 2 - Math.abs(lyingAxis(diameter) - headOutletHeight(diameter))));
    }

    /** The largest pipe a drum's fixed outlets take: 0.8 m, at most half the drum's diameter. */
    private static double outletLargest(double diameter) {
        return Math.min(PipeSizes.MAX_OUTSIDE, diameter / 2);
    }

    /** This drum's boot, or null when it has none. */
    public @Nullable Boot bootPlace() {
        return boot ? bootFor(diameter, tangentLength) : null;
    }

    /**
     * The lowest saddles a drum with a boot stands on, m: its boot's drain keeps a block for its pipe above the ground
     * (photo survey A: boot drums stand visibly higher). The liquid outlet beside the boot faces down from the shell's
     * bottom, at least the boot's 0.9 m higher, so its pipe has that block too.
     */
    public static int minimumBootSupport(double diameter, double tangentLength) {
        var b = bootFor(diameter, tangentLength);
        if (b == null) return MAX_SUPPORT + 1;
        double bottom = lyingAxis(diameter) - diameter / 2 - b.length();
        return Math.max(1, 1 - (int) Math.floor(bottom - bootDrop(b.diameter())));
    }

    /** The boot's room, {x0, y0, z0, x1, y1, z1} in the frame, or null. */
    private double @Nullable [] bootBox() {
        var b = bootPlace();
        if (b == null) return null;
        double a = axis(), rb = b.diameter() / 2;
        return new double[]{a - radius() - b.length() - b.diameter() / 4, b.station() - rb, a - rb, a - radius() + .1, b.station() + rb, a + rb};
    }

    /**
     * A drum's manway (owner request 2026-10-16; photo survey D), or null for a drum under 0.9 m: a standing drum's on its
     * shell 0.76 m over the bottom tangent line facing the back, where the outlets and the standard inlet leave it room,
     * and none where the shell is too short to hold its cover there (a short pot has a body flange or an inspection hole); a lying drum's on its far head's centre, its davit above it (the front head carries
     * the liquid outlet, a boot drum's leaves underneath). A turned bottom outlet leaves its skirt under the bottom head,
     * clear of a standing drum's manway on the shell above it.
     */
    public @Nullable Manway manway() {
        double size = Manway.size(diameter);
        if (!drum() || size == 0) return null;
        double a = axis(), r = radius(), rr = size / 2, cover = Manway.COVER_RATIO * rr;
        if (orientation.horizontal()) {
            double depth = headDepth(), start = depth * Math.sqrt(Math.max(0, 1 - rr * rr / (r * r))) - .03;
            return new Manway(new double[]{a, topTangent(), a}, new double[]{0, 1, 0}, new double[]{1, 0, 0}, size, start, depth, depth + Manway.REACH);
        }
        double y = bottomTangent() + .76;
        if (y + cover + .1 > topTangent()) return null;
        double apothem = r * DeterministicMath.cos(Math.PI / facets());
        return new Manway(new double[]{a, y, a}, new double[]{0, 0, 1}, new double[]{0, 1, 0}, size, Math.sqrt(apothem * apothem - rr * rr) - .03, r, r + Manway.REACH);
    }

    /**
     * The lowest skirt, m: one whose bottom outlet line carries a pipe's largest on {@link #SKIRT_OUTLET}, its elbow (bend
     * radius 1 D) {@link #ELBOW_CLEARANCE} under the bottom head's pole (owner decisions 2026-10-21: the skirt gives way
     * to the line; 2026-10-25: a lower skirt; 2 m to D 2.5, 3 m to D 6.5, 4 m to D 10.5, 6 m for a D 15 column).
     */
    public static int minimumSkirt(double diameter) {
        return (int) Math.ceil(SKIRT_OUTLET + PipeSizes.MAX_OUTSIDE + ELBOW_CLEARANCE + diameter / 4 - 1e-9);
    }

    /**
     * The support a new design proposes: a skirt leaving at least 1 m under the bottom head for the outlet piping and the
     * bottoms pump's suction head, and no lower than {@link #minimumSkirt}; saddles 1 m high.
     */
    public static int defaultSupport(double diameter, Orientation orientation) {
        return orientation.horizontal() ? 1 : Math.max(minimumSkirt(diameter), (int) Math.ceil(diameter / 4 + 1));
    }

    public boolean column() {
        return kind == Kind.COLUMN;
    }

    public boolean drum() {
        return kind == Kind.DRUM;
    }

    public boolean unit() {
        return kind.unit();
    }

    /** A floating-roof tank or a storage sphere ({@link Kind#storage()}). */
    public boolean storage() {
        return kind.storage();
    }

    /** Drawn from its parts by its design ({@link Kind#designed()}): a storage vessel or a unit. */
    public boolean designed() {
        return kind.designed();
    }

    /** A fluid reservoir: a drum or a storage vessel. */
    public boolean reservoir() {
        return kind.reservoir();
    }

    /** Registers with the fluid engine when the whole structure has been built. */
    public boolean fluidDevice() {
        return reservoir() || kind.mover() || HeatSides.unit(kind) != null;
    }

    /** A vessel's skirt; a designed shape's supports are its own parts. */
    public boolean skirt() {
        return !designed() && orientation == Orientation.VERTICAL && support > 0;
    }

    /** A lying drum's two saddles; a unit's supports are its own parts. */
    public boolean saddles() {
        return drum() && orientation.horizontal();
    }

    public double radius() {
        return diameter / 2;
    }

    public double headDepth() {
        return diameter / 4;
    }

    /** Radius of the outside surface: a column's cladding over its insulation, a drum's shell. */
    public double outerRadius() {
        return radius() + (column() ? INSULATION : 0);
    }

    /** Depth of the outside of the top head over the top tangent line. */
    public double outerHeadDepth() {
        return headDepth() + (column() ? INSULATION : 0);
    }

    /** Shell facets: a multiple of four (the vessel is symmetric about both axes across it), each at most about 1/3 m (a column's 1/4 m). */
    public int facets() {
        return Math.max(12, 4 * (int) Math.ceil((column() ? 1 : .75) * Math.PI * diameter));
    }

    /** Latitude bands of each head. */
    public int bands() {
        return facets() / 4;
    }

    /** Length of the vessel from apex to apex, m: tangent length plus two head depths; a designed shape's along its frame z. */
    public double overallLength() {
        return designed() ? ProcessUnit.of(this).length() : tangentLength + diameter / 2;
    }

    /** Height of the vessel's top above the ground, m. */
    public double topElevation() {
        return designed() ? ProcessUnit.of(this).height() : orientation.horizontal() ? support + axis() + radius() : top();
    }

    /**
     * A floating-roof tank's fill height, m: its shell less the roof's own depth (plan 2026-10-21-floating-roof-tanks,
     * section 3.3), where its overflow stands; 0 for any other shape.
     */
    public double fillHeight() {
        return kind == Kind.TANK ? tangentLength - TankDesign.FREEBOARD : 0;
    }

    /**
     * Design volume of the smooth vessel, m³: the cylinder plus two 2:1 heads of π D³/24 each; a floating-roof tank's
     * cylinder up to its fill height, π D²/4 · H, and a sphere's π D³/6. Supports and nozzles hold no volume.
     */
    public com.wormzjl.icheme.science.fluid.network.VesselGeometry fluidGeometry() {
        if(kind==Kind.KETTLE)return HeatSides.kettleShell(diameter,tangentLength);
        var form=kind==Kind.TANK?com.wormzjl.icheme.science.fluid.network.VesselGeometry.Form.CYLINDER:kind==Kind.SPHERE?com.wormzjl.icheme.science.fluid.network.VesselGeometry.Form.SPHERE:orientation.horizontal()?com.wormzjl.icheme.science.fluid.network.VesselGeometry.Form.HORIZONTAL_DRUM:com.wormzjl.icheme.science.fluid.network.VesselGeometry.Form.VERTICAL_DRUM;
        var b=bootPlace();return com.wormzjl.icheme.science.fluid.network.VesselGeometry.round(form,diameter,kind==Kind.TANK?fillHeight():kind==Kind.SPHERE?0:tangentLength,b==null?0:b.diameter(),b==null?0:b.length());
    }

    public double volume() {
        if (kind == Kind.KETTLE) return fluidGeometry().capacity();
        if (kind == Kind.TANK) return Math.PI * diameter * diameter / 4 * fillHeight();
        if (kind == Kind.SPHERE) return Math.PI * diameter * diameter * diameter / 6;
        var b = bootPlace();
        double boot = b == null ? 0 : Math.PI * b.diameter() * b.diameter() / 4 * b.length() + Math.PI * b.diameter() * b.diameter() * b.diameter() / 24;
        return Math.PI * diameter * diameter / 4 * tangentLength + Math.PI * diameter * diameter * diameter / 12 + boot;
    }

    /**
     * Outside area of the smooth vessel, m²: the cylinder plus two 2:1 heads (supports and nozzles excluded); a
     * floating-roof tank's shell and bottom, a sphere's π D².
     */
    public double surfaceArea() {
        if (kind == Kind.TANK) return Math.PI * diameter * tangentLength + Math.PI * diameter * diameter / 4;
        if (kind == Kind.SPHERE) return Math.PI * diameter * diameter;
        return Math.PI * diameter * tangentLength + 2 * HEAD_AREA_FACTOR * diameter * diameter;
    }

    /**
     * The height over which the vessel's liquid level stands, m, for its liquid head {@code g m_c H / V}: a vertical
     * vessel's apex-to-apex length, a horizontal one's diameter; a floating-roof tank's fill height (the head
     * {@code ρ g h} is then exact at every level, plan section 1), a sphere's diameter (exact at half full, as a lying
     * drum's).
     */
    public double levelHeight() {
        if (kind == Kind.KETTLE) return fluidGeometry().height();
        if (kind == Kind.TANK) return fillHeight();
        if (kind == Kind.SPHERE) return diameter;
        return orientation.horizontal() ? diameter : overallLength();
    }

    /**
     * Cells across the vessel: the shell, a skirt's base ring, or a column's platforms and their handrails, rounded up to an
     * odd count so the axis runs through cell centres.
     */
    public int width() {
        int cells = column() ? (int) Math.ceil(2 * (ColumnStructure.minimumDeckRadius(this) + .1)) : (int) Math.ceil(diameter + (skirt() ? 2 * BASE_RING : 0));
        return cells % 2 == 0 ? cells + 1 : cells;
    }

    /**
     * Anchor-bolt chairs around a skirt's base ring, about one per 1.5 m of circumference in fours; none under 1.5 m,
     * where a chair would be wider than a shell facet.
     */
    public int anchorChairs() {
        return skirt() ? anchorChairs(diameter) : 0;
    }

    /** The anchor chairs round a skirt under a vessel {@code diameter} across ({@link #anchorChairs()}). */
    public static int anchorChairs(double diameter) {
        return diameter >= 1.5 ? 4 * (int) Math.max(1, Math.round(Math.PI * diameter / 6)) : 0;
    }

    /** Size of the vessel's box in cells along world x. */
    public int sizeX() {
        return box()[0];
    }

    /** Size of the vessel's box in cells along world y. */
    public int sizeY() {
        return box()[1];
    }

    /** Size of the vessel's box in cells along world z. */
    public int sizeZ() {
        return box()[2];
    }

    private int[] box() {
        var t = transform();
        var f = frameBox();
        double[] a = t.point(new double[]{f[0], f[1], f[2]}), b = t.point(new double[]{f[3], f[4], f[5]});
        return new int[]{(int) Math.round(Math.abs(b[0] - a[0])), (int) Math.round(Math.abs(b[1] - a[1])), (int) Math.round(Math.abs(b[2] - a[2]))};
    }

    /**
     * The frame-to-box transform: a quarter-turn rotation (a lying drum: frame x up, frame y along world -x before the
     * turn, so the front head faces west; a unit's frame stands, as a vertical vessel's, its front frame -z) turning the
     * front to {@link #front()}, then a shift so the box starts at 0.
     */
    public Mesh.Transform transform() {
        Mesh.Transform base = saddles() ? new Mesh.Transform(new int[]{0, 1, 0, 1, 0, 0, 0, 0, -1}, new double[3]) : Mesh.Transform.IDENTITY;
        var turned = Mesh.Transform.yaw(front.turnsFrom(saddles() ? Heading.WEST : Heading.NORTH), 0, 0, 0).after(base);
        var f = frameBox();
        double[] a = turned.point(new double[]{f[0], f[1], f[2]}), b = turned.point(new double[]{f[3], f[4], f[5]});
        var shift = new Mesh.Transform(Mesh.Transform.IDENTITY.matrix(), new double[]{-Math.min(a[0], b[0]), -Math.min(a[1], b[1]), -Math.min(a[2], b[2])});
        return shift.after(turned);
    }

    /**
     * The box in the vessel frame, {x0, y0, z0, x1, y1, z1}: the vessel and its supports (a column's up to its lifting
     * davit), widened to every nozzle's flange face, in whole cells; a unit's from its parts ({@link ProcessUnit#box()}).
     */
    double[] frameBox() {
        if (designed()) return ProcessUnit.of(this).box();
        double x0 = orientation.horizontal() ? -support : 0, x1 = width(), y0 = 0, z0 = 0, z1 = width();
        double y1 = column() ? Math.max(axialCells(), Math.ceil(ColumnStructure.davitTop(this))) : axialCells();
        for (var nozzle : nozzles()) {
            var face = nozzle.face();
            switch (nozzle.axis * 2 + (nozzle.sign > 0 ? 1 : 0)) {
                case 0 -> x0 = Math.min(x0, face[0]);
                case 1 -> x1 = Math.max(x1, face[0]);
                case 2 -> y0 = Math.min(y0, face[1]);
                case 3 -> y1 = Math.max(y1, face[1]);
                case 4 -> z0 = Math.min(z0, face[2]);
                default -> z1 = Math.max(z1, face[2]);
            }
        }
        var manway = manway();
        if (manway != null) {
            var m = manway.box();
            x0 = Math.min(x0, Math.floor(m[0]));
            y0 = Math.min(y0, Math.floor(m[1]));
            z0 = Math.min(z0, Math.floor(m[2]));
            x1 = Math.max(x1, Math.ceil(m[3]));
            y1 = Math.max(y1, Math.ceil(m[4]));
            z1 = Math.max(z1, Math.ceil(m[5]));
        }
        return new double[]{x0, y0, z0, x1, y1, z1};
    }

    /** The nozzles in the frame: a drum's standard set, a column's from its trays, a unit's own. */
    public List<Nozzle> nozzles() {
        if (designed()) return ProcessUnit.of(this).nozzles();
        var out = new ArrayList<Nozzle>();
        double a = axis(), r = radius(), bT = bottomTangent(), tT = topTangent(), apothem = r * DeterministicMath.cos(Math.PI / facets());
        if (column()) {
            ColumnStructure.nozzles(this, out);
            return out;
        }
        if (!orientation.horizontal()) {
            // Top head centre: the vapour outlet.
            double topOd = outletLargest(diameter);
            double topBase = headSurface(PipeSizes.MAX_OUTSIDE / 2) - .03;
            out.add(new Nozzle(Role.VAPOUR_OUTLET, new double[]{a, topBase, a}, 1, 1, Math.ceil(top() + STAND_OUT) - topBase, clampOd(.2 * diameter, topOd), 0));
            // Liquid outlet. On a skirt it is the bottom outlet (owner report 2026-10-16), through the skirt's port on its side.
            var bottom = skirtOutlet(Role.LIQUID_OUTLET, .1 * diameter);
            if (bottom != null) out.add(bottom);
            else {
                // Without room under the head: the lowest cell centre at least 0.25 m above the bottom tangent, facing the front (frame -z).
                double yL = Math.ceil(bT + .25 - .5) + .5;
                double liquidOd = Math.min(Math.min(PipeSizes.MAX_OUTSIDE, diameter / 2), 2 * (Math.min(yL - bT, tT - yL) - .05));
                if (yL < tT && liquidOd > .05)
                    out.add(side(Role.LIQUID_OUTLET, a, yL, 2, -1, apothem, clampOd(.1 * diameter, liquidOd)));
            }
            for (var c : connections) out.add(connection(c, a, bT, tT, apothem));
            return out;
        }
        // Horizontal: the outlets leave at the front end, the feed enters at the far end (owner decision 2026-10-25). The
        // vapour outlet on top (frame +x) near the front end, a liquid outlet low on the front head (frame -y) or, with a
        // boot, underneath between the boot and the front head.
        double topOd = outletLargest(diameter);
        // A drum too short for a connection beside it: the outlet at the cell centre nearest its middle.
        double front = Math.ceil(bT) + .5;
        if (front > Math.floor(tT - 1) + .5) front = Math.floor((bT + tT) / 2) + .5;
        double topStart = Math.sqrt(Math.max(0, apothem * apothem - PipeSizes.MAX_OUTSIDE * PipeSizes.MAX_OUTSIDE / 4)) - .03;
        out.add(new Nozzle(Role.VAPOUR_OUTLET, new double[]{topStart + a, front, a}, 0, 1, Math.ceil(a + r + STAND_OUT) - (topStart + a), clampOd(.2 * diameter, topOd), 0));
        var b = bootPlace();
        if (b != null && !Double.isNaN(b.outlet())) {
            // A three-phase drum's oil leaves behind the overflow baffle beyond the boot (owner decision 2026-10-25, bootFor):
            // on the bottom centre line facing down, its root buried for its largest neck under the shell or, near the
            // tangent line, under the head's bottom where the neck's far edge meets it, its face on the next block face
            // under the shell. Its largest line may be smaller than the drum's (Boot.largest).
            double oilOd = Math.min(topOd, PipeSizes.outside(b.largest()));
            double rn = oilOd / 2, s = bT - b.outlet() + rn, h = headDepth();
            double under = s <= 0 ? apothem : apothem * Math.sqrt(Math.max(0, 1 - s * s / (h * h)));
            double root = a - Math.sqrt(Math.max(0, under * under - rn * rn)) + .03;
            out.add(new Nozzle(Role.LIQUID_OUTLET, new double[]{root, b.outlet(), a}, 0, -1, root - Math.floor(a - r - STAND_OUT), clampOd(.1 * diameter, oilOd), 0));
        } else {
            // Low on the front head (a boot drum's too where it has no room under the shell beyond its front saddle): the
            // lowest cell centre at least 0.3 m above the shell's bottom.
            double xH = headOutletHeight(diameter), e = a - xH, headOd = headOutletLargest(diameter);
            if (headOd > .05) {
                double edge = (Math.abs(e) + PipeSizes.MAX_OUTSIDE / 2) / r, depth = headDepth() * Math.sqrt(Math.max(0, 1 - edge * edge));
                double start = bT - depth + .03;
                out.add(new Nozzle(Role.LIQUID_OUTLET, new double[]{xH, start, a}, 1, -1, start - Math.floor(-STAND_OUT), clampOd(.1 * diameter, headOd), 0));
            }
        }
        if (b != null) {
            // The boot's drain at its bottom head's centre, facing down (photo survey A), its pipe straight down.
            double rb = b.diameter() / 2, bottom = a - r - b.length(), depth = b.diameter() / 4, od = Math.min(PipeSizes.MAX_OUTSIDE, rb);
            double edge = PipeSizes.MAX_OUTSIDE / 2 / rb, base = bottom - depth * Math.sqrt(Math.max(0, 1 - edge * edge)) + .03;
            out.add(new Nozzle(Role.BOOT_DRAIN, new double[]{base, b.station(), a}, 0, -1, base - Math.floor(bottom - bootDrop(b.diameter())), clampOd(.05 * diameter, od), 0));
        }
        for (var c : connections) out.add(connection(c, a, bT, tT, apothem));
        return out;
    }

    /**
     * Side connection {@code c} as a nozzle. Its default size keeps its edge 0.05 m inside the tangent lines and within
     * 0.85 of the radius across; its neck starts under the shell where a pipe's largest neck's edge meets it (owner
     * decision 2026-10-21: a 1 m pipe has a 1 m root), its flange face on the next block face.
     */
    private Nozzle connection(Connection c, double a, double bT, double tT, double apothem) {
        double y = c.station() + .5, off = Math.abs(c.offset());
        double od = Math.min(Math.min(PipeSizes.MAX_OUTSIDE, diameter / 2), 2 * Math.min(Math.min(y - bT, tT - y) - .05, .85 * radius() - off));
        double edge = off + PipeSizes.MAX_OUTSIDE / 2, start = Math.sqrt(Math.max(0, apothem * apothem - edge * edge)) - .03;
        double surface = a + c.sign() * outerRadius(), plane = c.sign() > 0 ? Math.ceil(surface + STAND_OUT) : Math.floor(surface - STAND_OUT);
        var base = new double[]{a, y, a};
        base[c.axis()] = a + c.sign() * start;
        base[c.across()] = a + c.offset();
        return new Nozzle(Role.SIDE, base, c.axis(), c.sign(), Math.abs(plane - base[c.axis()]), clampOd(.15 * diameter, od), 0);
    }

    /**
     * The frame stations (cell rows along the axis) a drum's side connection may take: its centre line at least 0.1 m
     * inside both tangent lines, {lowest, highest}.
     */
    public static int[] stations(double diameter, double tangentLength, Orientation orientation, int support) {
        double bT = orientation.horizontal() || support == 0 ? diameter / 4 : support, tT = bT + tangentLength;
        return new int[]{(int) Math.ceil(bT - .4 - 1e-9), (int) Math.floor(tT - .6 + 1e-9)};
    }

    /** The largest offset of a side connection across the drum, whole cells: a hillside nozzle's edge stays within 0.85 of the radius. */
    public static int maximumOffset(double diameter) {
        return Math.max(0, (int) Math.floor(.85 * diameter / 2 - .05 + 1e-9));
    }

    /**
     * Where the side connections cannot be built as placed: one overlapping another or a fixed outlet with the largest
     * pipe's flange (one block: nozzles on different block lines never meet) or crowding its pipe ({@link Nozzle#crowds}), one on a lying drum's saddle, its manway
     * or its boot, or one facing down with no cell for a pipe above the ground. Building refuses a drum with any; the constructor's screen
     * shows the first.
     */
    public List<Clash> clashes() {
        if (column() || connections.isEmpty()) return List.of();
        var all = nozzles();
        int fixed = all.size() - connections.size();
        var out = new ArrayList<Clash>();
        var manway = manway();
        double[] bootBox = bootBox();
        double sr = radius() + PLATE, lateral = sr * DeterministicMath.sin(Math.toRadians(SADDLE_CONTACT_DEGREES / 2)),
                horn = axis() - sr * DeterministicMath.cos(Math.toRadians(SADDLE_CONTACT_DEGREES / 2));
        for (int i = fixed; i < all.size(); i++) {
            var box = reach(all.get(i));
            for (int j = 0; j < i; j++)
                if (overlap(box, reach(all.get(j))) || all.get(i).crowds(all.get(j)))
                    out.add(new Clash(i - fixed, j >= fixed ? j - fixed : switch (all.get(j).role()) {
                        case VAPOUR_OUTLET -> Clash.VAPOUR;
                        case BOOT_DRAIN -> Clash.BOOT;
                        default -> Clash.LIQUID;
                    }));
            if (manway != null && overlap(box, manway.box())) out.add(new Clash(i - fixed, Clash.MANWAY));
            if (bootBox != null && overlap(box, bootBox)) out.add(new Clash(i - fixed, Clash.BOOT));
            if (!saddles()) continue;
            for (double c : saddleCentres())
                if (overlap(box, new double[]{-support, c - SADDLE_WIDTH / 2, axis() - lateral, horn, c + SADDLE_WIDTH / 2, axis() + lateral}))
                    out.add(new Clash(i - fixed, Clash.SADDLE));
            var n = all.get(i);
            if (n.axis() == 0 && n.sign() < 0 && n.face()[0] - 1 < -support - 1e-9) out.add(new Clash(i - fixed, Clash.GROUND));
        }
        return out;
    }

    /**
     * A nozzle's room with the largest pipe outside the drum, {x0, y0, z0, x1, y1, z1} in the frame: its neck from where its
     * centre line leaves the drum ({@link #leaves}) and its flange, and a riser's line. The neck's root under the shell,
     * deep enough for a pipe's largest (owner decision 2026-10-21: a 1 m pipe has a 1 m root), lies inside the drum.
     */
    private double[] reach(Nozzle n) {
        double half = PipeSizes.flange(PipeSizes.MAX_OUTSIDE) / 2;
        var box = new double[6];
        for (int a = 0; a < 3; a++) {
            box[a] = n.base()[a] - half;
            box[a + 3] = n.base()[a] + half;
        }
        double face = n.face()[n.axis()], from = leaves(n);
        box[n.axis()] = Math.min(from, face);
        box[n.axis() + 3] = Math.max(from, face);
        if (n.riser() > 0) box[4] = Math.max(box[4], n.riser());
        return box;
    }

    /**
     * Where nozzle {@code n}'s centre line leaves the drum's outside, its frame coordinate along the nozzle's axis: through
     * a head (the 2:1 ellipsoid at the line's distance from the axis) or the straight shell; its base where that lies
     * further out or the line meets neither (a skirted outlet's line, a boot's drain).
     */
    private double leaves(Nozzle n) {
        double[] b = n.base();
        int ax = n.axis();
        double a = axis(), at = b[ax], out;
        if (n.riser() > 0) return at;
        if (ax == 1) {
            double dip = headSurface(Math.sqrt((b[0] - a) * (b[0] - a) + (b[2] - a) * (b[2] - a))) - topTangent();
            out = n.sign() > 0 ? topTangent() + dip : bottomTangent() - dip;
        } else {
            if (b[1] < bottomTangent() || b[1] > topTangent()) return at;
            double off = b[2 - ax] - a, ro = outerRadius();
            out = a + n.sign() * Math.sqrt(Math.max(0, ro * ro - off * off));
        }
        return n.sign() > 0 ? Math.max(at, out) : Math.min(at, out);
    }

    private static boolean overlap(double[] a, double[] b) {
        for (int i = 0; i < 3; i++) if (a[i] >= b[i + 3] - 1e-6 || b[i] >= a[i + 3] - 1e-6) return false;
        return true;
    }

    /**
     * A skirted vessel's bottom outlet (a drum's liquid outlet, owner report 2026-10-16; a column's bottoms, owner request
     * 2026-10-16: "output on the skirt like the drum"), or null when the skirt leaves it no room: its line drops from the
     * bottom head's lowest point inside the skirt, turns in an elbow of bend radius 1 D of the line drawn, and leaves
     * through the skirt's port on {@link #SKIRT_OUTLET} towards {@link #SIDES}[{@link #outlet}]: the front (frame -z)
     * unless a drum's is turned (owner request 2026-10-25), its port, slots and chairs turning with it
     * ({@link ShapeRasterizer}). The riser stands at the axis, a pipe's largest behind the neck's start, and the flange
     * face at least a pipe's largest neck with its hub beyond that (owner decision 2026-10-21: a 1 m pipe has a 1 m root;
     * on a vessel under about 2 m the face steps out to give way), on the next block face.
     */
    @Nullable Nozzle skirtOutlet(Role role, double defaultOd) {
        if (!skirtOutletFits()) return null;
        int axis = SIDES[outlet][0], sign = SIDES[outlet][1];
        double a = axis(), pole = bottomTangent() - headDepth(), max = PipeSizes.MAX_OUTSIDE;
        double out = Math.max(outerRadius() + STAND_OUT, max + PipeSizes.flangeThickness(max) + PipeSizes.hub(max) + .02), plane = sign > 0 ? Math.ceil(a + out) : Math.floor(a - out);
        var base = new double[]{a, SKIRT_OUTLET, a};
        base[axis] = a + sign * max;
        return new Nozzle(role, base, axis, sign, Math.abs(plane - base[axis]), clampOd(defaultOd, max), pole + .03);
    }

    /**
     * Whether a skirted vessel's bottom outlet line fits under its head (owner requests 2026-10-16: the line follows the
     * pipe and rises with the skirt; 2026-10-21: the skirt hole takes the pipe's size, up to 1 m): on
     * {@link #SKIRT_OUTLET}, a pipe's largest line's elbow {@link #ELBOW_CLEARANCE} under the bottom head's pole and its
     * port, its hole and collar round it, 0.1 m under the head seam. Every skirt from {@link #minimumSkirt} up.
     */
    private boolean skirtOutletFits() {
        double max = PipeSizes.MAX_OUTSIDE, pole = bottomTangent() - headDepth();
        return skirt() && SKIRT_OUTLET + max + ELBOW_CLEARANCE <= pole + 1e-9 && SKIRT_OUTLET + portRadius(max) + PORT_COLLAR <= bottomTangent() - .1;
    }

    /**
     * Radius of a skirt's port at its largest, or 0: the hole a pipe's largest line takes; the hole drawn is the line's
     * own outside and {@link #PORT_CLEARANCE} ({@link ShapeRasterizer}, per pipe size).
     */
    public double openingRadius() {
        return skirtOutletFits() ? portRadius(PipeSizes.MAX_OUTSIDE) : 0;
    }

    /** Centre height of a skirt's port, or 0: on its bottom outlet line. */
    public double openingHeight() {
        return skirtOutletFits() ? SKIRT_OUTLET : 0;
    }

    /** A radial nozzle on the cylindrical shell (a column's cladding) at height {@code y}, along frame axis {@code axis} in direction {@code sign}. */
    Nozzle side(Role role, double a, double y, int axis, int sign, double apothem, double defaultOd) {
        double start = Math.sqrt(Math.max(0, apothem * apothem - PipeSizes.MAX_OUTSIDE * PipeSizes.MAX_OUTSIDE / 4)) - .03;
        double surface = a + sign * outerRadius(), plane = sign > 0 ? Math.ceil(surface + STAND_OUT) : Math.floor(surface - STAND_OUT);
        var base = new double[]{a, y, a};
        base[axis] = a + sign * start;
        return new Nozzle(role, base, axis, sign, Math.abs(plane - base[axis]), defaultOd, 0);
    }

    private static double clampOd(double od, double max) {
        return Math.max(Math.min(.1, max), Math.min(od, max));
    }

    /** The outside of the top head at radial distance {@code r} from the axis (the smooth 2:1 ellipsoid, a column's clad one). */
    double headSurface(double r) {
        double outer = outerRadius();
        return topTangent() + outerHeadDepth() * Math.sqrt(Math.max(0, 1 - (r / outer) * (r / outer)));
    }

    // The vessel's own frame: axis along +y at x = z = axis(), from y = 0 (bottom apex or skirt base) to y = top().

    int axialCells() {
        return (int) Math.ceil(top());
    }

    double axis() {
        return width() / 2.0;
    }

    double bottomTangent() {
        return skirt() ? support : headDepth();
    }

    double topTangent() {
        return bottomTangent() + tangentLength;
    }

    /** The top of the vessel's outside: the apex of its top head (a column's clad head). */
    double top() {
        return topTangent() + outerHeadDepth();
    }

    /** Axial centres of the saddles, or none. */
    double[] saddleCentres() {
        return saddles() ? new double[]{bottomTangent() + SADDLE_POSITION * tangentLength, topTangent() - SADDLE_POSITION * tangentLength} : new double[0];
    }
}
