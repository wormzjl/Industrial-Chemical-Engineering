// Compare the built design's polygons (out/) with the scratch form A (tmp/plunger-forms/geometry-neck/): counts by material, bounds, and the whole text.
const fs=require("fs");
function load(f){const j=JSON.parse(fs.readFileSync(f,"utf8"));const by={};const lo=[1e9,1e9,1e9],hi=[-1e9,-1e9,-1e9];
 for(const p of j.polygons){by[p.m]=(by[p.m]||0)+1;for(let i=0;i<p.v.length;i++){lo[i%3]=Math.min(lo[i%3],p.v[i]);hi[i%3]=Math.max(hi[i%3],p.v[i]);}}
 return {n:j.polygons.length,by,lo,hi};}
for(const c of [3,10,30,100]){const a=load("out/A_"+c+".json"),b=load("../../plunger-forms/geometry-neck/A_"+c+".json");
 const same=fs.readFileSync("out/A_"+c+".json","utf8")===fs.readFileSync("../../plunger-forms/geometry-neck/A_"+c+".json","utf8");
 console.log(`PP-${c}: built ${a.n}, scratch ${b.n}; by material equal ${JSON.stringify(a.by)===JSON.stringify(b.by)}; bounds equal ${JSON.stringify([a.lo,a.hi])===JSON.stringify([b.lo,b.hi])}; files identical ${same}`);
 console.log("   "+Object.entries(a.by).map(([m,n])=>m+" "+n).join(", ")+"; box "+a.lo.map(x=>x.toFixed(3))+" .. "+a.hi.map(x=>x.toFixed(3)));}
