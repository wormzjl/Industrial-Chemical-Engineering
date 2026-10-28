package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Heading;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.wormzjl.icheme.science.equipment.Access.*;
import static com.wormzjl.icheme.science.equipment.ColumnStructure.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A column's access structure and nozzles (batch 2026-10-15-custom-equipment-rendering, photo survey section 2): where
 * the platforms, ladders, manways and nozzles stand, and what a player walks on and climbs. Finding F3 (batch
 * 2026-10-26-multi-machine-framework): the nozzles stand at their stages and turn when crowded, the access is fitted
 * round them.
 */
class ColumnStructureTest {
    /** A small column, a crude-like one with draws, pumparounds and steam, a slim one, and the widest on its tallest skirt. */
    private static final List<EquipmentShape> COLUMNS = List.of(
            EquipmentShape.column(3, ColumnTrays.simple(12, 6), Heading.SOUTH, 4),
            EquipmentShape.column(6, new ColumnTrays(40, 20, List.of(10, 25), List.of(41),
                    List.of(new ColumnTrays.Pumparound(12, 10), new ColumnTrays.Pumparound(30, 28))), Heading.NORTH, 5),
            EquipmentShape.column(1, ColumnTrays.simple(20, 10), Heading.EAST, 3),
            EquipmentShape.column(15, ColumnTrays.simple(30, 15), Heading.WEST, 16));

    @Test
    void platformsStandUnderTheManwaysWithinAClimbOfEachOther() {
        for (var shape : COLUMNS) {
            var plan = ColumnStructure.plan(shape);
            var levels = byLevel(plan);
            var ladders = plan.ladders();
            double clear = ColumnStructure.clearance(shape);
            int previous = 0, k = 0;
            for (var entry : levels.entrySet()) {
                int level = entry.getKey();
                assertTrue(level >= 1 && level - previous <= Access.MAX_CLIMB, shape + " climb to " + level);
                if (previous > 0) assertTrue(level - previous >= MIN_PITCH, shape + " platforms at " + previous + " and " + level);
                previous = level;
                var pieces = entry.getValue();
                assertTrue(pieces.stream().filter(Platform::manway).count() <= 1, shape + " one manway at " + level);
                boolean top = level == levels.lastKey();
                for (var p : pieces) {
                    assertEquals(top, p.ring(), shape + " the top deck rings the column, the others not");
                    if (p.manway()) {
                        assertTrue(p.manwayHeight() - COVER / 2 > shape.bottomTangent() && p.manwayHeight() + COVER / 2 < shape.topTangent(), shape + " manway on the shell");
                        assertEquals(MANWAY_HEIGHT, p.manwayHeight() - p.level() - DECK, 1e-12);
                    }
                    // A part ring over the back, between the two ladders that meet it, in pieces where a nozzle cuts it.
                    if (!top) {
                        double in = ladders.get(k).angle(), out = ladders.get(k + 1).angle();
                        assertTrue(p.from() >= Math.min(in, out) - clear - 1e-12 && p.to() <= Math.max(in, out) + clear + 1e-12, shape + " " + pieces);
                    }
                }
                k++;
            }
            // Each ladder at its back diagonal's side of the back, alternating.
            for (int j = 0; j < ladders.size(); j++) {
                double a = ladders.get(j).angle();
                assertTrue(j % 2 == 0 ? a > BACK && a <= Math.PI : a >= 0 && a < BACK, shape + " ladder " + j + " at " + a);
            }
            assertTrue(levels.lastEntry().getValue().stream().anyMatch(Platform::manway), shape + ": the top ring carries the top manway");
            assertTrue(plan.platforms().stream().filter(Platform::manway).count() >= 2, shape + ": top and bottom manways");
            // The lifting davit stands on the top ring and rises over the top head.
            assertTrue(plan.davitTop() > shape.top() + 1 && plan.davitTop() <= shape.sizeY());
        }
        // The tall skirt: a rest platform under the bottom manway, which carries none.
        var tall = ColumnStructure.plan(COLUMNS.get(3));
        assertFalse(tall.platforms().getFirst().manway(), "a rest platform on a 16 m skirt");
    }

