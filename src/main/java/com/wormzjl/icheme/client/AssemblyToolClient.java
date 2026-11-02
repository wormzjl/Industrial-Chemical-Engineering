package com.wormzjl.icheme.client;

import com.wormzjl.icheme.client.gui.common.AssemblyDiagram;
import com.wormzjl.icheme.client.gui.screens.inventory.AssemblyScreen;
import com.wormzjl.icheme.network.AssemblyNetwork;
import com.wormzjl.icheme.science.assembly.AssemblyReport;
import com.wormzjl.icheme.science.assembly.AssemblyReport.PortReport;
import com.wormzjl.icheme.science.assembly.AssemblyReport.PortState;
import com.wormzjl.icheme.science.assembly.AssemblyReport.SlotReport;
import com.wormzjl.icheme.science.assembly.AssemblyView;
import com.wormzjl.icheme.science.assembly.ColumnBlueprint;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The laying tool's view of the column assemblies around the player (batch 2026-10-26-multi-machine-framework, plan
 * section 9, W7). While the player holds the tool the client watches them ({@link AssemblyNetwork.WatchPayload}): the
 * server sends each column's stored view and its changes on the engine's buckets. A watched port gets a frame around the
 * tool's square: its GUI colour while needed, grey once connected, yellow on a misplaced part's ports. The square itself
 * keeps the GUI colour; aiming at it names its line. Render thread only.
 */
public final class AssemblyToolClient {
    /** The watched columns' views, by master. */
    private static final Map<Long, AssemblyView> VIEWS = new TreeMap<>();
    /** Drawing models only, shared by equal loaded designs; discarded with the watch or its next view. */
    private static final Map<ColumnTrays, AssemblyDiagram> DIAGRAMS = new HashMap<>();
    /** The player who watches, or null: a new player (a respawn, another world) starts a new watch. */
    private static @Nullable LocalPlayer watching;

    private AssemblyToolClient() {
    }

    /** Each tick of the laying tool's client: the watch starts when the player takes the tool up and ends when they put it away. */
    static void watch(@Nullable LocalPlayer player, boolean holding) {
        if (player != null && holding) {
            if (watching == player) return;
            VIEWS.clear();
            DIAGRAMS.clear();
            watching = player;
            PacketDistributor.sendToServer(new AssemblyNetwork.WatchPayload(true));
        } else if (watching != null) {
            boolean connected = player != null && player == watching;
            watching = null;
            VIEWS.clear();
            DIAGRAMS.clear();
            if (connected) PacketDistributor.sendToServer(new AssemblyNetwork.WatchPayload(false));
        }
    }

    /** A watched column's view, or its end ({@code null}). */
    public static void received(long master, @Nullable AssemblyView view) {
        if (watching == null) return;
        if (view == null) VIEWS.remove(master);
        else VIEWS.put(master, view);
        DIAGRAMS.clear();
    }

    /** What a watched report says of a port: its master and report, the slot its unit fills, and the port's own state. */
    private record Seen(long master, AssemblyReport report, SlotReport slot, @Nullable PortReport port, EquipmentShape.Role role, int ordinal) {
    }

    private static @Nullable Seen seen(EquipmentShellBlockEntity shell, Direction face) {
        if (VIEWS.isEmpty()) return null;
        var origin = shell.origin();
        var port = shell.port(face);
        var shape = shell.shape();
        if (origin == null || port == null || shape == null) return null;
        var role = shape.nozzles().get(port.nozzle()).role();
        // A port is its unit's nozzle of that role, counted in nozzle order (AssemblyWalker).
        int ordinal = 0;
        for (int k = 0; k < port.nozzle(); k++) if (shape.nozzles().get(k).role() == role) ordinal++;
        long unit = origin.asLong();
        for (var watched : VIEWS.entrySet()) {
            var report = watched.getValue().report();
            if (report == null) continue;
            var slot = report.slots().stream().filter(s -> s.member() == unit).findFirst().orElse(null);
            if (slot == null) continue;
            int n = ordinal;
            var own = report.ports().stream().filter(p -> p.unit() == unit && p.role() == role && p.ordinal() == n).findFirst().orElse(null);
            return new Seen(watched.getKey(), report, slot, own, role, ordinal);
        }
        return null;
    }

