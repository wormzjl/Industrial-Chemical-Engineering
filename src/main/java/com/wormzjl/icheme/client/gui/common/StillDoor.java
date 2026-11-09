package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * A batch still's groups on its pot's door column (batch 2026-11-09-batch-distiller, W6), the rows the device column
 * leads with ({@code DeviceColumn.Input.lead}): Firing (Off and On, then the damper's, the burners' or the power's
 * opening), the Reflux valve on a still with a column (Closed and Open, then its ratio), and a solid still's Fuel (Load and
 * Take out, then what the slot holds and the fire left). A line under a setting says where a data-network controller
 * holds it. The readings (temperatures, level, heat, flows, condenser share, reflux) are the device column's meters and the
 * status stays on the message display. No Minecraft classes.
 *
 * <p>Widget keys: {@code still_firing:off}, {@code still_firing:on}, {@code still_reflux:closed}, {@code still_reflux:open},
 * {@code still_fuel:load}, {@code still_fuel:unload}; the fields' draft keys {@code stillFiring} (%) and {@code stillReflux}.
 */
public final class StillDoor {
    private StillDoor() {
    }

    /** The heater, by its lower-case name: {@code solid}, {@code liquid} or {@code electric}. */
    public enum Heater {
        SOLID, LIQUID, ELECTRIC;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A solid still's fuel slot as shown: the item's name (null for an empty slot), its count, and the fire left at the
     * firing it runs at (minutes, "—" while out), its hover's heat (GJ).
     */
    public record Fuel(@Nullable String item, String count, String left, String heat) {
    }

    /**
     * What the groups read: the heater; whether this client may edit; whether the drafted switch stands On; whether the
     * still has a column (its reflux valve) and whether the drafted valve stands Open; what a controller holds instead
     * (the firing as shown in %, the reflux ratio as shown, or the word for a closed valve; null where the setting rules);
     * a solid still's fuel slot (null for another heater).
     */
    public record Input(Heater heater, boolean canEdit, boolean firing, boolean column, boolean open, @Nullable String heldFiring,
                        @Nullable String heldReflux, @Nullable Fuel fuel) {
    }

    public static List<Column.Row> rows(Input in, HeatControlColumn.Texts t) {
        var rows = new ArrayList<Column.Row>();
        String heater = in.heater().key();
        var firing = new ArrayList<Column.Row>();
        firing.add(new Column.Buttons(List.of(
                position("still_firing:off", t.get("heat_switch.off"), t.get("still_firing.off.hint." + heater), !in.firing(), in.canEdit()),
                position("still_firing:on", t.get("heat_switch.on"), t.get("still_firing.on.hint." + heater), in.firing(), in.canEdit()))));
        if (in.canEdit())
            firing.add(new Column.Field("stillFiring", t.get("still_field.firing." + heater), "%", t.get("still_field.firing." + heater + ".hint")));
        if (in.heldFiring() != null) firing.add(new Column.Paragraph(t.get("still_held", in.heldFiring() + " %"), Palette.ACCENT, t.get("still_held.hint")));
        rows.add(new Column.Group(t.get("still_group.firing." + heater), t.get("still_group.firing." + heater + ".hint"), firing));
        if (in.column()) {
            var valve = new ArrayList<Column.Row>();
            valve.add(new Column.Buttons(List.of(
                    position("still_reflux:closed", t.get("still_reflux.closed"), t.get("still_reflux.closed.hint"), !in.open(), in.canEdit()),
                    position("still_reflux:open", t.get("still_reflux.open"), t.get("still_reflux.open.hint"), in.open(), in.canEdit()))));
            if (in.canEdit()) valve.add(new Column.Field("stillReflux", t.get("still_field.reflux"), "", t.get("still_field.reflux.hint")));
            if (in.heldReflux() != null) valve.add(new Column.Paragraph(t.get("still_held", in.heldReflux()), Palette.ACCENT, t.get("still_held.hint")));
            rows.add(new Column.Group(t.get("still_group.reflux"), t.get("still_group.reflux.hint"), valve));
        }
        var fuel = in.fuel();
        if (fuel != null) {
            var slot = new ArrayList<Column.Row>();
            slot.add(new Column.Buttons(List.of(
                    new Column.Button("still_fuel:load", t.get("still_fuel.load"), in.canEdit(), t.get("still_fuel.load.hint")),
                    new Column.Button("still_fuel:unload", t.get("still_fuel.unload"), in.canEdit() && fuel.item() != null, t.get("still_fuel.unload.hint")))));
            // What the slot holds follows the player's loading and the fire; the screen rebuilds when the item changes.
            slot.add(fuel.item() == null ? new Column.Line(t.get("still_fuel.empty"), Palette.MUTED) : new Column.Paragraph(fuel.item(), Palette.TEXT, null));
            slot.add(new Column.Reading("still:fuel_count", t.get("still_fuel.count"), fuel.count(), "", t.get("still_fuel.count.hint")));
            slot.add(new Column.Reading("still:fuel_left", t.get("still_fuel.left"), fuel.left(), "min", t.get("still_fuel.left.hint", fuel.heat())));
            rows.add(new Column.Group(t.get("still_group.fuel"), t.get("still_group.fuel.hint"), slot));
        }
        return rows;
    }

    /** A position of a switch: latched where it stands, a click acts on the other while this client edits. */
    private static Column.Button position(String key, String text, String hint, boolean current, boolean canEdit) {
        return new Column.Button(key, text, canEdit && !current, hint, current);
    }
}