    @Test
    void decksAreWalkableAndTheirEdgesFenced() {
        int s = ShapeRasterizer.SOLID_RESOLUTION;
        for (var shape : COLUMNS) {
            var plan = ColumnStructure.plan(shape);
            double inner = shape.outerRadius() + GAP, edge = plan.deckRadius();
            for (var p : plan.platforms()) {
                double a = (p.from() + p.to()) / 2, middle = (inner + edge) / 2;
                assertTrue(in(shape, middle, a, p.level() + DECK / 2), shape + " deck at " + p.level());
                assertFalse(in(shape, middle, a, p.level() + DECK + .3), shape + " room over the deck at " + p.level());
                assertTrue(in(shape, edge - .1, a, p.level() + 1), shape + " handrail at " + p.level());
                // The deck's collision is one 1/8 m layer: a player stands on it 1/8 m over the block boundary.
                var point = box(shape, middle, a, p.level() + DECK / 2);
                int x = (int) Math.floor(point[0]), y = (int) Math.floor(point[1]), z = (int) Math.floor(point[2]);
                int px = (int) ((point[0] - x) * s), pz = (int) ((point[2] - z) * s);
                assertTrue(ShapeRasterizer.solid(ShapeRasterizer.key(shape, x, y, z)).stream()
                        .anyMatch(b -> b.y0() == 0 && b.y1() == 1 && b.x0() <= px && px < b.x1() && b.z0() <= pz && pz < b.z1()), shape + " walkable deck at " + p.level());
            }
        }
    }

    @Test
    void laddersRiseFromTheGroundToEachDeckAndAlternate() {
        for (var shape : COLUMNS) {
            ProcessUnitTest.climbedFromShellCells(shape, new ColumnStructure(shape).climbs());
            var plan = ColumnStructure.plan(shape);
            var ladders = plan.ladders();
            var levels = byLevel(plan);
            var list = new java.util.ArrayList<>(levels.keySet());
            assertEquals(list.size(), ladders.size());
            assertEquals(0, ladders.getFirst().base());
            double rung = shape.outerRadius() + STANDOFF;
            for (int k = 0; k < ladders.size(); k++) {
                var l = ladders.get(k);
                assertEquals(list.get(k) + DECK + LANDING, l.top(), 1e-12, "rails stand 1.07 m over the landing");
                if (k > 0) {
                    assertEquals(list.get(k - 1) + DECK, l.base(), 1e-12, "a climb starts on the deck below");
                    assertNotEquals(ladders.get(k - 1).angle(), l.angle(), "ladders alternate");
                }
                // The climb ends beside its landing deck, not through it.
                for (var landing : levels.get(list.get(k))) assertFalse(on(l.angle(), landing), shape + " ladder " + k + " lands beside the deck");
                double y = (l.base() + l.top()) / 2;
                var front = box(shape, rung + .3, l.angle(), y);
                assertTrue(ShapeRasterizer.climbable(shape, front[0], front[1], front[2]), shape + " climbable in front of the rungs");
                var away = box(shape, rung + 1.5, l.angle(), y);
                assertFalse(ShapeRasterizer.climbable(shape, away[0], away[1], away[2]), shape + " not climbable outside the cage");
                assertTrue(in(shape, rung - .04, l.angle(), y), shape + " the rung plane is pressed against to climb");
                assertFalse(in(shape, rung + .3, l.angle(), y), shape + " room to climb in front of the rungs");
            }
        }
    }

