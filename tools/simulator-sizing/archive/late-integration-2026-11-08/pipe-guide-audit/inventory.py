from pathlib import Path
import re,json
root=Path('documentation/reference/code-guide')
pattern=re.compile(r'pipe|pipeline|rack|nozzle|conduit|laying|LineGeometry|LineSelection|LineShapes|PortGraph|PortColours|flange|junction',re.I)
entries=[]
for path in sorted(root.glob('*.md')):
 lines=path.read_text(encoding='utf-8').splitlines();headings=[];i=0
 while i<len(lines):
  line=lines[i]
  if line.startswith('#'):
   level=len(line)-len(line.lstrip('#'));headings=headings[:level-1]+[line.lstrip('# ').strip()];i+=1;continue
  if not line.strip() or re.match(r'^\|[- :|]+\|$',line):i+=1;continue
  start=i;block=[line];i+=1
  if line.startswith('|'):pass
  elif line.startswith('```'):
   while i<len(lines):
    block.append(lines[i]);i+=1
    if block[-1].startswith('```'):break
  else:
   while i<len(lines) and lines[i].strip() and not lines[i].startswith(('#','|','```')):
    if re.match(r'^\s*(?:[-*]|\d+\.)\s',lines[i]):break
    block.append(lines[i]);i+=1
  text='\n'.join(block)
  if path.name=='pipe-laying.md' or pattern.search(text):
   entries.append(dict(id=f'E{len(entries)+1:04}',file=path.name,line=start+1,heading=' / '.join(headings),text=text,status='UNREVIEWED',evidence=''))
out=Path('tmp/pipe-guide-audit');(out/'entries.json').write_text(json.dumps(entries,ensure_ascii=False,indent=2),encoding='utf-8')
for name in sorted({e['file'] for e in entries}):
 group=[e for e in entries if e['file']==name];print(name,len(group),sum(len(e['text']) for e in group))
print('TOTAL',len(entries),sum(len(e['text']) for e in entries))
