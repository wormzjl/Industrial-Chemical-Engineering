from pathlib import Path
r=Path('src/main/java/com/wormzjl/icheme')
p=r/'world/level/block/LinePieces.java';s=p.read_text();a=s.index('        final Map<Piece,Integer> refs');b=s.index('\n    // Values contain',a);s=s[:a]+'''        final Map<Piece,Integer> refs=new HashMap<>();
        final Map<Piece,Map<BlockPos,VoxelShape>> geometry=new HashMap<>();
        final ConcurrentHashMap<BlockPos,Bucket> cells=new ConcurrentHashMap<>();
        volatile List<Piece> visible=List.of();
        void set(Object source,List<Piece> next){
            var old=sources.getOrDefault(source,List.of());if(old.equals(next))return;
            var touched=new HashMap<BlockPos,Set<Piece>>();
            for(var p:old){int n=refs.get(p)-1;if(n>0){refs.put(p,n);continue;}refs.remove(p);
                for(var cell:geometry.get(p).keySet())touched.computeIfAbsent(cell,k->new HashSet<>(cells.get(k).pieces())).remove(p);
            }
            if(next.isEmpty())sources.remove(source);else sources.put(source,List.copyOf(next));
            for(var p:next){int n=refs.merge(p,1,Integer::sum);if(n>1)continue;geometry.computeIfAbsent(p,k->p.cells());
                for(var cell:geometry.get(p).keySet())touched.computeIfAbsent(cell,k->new HashSet<>(cells.containsKey(k)?cells.get(k).pieces():List.of())).add(p);
            }
            for(var entry:touched.entrySet()){
                var cell=entry.getKey();var all=entry.getValue();if(all.isEmpty()){cells.remove(cell);continue;}
                var shape=Shapes.empty();for(var p:all)shape=Shapes.or(shape,geometry.get(p).get(cell));
                cells.put(cell,new Bucket(List.copyOf(all),shape.optimize()));
            }
            geometry.keySet().removeIf(p->!refs.containsKey(p));visible=List.copyOf(refs.keySet());
        }
    }
'''+s[b:];s=s.replace('return index(level).cells.values().stream().flatMap(b->b.pieces().stream()).distinct().toList();','return index(level).visible;');p.write_text(s)
p=r/'world/level/block/LineLaying.java';s=p.read_text().replace('action!=Action.LAY&&!path.isEmpty()', 'action!=Action.LAY&&(!path.isEmpty()||!first.equals(last))');a=s.index('    public static void breakPiece(');b=s.index('    private static Result result(',a);s=s[:a]+'''    public static void breakPiece(ServerPlayer player,LinePieces.Piece piece){
        if(player.isSpectator()||!player.mayBuild())return;
        for(var cell:piece.cells().keySet())if(!LinePlacement.editable(player,cell))return;
        var target=new LineTarget(null,piece.parent().cell(),piece.port().face(),new net.minecraft.world.phys.Vec3(.5,.5,.5),piece.parent().lane());
        FluidWorldAuthority.find(player.server).ifPresent(world->dismantle(player,world,target,new LineProfile.Settings(LineProfile.of(piece.port().kind().medium()),piece.port().kind().medium().outside(),piece.port().kind().medium().outside(),0,0)));
    }
'''+s[b:];p.write_text(s)
p=r/'world/level/block/entity/LinePieceBlockEntity.java';s=p.read_text();a=s.index('        // Reconciliation removes');b=s.index('\n    }',a);s=s[:a]+'''        // A proxy has no independent fact to delete. Native player removal is admitted by LineLaying;
        // unrelated block destruction only schedules reconstruction from the surviving connection facts.
        if(level instanceof ServerLevel server)for(var p:pieces)LinePieces.queue(server.getServer(),p.parent());'''+s[b:];p.write_text(s)
p=r/'world/level/block/LinePieceBlock.java';s=p.read_text();i=s.index('    @Override protected void onRemove(');s=s[:i]+'''    @Override public BlockState playerWillDestroy(Level level,BlockPos pos,BlockState state,net.minecraft.world.entity.player.Player player){
        if(player instanceof net.minecraft.server.level.ServerPlayer server&&level.getBlockEntity(pos) instanceof LinePieceBlockEntity copy)for(var piece:copy.linePieces())LineLaying.breakPiece(server,piece);
        return super.playerWillDestroy(level,pos,state,player);
    }
'''+s[i:];p.write_text(s)
p=r/'world/level/block/LinePlacement.java';s=p.read_text().replace('inflate(.09)','inflate(Math.max(.09,lead.outside()/2+.04))');s=s.replace('work.finish().run();return null;','work.finish().run();LinePieces.flush(player.server);return null;');p.write_text(s)
p=r/'world/level/block/PipeRackBlock.java';s=p.read_text().replace('return rack.shape();','return LinePieces.shape(level,position,rack.shape());');# inspect parameter before applied if mismatch compile
p.write_text(s)
# derived occupancy block has no client model or portable item
p=Path('src/main/resources/assets/icheme/blockstates/line_piece.json');p.write_text('{"variants":{"":{"model":"minecraft:block/air"}}}\n')
p=Path('src/main/resources/data/create/tags/block/non_movable.json');s=p.read_text().replace('    "icheme:data_cable",','    "icheme:data_cable",\n    "icheme:line_piece",');p.write_text(s)
