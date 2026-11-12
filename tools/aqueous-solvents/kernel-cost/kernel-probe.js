// Cost probe of the kernel (tools/aqueous-solvents/kernel-cost/README.md): node tools/aqueous-solvents/kernel-cost/kernel-probe.js on|off
const fs=require('fs');const CR=String.fromCharCode(13),LF=String.fromCharCode(10);
const p='src/main/java/com/wormzjl/icheme/science/thermo/PengRobinsonKernel.java';
const t='src/test/java/com/wormzjl/icheme/science/thermo/KernelCostProbeTest.java';
const raw=fs.readFileSync(p,'utf8');const crlf=raw.includes(CR+LF);let s=raw.split(CR+LF).join(LF);
const A='        this.count = criticalTemperaturesKelvin.length;\n',B=A+'        if (count > 10) PROBE_LARGE = this; // PROBE\n';
const C='    private final int count;\n',D=C+'    public static volatile PengRobinsonKernel PROBE_LARGE; // PROBE\n';
if(process.argv[2]==='on'){
  if(s.split(A).length!==2||s.split(C).length!==2)throw new Error('site');s=s.replace(A,B).replace(C,D);
  fs.writeFileSync(t,fs.readFileSync('tools/aqueous-solvents/kernel-cost/KernelCostProbeTest.java','utf8'));
}else{s=s.replace(B,A).replace(D,C);if(fs.existsSync(t))fs.unlinkSync(t);}
fs.writeFileSync(p,crlf?s.split(LF).join(CR+LF):s);console.log('probe',process.argv[2]);
