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
 * The reflection pass's one material table ({@link ReflectionClass}): which materials it draws in which class, the
 * measured albedo of the bare metals against their sprites, and the LabPBR maps {@code tools/pbr-textures} writes from
 * the same numbers (batch 2026-11-18-equipment-reflections).
 */
class ReflectionClassTest {
    private static final Path DIR = Path.of("src/main/resources/assets/icheme/textures/block");
    private static final Path SHELL = Path.of("src/main/resources/assets/icheme/models/block/equipment_shell.json");
    /** The relief sprites keep their own LabPBR maps ({@code EquipmentTexturesTest}). */
    private static final Set<String> RELIEF = Set.of("equipment_bolt_face", "motor_louvre", "equipment_cladding", "painted_cladding");

    @Test
    void theClassesAreTheBriefsTable() {
        assertEquals(.05, ReflectionClass.PAINT.f0);
        assertEquals(.30, ReflectionClass.PAINT.blur);
        assertEquals(120, ReflectionClass.PAINT.exponent);
        assertEquals(.9, ReflectionClass.PAINT.strength);
        assertFalse(ReflectionClass.PAINT.tinted);
        assertEquals(.55, ReflectionClass.STEEL.f0);
        assertEquals(.35, ReflectionClass.STEEL.blur);
        assertEquals(60, ReflectionClass.STEEL.exponent);
        assertEquals(1.6, ReflectionClass.STEEL.strength);
        assertTrue(ReflectionClass.STEEL.tinted);
        assertEquals(.55, ReflectionClass.CLADDING.f0);
        assertEquals(.18, ReflectionClass.CLADDING.blur);
        assertTrue(ReflectionClass.CLADDING.tinted);
    }

    @Test
    void theSheensBodiesArePaintAndTheBareSteelIsSteel() {
        var painted = EnumSet.of(Mesh.Material.SHELL, Mesh.Material.HEAD, Mesh.Material.SUPPORT, Mesh.Material.HEATER, Mesh.Material.MACHINE, Mesh.Material.MOTOR,
                Mesh.Material.AC_HEADER, Mesh.Material.PUMP_CASTING, Mesh.Material.PUMP_VOLUTE, Mesh.Material.PUMP_FINS, Mesh.Material.PUMP_JOINT,
                Mesh.Material.MOTOR_FRAME, Mesh.Material.MOTOR_PLAIN, Mesh.Material.HANDRAIL);
        var steel = EnumSet.of(Mesh.Material.PIPE, Mesh.Material.FLANGE, Mesh.Material.BOLT, Mesh.Material.BOLT_FACE, Mesh.Material.SHAFT,
                Mesh.Material.STAINLESS, Mesh.Material.CARBON_STEEL, Mesh.Material.GRATING);
        for (var m : Mesh.Material.values()) {
            var expected = painted.contains(m) ? ReflectionClass.PAINT : steel.contains(m) ? ReflectionClass.STEEL : m == Mesh.Material.CLADDING ? ReflectionClass.CLADDING : null;
            assertEquals(expected, ReflectionClass.of(m), m.name());
        }
        assertNull(ReflectionClass.of(Mesh.Material.GLASS));
        assertNull(ReflectionClass.of(Mesh.Material.CONCRETE));
    }

    @Test
    void aMetalsReflectionTakesItsSpritesColour() throws IOException {
        for (var e : ReflectionClass.SPRITES.entrySet()) {
            var image = ImageIO.read(DIR.resolve(e.getValue() + ".png").toFile());
            double[] sum = new double[3];
            for (int y = 0; y < image.getHeight(); y++)
                for (int x = 0; x < image.getWidth(); x++)
                    for (int c = 0; c < 3; c++) sum[c] += image.getRGB(x, y) >> 16 - 8 * c & 255;
            int[] albedo = ReflectionClass.albedo(e.getKey());
            assertNotNull(albedo, e.getKey().name());
            for (int c = 0; c < 3; c++)
                assertEquals(sum[c] / (image.getWidth() * image.getHeight()), albedo[c], 1, e.getValue() + " channel " + c);
        }
        assertEquals(0xffffff, ReflectionClass.tint(Mesh.Material.SHELL, 0x2050a0), "paint mirrors white");
        assertEquals(161 << 16 | 161 << 8 | 160, ReflectionClass.tint(Mesh.Material.PIPE, 0x2050a0), "bare pipe: its sprite's grey");
        assertEquals(0x2050a0, ReflectionClass.tint(Mesh.Material.CLADDING, 0x2050a0), "painted jacketing: its paint");
        assertEquals(0xffffff, ReflectionClass.tint(Mesh.Material.CONCRETE, 0x2050a0));
    }

    @Test
    void labPbrMapsCarryTheSameSmoothnessAndF0() throws IOException {
        assertEquals(115, ReflectionClass.PAINT.labSmoothness());
        assertEquals(13, ReflectionClass.PAINT.labF0());
        assertEquals(104, ReflectionClass.STEEL.labSmoothness());
        assertEquals(230, ReflectionClass.STEEL.labF0());
        assertEquals(232, ReflectionClass.CLADDING.labF0());
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
