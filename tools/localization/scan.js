// Static scan: translation-call literals in src/main/java that resolve to no en_us key.
const fs=require('fs'),path=require('path');
const root=process.argv[2];
const en=JSON.parse(fs.readFileSync(path.join(root,'src/main/resources/assets/icheme/lang/en_us.json'),'utf8'));
const keys=Object.keys(en);
const has=k=>k in en, hasPrefix=p=>keys.some(k=>k.startsWith(p));
const P=['','gui.icheme.','gui.icheme.fluid.','gui.icheme.column.','gui.icheme.heat.','gui.icheme.assembly.','gui.icheme.assembly.operation.','gui.icheme.equipment_constructor.','gui.icheme.vessel.','gui.icheme.gauge.','gui.icheme.pipeline_tool.','gui.icheme.common.','gui.icheme.paint.','message.icheme.','item.icheme.','tooltip.icheme.','message.icheme.pipe_laying.','message.icheme.equipment.'];
const files=[];(function walk(d){for(const f of fs.readdirSync(d)){const p=path.join(d,f);fs.statSync(p).isDirectory()?walk(p):p.endsWith('.java')&&files.push(p)}})(path.join(root,'src/main/java'));
const call=/(?:\b(?:tr|lang|t|text|get|of|translatable|translatableWithFallback|key|label|line|hint|row|title|word|button|msg|message|warn|status|tip)\(|KEY\s*\+\s*|translation\s*\+\s*)"([a-z0-9_]+(?:\.[a-z0-9_]+)*\.?)"/g;
const out=[];const used=new Set();
for(const f of files){const src=fs.readFileSync(f,'utf8');let m;
  // file-specific prefixes from string constants ending in '.'
  const local=[...src.matchAll(/"([a-z_]+\.icheme\.[a-z0-9_.]*\.)"/g)].map(x=>x[1]);
  while((m=call.exec(src))){const lit=m[1];const pre=[...P,...local];
    if(lit.endsWith('.')){const c=pre.map(p=>p+lit).filter(hasPrefix);if(!c.length)out.push(['PREFIX',lit,f,src.slice(0,m.index).split('\n').length]);else c.forEach(x=>used.add(x+'*'));continue;}
    if(/^[a-z_]+$/.test(lit)&&!/^(tr|lang)$/.test('')){/* single word: only report if no candidate */}
    const c=pre.map(p=>p+lit).filter(has);
    if(!c.length&&!pre.some(p=>hasPrefix(p+lit+'.')))out.push(['MISSING',lit,f,src.slice(0,m.index).split('\n').length]);else c.forEach(x=>used.add(x));}
  for(const x of src.matchAll(/"([a-zA-Z]+\.icheme\.[a-z0-9_.]*[a-z0-9_])"/g))if(!has(x[1])&&!hasPrefix(x[1]+'.'))out.push(['FULL',x[1],f,src.slice(0,x.index).split('\n').length]);
}
for(const o of out)console.log(o[0],o[1],path.relative(root,o[2]).split(path.sep).join('/')+':'+o[3]);
