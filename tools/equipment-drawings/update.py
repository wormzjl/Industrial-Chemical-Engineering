#!/usr/bin/env python3
"""One offline command: compile current sources, export meshes, draw and rasterize sheets."""
import argparse
from datetime import datetime,timezone
import hashlib
import importlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid

TOOL=Path(__file__).resolve().parent
REPO=TOOL.parents[1]

def gradle_wrapper():
    """Invoke the project's wrapper JAR directly: no Windows batch-shell quoting."""
    home=os.environ.get('JAVA_HOME')
    java=Path(home)/'bin'/('java.exe' if os.name=='nt' else 'java') if home else None
    if java is None or not java.is_file():
        found=shutil.which('java')
        if not found:raise RuntimeError('Java was not found. Install Java 21 or set JAVA_HOME for the project Gradle toolchain.')
        java=Path(found)
    wrapper=REPO/'gradle/wrapper/gradle-wrapper.jar'
    if not wrapper.is_file():raise RuntimeError('The project Gradle wrapper JAR is missing.')
    return [str(java),'-Xmx64m','-Xms64m','-Dorg.gradle.appname=gradlew','-classpath',str(wrapper),'org.gradle.wrapper.GradleWrapperMain']

def dependencies(raster=True):
    missing=[]
    for name in ['numpy','PIL','reportlab','pypdf']:
        try:importlib.import_module(name)
        except ImportError:missing.append(name)
    if missing:raise RuntimeError('Missing Python dependencies: '+', '.join(missing)+'. Install with: '+sys.executable+' -m pip install -r tools/equipment-drawings/requirements.txt')
    if raster and not shutil.which('pdftoppm'):raise RuntimeError('Poppler pdftoppm is missing from PATH. See tools/equipment-drawings/README.md.')
    if not (REPO/('gradlew.bat' if os.name=='nt' else 'gradlew')).is_file():raise RuntimeError('Gradle wrapper not found in this checkout.')
    gradle_wrapper()

def source_identity():
    result=subprocess.run(['git','rev-parse','--short','HEAD'],cwd=REPO,capture_output=True,text=True,check=True)
    snapshot=result.stdout.strip();digest=hashlib.sha256()
    paths=sorted(p for root in [REPO/'src/main/java',REPO/'src/main/resources/assets/icheme'] for p in root.rglob('*') if p.is_file())
    for p in paths:digest.update(p.relative_to(REPO).as_posix().encode());digest.update(p.read_bytes())
    dirty=subprocess.run(['git','status','--porcelain','--','src/main'],cwd=REPO,capture_output=True,text=True,check=True).stdout.strip()
    return dict(snapshot=snapshot+(' + working tree' if dirty else ''),sourceDigest=digest.hexdigest(),sourceFiles=len(paths))

def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check',action='store_true',help='Check dependencies without compiling or writing output.')
    parser.add_argument('--only',default='*',help='Comma-separated scene IDs or equipment kinds; default all constructor kinds.')
    parser.add_argument('--heading',choices=['NORTH','EAST','SOUTH','WEST'],default='NORTH')
    parser.add_argument('--output-dir',type=Path,help='New run directory below this checkout tmp/equipment-drawings/.')
    parser.add_argument('--dpi',type=int,default=100)
    parser.add_argument('--no-raster',action='store_true',help='Draft PDF only; visual verification remains pending.')
    parser.add_argument('--sprites',action='store_true',help='Also render representative native interfaces with the actual game sprites.')
    args=parser.parse_args(argv)
    if not 40<=args.dpi<=300:parser.error('--dpi must be between 40 and 300')
    selection=args.only.upper().replace(' ','')
    if selection!='*' and not re.fullmatch(r'[A-Z0-9_]+(?:,[A-Z0-9_]+)*',selection):parser.error('--only takes equipment identifiers separated by commas')
    dependencies(not args.no_raster)
    if args.check:
        print('Ready: Python packages, Gradle wrapper'+(', Poppler rasterizer' if not args.no_raster else '')+'. Java 21 is selected by the project Gradle toolchain.');return 0
    base=(REPO/'tmp/equipment-drawings').resolve()
    work=(args.output_dir or base/(datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:6])).resolve()
    if not work.is_relative_to(base) or work==base:parser.error('--output-dir must be a new child of this checkout tmp/equipment-drawings/')
    if work.exists():parser.error('Output already exists; choose a new run directory to preserve prior evidence.')
    identity=source_identity();work.mkdir(parents=True)
    tasks=['exportEquipmentDrawings']+(['renderEquipmentServices'] if args.sprites else [])
    command=gradle_wrapper()+['--offline','--no-daemon','--max-workers=2','--no-configuration-cache','--init-script',str(TOOL/'export.init.gradle')]+tasks+['-PequipmentDrawingWork='+str(work),'-PequipmentDrawingSnapshot='+identity['snapshot'],'-PequipmentDrawingHeading='+args.heading,'-PequipmentDrawingOnly='+('ALL' if selection=='*' else selection)]
    print('Compiling/exporting current game geometry (no tests or client).',flush=True)
    with (work/'export.log').open('w',encoding='utf-8') as log:
        result=subprocess.run(command,cwd=REPO,stdout=log,stderr=subprocess.STDOUT)
    if result.returncode:raise RuntimeError('Geometry export failed. Read '+str(work/'export.log'))
    after=source_identity()
    if identity!=after:raise RuntimeError('Model sources changed during export; preserve this run and generate a fresh one.')
    manifest_path=work/'geometry/manifest.json';manifest=json.loads(manifest_path.read_text());manifest.update(identity);manifest['generatedAt']=datetime.now(timezone.utc).isoformat();manifest_path.write_text(json.dumps(manifest,indent=2))
    if selection!='*':
        matched={v for entry in manifest['entries'] for v in [entry['id'],entry['kind']]}
        absent=set(selection.split(','))-matched
        if absent:raise RuntimeError('No exported reference for: '+', '.join(sorted(absent)))
    from sheets import generate
    report=generate(work)
    if not args.no_raster:
        pages=work/'sheets/pages';pages.mkdir()
        result=subprocess.run(['pdftoppm','-r',str(args.dpi),'-png',report['pdf'],str(pages/'sheet')],capture_output=True,text=True)
        if result.returncode:raise RuntimeError('PDF rasterization failed: '+result.stderr)
        report['rasterPages']=len(list(pages.glob('sheet-*.png')))
        if report['rasterPages']!=report['pages']:raise RuntimeError('Rasterized page count differs from the PDF')
        if result.stderr.strip():(work/'raster.log').write_text(result.stderr)
        from sheets import add_gallery
        add_gallery(work/'sheets')
    report.update(identity,visualInspection='pending - inspect the generated sheets',profile='native-current-model')
    (work/'report.json').write_text(json.dumps(report,indent=2));(base/'latest.json').write_text(json.dumps(dict(work=str(work),**report),indent=2))
    print(json.dumps(report,indent=2));return 0

if __name__=='__main__':
    try:sys.exit(main())
    except (RuntimeError,subprocess.CalledProcessError) as error:print(str(error),file=sys.stderr);sys.exit(1)
