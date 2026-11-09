from pathlib import Path
root=Path('src/main/java/com/wormzjl/icheme')
p=root/'world/level/block/PipeAnchor.java';s=p.read_text();a=s.index('        var clicked = click.clicked;',s.index('public static Resolved resolve('));b=s.index('    /** Free surfaces',a);s=s[:a]+'''        var resolved=LineAnchors.resolve(level,LineTarget.of(click),new LineProfile.Settings(LineProfile.FLUID,.05,outside,0,0));
        return new Resolved(resolved.anchor()==null?null:resolved.anchor().placement(),resolved.refusal());
    }
'''+s[b:];s=s.replace('private static Resolved validate(','static Resolved validate(');p.write_text(s)
p=root/'world/level/block/LineLayout.java';s=p.read_text().replace('int packed=anchor.point()>=0?anchor.point():0;\n                cell=', 'int packed=anchor.point()>=0?anchor.point():(int)(cell.points()>>10*face)&1023;\n                cell=');p.write_text(s)
p=root/'world/level/block/FluidLineBackend.java';s=p.read_text().replace('return new LinePlacement.Work(edits,()->refresh(level,touched));','''return new LinePlacement.Work(edits,()->{
            for(var at:touched)if(level.getBlockEntity(at) instanceof PipeRackBlockEntity rack)rack.refresh(false);
            for(var at:touched)if(level.getBlockEntity(at) instanceof FluidDeviceBlockEntity pipe)pipe.refreshGeometry(layout.cells().stream().anyMatch(c->c.site().cell().equals(at))?settings.bore():pipe.bore(),true);
            for(var at:touched)if(level.getBlockEntity(at) instanceof PipeRackBlockEntity rack)rack.refresh(true);
        });''');p.write_text(s)
p=root/'world/level/block/LineBackend.java';s=p.read_text().replace('        LineLayout.Site site()','        public LineLayout.Site site()').replace('        double outside()','        public double outside()');s=s.replace('    default void registerSocket','    default UUID binding(Level level,Host host,int face){return null;}\n    default void registerSocket');p.write_text(s)
p=root/'world/level/block/ConduitLineBackend.java';s=p.read_text().replace('final class ConduitLineBackend','public final class ConduitLineBackend').replace('    static List<DataBus.Cable> wires','    public static List<DataBus.Cable> wires');s=s.replace('    public void registerSocket','''    public UUID binding(Level level,Host host,int face){return cables(level,host.cell()).stream().filter(c->c.site().lane()==host.lane()).map(c->c.endpoints().get(Direction.values()[face])).filter(Objects::nonNull).findFirst().orElse(null);}
    public void registerSocket''');p.write_text(s)
p=root/'world/level/block/LineLaying.java';s=p.read_text();a=s.index('        var old=ConduitLineBackend.cables');b=s.index('        long points=',a);s=s[:a]+'''        var bound=backend.binding(level,host,port.face().ordinal());
        if(bound!=null&&!bound.equals(port.id()))return failed(PipeLaying.message("anchor.fitting"));
'''+s[b:];p.write_text(s)