    /**
     * A player (0.6 m square, 1.8 m tall, aligned with the world, not the deck) walks each deck from the landing beside the
     * arriving ladder to the next ladder, or round the top ring to its manway, and stands on the deck in front of the next
     * ladder; tested against the cells' collision boxes.
     */
    @Test
    void aPlayerWalksTheDecksAndStandsInFrontOfTheNextLadder() {
        for (var shape : COLUMNS) {
            var plan = ColumnStructure.plan(shape);
            var list = new java.util.ArrayList<>(byLevel(plan).keySet());
            var layout = ShapeRasterizer.layout(shape);
            var shell = new java.util.HashSet<>(layout.shell());
            var interior = new java.util.HashSet<>(layout.interior());
            double rung = shape.outerRadius() + STANDOFF, edge = plan.deckRadius(), walk = (rung + RUNG_FACE + edge - RAIL_BAND) / 2;
            double clear = ColumnStructure.clearance(shape);
            for (int k = 0; k < list.size(); k++) {
                double y = list.get(k) + DECK, arrive = plan.ladders().get(k).angle();
                double end = k + 1 < list.size() ? plan.ladders().get(k + 1).angle() : BACK, way = Math.signum(end - arrive);
                for (double a = arrive + way * (clear + .05); way * (end - a) >= 0; a += way * .05)
                    assertTrue(fits(shape, shell, interior, walk, a, y), shape + " walks at " + a + " on the deck at " + list.get(k));
                if (k + 1 == list.size()) continue;
                assertTrue(fits(shape, shell, interior, walk, end, y), shape + " stands in front of the ladder on the deck at " + list.get(k));
                assertFalse(fits(shape, shell, interior, walk, end, y - .05), shape + " is held by the deck at " + list.get(k));
            }
        }
    }

    /**
     * Owner decision 2026-10-16 (home sides): feed and bottoms face the front, reflux and pumparounds the right, draws,
     * steam and the reboiler's vapour return the left (frame +x); the overhead vapour leaves upwards. Finding F3: a
     * crowded nozzle turns to another side; a column with room keeps every nozzle on its home side.
     */
    @Test
    void nozzlesTakeTheirHomeSidesOrTurnAndEndInsideTheHandrail() {
        var crude = COLUMNS.get(1);
        var roles = crude.nozzles().stream().map(EquipmentShape.Nozzle::role).toList();
        assertEquals(List.of(1L, 1L, 1L, 1L, 2L, 2L, 2L, 1L, 1L), List.of(Role.VAPOUR_OUTLET, Role.REFLUX, Role.FEED, Role.BOTTOMS, Role.DRAW, Role.PUMPAROUND_DRAW, Role.RETURN, Role.STEAM,
                        Role.VAPOUR_RETURN).stream()
                .map(r -> roles.stream().filter(r::equals).count()).toList(), "every process connection has its nozzle: " + roles);
        var homes = java.util.Map.of(Role.FEED, FRONT, Role.BOTTOMS, FRONT, Role.REFLUX, RIGHT, Role.PUMPAROUND_DRAW, RIGHT, Role.RETURN, RIGHT,
                Role.DRAW, LEFT, Role.STEAM, LEFT, Role.VAPOUR_RETURN, LEFT);
        for (var shape : COLUMNS) {
            double edge = ColumnStructure.plan(shape).deckRadius();
            assertEquals(Role.VAPOUR_OUTLET, shape.nozzles().getFirst().role());
            assertEquals(List.of(1, 1), List.of(shape.nozzles().getFirst().axis(), shape.nozzles().getFirst().sign()), "the overhead vapour leaves upwards");
            for (var n : ColumnDesign.sides(shape)) {
                assertEquals(homes.get(n.role()), n.home(), shape + " " + n.role());
                assertTrue(n.placed(), shape + " " + n);
                int[] facing = EquipmentShape.side(n.side());
                assertEquals(List.of(facing[0], facing[1]), List.of(n.nozzle().axis(), n.nozzle().sign()), shape + " " + n.role() + " faces its side");
                double reach = Math.abs(n.nozzle().face()[n.nozzle().axis()] - shape.axis());
                assertTrue(reach < edge - .1, shape + " " + n.role() + " flange at " + reach + " past the handrail at " + edge);
            }
        }
        for (var n : ColumnDesign.sides(COLUMNS.get(0))) assertEquals(n.home(), n.side(), "room on every home side: " + n);
        assertTrue(ColumnDesign.sides(crude).stream().anyMatch(n -> n.side() != n.home()), "the crude column's sump steam turns from the vapour return's row");
    }

