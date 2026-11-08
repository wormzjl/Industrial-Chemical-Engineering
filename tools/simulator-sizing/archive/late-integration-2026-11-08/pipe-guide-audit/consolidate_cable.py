from pathlib import Path
p=Path('src/main/java/com/wormzjl/icheme/client/CableLayingClient.java');s=p.read_text(encoding='utf-8')
s=s.replace('    private static CableLaying.End start,end,hovered;private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;\n    private static CableMedium startedMedium;','    // Read-only drawing projection of the common session.\n    private static CableLaying.End start,end,hovered;')
s=s.replace('    private static Component refusal;private static boolean serverRefused;','    private static Component refusal;')
s=s.replace('    private static UUID request;private static boolean armed=true,sent;private static int age;', '    private static UUID request;private static boolean removal;private static int age;')
a=s.index('    @SubscribeEvent public static void use(');b=s.index('    private static void updateMarkers(',a)
s=s[:a]+'''    private record Plan(CableLaying.End end,CableLaying.Anchor from,CableLaying.Anchor to,PipeRoute.Result route,Component refusal,List<DataBus.Cable> ghost,List<DataBus.Cable> hosts){}
    private static final LineToolSession<CableLaying.End,CableLaying.End,Plan> SESSION=new LineToolSession<>(new LineToolSession.Adapter<>(){
        public CableLaying.End aimed(){return CableLayingClient.aimed();}
        public BlockPos cell(CableLaying.End click){return click.clicked();}
        public LineToolSession.Selected<CableLaying.End> select(CableLaying.End click){
            var mc=Minecraft.getInstance();var anchor=CableLaying.resolve(mc.level,click,DataControlRenderer.ports(mc.level,mc.player.position(),96,medium()),medium());
            return new LineToolSession.Selected<>(anchor==null?null:click,anchor==null?noRoute():null);
        }
        public Plan plan(CableLaying.End selected,CableLaying.End end){return build(selected,end);}
        public boolean same(CableLaying.End a,CableLaying.End b){
            if(!a.clicked().equals(b.clicked())||a.face()!=b.face()||a.lane()!=b.lane()||!Objects.equals(a.port(),b.port()))return false;
            var mc=Minecraft.getInstance();
            return a.port()!=null||a.lane()>0||!(mc.level.getBlockEntity(a.clicked()) instanceof PipeRackBlockEntity rack)
                    ||Objects.equals(PipeAnchor.aimed(rack,a.local(),medium().outside()),PipeAnchor.aimed(rack,b.local(),medium().outside()));
        }
        public Component refusal(Plan plan){return plan.refusal();}
        public Component started(CableLaying.End selected){return Component.translatable("gui.icheme."+(medium()==CableMedium.POWER?"power":"data")+".started");}
        public Component hud(CableLaying.End selected,Plan plan){
            if(plan==null)return Component.translatable("gui.icheme."+(medium()==CableMedium.POWER?"power":"data")+".started");
            var p=Minecraft.getInstance().player;return LineToolFeedback.route(plan.from().placement(),plan.to().placement(),plan.route(),medium().outside(),p.getAbilities().instabuild,(medium()==CableMedium.POWER?ModItems.POWER_CABLE:ModItems.DATA_CABLE).get().getDescription());
        }
        public void show(LineToolSession.View<CableLaying.End,CableLaying.End,Plan> view){project(view);}
        public void message(Component message){var p=Minecraft.getInstance().player;if(p!=null)p.displayClientMessage(message,true);}
        public boolean removable(CableLaying.End click){return CableLaying.cables(Minecraft.getInstance().level,click.clicked()).stream().anyMatch(c->c.site().lane()==click.lane()&&c.geometry().medium()==medium());}
        public void dismantle(boolean offHand,CableLaying.End click){removal=true;request=ControlNetwork.cable(offHand,true,medium(),click,click,List.of(),List.of());}
        public void settings(boolean offHand){FluidNetwork.openPipeLaying(offHand);}
        public LineToolSession.Immediate immediate(CableLaying.End click){
            var mc=Minecraft.getInstance();var ports=DataControlRenderer.ports(mc.level,mc.player.position(),96,medium());var port=click.port()==null?null:ports.get(click.port());
            if(port==null||!(mc.level.getBlockEntity(port.cell()) instanceof ConduitBlockEntity wire)||wire.geometry().medium()!=medium()||Objects.equals(wire.cable().endpoints().get(port.face()),port.id()))return null;
            var attachment=CableLaying.attachment(mc.level,click,ports,medium());var reason=attachment==null?noRoute():LinePlacement.check(mc.level,attachment.plan());
            return new LineToolSession.Immediate(reason,offHand->{removal=false;request=ControlNetwork.cable(offHand,false,medium(),click,click,List.of(),List.of());});
        }
        public void lay(boolean offHand,CableLaying.End click,CableLaying.End selected,CableLaying.End end,Plan plan){removal=false;request=ControlNetwork.cable(offHand,false,medium(),click,end,plan.route().cells().stream().map(CableLaying::block).toList(),plan.route().options());}
    });
    static LineToolSession<CableLaying.End,CableLaying.End,Plan> session(){return SESSION;}
    private static Component noRoute(){return Component.translatable("gui.icheme."+(medium()==CableMedium.POWER?"power":"data")+".no_route");}
    private static Plan build(CableLaying.End selected,CableLaying.End end){
        var mc=Minecraft.getInstance();var ports=DataControlRenderer.ports(mc.level,mc.player.position(),96,medium());
        var a=CableLaying.resolve(mc.level,selected,ports,medium());var b=CableLaying.resolve(mc.level,end,ports,medium());
        Component reason=a==null||b==null?noRoute():null;PipeRoute.Result path=null;List<DataBus.Cable> wires=List.of(),hosts=List.of();
        if(reason==null){var fit=PipeAnchor.refuses(a.placement(),b.placement(),medium().outside());reason=fit==null?LinePlacement.checkLeads(mc.level,CableLaying.leads(a,b)):PipeLaying.refusal(fit);}
        if(reason==null){path=LinePlacement.search(mc.level,a.placement(),b.placement(),medium().outside(),CableLaying.leads(a,b));if(!path.found())reason=PipeLaying.refusal(path.refusal());}
        if(reason==null){var edits=CableLaying.hosts(a,b,site->CableLaying.cables(mc.level,site.cell()).stream().filter(c->c.site().equals(site)).findFirst().orElse(null));reason=edits.refusal();hosts=edits.cables();}
        if(reason==null)wires=CableLaying.design(mc.level,a,b,path,medium());
        return new Plan(end,a,b,path,reason,wires,hosts);
    }
    public static void answered(UUID ticket,boolean success,String detail){
        if(!Objects.equals(ticket,request))return;request=null;
        SESSION.answered(success?Component.translatable("gui.icheme."+(medium()==CableMedium.POWER?"power":"data")+(removal?".removed":".laid")):Component.literal(detail),success);
    }
    private static void project(LineToolSession.View<CableLaying.End,CableLaying.End,Plan> view){
        start=view.start();hovered=view.hovered();var plan=view.plan();end=plan==null?null:plan.end();age=view.age();refusal=view.refusal();
        route=plan==null?null:plan.route();from=plan==null?null:plan.from();to=plan==null?null:plan.to();
        ghost=plan==null||refusal!=null?List.of():plan.ghost();hostGhost=plan==null||refusal!=null?List.of():plan.hosts();
        var mc=Minecraft.getInstance();
        if(mc.player==null||mc.level==null||!holding(mc.player)||mc.screen!=null){markers.clear();nearbyMarks=List.of();markedLevel=null;markedMedium=null;attachGhost=null;return;}
        attachGhost=start==null&&hovered!=null&&hovered.port()!=null?CableLaying.attachment(mc.level,hovered,DataControlRenderer.ports(mc.level,mc.player.position(),96,medium()),medium()):null;
        attachRefused=attachGhost!=null&&LinePlacement.check(mc.level,attachGhost.plan())!=null;
        updateMarkers(mc);
    }
'''+s[b:]
p.write_text(s,encoding='utf-8',newline='\r\n')
