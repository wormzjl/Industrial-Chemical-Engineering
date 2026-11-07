from pathlib import Path
cp=Path('tmp/machine-rework/classpath.txt').read_text(encoding='utf-8').strip().replace('\\','/')+';build/classes/java/test;tmp/cable-stress/classes'
Path('tmp/cable-stress/javac.args').write_text('-encoding\nUTF-8\n-cp\n"'+cp+'"\n-d\ntmp/cable-stress/classes\ntmp/cable-stress/CableStress.java\n',encoding='utf-8')
Path('tmp/cable-stress/stress.args').write_text('-Xms128m\n-Xmx2048m\n-XX:ParallelGCThreads=2\n-XX:ConcGCThreads=1\n-Djava.io.tmpdir=tmp/cable-stress/java-temp\n-cp\n"'+cp+'"\nCableStress\ntmp/cable-stress\n',encoding='utf-8')
for name in ['motor','generator']:
 s=Path('tmp/power-conduit/'+name+'.args').read_text(encoding='utf-8').replace('tmp/power-conduit/renders','tmp/cable-stress/renders').replace('tmp/power-conduit/java-temp','tmp/cable-stress/java-temp')
 Path('tmp/cable-stress/'+name+'.args').write_text(s,encoding='utf-8')
Path('tmp/cable-stress/PortSheet.java').write_text(Path('tmp/power-conduit/PortSheet.java').read_text(encoding='utf-8'),encoding='utf-8')