    /**
     * Owner request 2026-10-16 ("let users arrange them"): every nozzle leaves a free block beyond its flange for the
     * player's pipe, clear of platforms, cages and the davit, whatever the diameter.
     */
    @Test
    void everyNozzleLeavesAFreeBlockForItsPipe() {
        // A crowded design (draws and pumparounds on neighbouring trays) keeps its pipes apart too.
        var crowded = EquipmentShape.column(4, new ColumnTrays(30, 15, List.of(8, 9, 20, 21), List.of(31),
                List.of(new ColumnTrays.Pumparound(11, 10), new ColumnTrays.Pumparound(23, 22))), Heading.NORTH, 4);
        assertEquals(14, crowded.nozzles().size());
        for (var shape : java.util.stream.Stream.concat(COLUMNS.stream(), java.util.stream.Stream.of(crowded)).toList()) {
            ProcessUnitTest.pipesApart(shape);
            var layout = ShapeRasterizer.layout(shape);
            var occupied = new java.util.HashSet<ShapeRasterizer.Cell>(layout.shell());
            occupied.addAll(layout.interior());
            var ports = ShapeRasterizer.ports(shape);
            assertEquals(shape.nozzles().size(), ports.size(), shape.toString());
            for (var port : ports) {
                var pipe = new ShapeRasterizer.Cell(port.cell().x() + port.dx(), port.cell().y() + port.dy(), port.cell().z() + port.dz());
                assertFalse(occupied.contains(pipe), shape + " " + shape.nozzles().get(port.nozzle()).role() + ": no room for a pipe at " + pipe);
            }
        }
    }

    /**
     * Finding F1 (batch 2026-10-26-multi-machine-framework): a short column with four draws and two steam feeds has no row
     * by their trays for every nozzle, on any side; the ones left out are listed at their home side, not dropped in
     * silence. The other designs place all.
     */
    @Test
    void aNozzleWithoutAFreeRowIsListedAsUnplaced() {
        var crowded = EquipmentShape.column(2, new ColumnTrays(4, 2, List.of(1, 2, 3, 4), List.of(4, 5), List.of()), Heading.NORTH, 3);
        var unplaced = ColumnStructure.unplaced(crowded);
        assertFalse(unplaced.isEmpty(), "seven nozzles by four trays of a 2 m shell");
        for (var u : unplaced) {
            assertTrue(u.role() == Role.VAPOUR_RETURN || u.role() == Role.DRAW || u.role() == Role.STEAM, u.toString());
            assertNull(u.nozzle());
            assertFalse(u.turned());
            assertEquals(LEFT, u.side(), "listed at its home side");
        }
        // Vapour outlet, reflux, feed, vapour return, four draws, two steam and the bottoms through the skirt, less those left out.
        assertEquals(11 - unplaced.size(), crowded.nozzles().size());
        for (var shape : COLUMNS) assertEquals(List.of(), ColumnStructure.unplaced(shape), shape.toString());
    }

    /**
     * Finding F3 (owner decision 2026-10-27): a player turns a side nozzle in the constructor; it keeps that side, or is
     * listed as unplaced there, the design refused with the side named. Its other nozzles take their sides by themselves.
     */
    @Test
    void aTurnedNozzleKeepsItsSideOrIsUnplacedThere() {
        java.util.function.IntFunction<EquipmentShape> turned = side -> EquipmentShape.column(3, ColumnTrays.simple(12, 6)
                .withTurns(List.of(new ColumnTrays.Turn(Role.REFLUX, 1, side))), Heading.SOUTH, 4);
        for (int side : new int[]{FRONT, LEFT, RIGHT}) {
            var reflux = ColumnDesign.sides(turned.apply(side)).getFirst();
            assertEquals(List.of(Role.REFLUX, side, true, true), List.of(reflux.role(), reflux.side(), reflux.turned(), reflux.placed()));
        }
        // The back: the top platform's rows by the top tray, and no row under them in the reflux's band.
        var back = turned.apply(BACK_SIDE);
        var reflux = ColumnDesign.sides(back).getFirst();
        assertEquals(List.of(BACK_SIDE, true, false), List.of(reflux.side(), reflux.turned(), reflux.placed()));
        assertEquals("the reflux nozzle has no free row by its tray on the back side it is turned to of a 14-tray column", ColumnDesign.misfit(back).orElseThrow());
        // A turn for a nozzle the column does not have changes nothing.
        var none = EquipmentShape.column(3, ColumnTrays.simple(12, 6).withTurns(List.of(new ColumnTrays.Turn(Role.DRAW, 2, FRONT))), Heading.SOUTH, 4);
        assertEquals(COLUMNS.get(0).nozzles().stream().map(n -> java.util.Arrays.toString(n.base()) + n.axis() + n.sign()).toList(),
                none.nozzles().stream().map(n -> java.util.Arrays.toString(n.base()) + n.axis() + n.sign()).toList());
    }

