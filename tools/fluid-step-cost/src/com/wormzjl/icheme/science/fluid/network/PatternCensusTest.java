package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * The Jacobian pattern census of the fluid step (S0 of batch 2026-11-15-fluid-step-cost, FLUID_STEP_COST_PLAN.md). Not a
 * gate: attached to the test source set only through {@code tools/fluid-step-cost/census.init.gradle}, beside the bundled
 * hold-fuzz harness, whose worlds it runs in this JVM ({@code HoldFuzzTest.placementSequenceFuzz}, the harness's own
 * entry, so the seeds, threads and slices are its {@code holdFuzz.*} properties).
 *
 * <p>It installs a {@link SolverDiagnostics.PatternObserver} for the run. For every Newton factorization whose equations are
 * a {@link StepEquations} it classifies each declared entry by row kind, column kind and relation (read off the pass's
 * layout: {@code layout}, {@code offsets}, {@code edgeOffset}, {@code controlOffsets}, {@code filterOffsets},
 * {@code weirOffsets}, {@code nodeEdges}, {@code remoteSuctions}, the graph, and {@link PhaseLayout}'s order of a node
 * block), and records per pattern (the identity of the {@code offsets} array; the first {@value Census#TRACKED} patterns)
 * whether the entry was ever zero, ever nonzero, and the largest {@code |value| / max |value| of its row} over its nonzero
 * sightings. A directional relation (the other node of a connection, a connection row's end) is decided per
 * factorization by the sign of the connection's law flow in the iterate, so an entry is tracked once per direction it was
 * seen in. Other equations (a vessel's closure, a junction's split, a crystal's closure) are counted by class name.</p>
 *
 * <p>The observer only reads the arrays it is handed and copies nothing into the solve; a failure inside it is counted
 * and swallowed, so the census cannot change a solve. System property {@code census.out}: the report file.</p>
 */
class PatternCensusTest {
    enum RowKind {
        COMPONENT_BALANCE("vessel component balance"),ENERGY("energy"),WALL("wall"),EQUILIBRIUM("equilibrium"),
        JUNCTION_MIXING("junction mixing"),CONTINUITY("continuity"),JUNCTION_ENTHALPY("junction enthalpy"),
        NORMALIZATION("normalization"),SOLIDS("solids"),CONNECTION_LAW("connection law"),ACTUATOR("actuator"),
        FILTER_LOADING("filter loading"),WEIR("weir"),UNKNOWN("unclassified");
        final String label;RowKind(String label){this.label=label;}
    }
    enum ColumnKind {
        AMOUNT("amount"),PHASE_SPLIT("phase split"),TEMPERATURE("temperature"),PRESSURE("pressure"),
        SOLID_MOMENT("solid moment"),LAW_FLOW("law flow"),HEAD("head"),FILTER_LOADING("filter loading"),
        WEIR_FLOW("weir flow"),UNKNOWN("unclassified");
        final String label;ColumnKind(String label){this.label=label;}
    }
    /** The three directional pairs are in the order upstream, downstream, no flow (their slot, {@link #slot}). */
    enum Relation {
        OWN_BLOCK("own block"),
        NEIGHBOUR_UPSTREAM("other node, column's node upstream"),NEIGHBOUR_DOWNSTREAM("other node, column's node downstream"),
        NEIGHBOUR_NO_FLOW("other node, zero law flow"),
        END_UPSTREAM("connection row, column's node upstream"),END_DOWNSTREAM("connection row, column's node downstream"),
        END_NO_FLOW("connection row, zero law flow"),
        CONNECTION_OWN("connection's own column"),REMOTE_SUPPLIER("remote suction's supplier"),UNKNOWN("unclassified");
        final String label;Relation(String label){this.label=label;}
    }
    static final RowKind[] ROWS=RowKind.values();static final ColumnKind[] COLUMNS=ColumnKind.values();static final Relation[] RELATIONS=Relation.values();
    static final int CODES=ROWS.length*COLUMNS.length*RELATIONS.length;
    static int code(RowKind row,ColumnKind column,Relation relation){return (row.ordinal()*COLUMNS.length+column.ordinal())*RELATIONS.length+relation.ordinal();}
    static RowKind rowOf(int code){return ROWS[code/(COLUMNS.length*RELATIONS.length)];}
    static ColumnKind columnOf(int code){return COLUMNS[code/RELATIONS.length%COLUMNS.length];}
    static Relation relationOf(int code){return RELATIONS[code%RELATIONS.length];}
    /** The direction slot of a relation: 0 upstream (and every relation without a direction), 1 downstream, 2 no flow. */
    static int slot(Relation relation) {
        return switch(relation) {
            case NEIGHBOUR_DOWNSTREAM,END_DOWNSTREAM->1;
            case NEIGHBOUR_NO_FLOW,END_NO_FLOW->2;
            default->0;
        };
    }

    /** The kinds and owners of one pass's rows and columns, a function of its {@link StepEquations} alone. */
    static final class Structure {
        final StepEquations equations;final int size;
        final RowKind[] rowKind;final ColumnKind[] columnKind;
        /** The node a row or column belongs to, or -1; the connection, or -1. */
        final int[] rowNode,rowEdge,columnNode,columnEdge;
        Structure(StepEquations e) {
            equations=e;size=e.size;
            rowKind=new RowKind[size];columnKind=new ColumnKind[size];Arrays.fill(rowKind,RowKind.UNKNOWN);Arrays.fill(columnKind,ColumnKind.UNKNOWN);
            rowNode=new int[size];rowEdge=new int[size];columnNode=new int[size];columnEdge=new int[size];
            Arrays.fill(rowNode,-1);Arrays.fill(rowEdge,-1);Arrays.fill(columnNode,-1);Arrays.fill(columnEdge,-1);
            for(int node=0;node<e.layout.length;node++) {
                PhaseLayout layout=e.layout[node];if(layout==null)continue;
                int base=e.offsets[node],n=layout.size(),components=layout.componentBalanceCount();
                int pressure=layout.pressureVariable(),temperature=pressure-1;
                boolean solids=layout.solidVariable(n-1),junction=e.graph.reservoirs().get(node).junction();
                for(int local=0;local<n;local++) {
                    int index=base+local;rowNode[index]=node;columnNode[index]=node;
                    columnKind[index]=layout.solidVariable(local)?ColumnKind.SOLID_MOMENT:layout.totalAmountVariable(local)?ColumnKind.AMOUNT
                            :local==temperature?ColumnKind.TEMPERATURE:local==pressure?ColumnKind.PRESSURE:ColumnKind.PHASE_SPLIT;
                    boolean solidRow=solids&&local>=components+2&&local<components+5;
                    if(!junction) {
                        rowKind[index]=local<components?RowKind.COMPONENT_BALANCE:local==components?RowKind.ENERGY:local==components+1?RowKind.WALL
                                :solidRow?RowKind.SOLIDS:RowKind.EQUILIBRIUM;
                    } else {
                        // PhaseLayout.junctionRows: M-1 mixing rows, continuity, enthalpy, normalization, the solids, then
                        // the equilibrium rows.
                        int mixing=Math.max(0,components-1);
                        rowKind[index]=local<mixing?RowKind.JUNCTION_MIXING:local==mixing?RowKind.CONTINUITY:local==mixing+1?RowKind.JUNCTION_ENTHALPY
                                :local==mixing+2?RowKind.NORMALIZATION:solidRow?RowKind.SOLIDS:RowKind.EQUILIBRIUM;
                    }
                }
            }
            int edges=e.graph.pipes().size();
            for(int edge=0;edge<edges;edge++) {
                own(e.edgeOffset+edge,edge,RowKind.CONNECTION_LAW,ColumnKind.LAW_FLOW);
                if(e.controlOffsets[edge]>=0)own(e.controlOffsets[edge],edge,RowKind.ACTUATOR,ColumnKind.HEAD);
                if(e.filterOffsets[edge]>=0)own(e.filterOffsets[edge],edge,RowKind.FILTER_LOADING,ColumnKind.FILTER_LOADING);
                for(int end=0;end<2;end++)if(e.weirOffsets[edge][end]>=0)own(e.weirOffsets[edge][end],edge,RowKind.WEIR,ColumnKind.WEIR_FLOW);
            }
        }
        private void own(int index,int edge,RowKind row,ColumnKind column){rowEdge[index]=edge;columnEdge[index]=edge;rowKind[index]=row;columnKind[index]=column;}
        private boolean end(int edge,int node){var pipe=equations.graph.pipes().get(edge);return pipe.first()==node||pipe.second()==node;}
        /** 0 where {@code node} is the connection's upstream end at the iterate's law flow, 1 downstream, 2 at zero flow. */
        private int direction(int edge,int node,double[] x) {
            double q=x[equations.edgeOffset+edge];if(q==0)return 2;
            var pipe=equations.graph.pipes().get(edge);
            return (q>0?pipe.first():pipe.second())==node?0:1;
        }
        /** The first incident connection of {@code from} (ascending, {@code nodeEdges}) whose other end is {@code to}. */
        private int joining(int from,int to) {
            for(int edge:equations.nodeEdges[from]){var pipe=equations.graph.pipes().get(edge);if(pipe.first()==to&&pipe.second()==from||pipe.second()==to&&pipe.first()==from)return edge;}
            return -1;
        }
        Relation relation(int row,int column,double[] x) {
            int rn=rowNode[row],re=rowEdge[row],cn=columnNode[column],ce=columnEdge[column];
            if(ce>=0)return re==ce||rn>=0&&end(ce,rn)?Relation.CONNECTION_OWN:Relation.UNKNOWN;
            if(cn<0)return Relation.UNKNOWN;
            if(rn>=0) {
                if(rn==cn)return Relation.OWN_BLOCK;
                int edge=joining(rn,cn);if(edge<0)return Relation.UNKNOWN;
                return RELATIONS[Relation.NEIGHBOUR_UPSTREAM.ordinal()+direction(edge,cn,x)];
            }
            if(re<0)return Relation.UNKNOWN;
            if(end(re,cn))return RELATIONS[Relation.END_UPSTREAM.ordinal()+direction(re,cn,x)];
            if(rowKind[row]==RowKind.ACTUATOR)for(int edge:equations.remoteSuctions[cn])if(edge==re)return Relation.REMOTE_SUPPLIER;
            return Relation.UNKNOWN;
        }
    }

    /** One tracked pattern: per declared entry and direction slot ({@code 3 entry + slot}), its class code (-1 unseen),
     * whether it was seen zero (1) and nonzero (2), and its largest row ratio over the nonzero sightings. */
    static final class PatternStats {
        final int[] code;final byte[] seen;final double[] largest;
        PatternStats(int entries){code=new int[3*entries];Arrays.fill(code,-1);seen=new byte[3*entries];largest=new double[3*entries];}
    }

    /** Per-thread scratch: the last pass's structure and the per-factorization arrays. */
    static final class Scratch {
        Structure structure;double[] rowMaximum=new double[0];int[] codes=new int[0];byte[] slots=new byte[0];
        final long[] sightings=new long[CODES],kept=new long[CODES];
        Structure of(StepEquations equations){if(structure==null||structure.equations!=equations)structure=new Structure(equations);return structure;}
    }

    static final class Census implements SolverDiagnostics.PatternObserver {
        static final int TRACKED=256;
        private final ThreadLocal<Scratch> scratch=ThreadLocal.withInitial(Scratch::new);
        // Guarded by this.
        private final Map<int[],PatternStats> patterns=new IdentityHashMap<>();
        private final Set<Integer> untrackedPatterns=new HashSet<>();
        private long untrackedFactorizations,factorizations,declared,kept,mismatches,failures;private String firstFailure;
        private final long[] sightings=new long[CODES],keptSightings=new long[CODES];
        private final Map<String,long[]> other=new TreeMap<>();

        @Override public void factored(Object equations,double[] x,int[] offsets,int[] rows,double[] values) {
            try{observe(equations,x,offsets,rows,values);}
            catch(RuntimeException failure) {
                synchronized(this){failures++;if(firstFailure==null)firstFailure=failure.toString();}
            }
        }
        private void observe(Object equations,double[] x,int[] offsets,int[] rows,double[] values) {
            int entries=rows.length;
            if(!(equations instanceof StepEquations step)) {
                long nonzero=0;for(int e=0;e<entries;e++)if(values[e]!=0)nonzero++;
                synchronized(this){var counts=other.computeIfAbsent(equations.getClass().getName(),k->new long[3]);counts[0]++;counts[1]+=entries;counts[2]+=nonzero;}
                return;
            }
            var local=scratch.get();var structure=local.of(step);int size=structure.size;
            if(local.rowMaximum.length<size)local.rowMaximum=new double[size];
            if(local.codes.length<entries){local.codes=new int[entries];local.slots=new byte[entries];}
            double[] rowMaximum=local.rowMaximum;int[] codes=local.codes;byte[] slots=local.slots;
            Arrays.fill(rowMaximum,0,size,0);Arrays.fill(local.sightings,0);Arrays.fill(local.kept,0);
            for(int e=0;e<entries;e++){double magnitude=Math.abs(values[e]);if(magnitude>rowMaximum[rows[e]])rowMaximum[rows[e]]=magnitude;}
            long nonzero=0;
            for(int column=0;column<offsets.length-1;column++)for(int e=offsets[column];e<offsets[column+1];e++) {
                int row=rows[e];var relation=structure.relation(row,column,x);
                int code=code(structure.rowKind[row],structure.columnKind[column],relation);
                codes[e]=code;slots[e]=(byte)slot(relation);local.sightings[code]++;
                if(values[e]!=0){nonzero++;local.kept[code]++;}
            }
            PatternStats stats;
            synchronized(this) {
                factorizations++;declared+=entries;kept+=nonzero;
                for(int c=0;c<CODES;c++){sightings[c]+=local.sightings[c];keptSightings[c]+=local.kept[c];}
                stats=patterns.get(offsets);
                if(stats==null) {
                    if(patterns.size()<TRACKED)patterns.put(offsets,stats=new PatternStats(entries));
                    else{untrackedFactorizations++;untrackedPatterns.add(System.identityHashCode(offsets));}
                }
            }
            if(stats==null)return;
            long mismatched=0;
            synchronized(stats) {
                for(int e=0;e<entries;e++) {
                    int index=3*e+slots[e];
                    if(stats.code[index]<0)stats.code[index]=codes[e];else if(stats.code[index]!=codes[e])mismatched++;
                    double value=values[e];
                    if(value==0){stats.seen[index]|=1;continue;}
                    stats.seen[index]|=2;
                    double ratio=Math.abs(value)/rowMaximum[rows[e]];
                    if(Double.isFinite(ratio)&&ratio>stats.largest[index])stats.largest[index]=ratio;
                }
            }
            if(mismatched>0)synchronized(this){mismatches+=mismatched;}
        }

        /** Per class: declared, never kept, always kept, flipping, the four ratio bins of the flipping ones, sightings, kept sightings. */
        private static final int FIELDS=10;
        private static int bin(double ratio){return ratio<1e-12?0:ratio<1e-9?1:ratio<1e-6?2:3;}
        synchronized String report(String run) {
            long[][] byCode=new long[CODES][FIELDS];long distinct=0;
            for(var stats:patterns.values())for(int index=0;index<stats.code.length;index++) {
                int code=stats.code[index];if(code<0)continue;
                var cell=byCode[code];cell[0]++;distinct++;
                switch(stats.seen[index]) {
                    case 1->cell[1]++;
                    case 2->cell[2]++;
                    case 3->{cell[3]++;cell[4+bin(stats.largest[index])]++;}
                    default->{}
                }
            }
            for(int code=0;code<CODES;code++){byCode[code][8]=sightings[code];byCode[code][9]=keptSightings[code];}
            var b=new StringBuilder();
            b.append("# Fluid step Jacobian pattern census (tools/fluid-step-cost, batch 2026-11-15-fluid-step-cost)\n\n");
            b.append(run).append('\n');
            double perFactorization=factorizations>0?1.0/factorizations:0;
            b.append(String.format(Locale.ROOT,"TOTALS step factorizations=%d declared=%d (mean %.1f) kept=%d (mean %.1f, share %.4f)\n",
                    factorizations,declared,declared*perFactorization,kept,kept*perFactorization,declared>0?kept/(double)declared:0));
            b.append(String.format(Locale.ROOT,"TOTALS patterns tracked=%d (tracked entry-directions %d) untracked factorizations=%d untracked patterns (identity hashes)=%d class mismatches=%d observer failures=%d%s\n",
                    patterns.size(),distinct,untrackedFactorizations,untrackedPatterns.size(),mismatches,failures,firstFailure==null?"":" first="+firstFailure));
            long unclassified=0;for(int code=0;code<CODES;code++)if(rowOf(code)==RowKind.UNKNOWN||columnOf(code)==ColumnKind.UNKNOWN||relationOf(code)==Relation.UNKNOWN)unclassified+=sightings[code];
            b.append("TOTALS unclassified sightings=").append(unclassified).append("\n\n");
            b.append("Declared, never kept, always kept and flipping count each entry of a tracked pattern once per direction it was seen in\n");
            b.append("(over the whole run); the four bins hold the flipping ones by their largest |value| / max |value| of the row.\n");
            b.append("Sightings and kept count every factorization, tracked patterns or not.\n\n");
            String header="| declared | never kept | always kept | flipping | flip <1e-12 | 1e-12..1e-9 | 1e-9..1e-6 | >=1e-6 | sightings | kept sightings | kept share |\n";
            String rule="|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n";
            b.append("## By class\n\n| row kind | column kind | relation ").append(header).append("|---|---|---").append(rule);
            for(int code=0;code<CODES;code++) {
                var cell=byCode[code];if(cell[0]==0&&cell[8]==0)continue;
                b.append("| ").append(rowOf(code).label).append(" | ").append(columnOf(code).label).append(" | ").append(relationOf(code).label).append(' ').append(cells(cell));
            }
            b.append("\n## By relation\n\n| relation ").append(header).append("|---").append(rule);
            for(var relation:RELATIONS) {
                long[] sum=new long[FIELDS];
                for(int code=0;code<CODES;code++)if(relationOf(code)==relation)for(int f=0;f<FIELDS;f++)sum[f]+=byCode[code][f];
                if(sum[0]==0&&sum[8]==0)continue;
                b.append("| ").append(relation.label).append(' ').append(cells(sum));
            }
            b.append("\n## Other equations (by class)\n\n| class | factorizations | declared | kept | mean declared | mean kept |\n|---|---:|---:|---:|---:|---:|\n");
            other.forEach((name,counts)->b.append(String.format(Locale.ROOT,"| %s | %d | %d | %d | %.1f | %.1f |\n",name,counts[0],counts[1],counts[2],counts[1]/(double)counts[0],counts[2]/(double)counts[0])));
            return b.toString();
        }
        private static String cells(long[] cell) {
            var b=new StringBuilder();
            for(int f=0;f<FIELDS;f++)b.append("| ").append(cell[f]).append(' ');
            return b.append(String.format(Locale.ROOT,"| %.4f |\n",cell[8]>0?cell[9]/(double)cell[8]:0)).toString();
        }
    }

    @Test void census() throws Throwable {
        String out=System.getProperty("census.out","build/fluid-step-cost/census.txt");
        String run="RUN seeds="+System.getProperty("holdFuzz.seeds","1-20")+" threads="+Integer.getInteger("holdFuzz.threads",8)
                +" slices="+Integer.getInteger("holdFuzz.slices",120)+" defaults="+Boolean.getBoolean("holdFuzz.defaults")
                +" holdFuzzReport="+System.getProperty("holdFuzz.out","build/hold-fuzz/report.txt");
        var census=new Census();Throwable failed=null;
        SolverDiagnostics.patternObserver=census;
        try{runHoldFuzz();}
        catch(Throwable failure){failed=failure;}
        finally{SolverDiagnostics.patternObserver=null;}
        var path=Path.of(out);Files.createDirectories(path.toAbsolutePath().getParent());
        String report=census.report(run+(failed==null?"":"\nHOLD-FUZZ FAILED "+failed));
        Files.writeString(path,report,StandardCharsets.UTF_8);
        System.out.println(report.lines().filter(line->line.startsWith("RUN")||line.startsWith("TOTALS")).reduce("",(a,b)->a+b+"\n"));
        System.out.println("Census report "+path.toAbsolutePath());
        if(failed!=null)throw failed;
    }
    /** The bundled hold-fuzz harness's own entry, in this JVM; it is package-private in another package, so by reflection. */
    private static void runHoldFuzz() throws Throwable {
        var harness=Class.forName("com.wormzjl.icheme.runtime.fluid.HoldFuzzTest");
        var constructor=harness.getDeclaredConstructor();constructor.setAccessible(true);
        var entry=harness.getDeclaredMethod("placementSequenceFuzz");entry.setAccessible(true);
        try{entry.invoke(constructor.newInstance());}
        catch(InvocationTargetException thrown){throw thrown.getCause();}
    }
}
