package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.PortMarkings.Flow;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Which way a stream passes each nozzle (batch 2026-10-26-multi-machine-framework, plan section 9). */
class PortMarkingsTest {
    @Test
    void aPassThroughUnitsPartnersPairOneInletWithOneOutlet() {
        for (var kind : Kind.values())
            for (var role : Role.values()) {
                var partner = PortMarkings.partner(kind, role);
                if (partner.isEmpty()) continue;
                assertEquals(Optional.of(role), PortMarkings.partner(kind, partner.get()), () -> kind + " " + role);
                assertNotEquals(PortMarkings.flow(kind, role), PortMarkings.flow(kind, partner.get()), () -> kind + " " + role);
                assertNotEquals(Flow.NONE, PortMarkings.flow(kind, role), () -> kind + " " + role);
            }
    }

    @Test
    void theColumnsFeedsAndReturnsComeInAndItsProductsGoOut() {
        for (var role : new Role[]{Role.FEED, Role.REFLUX, Role.RETURN, Role.STEAM, Role.VAPOUR_RETURN})
            assertEquals(Flow.IN, PortMarkings.flow(Kind.COLUMN, role), role::name);
        for (var role : new Role[]{Role.VAPOUR_OUTLET, Role.DRAW, Role.PUMPAROUND_DRAW, Role.BOTTOMS})
            assertEquals(Flow.OUT, PortMarkings.flow(Kind.COLUMN, role), role::name);
    }

    @Test
    void aDrumsSideConnectionsTakeItsFeedAndAStorageVesselsPortsServeEitherWay() {
        assertEquals(Flow.IN, PortMarkings.flow(Kind.DRUM, Role.SIDE));
        assertEquals(Flow.OUT, PortMarkings.flow(Kind.DRUM, Role.BOOT_DRAIN));
        for (var role : Role.values()) {
            assertEquals(Flow.NONE, PortMarkings.flow(Kind.TANK, role));
            assertEquals(Flow.NONE, PortMarkings.flow(Kind.SPHERE, role));
        }
    }

    /**
     * Every flow-marked nozzle of every kind at three sizes (the layout table's shapes), at its design size and at the
     * largest line: its arrows lie on neck that no solid hides, out to just past the neck's round, and their heads point
     * into the vessel for an inlet and out of it for an outlet. Only a few necks show too little for a band (plan 14.3).
     */
    @Test
    void everyArrowShowsOnItsNeckAndPointsWithTheStream() {
        int marked = 0, drawn = 0;
        for (var shape : EquipmentLayoutGoldenTest.shapes()) {
            var nozzles = shape.nozzles();
            for (int k = 0; k < nozzles.size(); k++) {
                var nozzle = nozzles.get(k);
                var flow = PortMarkings.flow(shape.kind(), nozzle.role());
                if (flow == Flow.NONE) continue;
                marked++;
                for (int code : new int[]{0, PipeSizes.code(PipeSizes.MAX_OUTSIDE)}) {
                    var mesh = new Mesh();
                    PortMarkings.band(mesh, shape, k, code, flow);
                    if (mesh.facets().isEmpty()) continue;
                    if (code == 0) drawn++;
                    int axis = nozzle.axis();
                    double[] base = nozzle.base();
                    double od = code == 0 ? nozzle.defaultOd() : PipeSizes.outsideOf(code);
                    var root = ShapeRasterizer.nozzleRoot(shape, k);
                    for (var facet : mesh.facets())
                        for (var p : facet.positions()) {
                            double s = (p[axis] - base[axis]) * nozzle.sign(), off = 0;
                            for (int a = 0; a < 3; a++) if (a != axis) off += (p[a] - base[a]) * (p[a] - base[a]);
                            var q = p.clone();
                            // Probe just outside the local neck radius, including a conical cast reducer.
                            double radius = od / 2;
                            if (root != null && s >= .01 && s < root.end(nozzle.length(), od))
                                radius = root.radius() + (radius - root.radius()) * (s - .01) / (root.end(nozzle.length(), od) - .01);
                            for (int a = 0; a < 3; a++) if (a != axis) q[a] = base[a] + (p[a] - base[a]) * (radius + .01) / Math.sqrt(off);
                            assertFalse(ShapeRasterizer.hides(shape, k, code, q), () -> shape + " " + nozzle.role() + " size " + code + " at " + s);
                        }
                    // An arrowhead's two base corners share a station along the neck; its tip lies beyond them, the way it points.
                    int heads = 0;
                    for (var facet : mesh.facets()) {
                        if (facet.positions().size() != 3) continue;
                        double[] s = facet.positions().stream().mapToDouble(p -> (p[axis] - base[axis]) * nozzle.sign()).sorted().toArray();
                        double tip = s[1] - s[0] < 1e-9 ? s[2] : s[0];
                        assertEquals(flow == Flow.IN ? -1 : 1, Math.signum(tip - s[1]), () -> shape + " " + nozzle.role());
                        heads++;
                    }
                    assertTrue(heads > 0, () -> shape + " " + nozzle.role());
                }
            }
        }
        int all = marked, shown = drawn;
        assertTrue(shown >= .98 * all, () -> shown + " of " + all + " flow-marked nozzles carry arrows");
    }

    @Test
    void aPumpKeepsItsArrowsAtEverySize() {
        for (var shape : EquipmentLayoutGoldenTest.shapes()) {
            if (shape.kind() != Kind.PUMP) continue;
            for (int k = 0; k < shape.nozzles().size(); k++) {
                var role = shape.nozzles().get(k).role();
                if (role != Role.SUCTION && role != Role.DISCHARGE) continue;
                var mesh = new Mesh();
                PortMarkings.band(mesh, shape, k, 0, PortMarkings.flow(Kind.PUMP, role));
                assertFalse(mesh.facets().isEmpty(), () -> shape + " " + role);
            }
        }
    }

    @Test
    void anEjectorPassesItsSuctionToItsDischargeAndTakesItsMotiveSteamApart() {
        assertEquals(Optional.of(Role.DISCHARGE), PortMarkings.partner(Kind.EJECTOR, Role.SUCTION));
        assertEquals(Optional.empty(), PortMarkings.partner(Kind.EJECTOR, Role.MOTIVE));
        assertEquals(Flow.IN, PortMarkings.flow(Kind.EJECTOR, Role.MOTIVE));
        // A kettle's tube bundle passes through; its shell holds the boiling liquid and has no shell nozzles.
        assertEquals(Optional.of(Role.TUBE_OUTLET), PortMarkings.partner(Kind.KETTLE, Role.TUBE_INLET));
        assertEquals(Optional.empty(), PortMarkings.partner(Kind.KETTLE, Role.SHELL_INLET));
    }
}
