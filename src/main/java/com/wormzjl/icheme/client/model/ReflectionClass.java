package com.wormzjl.icheme.client.model;

import com.wormzjl.icheme.science.equipment.Mesh;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * The surface classes of the equipment reflection pass (batch 2026-11-18-equipment-reflections,
 * REFLECTION_PASS_REVIEW.md section 2): how strongly a material mirrors its surroundings (Fresnel's F0), how blurred the
 * mirror image is, and the sun's highlight on it. The one table: the pass's vertices carry these numbers to the shader
 * ({@code EquipmentReflectionRenderer}), the software renders read them ({@code tools/reflection-render}) and
 * {@code tools/pbr-textures/equipment_textures.py} writes the same smoothness and F0 into the LabPBR maps, which
 * {@code ReflectionClassTest} checks. No Minecraft class.
 *
 * <p>Physics: a paint is a dielectric clear coat, F0 about 0.04 to 0.05, its reflection white; bare steel and metal
 * jacketing are metals, F0 about 0.55, their reflection tinted by the metal's own colour. Roughness widens the sun's
 * lobe and blurs the horizon.
 */
public enum ReflectionClass {
    /** Every painted round body (the shells, heads, casings and frames that took the baked sheen), and yellow handrails. */
    PAINT(.05, .30, 120, .9, false),
    /** Bare or galvanised steel: pipes, flanges, nuts, shafts, stainless and carbon steel sheet, grating. */
    STEEL(.55, .35, 60, 1.6, true),
    /** Metal insulation jacketing, smoother than plate. */
    CLADDING(.55, .18, 120, 1.6, true);

    /** Fresnel's reflectance head-on. */
    public final double f0;
    /** Half the width of the sky-ground join in the reflected direction's height, and the roughness the LabPBR map takes. */
    public final double blur;
    /** The highlight's power and strength. */
    public final double exponent, strength;
    /** Whether the reflection takes the surface's colour (a metal) or stays white (a dielectric coat). */
    public final boolean tinted;

    ReflectionClass(double f0, double blur, double exponent, double strength, boolean tinted) {
        this.f0 = f0;
        this.blur = blur;
        this.exponent = exponent;
        this.strength = strength;
        this.tinted = tinted;
    }

    /**
     * The materials the pass draws. Painted: the baked sheen's set without the cladding ({@code EquipmentShellModel}),
     * and the handrail, whose sprite is safety yellow paint (mean 214, 172, 36): a metal tinted that yellow would read as gold.
     */
    private static final Map<Mesh.Material, ReflectionClass> CLASSES = new EnumMap<>(Mesh.Material.class);

    static {
        for (var m : new Mesh.Material[]{Mesh.Material.SHELL, Mesh.Material.HEAD, Mesh.Material.SUPPORT, Mesh.Material.HEATER,
                Mesh.Material.MACHINE, Mesh.Material.MOTOR, Mesh.Material.AC_HEADER, Mesh.Material.PUMP_CASTING, Mesh.Material.PUMP_VOLUTE,
                Mesh.Material.PUMP_FINS, Mesh.Material.PUMP_JOINT, Mesh.Material.MOTOR_FRAME, Mesh.Material.MOTOR_PLAIN, Mesh.Material.HANDRAIL})
            CLASSES.put(m, PAINT);
        for (var m : new Mesh.Material[]{Mesh.Material.PIPE, Mesh.Material.FLANGE, Mesh.Material.BOLT, Mesh.Material.BOLT_FACE, Mesh.Material.SHAFT,
                Mesh.Material.STAINLESS, Mesh.Material.CARBON_STEEL, Mesh.Material.GRATING})
            CLASSES.put(m, STEEL);
        CLASSES.put(Mesh.Material.CLADDING, CLADDING);
    }

    /** The class of {@code material}, or null where the pass draws nothing (concrete, brick, glass, tower panels, guards, ...). */
    public static @Nullable ReflectionClass of(Mesh.Material material) {
        return CLASSES.get(material);
    }

    /**
     * The unpainted colour a metal reflection takes, 0 to 255 per channel: the mean of the material's baked sprite
     * (measured 2026-11-18; {@code ReflectionClassTest} reads the sprites again). Painted metal (the cladding) takes its
     * paint instead ({@link #tint}).
     */
    private static final Map<Mesh.Material, int[]> ALBEDO = new EnumMap<>(Map.of(
            Mesh.Material.PIPE, new int[]{161, 161, 160},
            Mesh.Material.FLANGE, new int[]{135, 136, 136},
            Mesh.Material.BOLT, new int[]{66, 68, 72},
            Mesh.Material.BOLT_FACE, new int[]{78, 78, 78},
            Mesh.Material.SHAFT, new int[]{178, 182, 190},
            Mesh.Material.STAINLESS, new int[]{170, 175, 180},
            Mesh.Material.CARBON_STEEL, new int[]{94, 97, 105},
            Mesh.Material.GRATING, new int[]{104, 106, 104}));

    /** The sprite each tinted material's albedo was measured on (the shell model JSON's texture). */
    public static final Map<Mesh.Material, String> SPRITES = Map.of(
            Mesh.Material.PIPE, "equipment_pipe", Mesh.Material.FLANGE, "equipment_flange", Mesh.Material.BOLT, "equipment_bolt",
            Mesh.Material.BOLT_FACE, "equipment_bolt_face", Mesh.Material.SHAFT, "shaft_steel", Mesh.Material.STAINLESS, "still_stainless",
            Mesh.Material.CARBON_STEEL, "still_carbon_steel", Mesh.Material.GRATING, "equipment_grating");

    /** The measured albedo of an unpainted metal, or null for another material. */
    public static int @Nullable [] albedo(Mesh.Material material) {
        var a = ALBEDO.get(material);
        return a == null ? null : a.clone();
    }

    /**
     * The reflection's colour on {@code material} as 0xRRGGBB: white for a dielectric, the measured albedo for bare
     * metal, the body paint {@code paintBody} (the colour the tinted neutral sprite shows) for painted metal (the cladding).
     */
    public static int tint(Mesh.Material material, int paintBody) {
        var c = of(material);
        if (c == null || !c.tinted) return 0xffffff;
        if (material.channel() >= 0) return paintBody & 0xffffff;
        var a = ALBEDO.get(material);
        return a == null ? 0xffffff : a[0] << 16 | a[1] << 8 | a[2];
    }

    /** LabPBR 1.3 perceptual smoothness, 0 to 255, for this class: roughness taken equal to {@link #blur}, smoothness = 1 - sqrt(roughness). */
    public int labSmoothness() {
        return (int) Math.round(255 * (1 - Math.sqrt(blur)));
    }

    /** LabPBR 1.3 green channel: linear F0 for a dielectric (0 to 229), the iron metal id 230 for steel, aluminium 232 for jacketing. */
    public int labF0() {
        return switch (this) {
            case PAINT -> (int) Math.round(255 * f0);
            case STEEL -> 230;
            case CLADDING -> 232;
        };
    }
}