    /**
     * Finding F3: each side nozzle stands in its stage's band, a cell row reaching into it: an inlet between its tray and
     * the tray over it (the top tangent over tray 1), an outlet between its tray and the tray under it (the sump's level
     * under the bottom tray).
     */
    @Test
    void eachSideNozzleStandsInItsStagesBand() {
        var crowded = EquipmentShape.column(4, new ColumnTrays(30, 15, List.of(8, 9, 20, 21), List.of(31),
                List.of(new ColumnTrays.Pumparound(11, 10), new ColumnTrays.Pumparound(23, 22))), Heading.NORTH, 4);
        for (var shape : java.util.stream.Stream.concat(COLUMNS.stream(), java.util.stream.Stream.of(crowded)).toList()) {
            var t = shape.trays();
            double d = shape.diameter(), yB = shape.bottomTangent(), yT = shape.topTangent();
            java.util.function.IntFunction<double[]> over = k -> new double[]{yB + t.trayHeight(d, k), k == 1 ? yT : yB + t.trayHeight(d, k - 1)};
            java.util.function.IntFunction<double[]> under = k -> new double[]{yB + t.trayHeight(d, Math.min(k, t.trays()) + 1), yB + t.trayHeight(d, Math.min(k, t.trays()))};
            for (var n : ColumnDesign.sides(shape)) {
                double[] band = switch (n.role()) {
                    case REFLUX -> over.apply(1);
                    case FEED -> t.feed() > t.trays() ? under.apply(t.feed()) : over.apply(t.feed());
                    case PUMPAROUND_DRAW -> under.apply(t.pumparounds().get(n.index() - 1).draw());
                    case RETURN -> over.apply(t.pumparounds().get(n.index() - 1).ret());
                    case DRAW -> under.apply(t.draws().get(n.index() - 1));
                    case STEAM -> under.apply(t.steam().get(n.index() - 1));
                    case VAPOUR_RETURN -> under.apply(t.trays() + 1);
                    default -> new double[]{yB, yB + ColumnTrays.bottomSpace(d) / 2};
                };
                double row = Math.floor(n.nozzle().base()[1]);
                assertTrue(row < band[1] && row + 1 > band[0], shape + " " + n.role() + " " + n.index() + " at " + n.nozzle().base()[1] + " outside " + java.util.Arrays.toString(band));
            }
        }
    }

    /**
     * Finding F3: over a grid of designs (diameters, tray counts, a plain column and one with draws, sump steam and two
     * pumparounds), no deck piece and no lifting davit stands in a nozzle's room (its neck and flange, and the block its
     * pipe stands in), no ladder's cage reaches into a pipe's block (a slim column's cage may pass a neck's margin), and
     * every deck carries a player from its ladder to the next ladder's foot, the top ring to its manway.
     */
    @Test
    void decksCagesAndTheDavitKeepOutOfTheNozzlesRoomsAndEveryWalkStaysWhole() {
        int built = 0, designs = 0;
        for (double d : new double[]{1, 2, 3, 6})
            for (int n : new int[]{5, 15, 30})
                for (boolean full : new boolean[]{false, true}) {
                    var trays = full ? new ColumnTrays(n, n / 2 + 1, List.of(Math.max(1, n / 4), Math.max(2, n / 2), Math.max(3, 3 * n / 4)), List.of(n + 1),
                            List.of(new ColumnTrays.Pumparound(Math.max(1, n / 3), Math.max(1, n / 3 - 2)), new ColumnTrays.Pumparound(Math.max(2, 2 * n / 3), Math.max(1, 2 * n / 3 - 2))))
                            : ColumnTrays.simple(n, n / 2 + 1);
                    var shape = EquipmentShape.column(d, trays, Heading.NORTH, EquipmentShape.minimumSkirt(d));
                    designs++;
                    if (ColumnDesign.misfit(shape).isPresent()) continue;
                    built++;
                    roomsKeptAndWalksWhole(shape);
                }
        assertTrue(built >= designs / 2, built + " of " + designs + " built");
    }

