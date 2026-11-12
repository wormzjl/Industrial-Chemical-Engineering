// probe-on3 (armed at a hold) plus a dump of the held interval's input graph (base64 of the codec's topology and state), for an offline replay.
// Undo: git checkout -- PassiveStepSolver.java PassiveIntervalSolver.java FluidCheckpointCodec.java.
const fs=require('fs'),cp=require('child_process');
cp.execFileSync('node',['tools/aqueous-solvents/drum-hold/probe-on3.js'],{stdio:'inherit'});
let p='src/main/java/com/wormzjl/icheme/runtime/fluid/FluidCheckpointCodec.java';let s=fs.readFileSync(p,'utf8');
let rep=(a,b)=>{if(s.split(a).length!==2)throw new Error('site '+a.slice(0,70)+' '+(s.split(a).length-1));s=s.replace(a,b);};
rep('    private static void topology(Writer w,PassiveNetwork graph) {','    public static byte[] probeGraph(PassiveNetwork graph){var w=new Writer();topology(w,graph);state(w,graph);return w.toByteArray();}\n    public static PassiveNetwork probeGraph(byte[] data,FluidThermodynamics model){var r=new Reader(data,0,data.length,"probe graph");return state(r,topology(r),model);}\n    private static void topology(Writer w,PassiveNetwork graph) {');
fs.writeFileSync(p,s);
p='src/main/java/com/wormzjl/icheme/science/fluid/network/PassiveIntervalSolver.java';s=fs.readFileSync(p,'utf8');
rep('PassiveStepSolver.PROBE=true;System.out.println("PROBE_RERUN first: "+held.getMessage());','PassiveStepSolver.PROBE=true;System.out.println("PROBE_RERUN first: "+held.getMessage());try{System.out.println("PROBE_GRAPH duration="+duration+" startingStep="+startingStep+" settings="+settings+" compounds="+model.compounds()+" wet="+model.wet()+" reads="+reads.size()+" ports="+ports.size()+" data="+java.util.Base64.getEncoder().encodeToString(com.wormzjl.icheme.runtime.fluid.FluidCheckpointCodec.probeGraph(initial)));}catch(RuntimeException dump){System.out.println("PROBE_GRAPH failed: "+dump);}');
fs.writeFileSync(p,s);
