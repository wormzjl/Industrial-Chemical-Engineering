from pathlib import Path
r=Path('src/main/java/com/wormzjl/icheme')
p=r/'world/level/block/PipeLaying.java';s=p.read_text(encoding='utf-8')
a=s.index('        var refused = PipeAnchor.refuses(');b=s.index('        var world = FluidWorldAuthority.find(',a)
s=s[:a]+'''        var refused=LinePlacement.check(level,start.anchor(),end.anchor(),path,options,outside);if(refused!=null)return refused;
        var cells=path.stream().map(PipeAnchor::cell).toList();int heading=start.anchor().leaving();
        for(var cell:path)if(!LinePlacement.editable(player,cell))return message("occupied",cell.toShortString());
'''+s[b:]
s=s.replace('!take(player, path.size())','!LinePlacement.take(player,ModItems.FLUID_PIPE.get(),path.size())')
a=s.index('    private static boolean take(');b=s.index('\n    }',a)+len('\n    }')
s=s[:a]+s[b:];p.write_text(s,encoding='utf-8')
p=r/'world/level/block/CableLaying.java';s=p.read_text(encoding='utf-8')
a=s.index('        var fit=PipeAnchor.refuses(');b=s.index('        var hosts=new HashMap',a)
s=s[:a]+'''        var refused=LinePlacement.check(level,a.placement(),b.placement(),path,options,medium.outside());if(refused!=null)return refused;
        var cells=path.stream().map(CableLaying::cell).toList();
'''+s[b:]
s=s.replace('!take(player,path.size(),medium)','!LinePlacement.take(player,item(medium),path.size())')
s=s.replace('private static boolean editable(ServerPlayer p,BlockPos pos){var l=p.serverLevel();return l.hasChunkAt(pos)&&l.getWorldBorder().isWithinBounds(pos)&&l.mayInteract(p,pos)&&!p.blockActionRestricted(l,pos,p.gameMode.getGameModeForPlayer());}','private static boolean editable(ServerPlayer p,BlockPos pos){return LinePlacement.editable(p,pos);}')
a=s.index('    private static boolean take(');b=s.index('\n',a);s=s[:a]+s[b:]
p.write_text(s,encoding='utf-8')
p=r/'client/DataControlRenderer.java';s=p.read_text(encoding='utf-8');a=s.index('    public static DataPort aim(');b=s.index('    private static boolean connected(',a);s=s[:a]+s[b:];p.write_text(s,encoding='utf-8')
p=r/'world/level/block/PipeAnchor.java';s=p.read_text(encoding='utf-8');a=s.index('        boolean inside = state.canBeReplaced();',s.index('public static Resolved open'));b=s.index('        return validate(level,anchor);',a)
segment=s[a:b].splitlines();s=s[:a]+'\n'.join(line[4:] if line.startswith('            ') else line for line in segment)+'\n'+s[b:];p.write_text(s,encoding='utf-8')
p=Path('tmp/cable-attachment/gametest.init.gradle');s=p.read_text().replace("dir('tmp/cable-attachment/world')","dir('tmp/cable-attachment/world-shared')");p.write_text(s)
