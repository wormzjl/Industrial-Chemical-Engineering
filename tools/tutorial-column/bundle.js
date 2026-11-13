// Writes the bundled small crude preset and example from candidate C's final run (tmp/tutorial-p4/C.json, C-sheet.json); run from the worktree root: node tools/tutorial-column/bundle.js
const fs=require("fs");
const R="src/main/resources/assets/icheme/";
const c=JSON.parse(fs.readFileSync("tmp/tutorial-p4/C.json","utf8"));
const p={schema_version:3,kind:"column",name:"Small crude tower (Medium crude)",translation_key:"gui.icheme.column_template.small_crude"};
for (const k of ["tray_count","feed_tray","design_feed_kmol_h","top_temperature_kelvin","reboiler_duty_mw","reflux_ratio","top_pressure_bar","diameter_m","draws","steam","pumparounds"]) p[k]=c[k];
p.reboiler_duty_mw=0; p.diameter_m=1;
fs.writeFileSync(R+"columnpreset/small_crude.json", JSON.stringify(p,null,2)+"\n");
let idx=JSON.parse(fs.readFileSync(R+"columnpreset/index.json","utf8")); if(!idx.includes("small_crude.json")) idx.splice(idx.indexOf("medium_crude.json")+1,0,"small_crude.json");
fs.writeFileSync(R+"columnpreset/index.json", JSON.stringify(idx,null,2)+"\n");
// example: C's sheet as solved (gson output); only unescape gson's HTML escapes
let sheet=fs.readFileSync("tmp/tutorial-p4/C-sheet.json","utf8").replace(/\u003d/g,"=").replace(/\u0027/g,"'").replace(/\u003c/g,"<").replace(/\u003e/g,">").replace(/\u0026/g,"&");
JSON.parse(sheet); if(!sheet.endsWith("\n")) sheet+="\n";
fs.writeFileSync(R+"flowsheet_examples/small_crude_unit.json", sheet);
let ex=JSON.parse(fs.readFileSync(R+"flowsheet_examples/index.json","utf8")); if(!ex.includes("small_crude_unit.json")) ex.splice(ex.indexOf("crude_unit.json")+1,0,"small_crude_unit.json");
fs.writeFileSync(R+"flowsheet_examples/index.json", JSON.stringify(ex,null,2)+"\n");
// The paired stream (owner, D4 option A): the medium crude mixture at the small tower's design feed. The grades' streams stand at
// crude_unit's heater outlet (638.15 K); at the small unit's (663.15 K) the calculator leaves 44.9 % in the bottoms. This one stands
// at the feed T-101 meets on the example's run, S-7 after V-101 (657.15 K, 384 C), where it gives 18.4 / 35.2 / 46.4 % (PairCheck).
const m=JSON.parse(fs.readFileSync(R+"mixturepreset/medium_crude.json","utf8"));
const small={schema_version:m.schema_version,kind:m.kind,name:"Small crude (Medium crude)",translation_key:"gui.icheme.column_preset.small_crude",
  temperature_kelvin:657.15,flow_kmol_h:c.design_feed_kmol_h,basis:m.basis,components:m.components};
fs.writeFileSync(R+"mixturepreset/small_crude.json", JSON.stringify(small,null,2)+"\n");
let mi=JSON.parse(fs.readFileSync(R+"mixturepreset/index.json","utf8")); if(!mi.includes("small_crude.json")) mi.splice(mi.indexOf("medium_crude_residue.json")+1,0,"small_crude.json");
fs.writeFileSync(R+"mixturepreset/index.json", JSON.stringify(mi,null,2)+"\n");
