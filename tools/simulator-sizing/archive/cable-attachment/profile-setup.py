from pathlib import Path
root=Path('tmp/cable-attachment')
cp=Path('tmp/machine-rework/classpath.txt').read_text(encoding='utf-8').strip().replace('\\','/')+';build/classes/java/test;tmp/cable-attachment/classes'
(root/'javac.args').write_text('-encoding\nUTF-8\n-cp\n"'+cp+'"\n-d\ntmp/cable-attachment/classes\ntmp/cable-attachment/ShapeProbe.java\n',encoding='utf-8')
(root/'probe.args').write_text('-Xmx1536m\n-Djava.io.tmpdir=tmp/cable-attachment/java-temp\n-cp\n"'+cp+'"\nShapeProbe\n',encoding='utf-8')
