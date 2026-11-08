"""One proposed instrumentation policy for drawings, permissions and enclosure notes."""
from signal_catalog import THERMAL_KINDS,TOWER_KINDS
ROTATING_KINDS=frozenset(['PUMP','COMPRESSOR','CENTRIFUGAL_COMPRESSOR','STEAM_TURBINE','MOTOR','GENERATOR','INLINE_PUMP','INLINE_COMPRESSOR'])
FLUID_ROTATING_KINDS=ROTATING_KINDS-{'MOTOR','GENERATOR'}

def rotating(d):return d['kind'] in ROTATING_KINDS
def fluid_rotating(d):return d['kind'] in FLUID_ROTATING_KINDS
def built_in(d):return rotating(d) or d['kind'] in THERMAL_KINDS or d.get('builtIn',False)
def manual_host(d):return not built_in(d)
def description(d):
 if fluid_rotating(d):return 'Built-in inlet/outlet P/T, flow and speed; controller via C1. No manual sensor mounts.'
 if rotating(d):return 'Built-in speed and drive feedback; controller via C1. No manual sensor mounts.'
 if d['kind'] in TOWER_KINDS:return 'Built-in inlet/outlet P/T and basin level via C1. No manual sensor mounts.'
 if d['kind'] in ['EXCHANGER','KETTLE']:return 'Built-in P/T/flow at every process/utility inlet and outlet; controller via C1.'
 if d['kind'] in THERMAL_KINDS:return 'Built-in inlet/outlet P/T on each passage; controller via C1. No manual mounts.'
 if d['kind']=='COLUMN':return 'Built-in top/bottom P/T; all process, utility and reflux streams expose P/T/flow via C1.'
 return 'Built-in instruments; manual sensor mounts removed.' if built_in(d) else 'Appropriate add-on instruments retained.'