    /** The checks of {@link #decksCagesAndTheDavitKeepOutOfTheNozzlesRoomsAndEveryWalkStaysWhole} for one column. */
    private static void roomsKeptAndWalksWhole(EquipmentShape shape) {
        var plan = ColumnStructure.plan(shape);
        double outer = shape.outerRadius(), face = Math.ceil(shape.axis() + outer + EquipmentShape.STAND_OUT) - shape.axis();
        for (var nozzle : shape.nozzles()) {
            if (nozzle.axis() == 1 || nozzle.role() == Role.BOTTOMS && nozzle.base()[1] < shape.bottomTangent()) continue;
            double row = Math.floor(nozzle.base()[1]), h = Math.atan2(nozzle.axis() == 2 ? nozzle.sign() : 0, nozzle.axis() == 0 ? nozzle.sign() : 0);
            double neck = Math.min(.55, PipeSizes.flange(nozzle.defaultOd()) / 2 + .05);
            var neckPart = new double[][]{at(outer, h, -neck), at(face, h, -neck), at(face, h, neck), at(outer, h, neck)};
            var block = new double[][]{at(face, h, -.55), at(face + 1, h, -.55), at(face + 1, h, .55), at(face, h, .55)};
            var pipe = new double[][]{at(face, h, -.5), at(face + 1, h, -.5), at(face + 1, h, .5), at(face, h, .5)};
            String what = shape + " " + nozzle.role() + " at " + nozzle.base()[1];
            for (var l : plan.ladders()) {
                if (!(row < l.top() && row + 1 > l.base())) continue;
                double r = outer + STANDOFF;
                var cage = new double[][]{at(outer, l.angle(), -CAGE_HALF), at(r + 2 * CAGE_HALF, l.angle(), -CAGE_HALF), at(r + 2 * CAGE_HALF, l.angle(), CAGE_HALF),
                        at(outer, l.angle(), CAGE_HALF)};
                assertFalse(overlap(cage, pipe), what + ": a cage in its pipe's block");
                if (shape.diameter() > 1.5) assertFalse(overlap(cage, neckPart) || overlap(cage, block), what + ": a cage in its room");
            }
            for (var p : plan.platforms()) {
                if (!(row < p.level() + DECK + RAIL + BAR && row + 1 > p.level() - .65)) continue;
                int pieces = Math.max(1, (int) Math.ceil((p.to() - p.from()) / (Math.PI / 12)));
                for (int k = 0; k < pieces; k++) {
                    double a0 = p.from() + (p.to() - p.from()) * k / pieces, a1 = p.from() + (p.to() - p.from()) * (k + 1) / pieces;
                    var piece = new double[][]{at(outer + GAP, a0, 0), at(plan.deckRadius(), a0, 0), at(plan.deckRadius(), (a0 + a1) / 2, 0), at(plan.deckRadius(), a1, 0),
                            at(outer + GAP, a1, 0)};
                    assertFalse(overlap(neckPart, piece) || overlap(block, piece), what + ": the deck at " + p.level() + " in its room");
                }
            }
            if (row + 1 > plan.top().level() && row < plan.davitTop()) {
                var c = at(outer + .35, plan.davitAngle(), 0);
                var mast = new double[][]{{c[0] - .1, c[1] - .1}, {c[0] + .1, c[1] - .1}, {c[0] + .1, c[1] + .1}, {c[0] - .1, c[1] + .1}};
                assertFalse(overlap(mast, neckPart) || overlap(mast, block), what + ": the davit in its room");
            }
        }
        // The walks: each part ring from its ladder's landing to the next ladder's foot, the top ring to its manway.
        var levels = byLevel(plan);
        var list = new java.util.ArrayList<>(levels.keySet());
        double clear = ColumnStructure.clearance(shape);
        for (int k = 0; k < list.size(); k++) {
            double in = plan.ladders().get(k).angle();
            boolean whole = false;
            if (k + 1 < list.size()) {
                double out = plan.ladders().get(k + 1).angle(), a = in > out ? in - clear : in + clear;
                for (var p : levels.get(list.get(k))) whole |= p.from() <= Math.min(a, out) + 1e-9 && p.to() >= Math.max(a, out) - 1e-9;
            } else for (var p : levels.get(list.get(k)))
                whole |= p.manway() && (Math.abs(p.to() - (in + 2 * Math.PI - clear)) < 1e-9 || Math.abs(p.from() - (in + clear)) < 1e-9);
            assertTrue(whole, shape + " the deck at " + list.get(k) + " is parted: " + levels.get(list.get(k)));
        }
    }

