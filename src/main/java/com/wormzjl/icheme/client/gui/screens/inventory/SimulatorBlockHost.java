package com.wormzjl.icheme.client.gui.screens.inventory;

import com.wormzjl.icheme.client.gui.flowsheet.SimulatorHost;
import com.wormzjl.icheme.client.gui.flowsheet.SimulatorRunner;
import com.wormzjl.icheme.network.SimulatorResults;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFailure;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFile;
import com.wormzjl.icheme.science.flowsheet.RevisionNotice;
import com.wormzjl.icheme.science.flowsheet.SheetBody;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * A screen open on a block (batch 2026-11-02-process-simulator, plan section 3.4): a {@link SimulatorRunner} for the run, and the block for the store. The client sends the sheet on Save, when a
 * run ends and when the screen closes, never on an edit, and sends nothing while it only reads. A body that is on its way counts as read only: the first edit waits for what the block holds.
 * A sheet this client cannot read leaves the block as it is, so a close does not overwrite it.
 */
public final class SimulatorBlockHost implements SimulatorHost {
    private final SimulatorRunner runner;
    private final Supplier<MaterialCatalog> catalogs;
    private final boolean reader;
    private final @Nullable String holder;
    private final Map<String, String> tokens;
    private final Consumer<byte[]> upload;
    private boolean waiting, unreadable;
    private @Nullable Said said;
    /** What the block last took, or what it replayed: the sheet and the run. */
    private @Nullable Flowsheet stored;
    private @Nullable Finished shipped;

    /**
     * @param reader  whether this player only reads
     * @param holder  who edits, for a reader
     * @param waiting whether a body is on its way
     * @param tokens  the server's revision tokens
     * @param upload  sends a packed body
     */
    public SimulatorBlockHost(Supplier<MaterialCatalog> catalogs, boolean reader, @Nullable String holder, boolean waiting, Map<String, String> tokens, Consumer<byte[]> upload) {
        this.catalogs = catalogs;
        runner = new SimulatorRunner(catalogs);
        this.reader = reader;
        this.holder = holder;
        this.waiting = waiting;
        this.tokens = tokens;
        this.upload = upload;
    }

    @Override public SimulatorRunner runner() {
        return runner;
    }

    @Override public double joulesPerFE() {
        return com.wormzjl.icheme.IChemE.joulesPerFE();
    }

    @Override public boolean canRun() {
        return !reader();
    }

    @Override public boolean stores() {
        return true;
    }

    @Override public boolean reader() {
        return reader || waiting || unreadable;
    }

    @Override public @Nullable String holder() {
        return reader ? holder : null;
    }

    @Override public Map<String, String> serverRevisions() {
        return tokens;
    }

    @Override public @Nullable Said said() {
        return said;
    }

    @Override public void say(@Nullable Said what) {
        said = what;
    }

    /** The sheet the block sees as this screen's starting point, before any body is asked of it. */
    public void opened(Flowsheet sheet) {
        stored = sheet;
    }

    /** Whether {@code sheet} differs from what the block holds. */
    public boolean unsaved(Flowsheet sheet) {
        return !reader() && sheet != stored;
    }

    /**
     * What the block replayed: its sheet, or null if this client cannot read it. Its results stand on show as the last run unless they are damaged or a package they name has moved; the next
     * run rebuilds them, and starts cold, since a warm start is no part of them.
     */
    public @Nullable Flowsheet replayed(byte[] packed, FluidThermodynamics model) {
        waiting = false;
        Flowsheet sheet;
        SheetBody.Contents contents;
        try {
            contents = SheetBody.unpack(packed);
            sheet = FlowsheetFile.read(contents.sheet());
        } catch (SheetBody.Refused | FlowsheetFailure refused) {
            unreadable = true;
            said = new Said("store.unreadable", List.of(), true);
            return null;
        }
        stored = sheet;
        var results = SimulatorResults.read(contents.results(), sheet, model);
        if (results != null && RevisionNotice.dropsResults(results.ranOn(), RevisionNotice.tokens(sheet, catalogs.get()))) {
            results = null;
            said = new Said("store.results_dropped", List.of(), false);
        }
        if (results != null) {
            runner.restore(results);
            shipped = results;
        }
        return sheet;
    }

    /** Sends {@code sheet} with the last run's results as far as they fit. Over the bound the block keeps what it has, and the player hears why. */
    @Override public void store(Flowsheet sheet) {
        if (reader()) return;
        byte[] bytes = FlowsheetFile.write(sheet);
        if (!SheetBody.fits(bytes.length, 0)) {
            said = new Said("store.too_large", List.of(), true);
            return;
        }
        var last = runner.last();
        upload.accept(SheetBody.pack(bytes, SimulatorResults.fitted(last, sheet, bytes)));
        stored = sheet;
        shipped = last;
    }

    /** A run has ended since the block last heard, or the screen is closing with something new: the block takes it. */
    public void settle(Flowsheet sheet) {
        if (runner.last() != shipped || unsaved(sheet)) store(sheet);
    }

    /** A run ended, so the block takes its results. An edit alone waits for Save or the close. */
    public void ranOut(Flowsheet sheet) {
        if (running() == null && runner.last() != shipped) store(sheet);
    }
}
