package com.wormzjl.icheme.client.fx;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * The instrument wiring textures and their LabPBR 1.3 maps (batch 2026-11-10-custom-shader-system,
 * CUSTOM_SHADER_SYSTEM_PLAN.md section 4.4, W2 gate), as {@code tools/pbr-textures/wiring_textures.py} writes them:
 * sizes, and the range of every channel.
 */
class WiringTexturesTest {
    private static final Path DIR = Path.of("src/main/resources/assets/icheme/textures/wiring");
    private static final int W = 64, H = 128;

    private static List<String> names() {
        var names = new ArrayList<String>(List.of("branch"));
        for (int n = 1; n <= 6; n++) names.add("tray_" + n);
        return names;
    }

    private static BufferedImage read(String file) throws IOException {
        var path = DIR.resolve(file + ".png");
        assertTrue(Files.isRegularFile(path), path + " is missing");
        var image = ImageIO.read(path.toFile());
        assertNotNull(image, path + " is not a PNG");
        assertEquals(W, image.getWidth(), file);
        assertEquals(H, image.getHeight(), file);
        return image;
    }

    @Test
    void everyTextureHasNormalAndSpecularMapsOfItsSize() throws IOException {
        for (var name : names()) {
            var albedo = read(name);
            var normal = read(name + "_n");
            var specular = read(name + "_s");
            int opaque = 0, holes = 0;
            for (int y = 0; y < H; y++)
                for (int x = 0; x < W; x++) {
                    int a = albedo.getRGB(x, y) >>> 24;
                    assertTrue(a == 0 || a == 255, name + " alpha is cutout only, " + a + " at " + x + "," + y);
                    if (a == 0) {
                        holes++;
                        continue;
                    }
                    opaque++;
                    int n = normal.getRGB(x, y), s = specular.getRGB(x, y);
                    double nx = (n >> 16 & 255) / 127.5 - 1, ny = (n >> 8 & 255) / 127.5 - 1;
                    assertTrue(nx * nx + ny * ny <= 1.02, name + "_n: XY outside the unit disc at " + x + "," + y);
                    assertTrue((n & 255) >= 128, name + "_n: AO below one half at " + x + "," + y);
                    assertTrue((n >>> 24) >= 1, name + "_n: height 0 (LabPBR asks for at least 1) at " + x + "," + y);
                    int f0 = s >> 8 & 255, porosity = s & 255, emission = s >>> 24;
                    assertTrue(f0 <= 229 || f0 == 230, name + "_s: G is neither F0 nor iron (230): " + f0);
                    assertTrue(porosity <= 64, name + "_s: B past porosity: " + porosity);
                    assertEquals(0, emission, name + "_s: the wiring emits no light");
                    assertTrue((s >> 16 & 255) > 0, name + "_s: smoothness 0 at " + x + "," + y);
                }
            assertTrue(opaque > W * H / 2, name + " is mostly holes");
            if (name.startsWith("tray_")) assertTrue(holes > 0, name + " has no perforations");
            else
                for (int y = 0; y < H; y++)
                    for (int x = W / 2; x < W; x++)
                        assertEquals(255, albedo.getRGB(x, y) >>> 24, "branch's sheath half must be solid");
        }
    }

    @Test
    void steelIsIronAndTheSheathIsADielectric() throws IOException {
        // The sheath half of branch.png: PVC, F0 about 0.04; the 316L saddle at the period's start: iron, smoother.
        var specular = read("branch_s");
        int sheath = specular.getRGB(48, 64);
        assertEquals(10, sheath >> 8 & 255);
        assertEquals(Math.round(.35 * 255), sheath >> 16 & 255);
        int saddle = specular.getRGB(16, 0);
        assertEquals(230, saddle >> 8 & 255);
        assertEquals(Math.round(.65 * 255), saddle >> 16 & 255);
    }
}
