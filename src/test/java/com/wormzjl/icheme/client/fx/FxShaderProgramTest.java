package com.wormzjl.icheme.client.fx;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every shader program's switch (batch 2026-11-18-equipment-reflections, owner: "make every custom shader in the mod
 * configurable"): the mode is the master, the program's own shader runs only with no pack, a loaded shader and its switch
 * on, and otherwise its effect falls back to its compatible drawing, never to nothing, except under OFF for the effects.
 */
class FxShaderProgramTest {
    @Test
    void autoRunsTheShaderOnlyWithNoPackALoadedShaderAndTheSwitchOn() {
        for (var program : FxShaderProgram.values()) {
            assertEquals(FxPath.SHADER, program.path(FxMode.AUTO, true, false, true, true), program.name());
            assertEquals(FxPath.COMPATIBLE, program.path(FxMode.AUTO, true, false, true, false), program + ": switched off");
            assertEquals(FxPath.COMPATIBLE, program.path(FxMode.AUTO, true, false, false, true), program + ": its shader failed");
            assertEquals(FxPath.COMPATIBLE, program.path(FxMode.AUTO, true, true, true, true), program + ": a shader pack in use");
            assertEquals(FxPath.COMPATIBLE, program.path(FxMode.AUTO, false, false, true, true), program + ": the Iris answer unknown");
        }
    }

    @Test
    void compatibleTurnsEveryShaderOffAndOffKeepsTheFallbacksOfWiringAndReflections() {
        for (var program : FxShaderProgram.values()) {
            assertEquals(FxPath.COMPATIBLE, program.path(FxMode.COMPATIBLE, true, false, true, true), program.name());
            assertEquals(program.effect ? FxPath.OFF : FxPath.COMPATIBLE, program.path(FxMode.OFF, true, false, true, true), program.name());
        }
        assertFalse(FxShaderProgram.WIRING.effect, "the wiring is drawn flat under OFF");
        assertFalse(FxShaderProgram.REFLECTION.effect, "the shells keep their sheen under OFF");
    }

    @Test
    void everyKindHasItsProgramAndEveryProgramItsOwnKey() {
        assertEquals(FxShaderProgram.FLAME, FxShaderProgram.of(FxKind.FLAME));
        assertEquals(FxShaderProgram.PLUME, FxShaderProgram.of(FxKind.SMOKE));
        assertEquals(FxShaderProgram.PLUME, FxShaderProgram.of(FxKind.PLUME));
        assertEquals(FxShaderProgram.HAZE, FxShaderProgram.of(FxKind.HAZE));
        var keys = Arrays.stream(FxShaderProgram.values()).map(p -> p.key).collect(Collectors.toSet());
        assertEquals(FxShaderProgram.values().length, keys.size(), "one switch per program");
        assertEquals(new HashSet<>(java.util.List.of("flame_shader", "smoke_shader", "heat_haze", "wiring_shader", "reflections")), keys);
    }

    @Test
    void everyShaderTheModShipsHasAProgram() throws java.io.IOException {
        try (var files = java.nio.file.Files.list(java.nio.file.Path.of("src/main/resources/assets/icheme/shaders/core"))) {
            var shipped = files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).map(n -> n.substring(0, n.length() - 5)).collect(Collectors.toSet());
            assertEquals(java.util.Set.of("fx_flame", "fx_plume", "fx_haze", "rendertype_wiring", "equipment_reflection"), shipped,
                    "a new core shader needs its FxShaderProgram, its switch in ClientConfig and its fallback");
        }
    }
}
