// Adds the probe's graph encoder and decoder to FluidCheckpointCodec (probe only; undo with git checkout -- that file).
const fs=require('fs');const p='src/main/java/com/wormzjl/icheme/runtime/fluid/FluidCheckpointCodec.java';let s=fs.readFileSync(p,'utf8');
if(!s.includes('probeGraph(')){const a='    private static void topology(Writer w,PassiveNetwork graph) {';if(s.split(a).length!==2)throw new Error('site');
s=s.replace(a,'    public static byte[] probeGraph(PassiveNetwork graph){var w=new Writer();topology(w,graph);state(w,graph);return w.toByteArray();}\n    public static PassiveNetwork probeGraph(byte[] data,FluidThermodynamics model){var r=new Reader(data,0,data.length,"probe graph");return state(r,topology(r),model);}\n'+a);fs.writeFileSync(p,s);}
