#!/usr/bin/env python3
"""Rebuild the labelled interface proposal from a fresh native drawing export.

Run the parent update.ps1 -Check and -Only workflow first, then:
  python tools/equipment-drawings/proposals/update.py --native-run tmp/equipment-drawings/<run> --output-dir tmp/equipment-drawings/<proposal>
--prepare writes the native-owner mesh inputs; --mesh-export runs the isolated Gradle drawing helper.
--render rebuilds/rasterizes the proposal book. No client, test suite or saved-world input is used.
"""
import argparse,importlib.util,json,os,shutil,subprocess,sys
from pathlib import Path

TOOL=Path(__file__).resolve().parent;REPO=TOOL.parents[2]

def native_tool():
 spec=importlib.util.spec_from_file_location('native_drawing_update',TOOL.parent/'update.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

def main():
 p=argparse.ArgumentParser(description=__doc__,formatter_class=argparse.RawDescriptionHelpFormatter)
 p.add_argument('--native-run',type=Path);p.add_argument('--output-dir',type=Path,required=True)
 p.add_argument('--prepare',action='store_true');p.add_argument('--prepare-wiring',action='store_true');p.add_argument('--only');p.add_argument('--mesh-export',action='store_true');p.add_argument('--render',action='store_true');p.add_argument('--refresh',default='*')
 args=p.parse_args();work=args.output_dir.resolve();base=(REPO/'tmp/equipment-drawings').resolve()
 if not work.is_relative_to(base) or work==base:p.error('--output-dir must stay inside this checkout tmp/equipment-drawings/')
 native=native_tool();native.dependencies()
 if args.native_run:
  source=args.native_run.resolve();manifest=json.loads((source/'geometry/manifest.json').read_text());identity=native.source_identity()
  if any(manifest[k]!=identity[k] for k in ['sourceDigest','sourceFiles']):raise RuntimeError('Native export no longer matches current game sources. Run the parent exporter again.')
  if (work/'geometry').exists():raise RuntimeError('Proposal geometry already exists; omit --native-run when re-rendering this run.')
  work.mkdir(parents=True,exist_ok=True);shutil.copytree(source/'geometry',work/'geometry')
  manifest['entries']=[e for e in manifest['entries'] if e['id']!='LENGTHWISE_BOOT'];manifest['profile']='explicit-interface-proposal';(work/'geometry/manifest.json').write_text(json.dumps(manifest,indent=2))
 manifest=json.loads((work/'geometry/manifest.json').read_text());identity=native.source_identity()
 if any(manifest[k]!=identity[k] for k in ['sourceDigest','sourceFiles']):raise RuntimeError('Game sources changed after the native export; generate a fresh run before updating the proposal.')
 os.environ['ICHEME_PROPOSAL_WORK']=str(work)
 os.environ['DRAW_REFRESH']='' if args.refresh=='*' else args.refresh
 import drawings as k
 if args.prepare:
  import sensor_layouts
  sensor_layouts.prepare(k);k.prepare_routes()
 if args.prepare_wiring:
  import instrument_wiring
  instrument_wiring.prepare(k,set(args.only.split(',')) if args.only else None)
 if args.mesh_export:
  command=native.gradle_wrapper()+['--offline','--no-daemon','--max-workers=2','--no-configuration-cache','--init-script',str(TOOL.parent/'export.init.gradle'),'exportEquipmentProposalMeshes','-PequipmentDrawingWork='+str(work)]
  with (work/'proposal-mesh-export.log').open('w',encoding='utf-8') as output:subprocess.run(command,cwd=REPO,stdout=output,stderr=subprocess.STDOUT,check=True)
 if args.render:
  k.main();pages=k.OUT/'pages';pages.mkdir(exist_ok=True)
  subprocess.run(['pdftoppm','-r','100','-png',str(k.OUT/'equipment-control-interfaces.pdf'),str(pages/'sheet')],check=True)
  subprocess.run([sys.executable,str(TOOL/'package_drawings.py'),str(k.OUT)],check=True)
 print('Proposal workspace:',work)

if __name__=='__main__':main()
