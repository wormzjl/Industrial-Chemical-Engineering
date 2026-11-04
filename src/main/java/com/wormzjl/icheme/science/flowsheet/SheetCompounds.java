package com.wormzjl.icheme.science.flowsheet;

import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.jetbrains.annotations.Nullable;

/**
 * The compounds a sheet runs on (batch 2026-11-04-refinery-compounds, W3; owner 2026-11-04: "The process simulator runs each sheet
 * on its own compound list, with the network's method and records"). Nothing reacts on a sheet, so a compound that no feed brings
 * stays at exactly zero everywhere on it. A sheet's list is therefore the network package's compounds that its feeds hold with a
 * non-zero amount, in the package's order: a feed's amounts by id or alias, a crystal it carries by its compound, and a line's first
 * estimate, which is a feed of its loop's first pass. Water is no package compound: every axis holds it last, as on the network. A
 * sheet that holds no network compound runs on Nitrogen, the network's placeholder, at zero. The list is derived from the feeds
 * whenever a run needs it, never stored: it grows when a feed gains a compound and shrinks when no feed holds one any more.
 *
 * <p>A run solves on the network package's view of the list ({@link MaterialCatalog#restrict}): the same method and records, the
 * pairs among its compounds, the package's ranges. Every mixing sum runs over the compounds present, so its numbers equal the
 * package's for every state that holds nothing else. Its streams go back onto the network's axis by id, where the screens, the stored
 * results and the sizing read them, and a stream from the network's axis comes onto the view's the same way. Minecraft-free.
 */
public final class SheetCompounds {
    private final FlowThermo thermo;
    /** The network package's model, or null where the view is that package itself. */
    private final @Nullable FluidThermodynamics network;
    /** Each view place's network place, and each network place's view place or -1; water last on both. */
    private final int[] up, down;

    private SheetCompounds(FlowThermo thermo, @Nullable FluidThermodynamics network) {
        this.thermo = thermo;
        this.network = network;
        var axis = thermo.axis();
        up = new int[axis.size()];
        down = new int[network == null ? axis.size() : network.componentCount()];
        Arrays.fill(down, -1);
        for (int i = 0; i < up.length; i++) {
            up[i] = network == null ? i : network.components().indexOf(axis.get(i));
            down[up[i]] = i;
        }
    }

    /** The view {@code thermo} runs on, beside the network package's {@code network} model, or null where it is that package. */
    static SheetCompounds of(FlowThermo thermo, @Nullable FluidThermodynamics network) {
        return new SheetCompounds(thermo, network);
    }

    /** The compounds {@code sheet} runs on, in the network package's order of {@code catalog}. */
    public static List<String> of(Flowsheet sheet, MaterialCatalog catalog) {
        var pack = catalog.requirePackage(FluidMaterialCatalog.networkPackage(catalog));
        var held = new HashSet<String>();
        for (var stream : sheet.streams()) {
            var feed = stream.feed();
            if (feed == null) continue;
            feed.amounts().forEach((id, amount) -> {
                if (amount > 0) held.add(pack.canonicalId(id));
            });
            for (var solid : feed.solids()) {
                var material = catalog.solids().materials().get(solid.material());
                if (solid.massFlow() > 0 && material != null && material.crystal() != null) held.add(material.crystal().component());
            }
        }
        var list = pack.components().stream().filter(held::contains).toList();
        return list.isEmpty() ? List.of(FluidMaterialCatalog.NITROGEN) : list;
    }

    /** {@link #refusal(Flowsheet, MaterialCatalog, List)} on the compounds {@code sheet} runs on. */
    public static @Nullable Note refusal(Flowsheet sheet, MaterialCatalog catalog) {
        return refusal(sheet, catalog, of(sheet, catalog));
    }

