package com.wormzjl.icheme.client.model;

import com.wormzjl.icheme.science.equipment.Mesh;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The gloss pass's one material table ({@link ReflectionClass}): which materials it draws in which class, and the
 * LabPBR maps {@code tools/pbr-textures} writes from the same numbers (batch 2026-11-18-equipment-reflections).
 */
class ReflectionClassTest {
    private static final Path DIR = Path.of("src/main/resources/assets/icheme/textures/block");
    private static final Path SHELL = Path.of("src/main/resources/assets/icheme/models/block/equipment_shell.json");
    /** The relief sprites keep their own LabPBR maps ({@code EquipmentTexturesTest}). */
    private static final Set<String> RELIEF = Set.of("equipment_bolt_face", "motor_louvre", "equipment_cladding", "painted_cladding");

    @Test
    void theClassesAreTheGlossTable() {
        // F0 (the sheen's share head-on), the highlight's power and strength (section 13)
        double[][] table = {{.05, 120, .9}, {.20, 60, .8}, {.20, 30, .5}, {.10, 15, .2}};
        var classes = new ReflectionClass[]{ReflectionClass.PAINT, ReflectionClass.STEEL, ReflectionClass.CLADDING, ReflectionClass.GALVANISED};
        for (int i = 0; i < classes.length; i++) {
            assertEquals(table[i][0], classes[i].f0, classes[i].name());
            assertEquals(table[i][1], classes[i].exponent, classes[i].name());
            assertEquals(table[i][2], classes[i].strength, classes[i].name());
        }
    }

    @Test
    void theSheensBodiesArePaintAndTheBareSteelIsSteel() {
        var painted = EnumSet.of(Mesh.Material.SHELL, Mesh.Material.HEAD, Mesh.Material.SUPPORT, Mesh.Material.HEATER, Mesh.Material.MACHINE, Mesh.Material.MOTOR,
                Mesh.Material.AC_HEADER, Mesh.Material.PUMP_CASTING, Mesh.Material.PUMP_VOLUTE, Mesh.Material.PUMP_FINS, Mesh.Material.PUMP_JOINT,
                Mesh.Material.MOTOR_FRAME, Mesh.Material.MOTOR_PLAIN, Mesh.Material.HANDRAIL);
        var steel = EnumSet.of(Mesh.Material.PIPE, Mesh.Material.FLANGE, Mesh.Material.BOLT, Mesh.Material.BOLT_FACE, Mesh.Material.SHAFT,
                Mesh.Material.STAINLESS, Mesh.Material.CARBON_STEEL);
        for (var m : Mesh.Material.values()) {
            var expected = painted.contains(m) ? ReflectionClass.PAINT : steel.contains(m) ? ReflectionClass.STEEL : m == Mesh.Material.CLADDING ? ReflectionClass.CLADDING
                    : m == Mesh.Material.GRATING ? ReflectionClass.GALVANISED : null;
            assertEquals(expected, ReflectionClass.of(m), m.name());
        }
        assertNull(ReflectionClass.of(Mesh.Material.GLASS));
        assertNull(ReflectionClass.of(Mesh.Material.CONCRETE));
    }

    @Test
    void labPbrMapsCarryTheSameSmoothnessAndF0() throws IOException {
        assertEquals(115, ReflectionClass.PAINT.labSmoothness());
        assertEquals(13, ReflectionClass.PAINT.labF0());
        assertEquals(104, ReflectionClass.STEEL.labSmoothness());
        assertEquals(230, ReflectionClass.STEEL.labF0());
        assertEquals(232, ReflectionClass.CLADDING.labF0());
        assertEquals(75, ReflectionClass.GALVANISED.labSmoothness());
        assertEquals(230, ReflectionClass.GALVANISED.labF0());
        String json = Files.readString(SHELL);
        int checked = 0;
        for (var m : Mesh.Material.values()) {
            var type = ReflectionClass.of(m);
            if (type == null) continue;
            var matcher = Pattern.compile("\"" + m.texture() + "_flat\"\\s*:\\s*\"icheme:block/(\\w+)_flat\"").matcher(json);
            assertTrue(matcher.find(), m + " has a flat sprite for shader packs");
            String sprite = matcher.group(1);
            if (RELIEF.contains(sprite)) continue;
            var specular = ImageIO.read(DIR.resolve(sprite + "_flat_s.png").toFile());
            var albedo = ImageIO.read(DIR.resolve(sprite + ".png").toFile());
            var flat = ImageIO.read(DIR.resolve(sprite + "_flat.png").toFile());
            assertEquals(albedo.getWidth(), specular.getWidth(), sprite);
            for (int y = 0; y < specular.getHeight(); y++)
                for (int x = 0; x < specular.getWidth(); x++) {
                    int s = specular.getRGB(x, y);
                    assertEquals(type.labSmoothness(), s >> 16 & 255, sprite + "_flat_s smoothness");
                    assertEquals(type.labF0(), s >> 8 & 255, sprite + "_flat_s F0");
                    assertEquals(albedo.getRGB(x, y), flat.getRGB(x, y), sprite + "_flat is the sprite itself (no relief)");
                }
            checked++;
        }
        assertEquals(21, checked, "every plain sprite the pass draws");
    }
}
