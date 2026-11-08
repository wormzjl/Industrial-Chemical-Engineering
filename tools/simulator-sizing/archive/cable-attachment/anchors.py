from pathlib import Path
r=Path('src/main/java/com/wormzjl/icheme')
p=r/'world/level/block/PipeAnchor.java';s=p.read_text(encoding='utf-8')
a=s.index('        } else {\n            boolean inside = state.canBeReplaced();');b=s.index('        if (level.isOutsideBuildHeight(anchor.cell)',a)
openbody=s[a+len('        } else {\n'):b]
assert openbody.endswith('        }\n')
openbody=openbody[:-len('        }\n')]
openbody=openbody.replace('            boolean inside = state.canBeReplaced();','        var clicked=click.clicked();var face=click.face();var next=clicked.relative(face);var state=level.getBlockState(clicked);\n        PipeAnchor anchor;\n        boolean inside = state.canBeReplaced();')
s=s[:a]+'''        } else return open(level,click,outside);
        return validate(level,anchor);
    }
    /** Free surfaces and rack slots follow the same resolution for pipes, data and power. */
    public static Resolved open(Level level,Click click,double outside){
'''+openbody+'''        return validate(level,anchor);
    }
    /** A registered physical socket/nozzle enters a particular face point of its first line cell. */
    public static Resolved socket(Level level,BlockPos cell,Direction joint,BlockPos owner,int point){
        return validate(level,new PipeAnchor(Kind.NOZZLE,cell,joint,owner,0,-1,joint.getOpposite(),point,Fit.ANY,null));
    }
    /** An existing line or lane grows through its selected face; topology-specific facts stay with its adapter. */
    public static Resolved continuation(Level level,BlockPos host,Direction face,int lane,int joints,int point){
        return validate(level,new PipeAnchor(lane>0?Kind.LANE:Kind.PIPE,host.relative(face),face.getOpposite(),host,lane,joints,face,point,Fit.ANY,null));
    }
    private static Resolved validate(Level level,PipeAnchor anchor){
'''+s[b:]
p.write_text(s,encoding='utf-8')
p=r/'world/level/block/CableLaying.java';s=p.read_text(encoding='utf-8')
s=s.replace('public record Anchor(PipeRoute.End end,int heading,DataPort port,DataBus.Site host,int face,int point){}','''public record Anchor(PipeAnchor placement,DataPort port,DataBus.Site host,int face,int point){
        public PipeRoute.End end(){return placement.end();}
        public int heading(){return placement.leaving();}
    }
    private static Anchor socket(Level level,DataPort p){var resolved=PipeAnchor.socket(level,p.cell(),p.face(),p.owner(),DataPorts.packed(p));return resolved.anchor()==null?null:new Anchor(resolved.anchor(),p,null,-1,DataPorts.packed(p));}''')
s=s.replace('return new Anchor(new PipeRoute.End(cell(p.cell()),p.face().ordinal(),DataPorts.packed(p)),p.face().getOpposite().ordinal(),p,null,-1,DataPorts.packed(p));','return socket(level,p);')
s=s.replace('            var next=click.clicked().relative(click.face());\n            return new Anchor(new PipeRoute.End(cell(next),face^1,packed),face,null,cable.site(),face,packed);','''            var resolved=PipeAnchor.continuation(level,click.clicked(),click.face(),cable.site().lane(),cable.geometry().joints()|1<<face,packed);
            return resolved.anchor()==null?null:new Anchor(resolved.anchor(),null,cable.site(),face,packed);''')
s=s.replace('return new Anchor(anchor.end(),anchor.leaving(),null,null,-1,-1);','return new Anchor(anchor,null,null,-1,-1);')
s=s.replace('if(port!=null)return new Anchor(new PipeRoute.End(cell(port.cell()),port.face().ordinal(),DataPorts.packed(port)),port.face().getOpposite().ordinal(),port,null,-1,DataPorts.packed(port));','if(port!=null)return socket(level,port);')
a=s.index('        var at=level.getBlockState(click.clicked()).canBeReplaced()');b=s.index('\n    }',a)
s=s[:a]+'''        // Clicking equipment must select its socket, never silently become an unconnected free end.
        if(be instanceof EquipmentShellBlockEntity||level.getBlockState(click.clicked()).getBlock() instanceof EquipmentInteriorBlock)return null;
        if(be instanceof com.wormzjl.icheme.runtime.instrument.Instrument.Holder holder
                &&(medium==CableMedium.POWER?holder.instruments().powerPort():holder.instruments().dataPort())!=null)return null;
        var resolved=PipeAnchor.open(level,new PipeAnchor.Click(click.clicked(),click.face(),click.local()),medium.outside());
        return resolved.anchor()==null?null:new Anchor(resolved.anchor(),null,null,-1,-1);'''+s[b:]
p.write_text(s,encoding='utf-8')