    /** The deck pieces at each level, bottom to top. */
    private static java.util.TreeMap<Integer, List<Platform>> byLevel(Plan plan) {
        var out = new java.util.TreeMap<Integer, List<Platform>>();
        for (var p : plan.platforms()) out.computeIfAbsent(p.level(), k -> new java.util.ArrayList<>()).add(p);
        return out;
    }

    /** Whether angle {@code a} lies on the arc of {@code piece}. */
    private static boolean on(double a, Platform piece) {
        double x = piece.from() + ((a - piece.from()) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI);
        return x <= piece.to();
    }

    /** The point {@code r} out from the axis at angle {@code a}, {@code t} across, seen from above (frame x, z). */
    private static double[] at(double r, double a, double t) {
        return new double[]{r * Math.cos(a) - t * Math.sin(a), r * Math.sin(a) + t * Math.cos(a)};
    }

    /** Whether two convex outlines overlap: no edge normal of either separates them. */
    private static boolean overlap(double[][] a, double[][] b) {
        return !separates(a, b) && !separates(b, a);
    }

    private static boolean separates(double[][] edges, double[][] other) {
        for (int i = 0; i < edges.length; i++) {
            double[] p = edges[i], q = edges[(i + 1) % edges.length];
            double nx = q[1] - p[1], nz = p[0] - q[0];
            double minA = Double.MAX_VALUE, maxA = -Double.MAX_VALUE, minB = Double.MAX_VALUE, maxB = -Double.MAX_VALUE;
            for (var v : edges) {
                minA = Math.min(minA, v[0] * nx + v[1] * nz);
                maxA = Math.max(maxA, v[0] * nx + v[1] * nz);
            }
            for (var v : other) {
                minB = Math.min(minB, v[0] * nx + v[1] * nz);
                maxB = Math.max(maxB, v[0] * nx + v[1] * nz);
            }
            if (maxA <= minB + 1e-9 || maxB <= minA + 1e-9) return true;
        }
        return false;
    }

    /**
     * Owner answer Q9 (a), batch 2026-10-26-multi-machine-framework: the reboiler's vapour return only on a column whose
     * component design has a kettle. With a kettle, and without a component design, the nozzles stand as before; the sump
     * steam stands a row over the return, under the bottom tray (the sump's stack, batch 2026-10-27-column-sump), turned to
     * the front: on its home side its pipe would crowd the return's (finding F3; finding F1 had left it out). A column
     * stripped by sump steam has no return, and its steam takes its home side at that row.
     */
    @Test
    void theVapourReturnComesWithAKettle() {
        var plain = new ColumnTrays(6, 3, List.of(), List.of(7), List.of());
        java.util.function.Function<Boolean, EquipmentShape> column = kettle -> EquipmentShape.column(2, kettle == null ? plain
                : plain.withComponents(new ColumnComponents(ColumnComponents.Condenser.AIR_COOLER, ColumnComponents.Pump.STANDARD, List.of(), false, kettle)),
                Heading.NORTH, EquipmentShape.minimumSkirt(2));
        java.util.function.Function<EquipmentShape, List<String>> places = s -> s.nozzles().stream()
                .map(n -> n.role() + " " + java.util.Arrays.toString(n.base()) + " " + n.axis() + n.sign()).toList();
        var before = column.apply(null);
        assertEquals(places.apply(before), places.apply(column.apply(true)));
        assertEquals(List.of(), ColumnStructure.unplaced(before));
        var steam = ColumnDesign.sides(before).stream().filter(n -> n.role() == Role.STEAM).findFirst().orElseThrow();
        var vapourReturn = ColumnDesign.sides(before).stream().filter(n -> n.role() == Role.VAPOUR_RETURN).findFirst().orElseThrow();
        assertEquals(List.of(FRONT, vapourReturn.nozzle().base()[1] + 1), List.of(steam.side(), steam.nozzle().base()[1]), "the steam turns a row over the return");
        assertTrue(steam.nozzle().base()[1] < before.bottomTangent() + plain.trayHeight(2, plain.trays()), "the steam stands under the bottom tray");
        var stripped = column.apply(false);
        assertEquals(List.of(), ColumnStructure.unplaced(stripped));
        assertEquals(steam.nozzle().base()[1], ColumnDesign.sides(stripped).stream().filter(n -> n.role() == Role.STEAM).findFirst().orElseThrow().nozzle().base()[1]);
        assertEquals(List.of(Role.VAPOUR_OUTLET, Role.REFLUX, Role.FEED, Role.STEAM, Role.BOTTOMS), stripped.nozzles().stream().map(EquipmentShape.Nozzle::role).toList());
        assertEquals(LEFT, ColumnDesign.sides(stripped).stream().filter(n -> n.role() == Role.STEAM).findFirst().orElseThrow().side());
    }

