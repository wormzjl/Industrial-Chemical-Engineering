from pathlib import Path
p=Path('src/main/java/com/wormzjl/icheme/client/PipeLayingClient.java')
s=p.read_text(encoding='utf-8')
a=s.index('    private static @Nullable Start start;');b=s.index('    private PipeLayingClient()',a)
s=s[:a]+'''    // Rendering projection only. LineToolSession is the sole owner of selection, pending commands and refusal state.
    private static @Nullable Start start;
    private static @Nullable Plan plan;
    private static @Nullable Slot slot;
    private static final LineToolMarkers markers=new LineToolMarkers();
    private static final LineToolSession<PipeAnchor.Click,Start,Plan> SESSION=new LineToolSession<>(new LineToolSession.Adapter<>(){
        public PipeAnchor.Click aimed(){return PipeLayingClient.aimed(Minecraft.getInstance());}
        public BlockPos cell(PipeAnchor.Click click){return click.clicked();}
        public LineToolSession.Selected<Start> select(PipeAnchor.Click click){
            var mc=Minecraft.getInstance();double outside=PipeSizes.outside(settingsDiameter(mc.player));
            var resolved=PipeAnchor.resolve(mc.level,click,outside);
            var reason=resolved.anchor()==null?resolved.refusal():resolved.anchor().refuses(outside);
            return reason==null?new LineToolSession.Selected<>(new Start(click,resolved.anchor(),mc.level.dimension()),null):new LineToolSession.Selected<>(null,PipeLaying.refusal(reason));
        }
        public Plan plan(Start selected,PipeAnchor.Click end){var mc=Minecraft.getInstance();return PipeLayingClient.plan(mc.level,end,settingsDiameter(mc.player),selected);}
        public boolean same(PipeAnchor.Click a,PipeAnchor.Click b){var mc=Minecraft.getInstance();return PipeLayingClient.same(mc.level,a,b,PipeSizes.outside(settingsDiameter(mc.player)));}
        public Component refusal(Plan plan){return plan.refusal();}
        public Component started(Start selected){return PipeLaying.message("started",PipeLaying.describe(selected.anchor()));}
        public Component hud(Start selected,Plan plan){var p=Minecraft.getInstance().player;return plan==null?PipeLaying.message("hud.aim",PipeLaying.describe(selected.anchor())):LineToolFeedback.route(selected.anchor(),plan.anchor(),plan.route(),settingsDiameter(p),p.getAbilities().instabuild,null);}
        public void show(LineToolSession.View<PipeAnchor.Click,Start,Plan> view){project(view);}
        public void message(Component message){var p=Minecraft.getInstance().player;if(p!=null)say(p,message);}
        public boolean removable(PipeAnchor.Click click){return PipeLayingClient.removable(Minecraft.getInstance().level,click)!=null;}
        public void dismantle(boolean offHand,PipeAnchor.Click click){FluidNetwork.dismantlePipe(offHand,click);}
        public void settings(boolean offHand){FluidNetwork.openPipeLaying(offHand);}
        public void lay(boolean offHand,PipeAnchor.Click click,Start selected,PipeAnchor.Click end,Plan plan){FluidNetwork.layPipe(offHand,click,end,plan.route().cells().stream().map(PipeAnchor::block).toList(),plan.route().options());}
    });
    static LineToolSession<PipeAnchor.Click,Start,Plan> session(){return SESSION;}

'''+s[b:]
a=s.index('    /** One command per press');b=s.index('    /**\n     * The click a use makes',a)
s=s[:a]+'''    public static void answered(Component message,boolean laid){SESSION.answered(message,laid);}

    private static void project(LineToolSession.View<PipeAnchor.Click,Start,Plan> view){
        var mc=Minecraft.getInstance();var player=mc.player;
        start=view.start();plan=view.plan();
        if(plan!=null&&view.refusal()!=null)plan=new Plan(plan.end,plan.anchor,plan.route,view.refusal(),plan.placement,plan.junctions);
        if(player==null||mc.level==null||!holding(player)||mc.screen!=null){start=null;plan=null;slot=null;markers.clear();return;}
        var hit=view.hovered();double outside=PipeSizes.outside(settingsDiameter(player));
        var shown=new HashSet<Object>();
        if(start!=null){
            shown.add(START_SLOT);Outlines.box(START_SLOT,new AABB(start.anchor.cell()),START_COLOUR,1/16F);
            if(view.refusal()!=null&&hit!=null){shown.add(ROUTE_SLOT);Outlines.line(ROUTE_SLOT,start.anchor.cell().getCenter(),(plan!=null&&plan.anchor!=null?plan.anchor.cell():hit.clicked()).getCenter(),REFUSED_COLOUR,1/16F);}
        }
        var removed=start==null&&player.isShiftKeyDown()&&hit!=null?removable(mc.level,hit):null;
        for(int i=0;removed!=null&&i<REMOVE_SLOTS.length&&i<removed.size();i++){shown.add(REMOVE_SLOTS[i]);Outlines.box(REMOVE_SLOTS[i],removed.get(i),REFUSED_COLOUR,1/16F);}
        if(start==null&&hit!=null&&view.age()%HUD_TICKS==0){var label=portLabel(mc.level,hit);if(label!=null)say(player,label);}
        boolean routeRefused=view.refusal()!=null;
        slot=hit!=null&&!player.isShiftKeyDown()&&(start==null||plan==null||routeRefused)?slot(mc.level,hit,outside,routeRefused):null;
        ports(mc.level,player,shown);openEnds(mc.level,player,shown);freeSlots(mc.level,hit,shown);
        if(slot!=null){var aimed=slot;shown.add(AIM_SLOT);Outlines.box(AIM_SLOT,square(aimed.cell,Direction.values()[aimed.through],RackGeometry.facePoint(aimed.frame,aimed.lane,aimed.through),Math.min(.45,aimed.lane.diameter()/2+.04)),aimed.refused?REFUSED_COLOUR:START_COLOUR,1/32F);}
        markers.adopt(shown);
    }

'''+s[b:]
s=s.replace('private static Plan plan(Level level, PipeAnchor.Click end, double diameter)', 'private static Plan plan(Level level, PipeAnchor.Click end, double diameter, Start selected)')
s=s.replace('var from = start.anchor;', 'var from = selected.anchor;')
a=s.index('    /** Start → end,');b=s.index('    /**\n     * Where plain route',a)
s=s[:a]+s[b:]
p.write_text(s,encoding='utf-8',newline='\r\n')
