package com.wormzjl.icheme.world.level.block.entity;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.equipment.DeviceName;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * A placed device's name in its block entity's NBT (batch 2026-11-02-process-simulator, N1): {@code Name}, a string,
 * saved only when the device is named, so a block saved before names reads unnamed. A saved value that breaks
 * {@link DeviceName}'s rule is cleaned and logged, never refused: a name is not worth a structure.
 */
public final class DeviceNames {
    /** The NBT key. */
    public static final String KEY = "Name";

    private DeviceNames() {
    }

    /** The name saved in {@code tag}; a repair is logged under {@code log}. */
    public static String read(CompoundTag tag, String log) {
        if (!tag.contains(KEY)) return DeviceName.NONE;
        if (!tag.contains(KEY, Tag.TAG_STRING)) {
            IChemE.LOGGER.warn("{} repair=name ({}) -> none", log, tag.get(KEY));
            return DeviceName.NONE;
        }
        String saved = tag.getString(KEY), cleaned = DeviceName.clean(saved);
        if (!cleaned.equals(saved)) IChemE.LOGGER.warn("{} repair=name ({}) -> {}", log, saved.length() > 80 ? saved.substring(0, 80) + "..." : saved, cleaned);
        return cleaned;
    }

    /** Writes {@code name} into {@code tag} when it is not empty. */
    public static CompoundTag write(CompoundTag tag, String name) {
        if (name != null && !name.isEmpty()) tag.putString(KEY, name);
        return tag;
    }
}