    /**
     * Why {@code sheet} cannot run on {@code catalog}'s network, or null. A sheet drawn on another package runs where that package
     * gives {@code compounds} the network's physics, compared by content ({@link MaterialCatalog#physicsContent}): the network's
     * records then answer every state it can reach as its own did. One whose package is gone, or holds other numbers for them, asks
     * for it.
     */
    public static @Nullable Note refusal(Flowsheet sheet, MaterialCatalog catalog, List<String> compounds) {
        String drawn = sheet.basis().packageId(), world = FluidMaterialCatalog.networkPackage(catalog);
        if (drawn.equals(world)) return null;
        var pack = catalog.packages().get(drawn);
        if (pack != null && pack.components().containsAll(compounds) && catalog.physicsContent(drawn, compounds).equals(catalog.physicsContent(world, compounds))) return null;
        return Note.error("sheet", "basis.package", drawn, world);
    }

    /** The stream thermo on the view. */
    FlowThermo thermo() {
        return thermo;
    }

    /** {@code run} on the network's axis. */
    FlowsheetRun up(FlowsheetRun run) {
        if (network == null) return run;
        var streams = new HashMap<String, FlowStream>();
        run.streams().forEach((id, stream) -> streams.put(id, up(stream)));
        return run.on(streams);
    }

    private FlowStream up(FlowStream stream) {
        var amounts = stream.amountsView();
        var state = stream.state();
        int n = network.componentCount();
        double[] placed = new double[n];
        for (int i = 0; i < amounts.length; i++) placed[up[i]] = amounts[i];
        if (state == null) return new FlowStream(stream.temperature(), stream.pressure(), placed, stream.enthalpy(), stream.mass(), null);
        double[] liquid = new double[n - 1], vapor = new double[n - 1], l = state.liquidView(), v = state.vaporView();
        for (int i = 0; i < l.length; i++) {
            liquid[up[i]] = l[i];
            vapor[up[i]] = v[i];
        }
        var on = network.state(state.temperature(), state.pressure(), liquid, vapor, state.waterLiquid(), state.waterVapor(), state.hydrocarbonPartialPressure()).withSolids(state.solids());
        return new FlowStream(stream.temperature(), stream.pressure(), placed, stream.enthalpy(), stream.mass(), on);
    }

    /** {@code run}'s streams on the view's axis, without those that hold a compound it lacks; null stays null. */
    @Nullable FlowsheetRun down(@Nullable FlowsheetRun run) {
        return run == null ? null : run.on(down(run.streams()));
    }

    /** {@code streams} on the view's axis, without those that hold a compound it lacks or that it cannot state. */
    Map<String, FlowStream> down(Map<String, FlowStream> streams) {
        var out = new HashMap<String, FlowStream>();
        streams.forEach((id, stream) -> {
            var mine = down(stream);
            if (mine != null) out.put(id, mine);
        });
        return out;
    }

    private @Nullable FlowStream down(FlowStream stream) {
        var amounts = stream.amountsView();
        if (amounts.length != down.length) return null;
        if (network == null) return stream;
        for (int j = 0; j < amounts.length; j++) if (amounts[j] != 0 && down[j] < 0) return null;
        double[] mine = new double[up.length];
        for (int i = 0; i < up.length; i++) mine[i] = amounts[up[i]];
        var state = stream.state();
        if (state == null) return new FlowStream(stream.temperature(), stream.pressure(), mine, stream.enthalpy(), stream.mass(), null);
        double[] liquid = new double[up.length - 1], vapor = new double[up.length - 1], l = state.liquidView(), v = state.vaporView();
        for (int i = 0; i < liquid.length; i++) {
            liquid[i] = l[up[i]];
            vapor[i] = v[up[i]];
        }
        try {
            var on = thermo.model().state(state.temperature(), state.pressure(), liquid, vapor, state.waterLiquid(), state.waterVapor(), state.hydrocarbonPartialPressure()).withSolids(state.solids());
            return new FlowStream(stream.temperature(), stream.pressure(), mine, stream.enthalpy(), stream.mass(), on);
        } catch (CancellationException stop) {
            throw stop;
        } catch (RuntimeException noState) {
            return null;
        }
    }
}