    /**
     * The frame around the tool's square on the port of {@code shell} facing {@code face}: yellow on a misplaced part,
     * grey once connected, its GUI colour while needed (class colour for utilities without a GUI square), or -1 for none.
     */
    static int frame(EquipmentShellBlockEntity shell, Direction face) {
        var seen = seen(shell, face);
        if (seen == null) return -1;
        if (seen.slot.state().colour == AssemblyReport.Colour.YELLOW) return PortColours.MISPLACED;
        if (seen.port != null && seen.port.state() == PortState.CONNECTED) return PortColours.CONNECTED;
        var port = shell.port(face);
        if (seen.port == null || seen.port.state() != PortState.NEEDED && seen.port.state() != PortState.UTILITY || port == null) return -1;
        int colour = colour(shell, face);
        return colour >= 0 ? colour : PortColours.of(port.port());
    }

    /** The GUI colour of this nozzle, or -1 when it has no assembly square. The column's own ports need no report. */
    static int colour(EquipmentShellBlockEntity shell, Direction face) {
        var shape = shell.shape();
        var port = shell.port(face);
        var origin = shell.origin();
        if (shape == null || port == null || origin == null) return -1;
        if (shape.trays() != null && shape.trays().components() != null) {
            var role = shape.nozzles().get(port.nozzle()).role();
            int ordinal = 0;
            for (int k = 0; k < port.nozzle(); k++) if (shape.nozzles().get(k).role() == role) ordinal++;
            return PortColours.assembly(diagram(shape.trays()), ColumnBlueprint.COLUMN, role, ordinal);
        }
        var seen = seen(shell, face);
        var trays = seen == null ? null : trays(seen.master, shell);
        return trays == null || trays.components() == null ? -1 : PortColours.assembly(diagram(trays), seen.slot.slot(), seen.role, seen.ordinal);
    }

    private static AssemblyDiagram diagram(ColumnTrays trays) {
        return DIAGRAMS.computeIfAbsent(trays, AssemblyDiagram::of);
    }

    /**
     * The assembly line of the port of {@code shell} facing {@code face}, for the tool's label: "pipes to the part (its
     * state)"; null when no watched report holds the port, or its design pipes nothing to it. The label names the nozzle
     * already, and a unit is in one report only: the column's coordinates would only push the action bar's one line off
     * the screen.
     */
    static @Nullable Component line(EquipmentShellBlockEntity shell, Direction face) {
        var seen = seen(shell, face);
        var trays = seen == null ? null : trays(seen.master, shell);
        if (trays == null) return null;
        var diagram = diagram(trays);
        var mark = diagram.ports().stream().filter(m -> m.slot().equals(seen.slot.slot()) && m.role() == seen.role && (m.ordinal() < 0 || m.ordinal() == seen.ordinal))
                .findFirst().orElse(null);
        var partner = mark == null ? null : diagram.part(mark.partner());
        if (partner == null) return null;
        var partnerReport = seen.report.slots().stream().filter(s -> s.slot().equals(mark.partner())).findFirst().orElse(null);
        return Component.translatable(AssemblyDiagram.KEY + "tool.line", AssemblyScreen.text(partner.name()), AssemblyScreen.word(partnerReport));
    }

    /** The trays of the column at {@code master}, from a loaded shell cell of it: {@code shell} itself when it is the column's. */
    private static @Nullable ColumnTrays trays(long master, EquipmentShellBlockEntity shell) {
        if (shell.origin() != null && shell.origin().asLong() == master && shell.shape() != null) return shell.shape().trays();
        for (var cell : EquipmentShellBlockEntity.clientPortCells())
            if (!cell.isRemoved() && cell.origin() != null && cell.origin().asLong() == master && cell.shape() != null) return cell.shape().trays();
        return null;
    }
}
