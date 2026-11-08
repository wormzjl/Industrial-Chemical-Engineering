from pathlib import Path
r=Path('src/main/java/com/wormzjl/icheme')
p=r/'world/level/block/LinePlacement.java';s=p.read_text();s=s.replace('PipeAnchor.space(level),PipeRoute.Limits.DEFAULT','space(level,leads),PipeRoute.Limits.DEFAULT').replace('plan.path(),plan.options(),plan.outside())','plan.path(),plan.options(),plan.outside(),plan.leads())').replace('List<Integer> options,double outside){\n        var fit=', 'List<Integer> options,double outside,List<Lead> leads){\n        var fit=').replace('outside,PipeAnchor.space(level));','outside,space(level,leads));');i=s.index('    /** Swept lead envelopes');s=s[:i]+'''    private static PipeRoute.Space space(Level level,List<Lead> leads){return reserve(PipeAnchor.space(level),leads);}
    /** Leads occupy cells before any new block is placed; only their own entries may carry the route. */
    public static PipeRoute.Space reserve(PipeRoute.Space world,List<Lead> leads){
        var occupied=new HashSet<BlockPos>();for(var lead:leads)occupied.addAll(LinePieces.slices(lead).keySet());for(var lead:leads)occupied.remove(lead.entry());
        return new PipeRoute.Space(){
            public PipeRoute.Room room(PipeRoute.Cell cell){return occupied.contains(PipeAnchor.block(cell))?PipeRoute.Room.BLOCKED:world.room(cell);}
            public Collection<PipeRoute.Cell> racks(PipeRoute.Cell low,PipeRoute.Cell high){return world.racks(low,high);}
        };
    }
'''+s[i:];# exclude self-copy from own casting check
s=s.replace('            if(own)occupied=Shapes.join(', '''            if(own&&LinePieces.at(level,pos).stream().anyMatch(p->p.lead().equals(lead))){var part=LinePieces.slices(lead).get(pos);if(part!=null)occupied=Shapes.join(occupied,part.move(pos.getX(),pos.getY(),pos.getZ()),BooleanOp.ONLY_FIRST);}
            if(own)occupied=Shapes.join(''')
