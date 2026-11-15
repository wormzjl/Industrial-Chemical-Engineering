package com.wormzjl.icheme.client.fx;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * The equipment relief sprites (batch 2026-11-15-equipment-visual-design, RELIEF_SPRITES_PLAN.md 4.1), as
 * {@code tools/pbr-textures/equipment_textures.py} writes them: each baked sprite with its flat albedo and that albedo's
 * LabPBR 1.3 maps, their sizes and channel ranges; no normal map beside a baked sprite; the baked sprites' mean colours
 * as they were before the relief (so paint tints and colours do not move); painted sprites neutral grey; the shell model
 * naming every flat sprite.
 */
class EquipmentTexturesTest {
    private static final Path DIR = Path.of("src/main/resources/assets/icheme/textures/block");
    private static final Path SHELL = Path.of("src/main/resources/assets/icheme/models/block/equipment_shell.json");
    /** Relief sprites and their size. */
    private static final Map<String, Integer> RELIEF = Map.of("equipment_bolt_face", 32, "motor_louvre", 64, "equipment_cladding", 64, "painted_cladding", 64);
    /** The mean colour of each changed baked sprite before the relief (2026-11-15, eada8e57d), per channel. */
    private static final Map<String, double[]> MEANS = Map.of(
            "equipment_bolt_face", new double[]{122.6, 122.6, 122.6},
            "equipment_cladding", new double[]{194.2, 197.2, 199.2},
            "painted_cladding", new double[]{234.9, 234.9, 234.9},
            "vessel_shell", new double[]{169.8, 173.8, 177.8},
            "painted_shell", new double[]{237.1, 237.1, 237.1},
            "vessel_head", new double[]{170.0, 174.0, 178.0},
            "painted_head", new double[]{237.0, 237.0, 237.0});

    private static BufferedImage read(String file, int size) throws IOException {
        var path = DIR.resolve(file + ".png");
        assertTrue(Files.isRegularFile(path), path + " is missing");
        var image = ImageIO.read(path.toFile());
        assertNotNull(image, path + " is not a PNG");
        assertEquals(size, image.getWidth(), file);
        assertEquals(size, image.getHeight(), file);
        return image;
    }

    @Test
    void everyReliefSpriteHasAFlatAlbedoWithNormalAndSpecularMaps() throws IOException {
        for (var entry : RELIEF.entrySet()) {
            String name = entry.getKey();
            int size = entry.getValue();
            var baked = read(name, size);
            var flat = read(name + "_flat", size);
            var normal = read(name + "_flat_n", size);
            var specular = read(name + "_flat_s", size);
            assertFalse(Files.exists(DIR.resolve(name + "_n.png")), name + ": a baked sprite has no normal map, or a shader pack lights its relief twice");
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++) {
                    assertEquals(255, baked.getRGB(x, y) >>> 24, name + " is opaque");
                    assertEquals(255, flat.getRGB(x, y) >>> 24, name + "_flat is opaque");
                    int n = normal.getRGB(x, y), s = specular.getRGB(x, y);
                    double nx = (n >> 16 & 255) / 127.5 - 1, ny = (n >> 8 & 255) / 127.5 - 1;
                    assertTrue(nx * nx + ny * ny <= 1.02, name + "_flat_n: XY outside the unit disc at " + x + "," + y);
                    assertTrue((n & 255) >= 128, name + "_flat_n: AO below one half at " + x + "," + y);
                    assertTrue((n >>> 24) >= 1, name + "_flat_n: height 0 (LabPBR asks for at least 1) at " + x + "," + y);
                    int f0 = s >> 8 & 255;
                    assertTrue(f0 <= 229 || f0 == 230 || f0 == 232, name + "_flat_s: G is neither F0 nor iron (230) or aluminium (232): " + f0);
                    assertEquals(0, s & 255, name + "_flat_s: no porosity");
                    assertEquals(0, s >>> 24, name + "_flat_s: no emission");
                    assertTrue((s >> 16 & 255) > 0, name + "_flat_s: smoothness 0 at " + x + "," + y);
                }
        }
    }

    @Test
    void bakedSpritesKeepTheirMeanColour() throws IOException {
        for (var entry : MEANS.entrySet()) {
            var image = ImageIO.read(DIR.resolve(entry.getKey() + ".png").toFile());
            double[] sum = new double[3];
            for (int y = 0; y < image.getHeight(); y++)
                for (int x = 0; x < image.getWidth(); x++)
                    for (int c = 0; c < 3; c++) sum[c] += image.getRGB(x, y) >> 16 - 8 * c & 255;
            for (int c = 0; c < 3; c++)
                assertEquals(entry.getValue()[c], sum[c] / (image.getWidth() * image.getHeight()), 2, entry.getKey() + " channel " + c);
        }
    }

    @Test
    void paintedSpritesStayNeutralGrey() throws IOException {
        for (var name : new String[]{"painted_cladding", "painted_cladding_flat", "painted_shell", "painted_head", "motor_louvre", "motor_louvre_flat"}) {
            var image = ImageIO.read(DIR.resolve(name + ".png").toFile());
            for (int y = 0; y < image.getHeight(); y++)
                for (int x = 0; x < image.getWidth(); x++) {
                    int rgb = image.getRGB(x, y);
                    assertTrue((rgb >> 16 & 255) == (rgb & 255) && (rgb >> 8 & 255) == (rgb & 255), name + " is not grey at " + x + "," + y);
                }
        }
    }

    @Test
    void theShellModelNamesEveryFlatSprite() throws IOException {
        String json = Files.readString(SHELL);
        var matcher = Pattern.compile("\"(\\w+)_flat\"\\s*:\\s*\"icheme:block/(\\w+)\"").matcher(json);
        int found = 0;
        while (matcher.find()) {
            found++;
            assertTrue(json.contains("\"" + matcher.group(1) + "\""), matcher.group(1) + "_flat names no material the model has");
            assertTrue(Files.isRegularFile(DIR.resolve(matcher.group(2) + ".png")), matcher.group(2) + " is missing");
            assertTrue(Files.isRegularFile(DIR.resolve(matcher.group(2) + "_n.png")), matcher.group(2) + " has no normal map");
        }
        assertEquals(3, found, "bolt_face, cladding and motor_louvre take flat sprites");
    }
}
