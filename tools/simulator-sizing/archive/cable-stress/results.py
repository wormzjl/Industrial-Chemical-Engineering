from pathlib import Path
import csv,json
p=Path('tmp/cable-stress')
rows={r['phase']:r for r in csv.DictReader((p/'metrics-representative.csv').open(encoding='utf-8'))}
summary=json.loads((p/'process-summary-representative.json').read_text(encoding='utf-8-sig'))
report={
 'scope':'Offline shipped DataBus, pipe routing and PowerGrid kernels with deterministic FE-capability fixtures; no player worlds or client',
 'representative_cables':999936,'representative_ports':79624,'wired_power_circuits':3906,
 'power_thread_cpu_ms_per_update':{k:float(rows['power_tick_'+k]['thread_cpu_ms'])/int(rows['power_tick_'+k]['iterations']) for k in ['previous','current']},
 'power_allocated_bytes_per_update':{k:int(rows['power_tick_'+k]['allocated_bytes'])/int(rows['power_tick_'+k]['iterations']) for k in ['previous','current']},
 'data_lookup_allocated_bytes_per_million':{k:int(rows['data_lookup_'+k]['allocated_bytes']) for k in ['previous','current']},
 'peak_process_working_set_bytes':summary['peakWorkingSetBytes'],
 'graph_rebuild_wall_ms':{k:float(rows[k+'_rebuild']['wall_ms']) for k in ['data','power']},
 'adversarial_assertions':27772,'representative_assertions':15706,
 'zero_supply':{'generators':256,'loads':4096,'updates':500,'load_probes':0},
 'focused_test_passes':{'DialogColumnsTest':8,'ControlSystemTest':8,'EnergyTransferTest':2,'PowerGridTest':4,'ConduitGeometryTest':2},
 'note':'Earlier alignment fixture used unsupported 1 kW; corrected to supported ratings. Sequential IDs were a pathological legacy-map workload, so representative performance uses distributed IDs.'}
(p/'results.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print(json.dumps(report,indent=2))
