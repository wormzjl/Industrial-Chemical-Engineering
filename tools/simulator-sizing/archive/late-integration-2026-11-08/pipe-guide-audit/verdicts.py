from pathlib import Path
import json,re,collections
root=Path('.');path=Path('tmp/pipe-guide-audit/entries.json');entries=json.loads(path.read_text(encoding='utf-8'))
# Chapter dispositions chosen after the entry-by-entry applicability review, not runtime test results.
chapters={
 'README.md':('REFERENCE_CONTEXT','Overview/history; follow the owning chapter. Historical versions and test counts are not current qualification.','PipeLayingClient.java,CableLayingClient.java,LinePlacement.java'),
 'client-gui.md':('APPLICABLE_OWNER_REVIEW','Common tool/session and rendering decisions belong to shared owners; fluid GUI/process displays remain their own adapters. Duplicate cable client session remains unresolved.','PipeLayingClient.java,CableLayingClient.java,LayingToolAim.java,Outlines.java,ModelledBlockEntity.java'),
 'column-operation.md':('FLUID_ONLY','Running-column process stations, flow/energy contracts and solver identities do not apply to data/FE conduit placement. No column solver changes.','ColumnOperation.java,DataBus.java'),
 'column-v3.md':('FLUID_ONLY','Save-design structure/nozzle validation is unrelated to physical cable route ownership.','ColumnDesign.java'),
 'cooling-towers.md':('DOMAIN_ADAPTER','Use tower service ports with common route/clearance; tower shell geometry and air/water conservation remain unchanged.','DataSocketGeometry.java,PowerPorts.java,LinePlacement.java'),
 'equilibrium-engine.md':('FLUID_ONLY','Thermodynamic equilibrium/bracket entries carry no data/FE placement decision; no equilibrium model changes.','DataBus.java,FluidTpEquilibrium.java'),
 'equipment.md':('DOMAIN_ADAPTER','Preserve equipment layout/nozzle/fluid facts; signal/FE socket shape is an adapter. Common model refresh, reserved space, rendering and physical placement contracts apply.','EquipmentStructure.java,ModelledBlockEntity.java,DataPorts.java,ConduitModel.java,LinePlacement.java'),
 'fluid-runtime.md':('DOMAIN_ADAPTER','Fluid ledger, inventory, process publications and hydraulic accessories remain fluid-only. Explicit joints, lanes, no-chunk-load and fact-preservation contracts apply to cables through their control facts adapter.','PhysicalFluidTopology.java,LinePlacement.java,DataBus.java,ControlFacts.java'),
 'fluid-science.md':('FLUID_ONLY','Hydraulic/thermal/phase/solid solver laws, relaxation junction buffers and reference campaigns do not describe metallic signal/FE conduits. No scientific-model edits or reruns.','PassiveStepSolver.java,DataBus.java'),
 'heat-exchangers.md':('FLUID_ONLY','Exchanger passages, energy exchange and process-line assemblies remain fluid domain; physical service ports use common route clearance.','HeatSides.java,ExchangerRelay.java,LinePlacement.java'),
 'instrument-accessories.md':('DOMAIN_ADAPTER','Fluid fittings/boring/paint/control laws are medium-specific. Sensor installation/removal must synchronise its service socket; no socket may survive its owning pipe sensor without an actuator.','InstrumentService.java,InstrumentHosts.java,ControlService.java'),
 'machine-assemblies.md':('DOMAIN_ADAPTER','Process blueprint/flowmeter/nozzle watches are fluid-specific; data connectivity is DataBus, not a duplicate fluid assembly walker. Common physical spacing and marker backend apply.','AssemblyWalker.java,AssemblyToolClient.java,DataBus.java,LinePlacement.java'),
 'pipe-laying.md':('APPLICABLE_OWNER_REVIEW','All common spatial, tool-session, marker, rack, permission and mutation decisions must use shared owners. Fixed enclosure size/finish, socket identity and domain registration are justified adapters; fluid inventory/resize/fittings remain fluid-only. See OWNERSHIP.md for unresolved session/direct-placement gaps.','PipeAnchor.java,PipeRoute.java,RackGeometry.java,LinePlacement.java,PipeLayingClient.java,CableLayingClient.java'),
 'process-control.md':('DOMAIN_ADAPTER','Sensor hardware updates are immediate, process plans remain scheduled. Controller loops and physical bus bindings have distinct owners; preserve stable historical IDs.','InstrumentService.java,ControlService.java,ControlFacts.java'),
 'process-simulator.md':('FLUID_ONLY','Flowsheet drawing and hydraulic line sizing are not physical signal/FE laying. No simulator changes.','LineSizing.java,SheetGestures.java'),
 'rotating-machinery.md':('FLUID_ONLY','Wear and process-observed sounds are unaffected by conduit overlay/rendering fixes; physical FE delivery still uses PowerNetwork.','PowerNetwork.java,MoverSounds.java'),
 'verification.md':('VERIFICATION_PENDING','Use affected filtered gates/fixtures only after owner client closure. Compile/software render evidence is separate from test/runtime qualification. No final lane/merge work.','build.gradle'),
 'wired-control-power.md':('APPLICABLE_OWNER_REVIEW','Mandatory shared laying architecture; control/FE graphs and fixed enclosure style are legitimate medium adapters. Shared client decision ownership still needs consolidation.','CableLaying.java,LinePlacement.java,ControlService.java,PowerNetwork.java'),
}
special={
 'E0019':('FOUND_GAP','Cable overlays bypassed Outlines; shared outline backend and face-square/range/cap now implemented, final driver lifecycle consolidation pending.'),
 'E0025':('FOUND_GAP','Socket/rack renderer omitted chunk vertex shade and duplicated material selection. ConduitModel.Finish now bakes all sections; runtime lighting check pending.'),
 'E0027':('SOURCE_CHECKED','PipeRackModel Look.occupied and RackGeometry.polygons already include cable occupancy; no separate brace fix needed.'),
 'E0037':('SOURCE_CHECKED','ModelledBlockEntity loads update data before refreshing geometry; keep the prior fix and add client conduit loaded-index lifecycle.'),
 'E0184':('FOUND_GAP','Direct cable BlockItem placement has no counterpart to pipe registration/joint initialisation; unresolved until physical placement adapter is implemented.'),
 'E0185':('FOUND_GAP','Cables had no loaded open-end index/marker path. Client loaded index and shared physical open-face matcher added; final qualification pending.'),
 'E0195':('FOUND_GAP','Direct hand-placed cable registration/joints missing; direct placement must retain fixed medium size and explicit neighbour matching.'),
 'E0198':('SOURCE_CHECKED','Both lay adapters use LinePlacement commit. Fluid ledger facts vs control Cable facts remain domain adapters; no fluid solve in cable commands.'),
 'E0216':('FOUND_GAP','Cable direct placement is missing; tool-laid joints/rack coordinate matching exist. Do not infer full placement parity from route search reuse.'),
 'E0437':('DOMAIN_ADAPTER','AssemblyToolClient watch covers process blueprint nozzle roles. Cable tool uses loaded structural sockets and requests no fluid assembly views.'),
 'E0695':('FOUND_GAP','Last pipe sensor removal retained its registered service socket. ControlService.bind now retires it and clears displayed IDs while retaining cable/endpoints/history; fixture compiled only pending.'),
}
sources=list(root.glob('src/**/*.java'))+[Path('build.gradle')]
byname=collections.defaultdict(list)
for p in sources:byname[p.name].append(str(p).replace('\\','/'))
for e in entries:
 status,reason,names=chapters[e['file']]
 if e['id'] in special:status,reason=special[e['id']]
 e['status']=status;e['review']='full-entry' if e['file'] not in {'fluid-science.md','verification.md','fluid-runtime.md'} else 'individual-scope-and-owner; applicable joint/fact paragraphs read in full'
 e['evidence']={'disposition':reason,'owners':[p for name in names.split(',') for p in byname.get(name,[])],'qualification':'source audit only; runtime tests pending owner client closure'}
 assert e['evidence']['owners'],e['id']
path.write_text(json.dumps(entries,ensure_ascii=False,indent=2),encoding='utf-8')
print('Individual dispositions:',len(entries),'chapters:',len(chapters));print(dict(collections.Counter(e['status'] for e in entries)))
