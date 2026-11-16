package com.wormzjl.icheme.client.model;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.equipment.Mesh;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The shell model's two variants (decision X5 A; batch 2026-11-15-equipment-visual-design, RELIEF_SPRITES_PLAN.md 4.2):
 * which one a cell bakes on, the cache key that keeps them apart, and the flat sprites' fallback to the baked ones.
 */
class ShellVariantTest {
    @Test
    void flatOnlyUnderAShaderPackWithPbrTexturesOn() {
        assertEquals(ShellVariant.FLAT, ShellVariant.of(true, true));
        assertEquals(ShellVariant.BAKED, ShellVariant.of(true, false));
        assertEquals(ShellVariant.BAKED, ShellVariant.of(false, true));
        assertEquals(ShellVariant.BAKED, ShellVariant.of(false, false));
    }

    @Test
    void theCacheKeepsTheVariantsOfOneCellApart() {
        var cache = new HashMap<ShellVariant.Key<String>, String>();
        cache.put(new ShellVariant.Key<>(ShellVariant.BAKED, "cell"), "baked quads");
        cache.put(new ShellVariant.Key<>(ShellVariant.FLAT, "cell"), "flat quads");
        assertEquals(2, cache.size());
        assertEquals("baked quads", cache.get(new ShellVariant.Key<>(ShellVariant.BAKED, "cell")));
        assertEquals("flat quads", cache.get(new ShellVariant.Key<>(ShellVariant.FLAT, "cell")));
        assertNull(cache.get(new ShellVariant.Key<>(ShellVariant.BAKED, "other")));
    }

    @Test
    void aMaterialWithoutAFlatSpriteKeepsItsBakedOne() {
        var materials = Mesh.Material.values();
        var textures = new String[materials.length];
        var baked = new String[materials.length];
        for (var m : materials) {
            textures[m.ordinal()] = m.texture();
            baked[m.ordinal()] = "baked:" + m.texture();
        }
        var named = Map.of("bolt_face_flat", "equipment_bolt_face_flat", "cladding_flat", "painted_cladding_flat");
        var flat = ShellVariant.flatSprites(baked, textures, named::get);
        assertEquals("equipment_bolt_face_flat", flat[Mesh.Material.BOLT_FACE.ordinal()]);
        assertEquals("painted_cladding_flat", flat[Mesh.Material.CLADDING.ordinal()]);
        assertEquals("baked:shell", flat[Mesh.Material.SHELL.ordinal()]);
        assertEquals("baked:shell", baked[Mesh.Material.SHELL.ordinal()]);
        assertEquals("baked:bolt_face", baked[Mesh.Material.BOLT_FACE.ordinal()], "the baked sprites stay as they are");
        assertEquals("bolt_face_flat", ShellVariant.flatTexture("bolt_face"));
    }
}
