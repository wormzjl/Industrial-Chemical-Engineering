from pathlib import Path
import json
p=Path('src/test/resources/compat/line-pieces');p.mkdir(exist_ok=True)
def pos(x,y,z):
 v=((x&0x3ffffff)<<38)|((z&0x3ffffff)<<12)|(y&0xfff)
 return v if v<1<<63 else v-(1<<64)
lines=['{Saved:{Joints:48,Offsets:0L,Medium:"DATA",Endpoints:[]},Expected:0}']
for kind,host in [('SENSOR',7),('POWER_INPUT',0)]:
 port='{id:[I;0,0,0,71],kind:"'+kind+'",host:'+str(host)+'L,dimension:"minecraft:overworld",owner:'+str(pos(0,3,4))+'L,x:0.9d,y:3.5d,z:4.5d,cell:'+str(pos(1,3,4))+'L,face:"WEST",u:3,v:-2}'
 lines.append('{Saved:{LinePieces:[{Port:'+port+',Lane:0}]},Expected:1}')
(p/'lead-copies-1.snbt').write_text('\n'.join(lines)+'\n')
# Side-effect free compile argument files. Test suites remain deferred by owner.
main=list(Path('src/main/java/com/wormzjl/icheme').rglob('*.java'))
