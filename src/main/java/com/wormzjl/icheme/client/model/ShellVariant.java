package com.wormzjl.icheme.client.model;

import java.util.function.Function;

/**
 * Which sprites and vertex colours a shell cell bakes on (decision X5 A of 2026-11-10-custom-shader-system; batch
 * 2026-11-15-equipment-visual-design, RELIEF_SPRITES_PLAN.md 4.2). Chunk geometry cannot use the mod's own shader, so a
 * relief sprite comes twice ({@code tools/pbr-textures/equipment_textures.py}): baked into the albedo for the game's
 * block shader, and flat with LabPBR maps for a shader pack. No Minecraft class, so its rules are tested alone.
 */
public enum ShellVariant {
    /** Today's look: the relief baked into the albedo, the directional shade and the sheen baked into the vertex colour. */
    BAKED,
    /** A shader pack in use: the flat albedo, an unshaded vertex colour; the pack lights it from the sprite's {@code _n} and {@code _s}. */
    FLAT,
    /**
     * The reflection pass draws (batch 2026-11-18-equipment-reflections): BAKED without the sheen, whose fixed highlight
     * the pass's view-dependent one replaces; the directional shade stays.
     */
    REFLECTED;

    /** FLAT while a shader pack is in use and {@code pbr_textures} is on; BAKED otherwise. */
    public static ShellVariant of(boolean pbrTextures, boolean packInUse) {
        return of(pbrTextures, packInUse, false);
    }

    /**
     * As {@link #of(boolean, boolean)}, REFLECTED in BAKED's place while the reflection pass draws ({@code reflections}:
     * its program on the SHADER path, which a shader pack never is, and a strength above 0).
     */
    public static ShellVariant of(boolean pbrTextures, boolean packInUse, boolean reflections) {
        if (pbrTextures && packInUse) return FLAT;
        return reflections && !packInUse ? REFLECTED : BAKED;
    }

    /** Whether quads of this variant bake the sheen over their shade. */
    public boolean sheen() {
        return this == BAKED;
    }

    /** A cell's quads in the model's cache: the variant they were baked on and the cell's key. */
    public record Key<K>(ShellVariant variant, K cell) {
    }

    /** The texture name of a material's flat sprite in a model JSON: its own name plus {@code _flat}. */
    public static String flatTexture(String texture) {
        return texture + "_flat";
    }

    /**
     * The flat variant's sprites: for each material (by index, named by {@code textures}) the sprite {@code flat} finds
     * under {@link #flatTexture}, or its baked sprite where it has none.
     */
    public static <T> T[] flatSprites(T[] baked, String[] textures, Function<String, T> flat) {
        var out = baked.clone();
        for (int i = 0; i < out.length; i++) {
            var sprite = flat.apply(flatTexture(textures[i]));
            if (sprite != null) out[i] = sprite;
        }
        return out;
    }
}
