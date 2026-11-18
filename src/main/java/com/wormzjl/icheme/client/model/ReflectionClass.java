package com.wormzjl.icheme.client.model;

import com.wormzjl.icheme.science.equipment.Mesh;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * The surface classes of the equipment gloss pass (batch 2026-11-18-equipment-reflections, REFLECTION_PASS_REVIEW.md
 * sections 2 and 13): how much of a surface the grey sheen takes head-on (F0; all of it at grazing angles), and the
 * sun's highlight on it; {@link #blur} is only the roughness the LabPBR maps take. The one table: the pass's vertices
 * carry these numbers to the shader ({@code EquipmentReflectionRenderer}), the software renders read them
 * ({@code tools/reflection-render}) and {@code tools/pbr-textures/equipment_textures.py} writes the same smoothness and
 * F0 into the LabPBR maps, which {@code ReflectionClassTest} checks. No Minecraft class.
 *
 * <p>A stylised gloss (owner, 2026-11-18: no colour, no real reflection): paint takes 5 % head-on, bare steel and
 * jacketing a fifth, galvanised grating a tenth; rougher surfaces a wider, weaker highlight. A higher share greys dark
 * metal (the nuts) and washes out a light jacket.
 */
public enum ReflectionClass {
    /** Every painted round body (the shells, heads, casings and frames that took the baked sheen), and yellow handrails. */
    PAINT(.05, .30, 120, .9),
    /** Bare steel: pipes, flanges, nuts, shafts, stainless and carbon steel sheet. */
    STEEL(.20, .35, 60, .8),
    /** Metal insulation jacketing: weathered, embossed aluminium sheet with laps; a soft, wide highlight. */
    CLADDING(.20, .40, 30, .5),
    /** Weathered hot-dip galvanised grating: matte zinc on an open mesh of bars; hardly any sheen or highlight. */
    GALVANISED(.10, .50, 15, .2);

    /** The sheen's share head-on (Schlick's F0). */
    public final double f0;
    /** The roughness the LabPBR map takes (the pass does not use it). */
    public final double blur;
    /** The highlight's power and strength. */
    public final double exponent, strength;

    ReflectionClass(double f0, double blur, double exponent, double strength) {
        this.f0 = f0;
        this.blur = blur;
        this.exponent = exponent;
        this.strength = strength;
    }

    /** The materials the pass draws. Painted: the baked sheen's set without the cladding ({@code EquipmentShellModel}), and the handrail (safety yellow paint). */
    private static final Map<Mesh.Material, ReflectionClass> CLASSES = new EnumMap<>(Mesh.Material.class);

    static {
        for (var m : new Mesh.Material[]{Mesh.Material.SHELL, Mesh.Material.HEAD, Mesh.Material.SUPPORT, Mesh.Material.HEATER,
                Mesh.Material.MACHINE, Mesh.Material.MOTOR, Mesh.Material.AC_HEADER, Mesh.Material.PUMP_CASTING, Mesh.Material.PUMP_VOLUTE,
                Mesh.Material.PUMP_FINS, Mesh.Material.PUMP_JOINT, Mesh.Material.MOTOR_FRAME, Mesh.Material.MOTOR_PLAIN, Mesh.Material.HANDRAIL})
            CLASSES.put(m, PAINT);
        for (var m : new Mesh.Material[]{Mesh.Material.PIPE, Mesh.Material.FLANGE, Mesh.Material.BOLT, Mesh.Material.BOLT_FACE, Mesh.Material.SHAFT,
                Mesh.Material.STAINLESS, Mesh.Material.CARBON_STEEL})
            CLASSES.put(m, STEEL);
        CLASSES.put(Mesh.Material.CLADDING, CLADDING);
        CLASSES.put(Mesh.Material.GRATING, GALVANISED);
    }

    /** The class of {@code material}, or null where the pass draws nothing (concrete, brick, glass, tower panels, guards, ...). */
    public static @Nullable ReflectionClass of(Mesh.Material material) {
        return CLASSES.get(material);
    }

    /** LabPBR 1.3 perceptual smoothness, 0 to 255, for this class: roughness taken equal to {@link #blur}, smoothness = 1 - sqrt(roughness). */
    public int labSmoothness() {
        return (int) Math.round(255 * (1 - Math.sqrt(blur)));
    }

    /** LabPBR 1.3 green channel: linear F0 for a dielectric (0 to 229), the iron metal id 230 for steel and galvanised grating (LabPBR has no zinc), aluminium 232 for jacketing. */
    public int labF0() {
        return switch (this) {
            case PAINT -> (int) Math.round(255 * f0);
            case STEEL, GALVANISED -> 230;
            case CLADDING -> 232;
        };
    }
}
