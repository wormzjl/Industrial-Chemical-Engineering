from pathlib import Path
b=Path('src/main/java/com/wormzjl/icheme')
def edit(path,old,new):
 p=b/path;s=p.read_text(encoding='utf-8');assert old in s,(path,old[:55]);p.write_text(s.replace(old,new),encoding='utf-8')
edit('compat/create/CreateShaftBlock.java','return face.getAxis()==getRotationAxis(state)&&!(level.getBlockState(pos.relative(face)).getBlock() instanceof ShaftBlock);','return face.getAxis()==getRotationAxis(state);')
edit('world/level/block/ShaftParts.java','        default void output(double rpm,double watts) {}','        default void output(double rpm,double watts) {}\n        default double nativeSpeed(){return 0;}')
edit('compat/create/CreateBridgeBlockEntity.java','    @Override public float getGeneratedSpeed()','    @Override public double nativeSpeed(){return Math.abs(getSpeed());}\n    @Override public float getGeneratedSpeed()')
edit('runtime/fluid/FluidWorldAuthority.java','return speeds.computeIfAbsent(unit.id(),id->{var view=shafts.view(id);return view==null?0:Math.max(0,view.speed());});','''return speeds.computeIfAbsent(unit.id(),id->{
            var view=shafts.view(id);double rpm=view==null?0:Math.max(0,view.speed());
            if(unit.kind()==com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.SHAFT&&(view==null||view.status().equals("LOOSE")||view.status().equals("NO DRIVER"))) {
                var level=server.getLevel(dimension(unit.position().dimension()));var pos=new BlockPos(unit.position().x(),unit.position().y(),unit.position().z());
                if(level!=null&&level.hasChunkAt(pos)&&level.getBlockEntity(pos) instanceof com.wormzjl.icheme.world.level.block.ShaftParts.Coupling nativeShaft)rpm=nativeShaft.nativeSpeed();
            }
            return rpm;
        });''')
edit('science/machine/ShaftNetwork.java','            Objects.requireNonNull(kind);\n            ends = List.copyOf(ends);','            Objects.requireNonNull(kind);Objects.requireNonNull(role);\n            ends = List.copyOf(ends);')