    /** A design imported from a column calculator keeps its numbers; cutting trays drops what no longer fits; the layout round-trips. */
    @Test
    void traysComeFromACalculatorAndRefitWhenCut() {
        var input = com.wormzjl.icheme.science.column.v3.V3HollandExample32.input();
        var imported = ColumnTrays.of(input);
        assertEquals(input.stageCount(), imported.trays());
        assertEquals(input.feedStageNumber(), imported.feed());
        assertEquals(List.of(input.sideDraws().getFirst().trayNumber()), imported.draws());
        var crude = COLUMNS.get(1).trays();
        assertEquals(crude, ColumnTrays.decode(crude.encode()));
        assertEquals(new ColumnTrays(20, 20, List.of(10), List.of(), List.of(new ColumnTrays.Pumparound(12, 10))), crude.withTrays(20));
        assertThrows(IllegalArgumentException.class, () -> ColumnTrays.decode(new int[]{12, 6, 1}));
    }

    /** Whether the frame point {@code r} out from the axis at angle {@code a} and height {@code y} lies in the solid. */
    private static boolean in(EquipmentShape shape, double r, double a, double y) {
        var p = box(shape, r, a, y);
        return ShapeRasterizer.inside(shape, p[0], p[1], p[2]);
    }

    /** Whether a player's box with its feet at frame point (r, a, y) meets no collision box of the column's cells. */
    private static boolean fits(EquipmentShape shape, java.util.Set<ShapeRasterizer.Cell> shell, java.util.Set<ShapeRasterizer.Cell> interior, double r, double a, double y) {
        var feet = box(shape, r, a, y);
        double[] lo = {feet[0] - .3, feet[1], feet[2] - .3}, hi = {feet[0] + .3, feet[1] + 1.8, feet[2] + .3};
        double s = ShapeRasterizer.SOLID_RESOLUTION, eps = 1e-7;
        for (int x = (int) Math.floor(lo[0]); x < hi[0]; x++)
            for (int cy = (int) Math.floor(lo[1]); cy < hi[1]; cy++)
                for (int z = (int) Math.floor(lo[2]); z < hi[2]; z++) {
                    var cell = new ShapeRasterizer.Cell(x, cy, z);
                    List<ShapeRasterizer.Box> boxes = shell.contains(cell) ? ShapeRasterizer.solid(ShapeRasterizer.key(shape, x, cy, z))
                            : interior.contains(cell) ? List.of(new ShapeRasterizer.Box(0, 0, 0, (int) s, (int) s, (int) s)) : List.of();
                    for (var b : boxes)
                        if (lo[0] < x + b.x1() / s - eps && hi[0] > x + b.x0() / s + eps && lo[1] < cy + b.y1() / s - eps && hi[1] > cy + b.y0() / s + eps
                                && lo[2] < z + b.z1() / s - eps && hi[2] > z + b.z0() / s + eps) return false;
                }
        return true;
    }

    private static double[] box(EquipmentShape shape, double r, double a, double y) {
        double axis = shape.axis();
        return shape.transform().point(new double[]{axis + r * Math.cos(a), y, axis + r * Math.sin(a)});
    }
}
