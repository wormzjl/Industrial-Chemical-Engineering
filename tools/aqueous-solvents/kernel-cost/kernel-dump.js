// Kernel output dump (tools/aqueous-solvents/kernel-cost/README.md): node tools/aqueous-solvents/kernel-cost/kernel-dump.js on|off
// on: copies KernelDumpTest.java into src/test; off: removes it. Never commit it there.
const fs=require('fs');
const t='src/test/java/com/wormzjl/icheme/science/thermo/KernelDumpTest.java';
if(process.argv[2]==='on')fs.writeFileSync(t,fs.readFileSync('tools/aqueous-solvents/kernel-cost/KernelDumpTest.java','utf8'));
else if(fs.existsSync(t))fs.unlinkSync(t);
console.log('dump',process.argv[2]);
