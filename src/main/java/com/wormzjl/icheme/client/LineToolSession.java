package com.wormzjl.icheme.client;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.world.item.PipeLayingToolItem;
import com.wormzjl.icheme.world.level.block.PipeLaying;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import java.util.Objects;
import java.util.function.Consumer;

/** The sole interaction/session state machine for fluid, data and FE laying. Adapters own domain data, not workflow. */
@EventBusSubscriber(modid=IChemE.MOD_ID,value=Dist.CLIENT)
public final class LineToolSession<C,S,P> {
    static final int HUD_TICKS=5,RECHECK_TICKS=20,DROP_RANGE=96;
    record Context(Object dimension,int mode,Vec3 position,boolean screen,boolean shift){}
    record Selected<S>(S value,Component refusal){}
    record Immediate(Component refusal,Consumer<Boolean> send){}
    record View<C,S,P>(C click,S start,C hovered,P plan,Component refusal,boolean pending,int age){}
    interface Adapter<C,S,P> {
        C aimed();BlockPos cell(C click);Selected<S> select(C click);
        P plan(S start,C end);boolean same(C first,C second);Component refusal(P plan);
        Component started(S start);Component hud(S start,P plan);void show(View<C,S,P> view);void message(Component message);
        boolean removable(C click);void dismantle(boolean offHand,C click);void settings(boolean offHand);
        default Immediate immediate(C click){return null;}
        void lay(boolean offHand,C click,S start,C end,P plan);
    }
    private final Adapter<C,S,P> adapter;
    private C click,hovered,lastEnd;private S selected;private P plan;
    private Object dimension;private int mode,age;private long generation,pendingGeneration;
    private boolean pending,serverRefused;private Component refusal;
    LineToolSession(Adapter<C,S,P> adapter){this.adapter=Objects.requireNonNull(adapter);}
    View<C,S,P> view(){return new View<>(click,selected,hovered,plan,refusal,pending,age);}
    private void reset(){generation++;click=null;selected=null;hovered=null;lastEnd=null;plan=null;dimension=null;refusal=null;serverRefused=false;}
    void deactivate(){reset();adapter.show(view());}
    void disconnect(){pending=false;deactivate();}
    private void context(Context context){
        if(dimension!=null&&(!dimension.equals(context.dimension())||mode!=context.mode()))reset();
        if(selected!=null&&adapter.cell(click).getCenter().distanceToSqr(context.position())>DROP_RANGE*DROP_RANGE){reset();adapter.message(PipeLaying.message("dropped"));}
    }
    void tick(Context context){
        context(context);age++;hovered=context.screen()?null:adapter.aimed();
        if(selected!=null&&!pending)update(hovered,false);
        adapter.show(view());
        if(selected!=null&&!context.screen()&&age%HUD_TICKS==0){var text=refusal!=null?refusal:adapter.hud(selected,plan);if(text!=null)adapter.message(text);}
    }
    void press(Context context,boolean offHand){
        context(context);var aim=adapter.aimed();hovered=aim;
        if(context.shift()){
            if(selected!=null){reset();adapter.message(PipeLaying.message("cancelled"));}
            else if(aim!=null&&adapter.removable(aim)){if(!pending)adapter.dismantle(offHand,aim);}
            else adapter.settings(offHand);
            adapter.show(view());return;
        }
        if(aim==null||pending)return;
        if(selected==null){
            var action=adapter.immediate(aim);
            if(action!=null){if(action.refusal()!=null)adapter.message(action.refusal());else{begin();action.send().accept(offHand);}return;}
            var start=adapter.select(aim);
            if(start.value()==null){if(start.refusal()!=null)adapter.message(start.refusal());return;}
            generation++;selected=start.value();click=aim;lastEnd=null;plan=null;refusal=null;serverRefused=false;
            dimension=context.dimension();mode=context.mode();adapter.message(adapter.started(selected));
        }else{
            update(aim,true);
            if(refusal!=null)adapter.message(refusal);
            else if(plan!=null){begin();adapter.lay(offHand,click,selected,aim,plan);}
        }
        adapter.show(view());
    }
    private void begin(){pending=true;pendingGeneration=generation;}
    private void update(C aim,boolean pressed){
        boolean same=lastEnd!=null&&aim!=null&&adapter.same(lastEnd,aim);
        if(same&&(serverRefused||!pressed&&plan!=null&&age%RECHECK_TICKS!=0))return;
        lastEnd=aim;serverRefused=false;refusal=null;
        plan=aim==null?null:adapter.plan(selected,aim);if(plan!=null)refusal=adapter.refusal(plan);
    }
    void answered(Component message,boolean success){
        if(!pending){if(message!=null)adapter.message(message);return;}
        pending=false;
        if(pendingGeneration==generation){if(success)reset();else{refusal=message;serverRefused=true;}}
        if(message!=null)adapter.message(message);adapter.show(view());
    }

    // One input owner also arbitrates the hand and the held mode. The inactive adapter never handles that press.
    private static boolean armed=true;
    private static LineToolSession<?,?,?> active;
    private static int activeMode=-1;
    private static int mode(net.minecraft.world.item.ItemStack held){return PipeLayingToolItem.power(held)?2:PipeLayingToolItem.data(held)?1:0;}
    private static Context context(Minecraft mc,int mode){return new Context(mc.level.dimension(),mode,mc.player.position(),mc.screen!=null,mc.player.isShiftKeyDown());}
    private static LineToolSession<?,?,?> activate(Minecraft mc){
        var held=LayingToolAim.held(mc.player);int mode=mode(held);
        var session=mode==0?PipeLayingClient.session():CableLayingClient.session();
        if(active!=session||activeMode!=mode){if(active!=null)active.deactivate();active=session;activeMode=mode;}
        return session;
    }
    @SubscribeEvent public static void use(InputEvent.InteractionKeyMappingTriggered event){
        var mc=Minecraft.getInstance();if(!event.isUseItem()||mc.player==null||mc.level==null||!LayingToolAim.holding(mc.player))return;
        if(mc.player.getItemInHand(event.getHand())!=LayingToolAim.held(mc.player))return;
        event.setCanceled(true);event.setSwingHand(false);if(!armed)return;armed=false;
        var session=activate(mc);session.press(context(mc,activeMode),event.getHand()==net.minecraft.world.InteractionHand.OFF_HAND);
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        var mc=Minecraft.getInstance();if(!mc.options.keyUse.isDown())armed=true;
        if(mc.player==null||mc.level==null){AssemblyToolClient.watch(null,false);PipeLayingClient.session().disconnect();CableLayingClient.session().disconnect();active=null;activeMode=-1;return;}
        boolean holding=mc.player!=null&&mc.level!=null&&LayingToolAim.holding(mc.player);
        AssemblyToolClient.watch(mc.player,holding&&mode(LayingToolAim.held(mc.player))==0);
        if(!holding){if(active!=null)active.deactivate();active=null;activeMode=-1;return;}
        activate(mc).tick(context(mc,activeMode));
    }
}
