package com.wormzjl.icheme.science.flowsheet;

import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jetbrains.annotations.Nullable;

/**
 * What a client's data have to do with the server's (batch 2026-11-02-process-simulator, plan sections 3.4 and 6). A client on a dedicated
 * server runs on the bundled catalog, not the server's datapacks. A token is a package's {@link MaterialCatalog#fluidRevision}: the identity
 * that moves with any record a run reads, where the package's own revision moves only with the package record. A mismatch is a notice,
 * and blocks nothing. Pure, and Minecraft-free.
 */
public final class RevisionNotice {
    private RevisionNotice() {
    }

    /** Every package's token, as a client or server states them. */
    public static Map<String, String> tokens(MaterialCatalog catalog) {
        var tokens = new TreeMap<String, String>();
        for (var pack : catalog.packages().keySet()) tokens.put(pack, catalog.fluidRevision(pack));
        return tokens;
    }

    /**
     * The token of what {@code sheet} runs on, what its results stand on: the network package's view of the sheet's compounds
     * ({@link SheetCompounds}), for its streams and its columns alike. The view's identity names only its compounds' records, so an
     * edit to another compound's data leaves the sheet's results and its columns' warm starts standing.
     */
    public static Map<String, String> tokens(Flowsheet sheet, MaterialCatalog catalog) {
        String network = FluidMaterialCatalog.networkPackage(catalog);
        return Map.of(network, catalog.restrict(network, SheetCompounds.of(sheet, catalog)).fluidRevision(network));
    }

    /** The packages whose data differ between client and server; none with no word from a server, which is what a single player has. */
    public static List<String> differing(Map<String, String> client, @Nullable Map<String, String> server) {
        var differ = new ArrayList<String>();
        if (server == null) return differ;
        var packs = new TreeSet<>(client.keySet());
        packs.addAll(server.keySet());
        for (var pack : packs) if (!Objects.equals(client.get(pack), server.get(pack))) differ.add(pack);
        return differ;
    }

    /** Whether results that stood on {@code ranOn} go: a package they name has moved, or is gone. The next run rebuilds them. */
    public static boolean dropsResults(Map<String, String> ranOn, Map<String, String> now) {
        for (var token : ranOn.entrySet()) if (!token.getValue().equals(now.get(token.getKey()))) return true;
        return false;
    }
}
