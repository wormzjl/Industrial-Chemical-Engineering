package com.wormzjl.icheme.science.flowsheet;

import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
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
 * results and the sizing read them, and a stream from the network's axis comes onto the view's the same way. The run and the sizing
 * both work on the view ({@link #on}), so no part of the simulator evaluates a compound the sheet does not hold.
 *
 * <p>A sheet drawn on another package, or on one since removed, runs on the network by compound ids: the owner's rule is the
 * network's method and records on the sheet's compounds, whatever package the sheet names. Results stored from other physics
 * show through {@link RevisionNotice}. A feed's compound that the network does not hold is parked, as the file rules park a
 * removed compound: its amount stays in the file, out of the solve, and the run names it ({@code basis.parked}). Minecraft-free.
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

    /**
     * {@code on} with the network package's view of {@code sheet}'s compounds on {@code catalog}, inside the view's runtime. What
     * {@code on} gives back is on the view's axis: {@link #up} puts a run back on the network's.
     */
    public static <T> T on(Flowsheet sheet, MaterialCatalog catalog, Runnable checkpoint, Function<SheetCompounds, T> on) {
        String network = FluidMaterialCatalog.networkPackage(catalog);
        var view = catalog.restrict(network, of(sheet, catalog));
        var whole = view == catalog ? null : MaterialRuntime.with(catalog, network, () -> FluidThermodynamics.forNetwork(catalog, network));
        return MaterialRuntime.with(view, network, () -> on.apply(new SheetCompounds(new FlowThermo(FluidThermodynamics.forNetwork(view, network), view, network, checkpoint), whole)));
    }

    /** {@link #on(Flowsheet, MaterialCatalog, Runnable, Function)} from {@code network}, the network package's thermo, whose model it keeps. */
    public static <T> T on(Flowsheet sheet, FlowThermo network, Function<SheetCompounds, T> on) {
        var catalog = network.catalog();
        String id = network.packageId();
        var view = catalog.restrict(id, of(sheet, catalog));
        if (view == catalog) return MaterialRuntime.with(catalog, id, () -> on.apply(new SheetCompounds(network, null)));
        return MaterialRuntime.with(view, id, () -> on.apply(new SheetCompounds(new FlowThermo(FluidThermodynamics.forNetwork(view, id), view, id, network.checkpoint()), network.model())));
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

    /** The stream thermo on the view. */
    public FlowThermo thermo() {
        return thermo;
    }

    /** {@code run} on the network's axis. */
    public FlowsheetRun up(FlowsheetRun run) {
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
        double[][] slots = new double[3][n];
        for (int slot = 0; slot < 3; slot++) {
            var held = state.amountsView(slot);
            for (int i = 0; i < held.length; i++) slots[slot][up[i]] = held[i];
        }
        var on = network.adoptingState(state.temperature(), state.pressure(), slots[0], slots[1], slots[2], null).withSolids(state.solids());
        return new FlowStream(stream.temperature(), stream.pressure(), placed, stream.enthalpy(), stream.mass(), on);
    }

    /** {@code run}'s streams on the view's axis, without those that hold a compound it lacks; null stays null. */
    public @Nullable FlowsheetRun down(@Nullable FlowsheetRun run) {
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
        double[][] slots = new double[3][up.length];
        for (int slot = 0; slot < 3; slot++) {
            var held = state.amountsView(slot);
            for (int i = 0; i < up.length; i++) slots[slot][i] = held[up[i]];
        }
        try {
            var on = thermo.model().adoptingState(state.temperature(), state.pressure(), slots[0], slots[1], slots[2], null).withSolids(state.solids());
            return new FlowStream(stream.temperature(), stream.pressure(), mine, stream.enthalpy(), stream.mass(), on);
        } catch (CancellationException stop) {
            throw stop;
        } catch (RuntimeException noState) {
            return null;
        }
    }
}
