package com.wormzjl.icheme.science.equipment;

/**
 * Where an equipment constructor builds its vessel, relative to itself and the way it faces. With the default offsets
 * the vessel's box stands on the constructor's level, directly in front of it and centred on its line. {@code forward}
 * is the distance from the constructor to the near face of the box (1 = adjacent), {@code right} shifts the box to the
 * constructor's right, {@code up} raises it. For an even size across the line the box centre lies half a block to the
 * left. The vessel's {@link Form} is relative to the constructor too.
 */
public record VesselPlacement(int forward, int right, int up) {
    public static final int MAX_FORWARD = 16, MAX_SHIFT = 16;
    public static final VesselPlacement DEFAULT = new VesselPlacement(1, 0, 0);

    /**
     * A drum standing, or lying with its axis along the constructor's facing (lengthwise) or across it (crosswise); a
     * distillation column; a floating-roof tank or a storage sphere (owner request 2026-10-21); or a unit (owner requests
     * 2026-10-16 and 2026-10-17): a heat exchanger, a kettle reboiler, an
     * air cooler bay, a steam ejector, a centrifugal pump, a reciprocating compressor, a vertical or a cabin heater, lying
     * lengthwise or standing.
     */
    public enum Form {
        VERTICAL(EquipmentShape.Kind.DRUM), LENGTHWISE(EquipmentShape.Kind.DRUM), CROSSWISE(EquipmentShape.Kind.DRUM), COLUMN(EquipmentShape.Kind.COLUMN),
        FLOATING_ROOF_TANK(EquipmentShape.Kind.TANK), SPHERE(EquipmentShape.Kind.SPHERE),
        EXCHANGER(EquipmentShape.Kind.EXCHANGER), KETTLE(EquipmentShape.Kind.KETTLE), AIR_COOLER(EquipmentShape.Kind.AIR_COOLER), EJECTOR(EquipmentShape.Kind.EJECTOR),
        PUMP(EquipmentShape.Kind.PUMP), COMPRESSOR(EquipmentShape.Kind.COMPRESSOR), VERTICAL_HEATER(EquipmentShape.Kind.VERTICAL_HEATER),
        CABIN_HEATER(EquipmentShape.Kind.CABIN_HEATER), STIRRED_REACTOR(EquipmentShape.Kind.STIRRED_REACTOR), ADSORBER(EquipmentShape.Kind.ADSORBER),
        TRICKLE_BED_REACTOR(EquipmentShape.Kind.TRICKLE_BED_REACTOR), EBULLATED_BED_REACTOR(EquipmentShape.Kind.EBULLATED_BED_REACTOR),
        CENTRIFUGAL_COMPRESSOR(EquipmentShape.Kind.CENTRIFUGAL_COMPRESSOR), STEAM_TURBINE(EquipmentShape.Kind.STEAM_TURBINE),
        CYCLONE(EquipmentShape.Kind.CYCLONE), FCC_CONVERTER(EquipmentShape.Kind.FCC_CONVERTER),
        DELAYED_COKER(EquipmentShape.Kind.DELAYED_COKER), FLUIDIZED_BED_BOILER(EquipmentShape.Kind.FLUIDIZED_BED_BOILER),
        FLUIDIZED_BED_GASIFIER(EquipmentShape.Kind.FLUIDIZED_BED_GASIFIER), GRATE_BOILER(EquipmentShape.Kind.GRATE_BOILER),
        FIXED_BED_GASIFIER(EquipmentShape.Kind.FIXED_BED_GASIFIER),
        INDUCED_DRAFT_COOLING_TOWER(EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER),
        NATURAL_DRAFT_COOLING_TOWER(EquipmentShape.Kind.NATURAL_DRAFT_COOLING_TOWER),
        MOTOR(EquipmentShape.Kind.MOTOR), GENERATOR(EquipmentShape.Kind.GENERATOR), GEARBOX(EquipmentShape.Kind.GEARBOX),
        FLARE_STACK(EquipmentShape.Kind.FLARE_STACK), BATCH_STILL(EquipmentShape.Kind.BATCH_STILL),
        STEEL_STACK(EquipmentShape.Kind.STEEL_STACK), BRICK_CHIMNEY(EquipmentShape.Kind.BRICK_CHIMNEY);

        private final EquipmentShape.Kind kind;

        Form(EquipmentShape.Kind kind) {
            this.kind = kind;
        }

        /** The kind of equipment this form builds. */
        public EquipmentShape.Kind kind() {
            return kind;
        }

        public EquipmentShape.Orientation orientation() {
            if (designed()) return ProcessUnit.orientation(kind());
            return this == LENGTHWISE || this == CROSSWISE ? EquipmentShape.Orientation.HORIZONTAL : EquipmentShape.Orientation.VERTICAL;
        }

        public boolean column() {
            return this == COLUMN;
        }

        public boolean unit() {
            return kind().unit();
        }

        /** A shape its kind's design draws ({@link EquipmentShape.Kind#designed()}): a storage vessel or a unit. */
        public boolean designed() {
            return kind().designed();
        }

        /**
         * The vessel's front for a constructor facing the horizontal unit step (stepX, stepZ): back towards the constructor
         * (a standing drum's liquid outlet, a lengthwise drum's front head, a column's piping side, a unit's channel, inlet
         * header, suction or process nozzles), or a crosswise drum's front head to the constructor's right.
         */
        public EquipmentShape.Heading front(int stepX, int stepZ) {
            if (Math.abs(stepX) + Math.abs(stepZ) != 1) throw new IllegalArgumentException("Facing must be a horizontal unit step");
            return this == CROSSWISE ? EquipmentShape.Heading.of(-stepZ, stepX) : EquipmentShape.Heading.of(-stepX, -stepZ);
        }
    }

    public VesselPlacement {
        if (forward < 1 || forward > MAX_FORWARD || Math.abs(right) > MAX_SHIFT || Math.abs(up) > MAX_SHIFT)
            throw new IllegalArgumentException("Offsets must be forward 1 to " + MAX_FORWARD + ", right and up within ±" + MAX_SHIFT);
    }

    /**
     * Lower north-west corner of a box of sizeX × sizeZ cells for a constructor at (x, y, z) facing the horizontal unit
     * step (stepX, stepZ).
     */
    public int[] origin(int x, int y, int z, int stepX, int stepZ, int sizeX, int sizeZ) {
        if (Math.abs(stepX) + Math.abs(stepZ) != 1) throw new IllegalArgumentException("Facing must be a horizontal unit step");
        // The constructor's right is its facing turned clockwise seen from above (north -> east). On each axis the box
        // extends either along the facing (from the near face) or across it (centred), never both.
        int rightX = -stepZ, rightZ = stepX;
        return new int[]{x + stepX * forward + rightX * right + Math.min(0, stepX * (sizeX - 1)) + across(rightX, sizeX), y + up,
                z + stepZ * forward + rightZ * right + Math.min(0, stepZ * (sizeZ - 1)) + across(rightZ, sizeZ)};
    }

    /** Offset of the box's lower corner from the line through the constructor, on an axis the line crosses ({@code right} ≠ 0). */
    private static int across(int right, int size) {
        int low = -(size / 2), high = size - 1 - size / 2;
        return Math.min(right * low, right * high);
    }
}
