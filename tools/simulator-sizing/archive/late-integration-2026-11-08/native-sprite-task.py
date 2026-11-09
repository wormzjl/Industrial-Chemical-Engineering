from pathlib import Path
p=Path('tools/equipment-drawings/export.init.gradle');s=p.read_text(encoding='utf-8').replace("java.srcDir p.file('tools/equipment-drawings')","java.srcDir p.file('tools/equipment-drawings')\n        java.srcDir p.file('tools/pump-sprites/probes')").replace("java.include 'ProposalMeshes.java'","java.include 'ProposalMeshes.java'\n        java.include 'ServiceRender.java'\n        java.include 'PumpRender.java'")
at=s.index("    p.tasks.register('exportEquipmentProposalMeshes'")
s=s[:at]+'''    p.tasks.register('renderEquipmentServices', JavaExec) {
        group = 'documentation'
        description = 'Render native service housings and instrument roots using PumpRender and the actual sprites.'
        dependsOn p.tasks.named(drawingSources.classesTaskName)
        classpath = drawingSources.runtimeClasspath
        mainClass = 'com.wormzjl.icheme.science.equipment.ServiceRender'
        args new File(work.get(), 'sprite-renders').absolutePath
        jvmArgs '-Djava.awt.headless=true'
    }
''' +s[at:];p.write_text(s,encoding='utf-8')
p=Path('tools/equipment-drawings/README.md');s=p.read_text(encoding='utf-8');s+='''

`renderEquipmentServices` in `export.init.gradle` supplements the sheets with actual-sprite close-ups (light/dark body paint, normal/low meshes). It reuses `tools/pump-sprites/probes/PumpRender.java`; `ServiceRender.java` supplies only native meshes, socket colours and camera framing. Run it after the native export, supplying the same `-PequipmentDrawingWork=<run-folder>` property. It starts no client. The `service_details.py` pages add mounted enclosure crops, example cable approaches, top-probe plans and reinforced-root close-ups to the native book.
''';p.write_text(s,encoding='utf-8')
