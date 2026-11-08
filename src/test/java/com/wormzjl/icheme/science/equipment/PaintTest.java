package com.wormzjl.icheme.science.equipment;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class PaintTest {
    @Test
    void paletteHoldsSixteenDistinctColoursAndTheDefaults() {
        // Owner request 2026-10-16: a palette and a few good industrial defaults.
        assertEquals(16, Paint.PALETTE.size());
        var colours = new HashSet<Integer>();
        var numbers = new HashSet<String>();
        for (var swatch : Paint.PALETTE) {
            assertTrue(colours.add(swatch.rgb()), swatch.toString());
            assertTrue(numbers.add(swatch.ral()), swatch.toString());
            assertSame(swatch, Paint.swatch(swatch.rgb()));
        }
        assertNotNull(Paint.swatch(Paint.DEFAULT.body()));
        assertNotNull(Paint.swatch(Paint.DEFAULT.structure()));
        assertNull(Paint.swatch(0x123456));
    }

    @Test
    void tintShowsThePaintOnTheNeutralSprite() {
        // The sprite's mean (SPRITE_VALUE) times the tint gives the paint back, within rounding; brighter clips at white.
        for (var swatch : Paint.PALETTE) {
            int tint = Paint.tint(swatch.rgb());
            for (int shift = 16; shift >= 0; shift -= 8) {
                int paint = swatch.rgb() >> shift & 0xFF, shown = Math.round((tint >> shift & 0xFF) * Paint.SPRITE_VALUE / 255F);
                assertEquals(Math.min(paint, Paint.SPRITE_VALUE), shown, 1, swatch.toString());
            }
        }
        int grey = Paint.SPRITE_VALUE * 0x010101;
        assertEquals(0xFFFFFF, Paint.tint(grey));
        assertEquals(0, Paint.tint(0));
    }

    @Test
    void paintRoundTripsAndRejectsWhatItCannotHold() {
        var paint = new Paint(0xA72920, 0x2F4538);
        assertEquals(paint, Paint.decode(paint.encode()));
        assertEquals(new Paint(0x123456, 0x2F4538), paint.with(Paint.BODY, 0x123456));
        assertEquals(new Paint(0xA72920, 0x123456), paint.with(Paint.STRUCTURE, 0x123456));
        assertEquals(0x2F4538, paint.colour(Paint.STRUCTURE));
        assertThrows(IllegalArgumentException.class, () -> Paint.decode(new int[0]));
        assertThrows(IllegalArgumentException.class, () -> Paint.decode(new int[]{1, 2, 3}));
        assertThrows(IllegalArgumentException.class, () -> new Paint(0x1000000, 0));
        assertThrows(IllegalArgumentException.class, () -> new Paint(0, -1));
    }

    @Test
    void bodyAndStructureMaterialsArePaintedTheRestKeepTheirColours() {
        var body = EnumSet.of(Mesh.Material.SHELL, Mesh.Material.HEAD, Mesh.Material.CLADDING, Mesh.Material.MACHINE, Mesh.Material.AC_RING,
                Mesh.Material.AC_HEADER, Mesh.Material.AC_PLENUM, Mesh.Material.HEATER, Mesh.Material.TOWER_PANEL, Mesh.Material.TOWER_LOUVER, Mesh.Material.TOWER_STACK,
                // The pumps' and motors' baked sprites (batch 2026-11-03-rotating-machinery): painted, as their machines are.
                Mesh.Material.PUMP_CASTING, Mesh.Material.PUMP_VOLUTE, Mesh.Material.PUMP_FINS, Mesh.Material.PUMP_JOINT, Mesh.Material.PUMP_DISC, Mesh.Material.PUMP_COVER,
                Mesh.Material.MOTOR_FRAME, Mesh.Material.MOTOR_SHIELD, Mesh.Material.MOTOR_PANEL, Mesh.Material.GENERATOR_PANEL, Mesh.Material.MOTOR_PLAIN);
        var structure = EnumSet.of(Mesh.Material.SUPPORT, Mesh.Material.STEELWORK, Mesh.Material.PUMP_BASE, Mesh.Material.PUMP_CHANNEL);
        for (var material : Mesh.Material.values())
            assertEquals(body.contains(material) ? Paint.BODY : structure.contains(material) ? Paint.STRUCTURE : -1, material.channel(), material.name());
    }
}