# Single application engine, reused by BlockItem finalisation as well.
a=s.index('        var work=prepare.get();');b=s.index('    public static boolean editable(',a);s=s[:a]+'''        apply(player.level(),prepare.get());LinePieces.flush(player.server);return null;
    }
    public static void apply(Level level,Work work){
        boolean laying=FluidDeviceBlock.laying;FluidDeviceBlock.laying=true;
        try{for(var edit:work.edits()){
            if(edit.state()!=null)level.setBlock(edit.at(),edit.state(),edit.flags());edit.bind().accept(level.getBlockEntity(edit.at()));
        }}finally{FluidDeviceBlock.laying=laying;}
        work.finish().run();
    }
'''+s[b:];p.write_text(s)
p=r/'world/level/block/FluidDeviceBlock.java';s=p.read_text().replace('if(kind!=TopologyCompiler.Kind.PIPE)return DEVICE;','if(kind!=TopologyCompiler.Kind.PIPE)return LinePieces.shape(level,position,DEVICE);').replace('return LineShapes.shape(mask,state.getValue(FACING).ordinal(),code,offsets);','return LinePieces.shape(level,position,LineShapes.shape(mask,state.getValue(FACING).ordinal(),code,offsets));').replace('registered itself ({@link PipeLaying})','registered itself ({@link LineLaying})');p.write_text(s)
p=r/'world/level/block/FluidLineBackend.java';s=p.read_text().replace('if(!edit.before().automatic())old.put','if(!edit.before().automatic()&&edit.joints()!=edit.before().joints())old.put');p.write_text(s)
p=r/'world/level/block/LineBackend.java';s=p.read_text().replace('    default UUID binding','    default List<Socket> socketsAt(Level level,FluidWorldAuthority world,BlockPos cell,LineProfile.Settings settings){return List.of();}\n    default UUID binding');p.write_text(s)
p=r/'world/level/block/ConduitLineBackend.java';s=p.read_text();i=s.index('    private static ConduitGeometry.Key connect(');s=s[:i]+'''    public List<Socket> socketsAt(Level level,FluidWorldAuthority world,BlockPos cell,LineProfile.Settings settings){
        return world.controls().facts().ports().values().stream().filter(p->p.dimension().equals(level.dimension().location().toString())&&p.cell().equals(cell)&&p.kind().medium()==settings.profile().medium()).sorted(Comparator.comparing(p->p.id().toString()))
                .map(p->socket(level,new LineTarget(p.id(),p.owner(),p.face().getOpposite(),new Vec3(.5,.5,.5),0),settings).socket()).filter(Objects::nonNull).toList();
    }
}
''';p.write_text(s)
p=r/'world/level/block/ConduitBlock.java';s=p.read_text().replace('ConduitLineBackend.handPlaced(level,wire,','LineLaying.handPlaced(level,pos,LineProfile.of(medium),');p.write_text(s)
p=r/'world/level/block/LineLaying.java';s=p.read_text();i=s.index('    /** Native destruction');s=s[:i]+'''    /** BlockItem placement uses the same host merge, socket checks and mutation owner as a tool command.
     * Fluid hand pipes retain their native automatic-face registration; conduits carry explicit structural joints. */
    public static void handPlaced(Level level,BlockPos cell,LineProfile profile,Direction facing,ServerPlayer player){
        if(!(level instanceof net.minecraft.server.level.ServerLevel server))return;
        var world=FluidWorldAuthority.find(server.getServer()).orElse(null);if(world==null)return;
        var backend=LineBackend.of(profile);var host=backend.hosts(level,cell,profile).stream().filter(h->h.lane()==0).findFirst().orElse(null);
        if(host==null||host.joints()!=0)return;
        var settings=new LineProfile.Settings(profile,host.bore(),host.outside(),0,0);var connections=new ArrayList<LineLayout.Connection>();var endpoints=new ArrayList<LineLayout.Endpoint>();var sockets=new ArrayList<LineBackend.Socket>();
        int joints=0;
        if(player!=null)for(var socket:backend.socketsAt(level,world,cell,settings)){
            int face=socket.face().ordinal();if((joints&1<<face)!=0)continue;
            var design=attachment(level,new LineTarget(socket.id(),socket.owner(),socket.face().getOpposite(),new net.minecraft.world.phys.Vec3(.5,.5,.5),0),settings);
            if(design.layout()==null||design.refusal()!=null||LinePlacement.touched(design.layout().spatial()).stream().anyMatch(p->!LinePlacement.editable(player,p)))continue;
            var candidate=new ArrayList<>(connections);candidate.add(new LineLayout.Connection(host,face,socket.point()));if(LineLayout.mergeHosts(candidate).refusal()!=null)continue;
            connections=candidate;joints|=1<<face;sockets.add(socket);endpoints.add(new LineLayout.Endpoint(host.site(),face,socket.id()));
        }
        for(var face:Direction.values()){
            if((joints&1<<face.ordinal())!=0)continue;
            var neighbour=backend.hosts(level,cell.relative(face),profile).stream().filter(h->(h.joints()&1<<face.getOpposite().ordinal())!=0).findFirst().orElse(null);if(neighbour==null)continue;
            var candidate=new ArrayList<>(connections);candidate.add(new LineLayout.Connection(host,face.ordinal(),(int)(neighbour.points()>>10*face.getOpposite().ordinal())&1023));
            if(LineLayout.mergeHosts(candidate).refusal()!=null)continue;connections=candidate;joints|=1<<face.ordinal();
        }
        if(joints==0){connections.add(new LineLayout.Connection(host,facing.ordinal(),0));connections.add(new LineLayout.Connection(host,facing.getOpposite().ordinal(),0));}
        else if(Integer.bitCount(joints)==1)connections.add(new LineLayout.Connection(host,Integer.numberOfTrailingZeros(joints)^1,0));
        var spatial=new LinePlacement.Plan(null,null,List.of(cell),List.of(),settings.outside(),sockets.stream().map(LineBackend.Socket::lead).filter(Objects::nonNull).distinct().toList());
        // A multi-socket hand placement also checks the combined physical leads before binding any endpoint.
        if(LinePlacement.check(level,spatial)!=null){endpoints.clear();sockets.clear();spatial=new LinePlacement.Plan(null,null,List.of(cell),List.of(),settings.outside(),List.of());connections=List.of(new LineLayout.Connection(host,facing.ordinal(),0),new LineLayout.Connection(host,facing.getOpposite().ordinal(),0));}
        var edits=LineLayout.mergeHosts(connections);if(edits.refusal()!=null)return;
        var layout=new LineLayout(spatial,List.of(),edits.edits(),endpoints);
        sockets.forEach(socket->backend.registerSocket(level,world,socket));LinePlacement.apply(level,backend.register(level,world,layout,settings));LinePieces.flush(server.getServer());
    }
'''+s[i:];s=s.replace('var connections=new ArrayList<LineLayout.Connection>();','List<LineLayout.Connection> connections=new ArrayList<>();');p.write_text(s)
