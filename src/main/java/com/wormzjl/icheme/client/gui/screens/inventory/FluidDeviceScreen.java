package com.wormzjl.icheme.client.gui.screens.inventory;
import com.wormzjl.icheme.science.display.DisplayUnit.PressureUnit;
import com.wormzjl.icheme.science.display.DisplayUnit;
import com.wormzjl.icheme.science.display.DisplayUnit.FlowRateUnit;
import com.wormzjl.icheme.science.display.DisplayUnit.TemperatureUnit;

import com.wormzjl.icheme.client.ClientConfig;
import com.wormzjl.icheme.client.gui.common.*;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.GuiCanvas;
import com.wormzjl.icheme.client.gui.common.kit.KitWidgets;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.common.kit.SkinButton;
import com.wormzjl.icheme.client.gui.common.kit.SkinField;
import com.wormzjl.icheme.client.gui.common.kit.WindowScale;
import com.wormzjl.icheme.network.FluidNetwork;
import com.wormzjl.icheme.runtime.fluid.HeatUnit;
import com.wormzjl.icheme.runtime.fluid.SlurryFeed;
import com.wormzjl.icheme.science.fluid.network.PipeResistance;
import com.wormzjl.icheme.science.fluid.state.ParticleSize;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.science.material.MaterialName;
import com.wormzjl.icheme.science.material.SolidMaterialCatalog;
import com.wormzjl.icheme.world.inventory.FluidDeviceMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import java.util.*;
import static com.wormzjl.icheme.client.gui.common.ProcessUi.*;

/** Shared process controls and phase tables. Opening replays the last published snapshot; new state follows engine buckets. */
public final class FluidDeviceScreen extends AbstractContainerScreen<FluidDeviceMenu> {
    private final Map<String,NumericDraft> fields=new LinkedHashMap<>();
    private final Map<String,EditBox> editors=new LinkedHashMap<>();
    private final List<Label> labels=new ArrayList<>();
    private final ProcessScroll scroll=new ProcessScroll();
    private RelativeComposition composition;
    private ComponentDropdown componentDropdown;
    // The units and the table and flow basis are the client's, one switch for every screen (ClientConfig).
    private TemperatureUnit temperatureUnit=ClientConfig.temperature();
    private PressureUnit pressureUnit=ClientConfig.pressure();
    private FlowRateUnit flowRateUnit=ClientConfig.flowTime();
    private boolean mass=ClientConfig.basis()==ClientConfig.Basis.MASS;
    /** The Phases group's basis, a display key of the door column's own: kept while the game runs. */
    private static PhaseRatioBasis preferredPhaseBasis=PhaseRatioBasis.VOLUME;
    private PhaseRatioBasis phaseBasis=preferredPhaseBasis;
    private String[][] particles=new String[64][3];
    private long revision=Long.MIN_VALUE,messageRevision;
    private int page,phase=2;
    private boolean templates,rebuildNext,hasConnections,powerOn=true,phaseSelected;
    private String search="",message="",hovered;
    private V3ClientPresets library;
    private V3ClientPresets.Snapshot mixtureSnapshot;
    private record Choice(String label,Runnable select){}
    private List<Choice> choices=List.of();
    /** Equipment datasheet (page 5), from the client's synced cells (VesselScreen.sheet); null for one-block devices. */
    private com.wormzjl.icheme.client.gui.common.DialogColumns.Sheet datasheet;

    public FluidDeviceScreen(FluidDeviceMenu menu,Inventory inventory,Component title){super(menu,inventory,title);}
    private static String tr(String key,Object...args){return Component.translatable("gui.icheme.fluid."+key,args).getString();}
    @Override public void removed(){super.removed();WindowScale.restore(minecraft);}
    @Override protected void init(){
        WindowScale.apply(minecraft,this,FluidGuiLayout.HEIGHT,this::fits);
        imageWidth=FluidGuiLayout.imageWidth(width);imageHeight=FluidGuiLayout.imageHeight(height);
        super.init();titleLabelY=inventoryLabelY=-1000;
        if(library==null)library=new V3ClientPresets(minecraft.gameDirectory.toPath(),name->{
            try{return minecraft.getResourceManager().getResource(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("icheme",name.substring("assets/icheme/".length()))).orElseThrow().open();}
            catch(java.io.IOException|NoSuchElementException e){return null;}
        });
        var vessel=minecraft.level==null?null:com.wormzjl.icheme.world.level.block.EquipmentStructure.find(minecraft.level,menu.position());
        datasheet=vessel==null?null:com.wormzjl.icheme.client.gui.screens.VesselScreen.sheet(vessel);
        refresh();rebuild();
    }
    private boolean generator(){return menu.clientData()!=null&&menu.clientData().kind()==TopologyCompiler.Kind.GENERATOR;}
    private boolean pipe(){return menu.clientData()!=null&&menu.clientData().kind()==TopologyCompiler.Kind.PIPE;}
    private boolean transport(){return menu.clientData()!=null&&menu.clientData().view().pipeInfo()!=null;}
    private com.wormzjl.icheme.science.fluid.network.FlowControl.ValveSource valveSource=com.wormzjl.icheme.science.fluid.network.FlowControl.ValveSource.NONE;
    private boolean canEdit(){return !menu.debug()&&menu.clientData()!=null;}
    private boolean constructedMover(){var data=menu.clientData();return data!=null&&datasheet!=null&&(data.kind()==TopologyCompiler.Kind.PUMP||data.kind()==TopologyCompiler.Kind.COMPRESSOR);}
    /** An air cooler's side: its fans switch as a mover does (batch 2026-10-27-heat-exchanger-design). */
    private boolean fans(){var data=menu.clientData();return data!=null&&data.view().heat()!=null&&data.view().heat().fans()!=null;}
    /** A fired heater's coil: its burners switch and its set point is a field, both coils at once (W6). */
    private boolean burners(){var data=menu.clientData();return data!=null&&data.view().heat()!=null&&data.view().heat().burners()!=null;}
    /** An exchanger side or an air cooler: its control's mode, set point, low limit and manual share are fields (W4). */
    private boolean controllable(){var data=menu.clientData();return data!=null&&data.controls().heat()!=null;}
    /** The control mode as drafted, OFF while the switch stands at Off; the set points, low limit and bypass are drafts in {@link #fields}. */
    private HeatUnit.Mode heatMode=HeatUnit.Mode.OFF;
    /** The target the switch's On holds: the drafted mode while On (OFF: an air cooler's fixed fan speed); for a side loaded Off, the outlet temperature. */
    private HeatUnit.Mode heatTarget=HeatUnit.Mode.OUTLET_TEMPERATURE;
    /** The door column's Off and On switch: a generator's or a built mover's. A heat unit's fans and burners are on its Control tab. */
    private boolean switchable(){return generator()||constructedMover();}
    /** The Control tab (HEAT_GUI_PLAN.md section 2): every heat unit's sides and a kettle's pool. */
    private static final int CONTROL=6;
    /** The page a heat unit's screen opens on: the one last chosen this client session (the side selector keeps it). */
    private static int preferredHeatPage=0;
    private boolean heatPageSet;
    private com.wormzjl.icheme.runtime.fluid.FluidView.Heat heat(){var data=menu.clientData();return data==null?null:data.view().heat();}
    private com.wormzjl.icheme.science.equipment.HeatSides.Unit unitKind(){return com.wormzjl.icheme.science.equipment.HeatSides.Unit.valueOf(heat().unit());}
    private com.wormzjl.icheme.science.equipment.HeatSides.Side sideKind(){return com.wormzjl.icheme.science.equipment.HeatSides.Side.valueOf(heat().side());}
    /** A kettle's shell (W7): its pool, which the tubes heat; it has no control of its own. */
    private boolean pool(){return heat()!=null&&unitKind()==com.wormzjl.icheme.science.equipment.HeatSides.Unit.KETTLE&&sideKind()==com.wormzjl.icheme.science.equipment.HeatSides.Side.SHELL;}
    private String flowUnit(){return flowRateUnit.unit(mass?"kg":"kmol");}
    // Every page but the Control tab: the door column from x 10 to side(), its bar right of it, then the tables' HMI
    // screen. The screen takes the scale at which the window holds its design height (FluidGuiLayout.HEIGHT), lower where
    // its tallest column still does not fit, down to the minimum a player reads (WindowScale, fits): 2 at 1920x1080, where
    // the design size's 4 left a pump's column 128 px. There the column scrolls in its own view (the Control tab's view),
    // as the table area scrolls its rows.
    private int side(){return FluidGuiLayout.side(imageWidth);}
    private int hoverX=-1,hoverY=-1;
    private int tableX(){return side()+24;}
    private int tableWidth(){return imageWidth-tableX()-22;}
    // Page 4 is a tank's "Connections by face": one two-line row per connection in the table area, scrolled like the
    // other tables, so the list is readable at every GUI scale (it was in the device's column, below the tank's values,
    // and did not fit below about 323 scaled pixels of height). Page 5 is an equipment datasheet, scrolled the same way.
    private boolean tank(){return menu.clientData()!=null&&menu.clientData().kind()==TopologyCompiler.Kind.RESERVOIR;}
    // A heat unit's Overview has its stream switch and the stream's line above the phase tabs (HEAT_GUI_PLAN.md section 2).
    private int tableTop(){return page==0?(heat()!=null?164:130):page==1?166:page==3?116:page==4||page==5?100:142;}
    private int rowHeight(){return page==0||page==3?18:page==4?24:page==5?com.wormzjl.icheme.client.gui.common.DialogColumns.PITCH:25;}
    private int visibleRows(){return Math.max(1,(imageHeight-64-tableTop())/rowHeight());}
    private void refresh(){
        if(menu.messageRevision()!=messageRevision){messageRevision=menu.messageRevision();message=menu.message();}
        var data=menu.clientData();if(data==null)return;
        // A heat unit opens on the page last chosen on one, so the side selector lands on the other side's Control tab.
        if(!heatPageSet&&data.view().heat()!=null){heatPageSet=true;if(page==0&&preferredHeatPage==CONTROL){page=CONTROL;rebuildNext=true;}}
        boolean connected=pipe()&&data.view().pipeInfo().connections().size()>1;
        if(connected!=hasConnections){hasConnections=connected;if(!connected&&page==3)page=0;rebuildNext=true;}
        // An initially empty publication must not leave a newly flowing void on an absent default phase.
        // Once the player chooses a phase, retain that choice across later publications.
        if(!phaseSelected){
            int selected=data.kind()==TopologyCompiler.Kind.FILTER?3:phase;double largest=0;
            if(data.kind()!=TopologyCompiler.Kind.FILTER){
                var phases=amounts();
                for(int i=0;i<phases.length;i++){double amount=Arrays.stream(phases[i]).sum();if(amount>largest){largest=amount;selected=i;}}
                if(largest==0&&!displayedSolids().empty())selected=3;
            }
            if(selected!=phase){phase=selected;scroll.reset();rebuildNext=true;}
        }
        if(data.view().inputRevision()==revision)return;
        revision=data.view().inputRevision();var c=data.controls();
        powerOn=c.power()==FluidNetwork.Power.ON;valveSource=c.valveSource();
        fields.clear();fields.put("temperature",new NumericDraft(c.temperature(),temperatureUnit));
        fields.put("pressure",new NumericDraft(c.pressure(),pressureUnit));fields.put("diameter",new NumericDraft(c.diameter()));
        fields.put("volumeFlow",new NumericDraft(c.volumeFlow(),flowRateUnit));fields.put("maximumAddedPressure",new NumericDraft(c.maximumAddedPressure(),pressureUnit));
        fields.put("maximumPressureRatio",new NumericDraft(c.maximumPressureRatio()));fields.put("massFlow",new NumericDraft(c.massFlow(),flowRateUnit));
        // A controllable heat side's control (W4): the set point of the other mode starts at 40 °C or 0 kW; no low limit is blank.
        var heat=c.heat();heatMode=heat==null?HeatUnit.Mode.OFF:heat.mode();heatTarget=heatMode==HeatUnit.Mode.OFF?HeatUnit.Mode.OUTLET_TEMPERATURE:heatMode;
        fields.put("heatTemperature",new NumericDraft(heat!=null&&heat.mode()==HeatUnit.Mode.OUTLET_TEMPERATURE?heat.target():313.15,temperatureUnit));
        fields.put("heatDuty",new NumericDraft(heat!=null&&heat.mode()==HeatUnit.Mode.DUTY?heat.target()/1000:0));
        fields.put("heatRatio",new NumericDraft(heat!=null&&heat.mode()==HeatUnit.Mode.RATIO?heat.target():1));
        var low=new NumericDraft(heat!=null&&heat.lowLimit()>0?heat.lowLimit():0,temperatureUnit);if(heat==null||heat.lowLimit()==0)low.text("");fields.put("lowLimit",low);
        fields.put("bypass",new NumericDraft(heat==null?0:heat.share()*100));
        // An air cooler's switch stands On while a controller holds a target or its fans run at a fixed speed (mode OFF, share above 0).
        if(heat!=null&&fans()){powerOn=heat.mode()!=HeatUnit.Mode.OFF||heat.share()>0;if(heat.mode()==HeatUnit.Mode.OFF&&heat.share()>0)heatTarget=HeatUnit.Mode.OFF;}
        fields.put("fans",new NumericDraft(heat!=null&&heat.share()>0?heat.share()*100:100));
        composition=new RelativeComposition(data.components(),data.molecularWeights(),c.composition());
        if(mass)composition.toggleBasis();
        loadSolids(c.solids());
        templates=false;scroll.reset();rebuildNext=true;
    }
    private void loadSolids(SlurryFeed solids){
        particles=new String[64][3];int i=0;
        for(var grade:solids.grades())particles[i++]=new String[]{grade.material(),new java.math.BigDecimal(grade.size().metres()).scaleByPowerOfTen(6).toPlainString(),Double.toString(grade.massShare())};
        fields.put("solidFraction",new NumericDraft(solids.volumeFraction()*100));
    }
    private Button button(String text,int x,int y,int w,Runnable action){
        return addRenderableWidget(ProcessUi.button(text,leftPos+x,topPos+y,w,action));
    }
    private Button action(String key,int x,int y,int w,Runnable run){
        var b=button(tr(key),x,y,w,run);b.setTooltip(Tooltip.create(Component.translatable("gui.icheme.fluid."+key+".hint")));return b;
    }
    // The panel door (GUI_TOOLKIT_PLAN.md sections 10 and 14): the frame, the title row, the tabs, the footer and the
    // door column (switches, set points, meters) are drawn by the theme's skin; the tables and the drawing sit in its HMI
    // screen, in the screens' dark palette, with the game's own widgets.
    private static Skin skin(){return ClientConfig.skin();}
    /** A button on the door, drawn by the theme's skin. */
    private SkinButton doorButton(String text,int x,int y,int w,Runnable action){
        var b=new SkinButton(leftPos+x,topPos+y,w,18,Component.literal(text),p->action.run(),FluidDeviceScreen::skin);
        b.setTooltip(Tooltip.create(Component.literal(text)));return addRenderableWidget(b);
    }
    private SkinButton doorAction(String key,int x,int y,int w,Runnable run){
        var b=doorButton(tr(key),x,y,w,run);b.setTooltip(Tooltip.create(Component.translatable("gui.icheme.fluid."+key+".hint")));return b;
    }
    /** The theme key on the title row: a square with the contrast sign (owner: "an icon instead of word"). */
    private int themeWidth(){return 18;}
    private EditBox field(String key,String label,int x,int y,int width){
        labels.add(new Label(label,x,y,width));
        var box=new EditBox(font,leftPos+x,topPos+y+12,width,18,Component.literal(label));
        box.setMaxLength(48);box.setValue(fields.get(key).text());box.setResponder(v->fields.get(key).text(v));
        box.setTooltip(Tooltip.create(Component.literal(label)));box.setEditable(canEdit());editors.put(key,addRenderableWidget(box));
        return box;
    }
    private void changePage(int target){if(heat()!=null&&(target==0||target==CONTROL))preferredHeatPage=target;page=target;templates=false;scroll.reset();doorView.reset();rebuild();}
    private void toggleBasis(){
        try{if(composition!=null){composition.toggleBasis();mass=composition.mass();}else mass=!mass;ClientConfig.basis(mass?ClientConfig.Basis.MASS:ClientConfig.Basis.MOLE);rebuild();}
        catch(IllegalArgumentException e){message=tr("invalid_composition");}
    }
    /** Validate all affected drafts first, so a refused unit change loses no input or precision. */
    private boolean convertUnit(DisplayUnit next,String... keys){
        try{
            var converted=new LinkedHashMap<String,NumericDraft>();
            // A blank field (a heat side's low limit: none) stays blank in the new unit.
            for(var key:keys)if(fields.containsKey(key)){var draft=fields.get(key);if(draft.text().isBlank()){var blank=new NumericDraft(0,next);blank.text("");converted.put(key,blank);}else converted.put(key,draft.in(next));}
            fields.putAll(converted);return true;
        }catch(IllegalArgumentException e){message=tr("invalid_unit");return false;}
    }
    private void toggleTemperature(){
        var next=temperatureUnit.next();if(convertUnit(next,"temperature","heatTemperature","lowLimit")){temperatureUnit=next;ClientConfig.temperature(next);rebuild();}
    }
    private void toggleFlowRate(){
        var next=flowRateUnit.next();if(convertUnit(next,"volumeFlow","massFlow")){flowRateUnit=next;ClientConfig.flowTime(next);rebuild();}
    }
    private void togglePressure(){
        var next=pressureUnit.next();if(convertUnit(next,"pressure","maximumAddedPressure")){pressureUnit=next;ClientConfig.pressure(next);rebuild();}
    }
    private void rebuild(){
        rebuildNext=false;String focused=editors.entrySet().stream().filter(e->e.getValue()==getFocused()).map(Map.Entry::getKey).findFirst().orElse(null);
        int cursor=focused==null?0:editors.get(focused).getCursorPosition();
        boolean dropdownOpen=componentDropdown!=null&&componentDropdown.expanded();
        clearWidgets();editors.clear();labels.clear();componentDropdown=null;doorWidgets.clear();doorColumns=List.of();
        var data=menu.clientData();
        var tabs=new ArrayList<String>();var pages=new ArrayList<Integer>();tabs.add("overview");pages.add(0);
        if(heat()!=null){tabs.add("control");pages.add(CONTROL);}
        if(generator()&&canEdit()){tabs.add("composition");pages.add(1);tabs.add("solids");pages.add(2);}
        if(pipe()&&data.view().pipeInfo().connections().size()>1){tabs.add("connections");pages.add(3);}
        if(tank()){tabs.add("outlets");pages.add(4);}
        if(datasheet!=null){tabs.add("datasheet");pages.add(5);}
        var tabSlots=FluidGuiLayout.tabs(imageWidth,tabs.size());
        for(int i=0;i<tabs.size();i++){int target=pages.get(i);var slot=tabSlots.get(i);doorAction(tabs.get(i),slot.x(),29,slot.width(),()->changePage(target)).latched(true).active=page!=target;}
        int themeWidth=themeWidth();
        // The theme key shows the contrast sign; its hover names the panel shown, its message stays for narration.
        String theme=tr(ClientConfig.theme()==ClientConfig.Theme.DARK?"theme.dark":"theme.light");
        doorButton(theme,imageWidth-10-themeWidth,5,themeWidth,()->{ClientConfig.toggleTheme();rebuild();}).icon(Skin.Icon.THEME)
            .setTooltip(Tooltip.create(Component.literal(theme+"\n").append(Component.translatable("gui.icheme.fluid.theme.hint"))));
        var footer=FluidGuiLayout.footer(imageWidth);int by=imageHeight-27;
        var close=footer.get(0);doorAction("close",close.x(),by,close.width(),this::onClose);
        // The basis and the units are display keys on their strip (renderBg): they change what the screen shows, not the
        // plant. Each shows its value alone; its hover says what it switches.
        var basis=footer.get(2);doorButton(tr(mass?"mass_basis_short":"mole_basis_short"),basis.x(),by,basis.width(),this::toggleBasis).soft(true)
            .setTooltip(Tooltip.create(Component.translatable("gui.icheme.fluid.table_basis.hint")));
        var temperature=footer.get(3);doorButton(temperatureUnit.symbol(),temperature.x(),by,temperature.width(),this::toggleTemperature).soft(true)
            .setTooltip(Tooltip.create(Component.translatable("gui.icheme.column.temperature_unit.hint")));
        var pressure=footer.get(4);doorButton(pressureUnit.symbol(),pressure.x(),by,pressure.width(),this::togglePressure).soft(true)
            .setTooltip(Tooltip.create(Component.translatable("gui.icheme.fluid.pressure_unit.hint")));
        var rate=footer.get(5);doorButton(flowRateUnit.suffix(),rate.x(),by,rate.width(),this::toggleFlowRate).soft(true)
            .setTooltip(Tooltip.create(Component.translatable("gui.icheme.fluid.flow_unit.hint")));
        if(data==null)return;
        // A heat side applies its control from its Control tab only.
        if(canEdit()&&data.kind()!=TopologyCompiler.Kind.RESERVOIR&&data.kind()!=TopologyCompiler.Kind.FILTER&&(data.kind()!=TopologyCompiler.Kind.HEAT_SIDE||page==CONTROL&&(fans()||burners()||controllable()))){var apply=footer.get(1);doorAction("apply",apply.x(),by,apply.width(),this::apply).primary(true);}
        if(page==CONTROL){
            buildControl();
            if(focused!=null&&editors.containsKey(focused)){setFocused(editors.get(focused));editors.get(focused).setCursorPosition(cursor);}
            return;
        }
        buildDevice();
        if(page==0)buildContents();else if(page==1)buildComposition();else if(page==2)buildSolids();
        else if(page==4)configureScroll(data.view().outlets().size());else if(page==5)configureScroll(datasheetGlass().lines().size());
        else configureScroll(data.view().pipeInfo().connections().size());
        if(focused!=null&&editors.containsKey(focused)){setFocused(editors.get(focused));editors.get(focused).setCursorPosition(cursor);}
        if(componentDropdown!=null&&"search".equals(focused))componentDropdown.expanded(dropdownOpen);
    }
    /** The Overview's stream (HEAT_GUI_PLAN.md section 2): an index into {@link #streams}. */
    private int stream;
    /** A heat unit's streams for the Overview's switch: each port of the unit, this side's first; a vessel's own contents (null) ahead of them. */
    private List<com.wormzjl.icheme.runtime.fluid.FluidView.Port> streams(){
        var h=heat();if(h==null||h.ports().isEmpty())return List.of();
        var list=new ArrayList<com.wormzjl.icheme.runtime.fluid.FluidView.Port>();if(pool())list.add(null);list.addAll(h.ports());return list;
    }
    /** The port the Overview shows, or null for the node's own contents (and on every other page). */
    private com.wormzjl.icheme.runtime.fluid.FluidView.Port selectedPort(){
        if(page!=0)return null;var list=streams();return stream>=0&&stream<list.size()?list.get(stream):null;
    }
    private String portName(String role){return Component.translatable(HeatDiagram.KEY+"port."+role.toLowerCase(Locale.ROOT)).getString();}
    private void buildContents(){
        int w=(tableWidth()-12)/4,tabsY=82;
        var list=streams();
        if(!list.isEmpty()){
            // The stream switch: one button per port, the selected one inactive, as the phase tabs are.
            int n=list.size(),bw=Math.max(20,(tableWidth()-4*(n-1))/n);
            for(int i=0;i<n;i++){
                int selected=i;var port=list.get(i);String label=port==null?tr("stream.contents"):portName(port.role());
                var b=button(label,tableX()+i*(bw+4),82,bw,()->{stream=selected;scroll.reset();rebuild();});b.active=stream!=i;
                b.setTooltip(Tooltip.create(Component.literal(label+"\n"+tr("stream.hint"))));
            }
            tabsY=118;
        }
        for(int i=0;i<4;i++){
            int selected=i;var tab=button(tr((tableWidth()<240?"phase_tab.":"phase.")+i),tableX()+i*(w+4),tabsY,w,()->{phase=selected;phaseSelected=true;scroll.reset();rebuild();});
            tab.active=phase!=i;tab.setTooltip(Tooltip.create(Component.literal(tr("phase."+i))));
        }
        configureScroll(phase==3?shownSolids().size():present().size());
    }
    private void configureScroll(int size){scroll.configure(leftPos+imageWidth-16,topPos+tableTop(),imageHeight-64-tableTop(),visibleRows(),size);}
    private void openTemplates(){
        mixtureSnapshot=library.refresh(V3ClientPresets.Kind.MIXTURE);choices=mixtureChoices();templates=true;scroll.reset();rebuild();
    }
    private List<Choice> mixtureChoices(){
        var data=menu.clientData();var list=new ArrayList<Choice>();
        for(var preset:data.presets())list.add(new Choice(preset.name(),()->{
            var replacement=new RelativeComposition(data.components(),data.molecularWeights(),preset.moleFractions());
            if(mass)replacement.toggleBasis();loadMixture(replacement,preset.solids());
        }));
        if(mixtureSnapshot!=null)for(var entry:mixtureSnapshot.entries()){
            var mixture=(V3ClientPresets.Mixture)entry.preset();
            if(!data.components().containsAll(mixture.amounts().keySet()))continue;
            String name=mixture.translationKey().isBlank()?mixture.name():Component.translatable(mixture.translationKey()).getString();
            list.add(new Choice(name+" · "+tr(entry.bundled()?"bundled":"custom"),()->{
                var replacement=new RelativeComposition(data.components(),data.molecularWeights(),new double[data.components().size()]);
                replacement.load(mixture.amounts(),mixture.mass());loadMixture(replacement,mixture.solids());
            }));
        }
        return List.copyOf(list);
    }
    /** Every preset choice loads a whole feed: its fluid composition and its solids (none clears the previous grades). */
    private void loadMixture(RelativeComposition replacement,SlurryFeed solids){
        composition=replacement;mass=replacement.mass();loadSolids(solids);
        templates=false;scroll.reset();message=tr("mixture_loaded");rebuild();
    }
    private void buildComposition(){
        int x=tableX(),w=tableWidth();
        if(templates){
            int bw=(w-12)/3;
            action("back",x,82,bw,()->{templates=false;scroll.reset();rebuild();});
            action("refresh",x+bw+6,82,bw,this::openTemplates);
            action("folder",x+2*(bw+6),82,bw,()->net.minecraft.Util.getPlatform().openPath(library.directory(V3ClientPresets.Kind.MIXTURE)));
            configureScroll(choices.size());
            for(int i=scroll.value();i<Math.min(choices.size(),scroll.value()+visibleRows());i++){
                var choice=choices.get(i);button(choice.label(),x,tableTop()+(i-scroll.value())*25,w-4,choice.select());
            }return;
        }
        int bw=Math.max(60,(w-12)/3);
        action("load_mixture",x,82,bw,this::openTemplates);
        action("new_mixture",x+bw+6,82,bw,()->{composition.clear();scroll.reset();message="";rebuild();});
        var options=new ArrayList<ComponentSearch.Option>();var data=menu.clientData();
        for(int i=0;i<data.components().size();i++)if(!composition.contains(i))
            options.add(new ComponentSearch.Option(i,material(i),data.components().get(i)));
        componentDropdown=new ComponentDropdown(font,leftPos+x,topPos+112,Math.min(360,w-4),imageHeight-178,options,search,
            value->search=value,id->{composition.add(id);search="";rebuild();});
        editors.put("search",addRenderableWidget(componentDropdown));
        var ids=composition.rows();configureScroll(ids.size());
        int amount=x+w/2,norm=amount+76;
        for(int i=scroll.value();i<Math.min(ids.size(),scroll.value()+visibleRows());i++){
            int id=ids.get(i),y=tableTop()+(i-scroll.value())*25;
            var box=new EditBox(font,leftPos+amount,topPos+y,64,18,Component.translatable("gui.icheme.fluid.relative"));
            box.setValue(composition.get(id));box.setMaxLength(32);box.setResponder(v->composition.set(id,v));
            box.setTooltip(Tooltip.create(Component.translatable("gui.icheme.column.relative_amount.hint")));editors.put("comp"+id,addRenderableWidget(box));
            button("×",x+w-26,y,22,()->{composition.remove(id);rebuild();}).setTooltip(Tooltip.create(Component.translatable("gui.icheme.column.remove_component.hint")));
        }
    }
    private void buildSolids(){
        field("solidFraction",tr("solid_fraction"),tableX(),82,Math.min(160,tableWidth()));
        configureScroll(64);
        for(int i=scroll.value();i<Math.min(64,scroll.value()+visibleRows());i++)for(int c=0;c<3;c++){
            int index=i,col=c,w=tableWidth(),x=tableX()+(c==0?0:c==1?w/2:3*w/4);
            int width=c==0?w/2-6:w/4-6;
            var box=new EditBox(font,leftPos+x,topPos+142+(i-scroll.value())*25,width,18,Component.literal(tr("solid_col."+c)));
            box.setMaxLength(c==0?128:64);box.setValue(particles[i][c]==null?"":particles[i][c]);
            box.setResponder(v->particles[index][col]=v);editors.put("solid"+i+"_"+c,addRenderableWidget(box));
        }
    }
    private SlurryFeed solids(){
        var grades=new ArrayList<SlurryFeed.Grade>();
        for(var row:particles){
            if(Arrays.stream(row).allMatch(v->v==null||v.isBlank()))continue;
            if(Arrays.stream(row).anyMatch(v->v==null||v.isBlank()))throw new IllegalArgumentException(tr("invalid_solid"));
            grades.add(new SlurryFeed.Grade(row[0].strip(),ParticleSize.micrometres(row[1].strip()),Double.parseDouble(row[2])));
        }
        return new SlurryFeed(fields.get("solidFraction").value()/100,grades);
    }
    private void apply(){
        if(!canEdit())return;
        // A compressor's ratio outside its bounds is named with them (the bounds Controls validates with), not the generic message.
        if(menu.clientData().kind()==TopologyCompiler.Kind.COMPRESSOR){
            double ratio;try{ratio=fields.get("maximumPressureRatio").value();}catch(IllegalArgumentException e){ratio=Double.NaN;}
            if(!FluidNetwork.pressureRatioAllowed(ratio)){message=tr("invalid_ratio",(Object[])FluidNetwork.pressureRatioBoundsText());return;}
        }
        try{
            var controls=new FluidNetwork.Controls(fields.get("temperature").si(),fields.get("pressure").si(),
                fields.get("diameter").value(),PipeResistance.DEFAULT_ROUGHNESS_METRES,fields.get("volumeFlow").si(),
                fields.get("maximumAddedPressure").si(),composition.moleFractions(),solids(),fields.get("maximumPressureRatio").value(),powerOn?FluidNetwork.Power.ON:FluidNetwork.Power.OFF,valveSource,fields.get("massFlow").si(),
                heatControl());
            FluidNetwork.sendEdit(menu,revision,controls);message=tr("waiting");
        }catch(IllegalArgumentException e){message=tr("invalid_input");}
    }
    /** A controllable heat side's control as drafted (W4), null for another device: the set point of its mode (K, or the
     * duty in kW as W), the low limit (blank for none) and the manual share: an exchanger side's bypass in %; an air
     * cooler's fans, still while Off, at the fixed fan speed in % with no controller. A controller starts from the
     * applied share. An invalid draft throws, which the caller reports. */
    private HeatUnit.Control heatControl(){
        if(!controllable())return null;
        double target=switch(heatMode){case OUTLET_TEMPERATURE->fields.get("heatTemperature").si();case DUTY->fields.get("heatDuty").value()*1000;case RATIO->fields.get("heatRatio").value();default->0;};
        var low=fields.get("lowLimit");double lowLimit=low.text().isBlank()?0:low.si();
        String manual=fans()?"fans":"bypass";
        double share=fans()&&!powerOn?0:heatMode==HeatUnit.Mode.OFF?fields.get(manual).value()/100:menu.clientData().controls().heat().share();
        return new HeatUnit.Control(heatMode,target,lowLimit,share);
    }
    private double[][] amounts(){
        var data=menu.clientData();var view=data.view();
        var port=selectedPort();if(port!=null)return port.phaseMoles();
        if(view.pipeInfo()!=null)return view.pipeInfo().contents().phaseMoles();
        return view.state()==null?new double[3][data.components().size()]:view.state().phaseMoles();
    }
    private List<Integer> present(){
        double[] values=amounts()[Math.min(2,phase)];var ids=new ArrayList<Integer>();
        for(int i=0;i<values.length;i++)if(values[i]>0)ids.add(i);return ids;
    }
    private double displayedFlow(){
        var data=menu.clientData();var view=data.view();
        if(view.pipeInfo()!=null){
            if(view.intervalSeconds()<=0)return Double.NaN;
            var stream=view.pipeInfo().contents();
            if(mass)return flowRateUnit.display(stream.massKg()/view.intervalSeconds());
            double moles=0;for(var phase:stream.phaseMoles())for(double amount:phase)moles+=amount;
            return flowRateUnit.display(moles/1000/view.intervalSeconds());
        }
        if(tank())return flowRateUnit.display(mass?view.outlets().stream().mapToDouble(com.wormzjl.icheme.runtime.fluid.FluidView.Outlet::massFlow).sum():view.outlets().stream().mapToDouble(com.wormzjl.icheme.runtime.fluid.FluidView.Outlet::moleFlow).sum()/1000);
        double flow=view.massFlow()*(data.kind()==TopologyCompiler.Kind.VOID?-1:1);
        if(mass||flow==0)return flowRateUnit.display(flow);
        if(view.state()==null)return Double.NaN;
        double moles=0;for(var phase:view.state().phaseMoles())for(double amount:phase)moles+=amount;
        // The bulk mass includes suspended solids, which do not contribute fluid moles.
        return view.state().mass()>0?flowRateUnit.display(flow*moles/view.state().mass()/1000):Double.NaN;
    }
    private SolidInventory displayedSolids(){
        var data=menu.clientData();if(data==null)return SolidInventory.EMPTY;var view=data.view();
        // A port carries fluid phases only (HEAT_GUI_PLAN.md section 1).
        if(selectedPort()!=null)return SolidInventory.EMPTY;
        if(view.filter()!=null)return view.filter().captured();
        if(view.pipeInfo()!=null)return view.pipeInfo().contents().solids();
        return view.state()==null?SolidInventory.EMPTY:view.state().solids();
    }
    /** The Solids tab's rows: one per solid at its mean size (owner decision D23: one size per solid in each stock), the
     * arrivals since the stock's last refresh merged in as its next refresh merges them. Display arithmetic on what the
     * server sent. */
    private List<SolidInventory.Population> shownSolids(){return displayedSolids().oneSizePerSolid().populations();}
    /** A crystal is its component in the solid phase, for example "Carbon dioxide (solid)"; a custom solid is its material id. */
    private String solidName(SolidMaterialCatalog.SolidMaterial material){
        var crystal=material.crystal();if(crystal==null)return material.id();
        return tr("crystal_name",materialName(menu.clientData().materialNames().getOrDefault(crystal.component(),MaterialName.chemical(crystal.component()))));
    }
    private String material(int id){
        var data=menu.clientData();String name=data.components().get(id);
        return materialName(data.materialNames().getOrDefault(name,MaterialName.chemical(name)));
    }
    private void text(GuiGraphics g,String value,int x,int y,int color,int width){
        String shown=font.plainSubstrByWidth(value,Math.max(0,width));g.drawString(font,shown,x,y,color,false);
        // A line cut to its column reads in full on hover, so a narrow screen drops nothing silently.
        if(shown.length()<value.length()&&hoverX>=x&&hoverX<x+width&&hoverY>=y&&hoverY<y+9)hovered=value;
    }
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partialTick){
        ProcessUi.restoreCursor(minecraft,this);refresh();if(rebuildNext)rebuild();hovered=null;
        // The door column's widgets follow its scroll before they draw.
        doorWidgets.place(doorView,leftPos,topPos);
        super.render(g,mouseX,mouseY,partialTick);
        if(hovered!=null&&(componentDropdown==null||!componentDropdown.popupContains(mouseX,mouseY))){
            var lines=new ArrayList<net.minecraft.util.FormattedCharSequence>();
            for(var line:hovered.split("\\n"))lines.addAll(font.split(Component.literal(line),Math.max(160,width/2)));
            g.renderTooltip(font,lines,mouseX,mouseY);
        }
        if(componentDropdown!=null)componentDropdown.renderSuggestions(g,mouseX,mouseY);
    }
    /** The drawing's box inside the Control tab's HMI screen, as {@link #renderBg} drew it this frame. */
    private Rect drawingBox;
    /** Every page but the Control tab: the tables' HMI screen right of the door column, from below the status to the footer's divider. */
    private Rect pageWell(){return new Rect(side()+15,72,imageWidth-side()-20,imageHeight-50-72);}
    @Override protected void renderBg(GuiGraphics g,float partialTick,int mouseX,int mouseY){
        var skin=skin();var canvas=new GuiCanvas(g,font,leftPos,topPos);
        g.pose().pushPose();g.pose().translate(leftPos,topPos,0);
        skin.page(canvas,new Rect(0,0,imageWidth,imageHeight));
        // The HMI screen goes under the widgets that sit on it.
        var data=menu.clientData();
        if(data!=null){if(page==CONTROL&&data.view().heat()!=null)drawingBox=skin.well(canvas,controlWell());else skin.well(canvas,pageWell());}
        skin.softKeys(canvas,FluidGuiLayout.softKeys(imageWidth,imageHeight));
        g.pose().popPose();
    }
    /** A line of text on the door in the tone {@code palette} names, cut to {@code width} and read in full on hover. */
    private void doorText(GuiCanvas canvas,Skin skin,String value,int x,int y,int palette,int width){
        String shown=font.plainSubstrByWidth(value,Math.max(0,width));skin.text(canvas,shown,x,y,palette);
        if(shown.length()<value.length()&&hoverX>=x&&hoverX<x+width&&hoverY>=y&&hoverY<y+9)hovered=value;
    }
    /** The status line on the door's message display; its full text on hover where it is cut. */
    private void doorStatus(GuiCanvas canvas,Skin skin,String value,int palette){
        int width=imageWidth-20;String shown=font.plainSubstrByWidth(value,width);skin.status(canvas,new Rect(10,59,width,9),shown,palette);
        if(shown.length()<value.length()&&hoverX>=10&&hoverX<10+width&&hoverY>=55&&hoverY<69)hovered=value;
    }
    @Override protected void renderLabels(GuiGraphics g,int mouseX,int mouseY){
        hoverX=mouseX-leftPos;hoverY=mouseY-topPos;
        var skin=skin();var canvas=new GuiCanvas(g,font,leftPos,topPos);
        String name=title.getString(),shownName=font.plainSubstrByWidth(name,imageWidth-36-themeWidth());skin.title(canvas,10,10,shownName);
        if(shownName.length()<name.length()&&hoverX>=6&&hoverX<imageWidth-20-themeWidth()&&hoverY>=6&&hoverY<22)hovered=name;
        var data=menu.clientData();
        if(data==null){doorStatus(canvas,skin,tr("waiting"),MUTED);return;}
        // A refused mover's reason ("ERROR: pump inlet not liquid ...") and a hold read in the warning colour.
        var view=data.view();
        skin.hDivider(canvas,8,imageWidth-8,imageHeight-49);
        // A message only (waiting, an invalid input); the view's time is gone (owner, 2026-10-28).
        if(!message.isBlank())doorText(canvas,skin,message,10,imageHeight-42,WARN,imageWidth-20);
        if(page==CONTROL&&view.heat()!=null){
            // The status in words, in the player's unit; the engine's own line on hover.
            doorStatus(canvas,skin,controlStatus(),warning(view.status())?WARN:ACCENT);
            if(hoverX>=10&&hoverX<imageWidth-10&&hoverY>=55&&hoverY<69)hovered=view.status();
            renderControl(g);
            return;
        }
        // The status in words, once, on the message display (a pipe's reversed interval first); the engine's own line on hover.
        String words=(pipe()&&view.pipeInfo().changedDirection()?tr("reversed_interval")+" ":"")+statusText(view.status());
        doorStatus(canvas,skin,words,warning(view.status())?WARN:MUTED);
        if(hoverX>=10&&hoverX<imageWidth-10&&hoverY>=55&&hoverY<69)hovered=words.equals(view.status())?words:words+"\n"+view.status();
        skin.vDivider(canvas,side()+13,controlTop(),controlBottom());
        for(var label:labels)text(g,label.text(),label.x(),label.y(),MUTED,label.width());
        renderDevice(canvas,skin);
        if(page==0)renderContents(g,mouseX-leftPos,mouseY-topPos);
        else if(page==1)renderComposition(g,mouseX-leftPos,mouseY-topPos);
        else if(page==3)renderConnections(g,mouseX-leftPos,mouseY-topPos);
        else if(page==4)renderOutlets(g,mouseX-leftPos,mouseY-topPos);
        else if(page==5)renderDatasheet(g,mouseX-leftPos,mouseY-topPos);
        else {
            int w=tableWidth();text(g,tr("solid_col.0"),tableX(),128,TEXT,w/2);
            text(g,tr("solid_col.1"),tableX()+w/2,128,TEXT,w/4);text(g,tr("solid_col.2"),tableX()+3*w/4,128,TEXT,w/4);
        }
        // Scrollbars store screen coordinates because all mouse events arrive there.
        g.pose().pushPose();g.pose().translate(-leftPos,-topPos,0);scroll.draw(g);g.pose().popPose();
    }
    private String displayPressure(Double value){return value==null?"—":pressureUnit.format(value);}
    private String quantity(double value,String unit){return (Double.isFinite(value)?number(value):"—")+" "+unit;}
    private String value(double v){return Double.isFinite(v)?number(v):"—";}
    private String temperatureValue(double kelvin){return value(temperatureUnit.display(kelvin));}
    private String pressureValue(Double pascals){return pascals==null?"—":pressureUnit.number(pascals);}
    private static DeviceColumn.Meter meter(String key,String value,String unit){return new DeviceColumn.Meter(key,value,unit,null);}
    /**
     * The Readings group's meters, in the player's units: one value a meter, its unit after it, what it reads on hover. A
     * switch on the column shows its own position, a tank's fill is its Level group, a valve's opening its Valve group.
     */
    private List<DeviceColumn.Meter> meters(){
        var data=menu.clientData();var view=data.view();var state=view.state();var rows=new ArrayList<DeviceColumn.Meter>();
        String t=temperatureUnit.symbol(),p=pressureUnit.symbol();
        // A heat side's burners and fans switch on its Control tab.
        if(fans()||burners())rows.add(meter(fans()?"fans_state":"burners_state",tr(data.controls().power()==FluidNetwork.Power.ON?"state_on":"state_off"),""));
        // A plain pipe's line reading (OWNER_TEST_REVIEW.md section 6): the stream it carries, as its gauge reads it.
        if(pipe()&&view.pipeInfo().reading()!=null){
            var line=view.pipeInfo().reading();
            rows.add(new DeviceColumn.Meter("temperature",temperatureValue(line.temperature()),t,tr("line_temperature.hint")));
            rows.add(meter("pressure",pressureValue(line.pressure()),p));
        }
        if(tank()){
            var vessel=view.tank();boolean empty=vessel!=null&&state==null;
            rows.add(meter("temperature",state==null?"—":temperatureValue(state.temperature()),t));
            rows.add(new DeviceColumn.Meter("top_pressure",pressureValue(vessel==null?(state==null?null:state.pressure()):vessel.topPressure()),p,tr("top_pressure.hint")));
            rows.add(new DeviceColumn.Meter("bottom_pressure",pressureValue(vessel==null?null:vessel.bottomPressure()),p,tr("bottom_pressure.hint")));
            rows.add(meter("mass",value(state!=null?state.mass():empty?0:Double.NaN),"kg"));
        }else if(!pipe()&&data.kind()!=TopologyCompiler.Kind.FILTER){
            boolean side=data.kind()==TopologyCompiler.Kind.HEAT_SIDE;
            if(data.kind()!=TopologyCompiler.Kind.VOID)rows.add(meter(side?"inlet_temperature":"temperature",state==null?"—":temperatureValue(state.temperature()),t));
            String key=data.kind()==TopologyCompiler.Kind.PUMP||data.kind()==TopologyCompiler.Kind.COMPRESSOR?"suction_pressure":data.kind()==TopologyCompiler.Kind.VALVE||side?"inlet_pressure":"pressure";
            rows.add(meter(key,pressureValue(state==null?null:state.pressure()),p));
        }
        rows.add(new DeviceColumn.Meter(tank()?"net_flow":"throughput",value(displayedFlow()),flowUnit(),tr(tank()?"net_flow.hint":"throughput.hint")));
        if(pipe()){
            var connections=view.pipeInfo().connections();
            range(rows,"velocity",connections,com.wormzjl.icheme.runtime.fluid.FluidView.PipeConnection::velocityMetresPerSecond,this::value,"m/s",tr("velocity.hint"));
            range(rows,"gradient",connections,com.wormzjl.icheme.runtime.fluid.FluidView.PipeConnection::pressureDropPascalPerMetre,pressureUnit::number,p+"/m",tr("gradient.hint"));
        }
        if(data.kind()==TopologyCompiler.Kind.FILTER){
            var filter=view.filter();
            rows.add(meter("filter_load",value(filter==null?Double.NaN:filter.loading()*100),"%"));
            rows.add(meter("captured",value(filter==null?Double.NaN:filter.captured().massKg()),"kg"));
        }
        if(view.devicePressureChange()!=null)rows.add(meter("pressure_change",pressureValue(view.devicePressureChange()),p));
        if(view.heat()!=null){var heat=view.heat();
            rows.add(meter("outlet_temperature",temperatureValue(heat.outletTemperature()),t));
            rows.add(new DeviceColumn.Meter("duty",value(Math.abs(heat.heatWatt())/1000),"kW",tr(heat.burners()!=null?"duty.fired":heat.heatWatt()<0?"duty.cooled":"duty.heated")));
            // The control itself (mode, set point, low limit) is on the Control tab; this column keeps what the actuator does.
            var control=data.controls().heat();
            if(heat.bypass()!=null)rows.add(new DeviceColumn.Meter("bypass",value(heat.bypass()*100),"%",tr("bypass.hint")));
            if(heat.fans()!=null&&control!=null&&control.mode()!=HeatUnit.Mode.OFF)rows.add(new DeviceColumn.Meter("fans_speed",value(heat.fans()*100),"%",tr("fans_speed.hint")));
            if(heat.fanPowerWatt()!=null)rows.add(new DeviceColumn.Meter("fan_power",value(heat.fanPowerWatt()/1000),"kW",tr("fan_power.hint")));
            if(heat.setPoint()!=null)rows.add(meter("set_temperature",temperatureValue(heat.setPoint()),t));
            if(heat.maximumWatt()!=null)rows.add(new DeviceColumn.Meter("duty_available",value(heat.maximumWatt()/1000),"kW",tr("duty_available.hint")));
        }
        if(data.kind()==TopologyCompiler.Kind.COMPRESSOR&&view.devicePressureChange()!=null&&state!=null&&state.pressure()>0)
            rows.add(meter("pressure_ratio",value((state.pressure()+view.devicePressureChange())/state.pressure()),""));
        // An instrument's main value on its meter, the other on hover: a flowmeter's mass flow (its volume flow), a level
        // gauge's share of its span (its height).
        for(var sensor:data.sensors()){
            String key=switch(sensor.kind()){case PRESSURE->"pressure_probe";case TEMPERATURE->"temperature_probe";case FLOW->"flowmeter";case LEVEL->sensor.mount().equals("level_boot")?"boot_level":"body_level";};
            var availability=sensor.availability();
            if(availability==com.wormzjl.icheme.runtime.instrument.Instrument.Availability.UNAVAILABLE){rows.add(new DeviceColumn.Meter(key,"—","",tr("reading.unavailable")));continue;}
            rows.add(switch(sensor.kind()){
                case PRESSURE->meter(key,pressureUnit.number(sensor.value()),p);
                case TEMPERATURE->new DeviceColumn.Meter(key,temperatureValue(sensor.value()),t,availability==com.wormzjl.icheme.runtime.instrument.Instrument.Availability.NO_FLOW?tr("reading.still"):null);
                case FLOW->new DeviceColumn.Meter(key,value(flowRateUnit.display(sensor.value())),flowRateUnit.unit("kg"),tr("reading.volume_flow",quantity(flowRateUnit.display(sensor.secondary()),flowRateUnit.unit("m³"))));
                case LEVEL->new DeviceColumn.Meter(key,value(sensor.secondary()),"%",tr(sensor.value()<0||sensor.value()>sensor.span()?"reading.height_overrange":"reading.height",quantity(sensor.value(),"m")));
            });
        }
        return List.copyOf(rows);
    }
    /** A pipe's reading over its connections: one meter where they read alike, else the lowest and the highest. */
    private static void range(List<DeviceColumn.Meter> rows,String key,List<com.wormzjl.icheme.runtime.fluid.FluidView.PipeConnection> connections,
            java.util.function.ToDoubleFunction<com.wormzjl.icheme.runtime.fluid.FluidView.PipeConnection> reading,java.util.function.DoubleFunction<String> shown,String unit,String hint){
        if(connections.isEmpty()){rows.add(new DeviceColumn.Meter(key,"—",unit,hint));return;}
        String low=shown.apply(connections.stream().mapToDouble(reading).min().orElse(0)),high=shown.apply(connections.stream().mapToDouble(reading).max().orElse(0));
        if(low.equals(high))rows.add(new DeviceColumn.Meter(key,low,unit,hint));
        else{rows.add(new DeviceColumn.Meter(key+"_lowest",low,unit,hint));rows.add(new DeviceColumn.Meter(key+"_highest",high,unit,hint));}
    }
    /** The Phases group: accepted passing material, stored vessel contents, configured generator feed, or the Overview's stream. */
    private DeviceColumn.Phases phases(){
        var data=menu.clientData();var view=data.view();var state=view.state();var info=view.pipeInfo();var port=selectedPort();
        // A heat unit's selected stream (the Overview's switch) sets the phase split shown.
        double[] volumes=port!=null?port.phaseVolumes():info!=null?info.contents().phaseVolumes():state!=null?state.phaseVolumes():new double[3];
        var solids=port!=null?SolidInventory.EMPTY:info!=null?info.contents().solids():state!=null?state.solids():SolidInventory.EMPTY;
        String hint=tr(generator()?"phase_ratio.generator_hint":tank()?"phase_ratio.tank_hint":"phase_ratio.hint");
        if(phaseBasis==PhaseRatioBasis.MOLAR)hint+="\n"+tr("phase_ratio.mole_hint");
        return new DeviceColumn.Phases(phaseBasis.key(),phaseBasis.amounts(volumes,amounts(),solids,data.components(),data.molecularWeights()),phaseBasis.excludesCustomSolids(solids),hint);
    }
    /** A tank's Level group: its fill (NaN while unknown), and its contents of its capacity in litres. */
    private DeviceColumn.Level level(){
        if(!tank())return null;
        var view=menu.clientData().view();var vessel=view.tank();var state=view.state();boolean empty=vessel!=null&&state==null;
        double capacity=vessel!=null?vessel.capacity():state==null?Double.NaN:state.capacity();
        return new DeviceColumn.Level(state!=null?state.fill():empty?0:Double.NaN,state!=null?number(state.volume()*1000):empty?number(0):"—",value(capacity*1000));
    }
    /** What the door column reads on a device's page (DeviceColumn): this device's view and drafts, the player's units. */
    private DeviceColumn.Input deviceInput(){
        var data=menu.clientData();var view=data.view();
        var valve=data.kind()!=TopologyCompiler.Kind.VALVE?DeviceColumn.Valve.NONE:data.valve()!=null&&!data.valve().configured()?DeviceColumn.Valve.UNCONFIGURED:switch(valveSource){
            case MASS_FLOW->DeviceColumn.Valve.MASS_FLOW;case VOLUME_FLOW->DeviceColumn.Valve.VOLUME_FLOW;default->DeviceColumn.Valve.PRESSURE;};
        var opening=data.valve()==null?null:new DeviceColumn.Opening(data.valve().opening(),data.valve().status());
        String junction=pipe()&&view.pipeInfo().junction()?tr("junction",view.pipeInfo().connections().size()):null;
        // A paired heat side leads with its side selector, as its Control tab does.
        var lead=heat()!=null?HeatControlColumn.sideSelector(controlInput(),FluidDeviceScreen::tr):List.<Column.Row>of();
        return new DeviceColumn.Input(data.kind(),canEdit(),switchable(),powerOn,pressureUnit.symbol(),temperatureUnit.symbol(),flowRateUnit.suffix(),valve,opening,
            view.filter()!=null&&!view.filter().captured().empty(),level(),junction,lead,phases(),meters());
    }
    private static boolean warning(String status){return status.contains("HELD")||status.contains("ERROR");}
    /**
     * A tank's connections by face (plan 4.3), page 4: what each outlet is and which face it leaves by, what it drew over
     * the last interval and its flow; one two-line row per connection in the table area, scrolled when they do not all fit.
     */
    private void renderOutlets(GuiGraphics g,int mx,int my){
        var outlets=menu.clientData().view().outlets();int x=tableX(),w=tableWidth();
        text(g,tr("outlets"),x,82,ACCENT,w);if(mx>=x&&mx<x+w&&my>=80&&my<92)hovered=tr("outlets.hint");
        configureScroll(outlets.size());
        for(int i=scroll.value();i<Math.min(outlets.size(),scroll.value()+visibleRows());i++){
            var outlet=outlets.get(i);int y=tableTop()+(i-scroll.value())*rowHeight();
            // No row tooltip: a screen opens with the cursor at the window centre, inside these rows at GUI scale 2 and
            // above, and a tooltip there would cover them. The hint is on the page title and on the tab button.
            text(g,outletName(outlet),x,y,MUTED,w-4);text(g,outletState(outlet),x,y+11,TEXT,w-4);g.hLine(x,x+w,y+21,LINE);
        }
        if(outlets.isEmpty())text(g,tr("outlets.none"),x,tableTop()+8,MUTED,w);
    }
    /** A drum's datasheet's lines on page 5's glass: its sizes, its notes wrapped and its nozzles' table (VesselScreen's glass with the sizes). */
    private com.wormzjl.icheme.client.gui.common.DialogColumns.Glass datasheetGlass(){
        return com.wormzjl.icheme.client.gui.common.DialogColumns.glass(datasheet,true,tableWidth()-1,GuiCanvas.measure(font),(k,a)->Component.translatable("gui.icheme."+k,a).getString());
    }
    /** A drum's datasheet, page 5: its geometry and nozzles from the client's cells, scrolled a line at a time; no line is cut. */
    private void renderDatasheet(GuiGraphics g,int mx,int my){
        int x=tableX(),w=tableWidth();
        text(g,tr("datasheet"),x,82,ACCENT,w);if(mx>=x&&mx<x+w&&my>=80&&my<92)hovered=tr("datasheet.hint");
        var glass=datasheetGlass();configureScroll(glass.lines().size());
        var canvas=new GuiCanvas(g,font,leftPos,topPos);var box=new Rect(x-3,tableTop()-2,w-1,visibleRows()*rowHeight()+2);
        canvas.clip(box);com.wormzjl.icheme.client.gui.common.DialogColumns.draw(canvas,glass,box,scroll.value()*rowHeight());canvas.unclip();
    }
    private String outletName(com.wormzjl.icheme.runtime.fluid.FluidView.Outlet outlet){
        String face=tr("face."+outlet.face().name().toLowerCase(Locale.ROOT));
        return switch(outlet.port()){
            case VAPOR->tr("outlet.top",face);
            case BULK->tr("outlet.side",face);
            case LIQUID,BOOT_WATER,BOOT_HC->outlet.head()>0?tr("outlet.bottom_head",face,pressureUnit.format(outlet.head())):tr("outlet.bottom",face);
            case OVERFLOW->tr("outlet.overflow",face);
        };
    }
    private String outletState(com.wormzjl.icheme.runtime.fluid.FluidView.Outlet outlet){
        double flow=flowRateUnit.display(mass?outlet.massFlow():outlet.moleFlow()/1000);String unit=" "+flowUnit();
        if(outlet.massFlow()<0)return tr("outlet.receiving")+" · "+number(-flow)+unit;
        if(!(outlet.massFlow()>0))return tr("outlet.idle");
        String drawing=outlet.port()==com.wormzjl.icheme.science.fluid.network.PassiveNetwork.PhasePort.BULK?tr("outlet.drawing_all")
            :outlet.drawn()==2?tr("outlet.drawing_gas")
            :outlet.port()==com.wormzjl.icheme.science.fluid.network.PassiveNetwork.PhasePort.VAPOR?tr("outlet.drawing_overflow")
            :outlet.drawn()==1?tr("outlet.drawing_water"):outlet.drawn()==0?tr("outlet.drawing_oil"):"—";
        return drawing+" · "+number(flow)+unit;
    }
    /**
     * The engine status in words, clause by clause (" / " between a device's prefixes and its island's status), so a prefixed
     * "/ FULL" reads as words too; a temperature a clause names is shown in the player's unit. A data-range notice (an
     * advisory line, never a hold) follows the island's own status.
     */
    private String statusText(String status){
        String notice=com.wormzjl.icheme.science.fluid.thermo.DataRangeNotices.suffix(status);
        if(!notice.isEmpty())return statusText(status.substring(0,status.length()-notice.length()))+notice;
        if(status.contains("HELD")||status.contains("ERROR")||status.contains("WAITING")||status.contains("closed")||status.contains("UNBOUND"))return status;
        var words=new ArrayList<String>();for(var clause:status.split(" / "))if(!clause.isBlank())words.add(clauseText(clause.strip()));
        return String.join(" · ",words);
    }
    /** A held low limit and a heater's shortfall, as {@code FluidWorldAuthority} writes them (K). */
    private static final java.util.regex.Pattern LOW_LIMIT=java.util.regex.Pattern.compile("LOW LIMIT: the outlet is held at ([0-9.]+) K"),
        DUTY_SHORT=java.util.regex.Pattern.compile("DUTY INSUFFICIENT: outlet ([0-9.]+) K below the set point"),SINCE=java.util.regex.Pattern.compile("since ([0-9.]+) s");
    private String clauseText(String clause){
        if(clause.startsWith("STEADY: no flow"))return tr("no_flow");
        if(clause.startsWith("STEADY:"))return tr("flowing");
        if(clause.equals("FULL")||clause.equals("READY"))return tr("ready");
        var low=LOW_LIMIT.matcher(clause);if(low.matches())return tr("status.low_limit",quantity(temperatureUnit.display(Double.parseDouble(low.group(1))),temperatureUnit.symbol()));
        // A difference: the same number in kelvin and in degrees Celsius.
        var short_=DUTY_SHORT.matcher(clause);if(short_.matches())return tr("status.duty_short",quantity(Double.parseDouble(short_.group(1)),temperatureUnit.symbol()));
        return clause;
    }
    /** The island's own state in a few words for the Control tab's status: steady since when, solving, or nothing. */
    private String islandWords(String status){
        String notice=com.wormzjl.icheme.science.fluid.thermo.DataRangeNotices.suffix(status);
        String base=status.substring(0,status.length()-notice.length());int cut=base.lastIndexOf(" / ");if(cut>=0)base=base.substring(cut+3);
        var since=SINCE.matcher(base);
        if(base.startsWith("STEADY: no flow"))return since.find()?tr("status_base.no_flow",since.group(1)):tr("no_flow");
        if(base.startsWith("STEADY:"))return since.find()?tr("status_base.steady",since.group(1)):tr("flowing");
        if(base.equals("FULL"))return tr("status_base.full");
        return "";
    }
    private void renderConnections(GuiGraphics g,int mx,int my){
        var view=menu.clientData().view();var metrics=view.pipeInfo().connections();int x=tableX(),w=tableWidth();
        text(g,tr("connections.hint"),x,82,MUTED,w);
        ProcessUi.tableRow(g,font,new String[]{tr("connection"),flowUnit(),tr("speed"),pressureUnit.symbol()+"/m"},x,tableTop()-16,w,true);
        configureScroll(metrics.size());
        for(int i=scroll.value();i<Math.min(metrics.size(),scroll.value()+visibleRows());i++){
            var metric=metrics.get(i);String name=tr("connection");
            for(var route:view.pipeRoutes())if(route.pipeId()==metric.pipeId())name=(metric.reverse()?route.second():route.first())+" → "+(metric.reverse()?route.first():route.second());
            int y=tableTop()+(i-scroll.value())*18;
            ProcessUi.tableRow(g,font,new String[]{name,number(connectionFlow(view,metric)),number(metric.velocityMetresPerSecond()),pressureUnit.number(metric.pressureDropPascalPerMetre())},x,y,w,false);
            if(mx>=x&&mx<x+w&&my>=y-2&&my<y+16)hovered=name;
        }
    }
    private double connectionFlow(com.wormzjl.icheme.runtime.fluid.FluidView view,
            com.wormzjl.icheme.runtime.fluid.FluidView.PipeConnection metric){
        if(mass)return flowRateUnit.display(metric.massRateKgPerSecond());
        if(view.intervalSeconds()<=0)return 0;
        for(var transfer:view.pipeHistory())if(transfer.pipeId()==metric.pipeId())
            return flowRateUnit.display((Arrays.stream(transfer.forward().componentMoles()).sum()+Arrays.stream(transfer.reverse().componentMoles()).sum())/1000/view.intervalSeconds());
        return 0;
    }
    private void renderContents(GuiGraphics g,int mx,int my){
        int x=tableX(),w=tableWidth();
        // The selected stream's line under the switch: temperature, pressure and flow.
        if(!streams().isEmpty()){
            var port=selectedPort();var state=menu.clientData().view().state();String line;
            if(port==null)line=tr("stream.contents")+": "+(state==null?"—":quantity(temperatureUnit.display(state.temperature()),temperatureUnit.symbol())+" · "+pressureUnit.format(state.pressure()));
            else line=portName(port.role())+": "+(port.temperature()==null?tr("stream.empty"):quantity(temperatureUnit.display(port.temperature()),temperatureUnit.symbol())+" · "+displayPressure(port.pressure())+" · "+quantity(flowRateUnit.display(Math.abs(port.massFlow())),flowRateUnit.unit("kg")));
            text(g,line,x,105,TEXT,w);
            if(mx>=x&&mx<x+w&&my>=103&&my<114)hovered=tr("stream.hint");
        }
        if(phase==3){
            boolean rates=transport()&&menu.clientData().view().filter()==null;
            double perInterval=transportMultiplier(rates);
            ProcessUi.tableRow(g,font,new String[]{tr("solid"),tr("mean_size"),rates?flowRateUnit.unit("kg"):tr("mass_kg")},x,tableTop()-16,w,true);
            var populations=shownSolids();configureScroll(populations.size());
            for(int i=scroll.value();i<Math.min(populations.size(),scroll.value()+visibleRows());i++){
                var p=populations.get(i);int y=tableTop()+(i-scroll.value())*18;
                double kg=p.massKg()*perInterval;
                ProcessUi.tableRow(g,font,new String[]{solidName(p.material()),number(p.size().diameterMetres()*1e6),Double.isFinite(kg)?number(kg):"—"},x,y,w,false);
                if(mx>=x&&mx<x+w/2&&my>=y-2&&my<y+16)hovered=p.material().crystal()==null?p.material().id():solidName(p.material())+"\n"+p.material().id()+"\n"+tr("crystal.hint");
            }
            if(populations.isEmpty())text(g,tr("absent"),x,tableTop()+8,MUTED,w);return;
        }
        var data=menu.clientData();boolean rates=transport()||selectedPort()!=null;double[] values=amounts()[phase];
        double[] weights=data.molecularWeights().stream().mapToDouble(Double::doubleValue).toArray();
        double total=0;for(int i=0;i<values.length;i++)total+=values[i]*(mass?weights[i]:1);
        // The percentage follows the basis toggle; the amount (or rate) shows on both bases, kmol and kg, side by side.
        ProcessUi.tableRow(g,font,new String[]{tr("component"),tr(mass?"mass_percent":"mole_percent"),rates?flowRateUnit.unit("kmol"):tr("kmol"),rates?flowRateUnit.unit("kg"):tr("kg")},x,tableTop()-16,w,true);
        var ids=present();configureScroll(ids.size());
        double perInterval=transportMultiplier(rates);
        for(int i=scroll.value();i<Math.min(ids.size(),scroll.value()+visibleRows());i++){
            int id=ids.get(i),y=tableTop()+(i-scroll.value())*18;double amount=values[id]*(mass?weights[id]:1);
            // values are mol and the molecular weights kg/mol (FluidThermodynamics), so mol x weight is already kg.
            double kmol=values[id]/1000*perInterval,kg=values[id]*weights[id]*perInterval;
            ProcessUi.tableRow(g,font,new String[]{material(id),number(100*amount/total),Double.isFinite(kmol)?number(kmol):"—",Double.isFinite(kg)?number(kg):"—"},x,y,w,false);
            if(mx>=x&&mx<x+w/2&&my>=y-2&&my<y+16)hovered=material(id)+"\n"+data.components().get(id);
        }
        if(ids.isEmpty())text(g,tr("absent"),x,tableTop()+8,MUTED,w);
    }
    private double transportMultiplier(boolean rates){
        // A port's amounts are already per second.
        if(selectedPort()!=null)return flowRateUnit.display(1);
        double seconds=menu.clientData().view().intervalSeconds();
        return !rates?1:seconds>0?flowRateUnit.display(1/seconds):Double.NaN;
    }
    private void renderComposition(GuiGraphics g,int mx,int my){
        int x=tableX(),w=tableWidth();
        if(templates){text(g,tr("mixture_library"),x,116,ACCENT,w);return;}
        text(g,tr("component"),x,150,TEXT,w/2-6);text(g,tr("relative"),x+w/2,150,TEXT,70);
        text(g,tr(mass?"mass_percent":"mole_percent"),x+w/2+76,150,TEXT,w/2-108);
        double[] fractions=null;try{fractions=composition.displayFractions();}catch(IllegalArgumentException ignored){}
        var ids=composition.rows();
        for(int i=scroll.value();i<Math.min(ids.size(),scroll.value()+visibleRows());i++){
            int id=ids.get(i),y=tableTop()+(i-scroll.value())*25;
            text(g,material(id),x+3,y+5,MUTED,w/2-8);text(g,fractions==null?"—":number(fractions[id]*100),x+w/2+76,y+5,TEXT,w/2-108);
            g.hLine(x,x+w,y+22,LINE);
            if(mx>=x&&mx<x+w/2&&my>=y&&my<y+23)hovered=material(id)+"\n"+menu.clientData().components().get(id);
        }
        if(ids.isEmpty())text(g,tr("empty_mixture"),x,tableTop()+8,MUTED,w);
    }
    // ---- The Control tab (HEAT_GUI_PLAN.md section 2): the unit's flowsheet on the left, its control column on the right ----

    // The door column's view on every page: the Control tab's columns, or a device's page's column (DeviceColumn).
    private final ScrollView doorView=new ScrollView();
    private final KitWidgets doorWidgets=new KitWidgets();
    /** The door columns as placed: layout C's two (GUI_TOOLKIT_PLAN.md section 10), one on a narrow screen, or a device's page's. */
    private List<Column> doorColumns=List.of();
    private HeatFlowsheet flowsheet;private String flowsheetKey="";
    private int controlTop(){return FluidGuiLayout.DOOR_TOP;}
    private int controlBottom(){return imageHeight-FluidGuiLayout.DOOR_FOOT;}
    /**
     * Whether the door fits in a window {@code width} by {@code height} GUI px (WindowScale.Fit): the device's column
     * and a heat unit's Control tab's columns, so a page change never changes the scale.
     */
    private boolean fits(int width,int height){return FluidGuiLayout.fits(width,height,this::tallest);}
    private int tallest(int width){
        if(menu.clientData()==null)return 0;
        int tall=deviceColumn(width).bounds().h();
        if(heat()!=null)for(var column:controlColumns(width))tall=Math.max(tall,column.bounds().h());
        return tall;
    }
    // A column that grows past its view after the screen opened (the first publication's readings) lowers the scale
    // where a lower one holds it; the screen builds again between frames, so no frame draws at two scales.
    @Override protected void containerTick(){super.containerTick();if(doorView.max()>0&&WindowScale.lower(minecraft,FluidGuiLayout.HEIGHT,this::fits))rebuildWidgets();}
    private FluidGuiLayout.ControlSlots controlSlots(){return FluidGuiLayout.control(imageWidth);}
    private int drawingRight(){return controlSlots().drawingRight();}
    private HeatControlColumn.Input controlInput(){return new HeatControlColumn.Input(heat(),menu.clientData().controls().heat(),canEdit(),controllable(),heatMode,powerOn,temperatureUnit.symbol());}
    /**
     * The Control column on the GUI kit (batch 2026-10-28-gui-toolkit): placed once from the columns' widths, at compact
     * heights; its fields and buttons are kept at their slots and moved as it scrolls, so a scroll rebuilds nothing. Both
     * columns scroll together, in one view with one bar.
     */
    private void buildControl(){
        var slots=controlSlots();
        doorColumns=controlColumns(imageWidth);
        int tall=doorColumns.stream().mapToInt(c->c.bounds().h()).max().orElse(0);
        doorView.layout(new Rect(slots.leftX()-2,controlTop(),slots.rightX()+slots.rightWidth()-slots.leftX()+4,controlBottom()-controlTop()),slots.scrollX(),tall);
        keepWidgets(this::controlAction);
    }
    /** The Control tab's columns at an image width: layout C's two, or one on a narrow screen. */
    private List<Column> controlColumns(int width){
        var slots=FluidGuiLayout.control(width);var input=controlInput();var measure=GuiCanvas.measure(font);
        if(!slots.split())return List.of(Column.place(HeatControlColumn.rows(input,FluidDeviceScreen::tr),slots.rightX(),controlTop(),slots.rightWidth(),measure,Column.Metrics.COMPACT));
        var two=HeatControlColumn.columns(input,FluidDeviceScreen::tr);
        return List.of(Column.place(two.left(),slots.leftX(),controlTop(),slots.leftWidth(),measure,Column.Metrics.COMPACT),
            Column.place(two.right(),slots.rightX(),controlTop(),slots.rightWidth(),measure,Column.Metrics.COMPACT));
    }
    /**
     * A device's page's door column (DeviceColumn, GUI_REVIEW.md order 1) in place of the old rail: from x 10 to
     * {@link #side}, its bar right of it. Placed at each rebuild for its widgets and again each frame for its texts
     * ({@link #renderDevice}).
     */
    private void buildDevice(){
        doorColumns=List.of(placeDevice());
        keepWidgets(this::deviceAction);
    }
    private Column placeDevice(){
        var column=deviceColumn(imageWidth);
        doorView.layout(new Rect(8,controlTop(),side()-6,controlBottom()-controlTop()),side()+3,column.bounds().h());
        return column;
    }
    /** The device's page's column at an image width: from x 10 to {@code FluidGuiLayout.side}. */
    private Column deviceColumn(int width){
        return Column.place(DeviceColumn.rows(deviceInput(),FluidDeviceScreen::tr),10,controlTop(),FluidGuiLayout.side(width)-10,GuiCanvas.measure(font),Column.Metrics.COMPACT);
    }
    /**
     * The door columns' fields and buttons, kept at their slots and moved as the view scrolls, so a scroll rebuilds
     * nothing; a button acts through {@code action}.
     */
    private void keepWidgets(java.util.function.Consumer<String> action){
        for(var column:doorColumns)for(var p:column.rows()){
            if(p.row() instanceof Column.Field f)doorWidgets.keep(doorBox(f,p.slots().get(f.key())),p.slots().get(f.key()));
            else if(p.row() instanceof Column.Buttons b)for(var spec:b.buttons()){
                var slot=p.slots().get(spec.key());
                // The side shown and the switch's position are latched, as the page's tab is; a display key is flat.
                var button=doorButton(spec.text(),slot.x(),slot.y(),slot.w(),()->action.accept(spec.key())).latched(spec.latched()).soft(spec.soft());
                button.active=spec.active();if(spec.hint()!=null)button.setTooltip(Tooltip.create(Component.literal(spec.hint())));
                doorWidgets.keep(button,slot);
            }
        }
        doorWidgets.place(doorView,leftPos,topPos);
    }
    /**
     * A device's page's action (DeviceColumn's keys): the other side's screen; the solids' recovery, sent at once; the
     * Phases' basis, the display's alone; the switch and a valve's target, drafts until Apply.
     */
    private void deviceAction(String action){
        if(action.startsWith("side:"))FluidNetwork.openPartner(menu);
        else if(action.equals("recover")){FluidNetwork.recoverSolids(menu,revision);message=tr("waiting");}
        else{
            switch(action){
                case "phase_basis"->phaseBasis=preferredPhaseBasis=phaseBasis.next();
                case "power:off","power:on"->powerOn=action.endsWith(":on");
                case "valve:mass"->valveSource=com.wormzjl.icheme.science.fluid.network.FlowControl.ValveSource.MASS_FLOW;
                case "valve:volume"->valveSource=com.wormzjl.icheme.science.fluid.network.FlowControl.ValveSource.VOLUME_FLOW;
                default->{}
            }
            rebuild();
        }
    }
    /** A device's page's door column, placed again from this frame's view: its texts follow the plant with no rebuild. */
    private void renderDevice(GuiCanvas canvas,Skin skin){
        doorColumns=List.of(placeDevice());
        drawDoor(canvas,skin);
    }
    /** The door columns as placed: drawn clipped to their view, a row's hint read on hover, the view's bar. */
    private void drawDoor(GuiCanvas canvas,Skin skin){
        canvas.clip(doorView.viewport());for(var column:doorColumns)column.draw(canvas,-doorView.offset(),skin);canvas.unclip();
        if(doorView.inside(hoverX,hoverY))for(var column:doorColumns){var hover=column.hover(hoverX,doorView.contentY(hoverY));if(hover!=null)hovered=hover;}
        doorView.drawBar(canvas,skin);
    }
    /** A field of a door column: a panel's LCD entry at rest, the game's text field while edited. */
    private EditBox doorBox(Column.Field f,Rect slot){
        var box=new SkinField(font,leftPos+slot.x(),topPos+slot.y(),slot.w(),slot.h(),Component.literal(f.label()),FluidDeviceScreen::skin);
        box.setMaxLength(48);box.setValue(fields.get(f.key()).text());box.setResponder(v->fields.get(f.key()).text(v));box.setEditable(canEdit());
        box.setTooltip(Tooltip.create(Component.literal(f.hint()==null?f.label():f.label()+"\n"+f.hint())));
        if(f.key().equals("lowLimit"))box.setHint(Component.literal(tr("heat_field.off")));
        editors.put(f.key(),addRenderableWidget(box));return box;
    }
    /**
     * A Control column action (HeatControlColumn's keys), a draft until Apply: the other side's screen; the switch (a
     * heater's burners; else Off, or the target On holds, an air cooler's fans running too); a target (an air cooler's
     * fixed fan speed: no controller); the lines' check (W8).
     */
    private void controlAction(String action){
        if(action.startsWith("side:"))FluidNetwork.openPartner(menu);
        else if(action.equals("check_lines")){FluidNetwork.checkLines(menu);message=tr("waiting");}
        else{
            if(action.startsWith("power:")){
                boolean on=action.endsWith(":on");
                if(burners())powerOn=on;
                else{if(fans())powerOn=on;heatMode=on?heatTarget:HeatUnit.Mode.OFF;}
            }else if(action.startsWith("target:")){
                String key=action.substring(7);
                heatMode=heatTarget=key.equals("fan_speed")?HeatUnit.Mode.OFF:HeatUnit.Mode.valueOf(key.toUpperCase(Locale.ROOT));
            }
            rebuild();
        }
    }
    /** The lines an exchanger's drawing shows: those its parts name. */
    private static com.wormzjl.icheme.science.assembly.ExchangerBlueprint.Design design(com.wormzjl.icheme.runtime.fluid.FluidView.Heat heat){
        if(heat.lines()==null)return com.wormzjl.icheme.science.assembly.ExchangerBlueprint.Design.NONE;
        return new com.wormzjl.icheme.science.assembly.ExchangerBlueprint.Design(drawnLines(heat,"tube"),drawnLines(heat,"shell"));
    }
    private static com.wormzjl.icheme.science.assembly.ExchangerBlueprint.Lines drawnLines(com.wormzjl.icheme.runtime.fluid.FluidView.Heat heat,String side){
        return heat.lines().part(side+"_inlet_tee")!=null?com.wormzjl.icheme.science.assembly.ExchangerBlueprint.Lines.BYPASS
            :heat.lines().part(side+"_feed_valve")!=null?com.wormzjl.icheme.science.assembly.ExchangerBlueprint.Lines.RATIO:com.wormzjl.icheme.science.assembly.ExchangerBlueprint.Lines.NONE;
    }
    private HeatFlowsheet flowsheet(){
        var heat=heat();var design=design(heat);String key=heat.unit()+"/"+heat.side()+"/"+design;
        if(flowsheet==null||!key.equals(flowsheetKey)){flowsheetKey=key;flowsheet=new HeatFlowsheet(HeatDiagram.of(unitKind(),sideKind(),design),HeatDiagram.roles(unitKind(),sideKind()));}
        return flowsheet;
    }
    private final HeatFlowsheet.Data flowData=new HeatFlowsheet.Data(){
        @Override public String value(HeatDiagram.Value value){return heatValue(value);}
        @Override public String state(String slot){return partState(slot);}
        @Override public String text(AssemblyDiagram.Text text){return AssemblyScreen.text(text).getString();}
    };
    /** A line part's hover: its name and its state in the column's words. */
    private static Component partHover(String slot,String state,String detail,long at){
        var name=Component.translatable("gui.icheme.assembly.part."+slot);var where=Component.translatable("gui.icheme.assembly.where."+slot);
        try{return AssemblyScreen.stateText(name,where,null,com.wormzjl.icheme.science.assembly.AssemblyReport.State.valueOf(state),detail,at);}
        catch(IllegalArgumentException notChecked){return Component.translatable("gui.icheme.assembly.state.not_validated",name,where);}
    }
    /** A drawing's reading from this side's view, in the player's units; empty (a pill "—") where nothing reads it yet. */
    private String heatValue(HeatDiagram.Value value){
        var heat=heat();
        for(var source:value.sources()){
            Double t=null,f=null;
            if(source.startsWith("port:")){var p=heat.port(source.substring(5));if(p==null)continue;t=p.temperature();f=p.temperature()==null?null:Math.abs(p.massFlow());}
            else if(source.startsWith("part:")){var p=heat.lines()==null?null:heat.lines().part(source.substring(5));if(p==null)continue;t=p.temperature();f=p.massFlow()==null?null:Math.abs(p.massFlow());}
            else if(source.equals("duty"))return quantity(Math.abs(heat.heatWatt())/1000,"kW");
            else if(source.equals("air"))return Component.translatable(HeatDiagram.KEY+"air",quantity(temperatureUnit.display(com.wormzjl.icheme.science.equipment.HeatSides.AIR_TEMPERATURE),temperatureUnit.symbol())).getString();
            String temperature=t==null?null:quantity(temperatureUnit.display(t),temperatureUnit.symbol()),flow=f==null?null:quantity(flowRateUnit.display(f),flowRateUnit.unit("kg"));
            switch(value.show()){
                case TEMPERATURE->{if(temperature!=null)return temperature;}
                case FLOW->{if(flow!=null)return flow;}
                case BOTH->{if(temperature!=null)return flow==null?temperature:temperature+" · "+flow;}
                default->{}
            }
        }
        return value.show()==HeatDiagram.Show.TEMPERATURE?"—":"";
    }
    /**
     * A line part's state: the report's; a valve's own flowmeter is in place on complete lines and missing on unmetered
     * ones (this side's valve), and otherwise reads as its valve.
     */
    private String partState(String slot){
        var lines=heat().lines();if(lines==null)return "NOT_VALIDATED";
        if(slot.endsWith("_valve_meter")){
            String valve=slot.substring(0,slot.length()-"_meter".length());
            if(valve.startsWith(sideKind().name().toLowerCase(Locale.ROOT))){if(lines.status().equals("COMPLETE"))return "OK";if(lines.status().equals("UNMETERED"))return "MISSING";}
            var p=lines.part(valve);return p==null?"NOT_VALIDATED":p.state();
        }
        var p=lines.part(slot);return p==null?"NOT_VALIDATED":p.state();
    }
    /** The Control tab's status in words, in the player's unit: what the control does now, then the island's state. */
    private String controlStatus(){
        var data=menu.clientData();var view=data.view();String status=view.status();var heat=view.heat();
        if(warning(status)||status.contains("WAITING")||status.contains("UNBOUND"))return statusText(status);
        String unit=temperatureUnit.symbol(),doing="";
        if(burners()){
            String target=heat.setPoint()==null?"—":quantity(temperatureUnit.display(heat.setPoint()),unit);
            if(data.controls().power()!=FluidNetwork.Power.ON)doing=tr("heat_status.burners_out");
            else if(status.contains("DUTY INSUFFICIENT: the coil fires at its maximum"))doing=tr("heat_status.firing_most",target);
            else if(status.contains("DUTY INSUFFICIENT")&&heat.setPoint()!=null)doing=tr("heat_status.firing_short",target,quantity(heat.setPoint()-heat.outletTemperature(),unit));
            else doing=tr("heat_status.firing",target);
        }else if(pool())doing=tr("heat_status.boiling",number(Math.abs(heat.heatWatt())/1000));
        else if(data.controls().heat()!=null){
            var c=data.controls().heat();
            String low=quantity(temperatureUnit.display(c.lowLimit()),unit);
            doing=switch(c.mode()){
                case OUTLET_TEMPERATURE,DUTY->{
                    String target=c.mode()==HeatUnit.Mode.OUTLET_TEMPERATURE?quantity(temperatureUnit.display(c.target()),unit):quantity(c.target()/1000,"kW");
                    yield switch(heat.limit()){case "LOW_LIMIT"->tr("heat_status.low_limit",low);case "LOWEST","HIGHEST"->tr("heat_status.short",target,limitClause(status));default->tr("heat_status.holding",target);};
                }
                case RATIO->heat.lines()!=null&&"COMPLETE".equals(heat.lines().status())?tr("heat_status.ratio",number(c.target())):tr("heat_status.ratio_waiting");
                default->heat.limit().equals("LOW_LIMIT")?tr("heat_status.low_limit",low):tr("heat_status.manual");
            };
        }
        String island=islandWords(status);
        return doing.isEmpty()?island:island.isEmpty()?doing:doing+" · "+island;
    }
    /** The words of a controller's LIMITED clause, as the server names its actuator at that end. */
    private static String limitClause(String status){
        for(var clause:status.split(" / "))if(clause.startsWith("LIMITED: "))return clause.substring("LIMITED: ".length());
        return "";
    }
    /** The legend's row under the drawing: its top, 12 px above the column's foot. */
    private int legendY(){return controlBottom()-12;}
    /** The Control tab's HMI screen: the drawing's well, above the legend where the drawing has line parts. */
    private Rect controlWell(){
        boolean legend=flowsheet().diagram.parts().stream().anyMatch(HeatDiagram.Part::line);
        int bottom=legend?legendY()-6:controlBottom();
        return new Rect(5,controlTop()-3,drawingRight()-3,bottom-controlTop()+3);
    }
    private void renderControl(GuiGraphics g){
        var skin=skin();var canvas=new GuiCanvas(g,font,leftPos,topPos);
        var sheet=flowsheet();boolean legend=sheet.diagram.parts().stream().anyMatch(HeatDiagram.Part::line);
        // The drawing on the HMI screen renderBg drew this frame.
        var box=drawingBox!=null?drawingBox:skin.well(canvas,controlWell());
        sheet.fit(box.x(),box.y(),box.w(),box.h());sheet.draw(canvas,flowData);
        // A line part reads its state in the column's words; a pill its full text where it was cut.
        if(box.contains(hoverX,hoverY)){
            var part=sheet.part(hoverX,hoverY);
            if(part!=null&&part.line()){var p=heat().lines()==null?null:heat().lines().part(part.slot());
                hovered=partHover(part.slot(),partState(part.slot()),p==null?"":p.detail(),p==null?com.wormzjl.icheme.science.assembly.AssemblyReport.NONE:p.at()).getString();}
            else{var pill=sheet.pill(hoverX,hoverY);if(pill!=null)hovered=portName(pill.role())+" "+heatValue(pill.value());}
        }
        if(legend){
            int lx=10,ly=legendY();
            int[][] marks={{Palette.TEAL_FILL,Palette.TEAL},{Palette.YELLOW_FILL,Palette.YELLOW},{Palette.RED_FILL,Palette.RED}};String[] words={"legend.in_place","legend.misplaced","legend.missing"};
            for(int i=0;i<3;i++){
                String word=tr(words[i]);if(lx+11+font.width(word)>drawingRight())break;
                g.fill(lx,ly,lx+8,ly+8,marks[i][1]);g.fill(lx+1,ly+1,lx+7,ly+7,marks[i][0]);skin.text(canvas,word,lx+11,ly,MUTED);lx+=19+font.width(word);
            }
        }
        var slots=controlSlots();
        skin.vDivider(canvas,slots.leftX()-6,controlTop(),controlBottom());
        if(slots.split())skin.vDivider(canvas,slots.rightX()-7,controlTop(),controlBottom());
        // The columns as placed: drawn clipped to their view, a row's hint read on hover.
        drawDoor(canvas,skin);
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(ProcessUi.textKeyPressed(this,key,scan,modifiers))return true;
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        if(componentDropdown!=null&&componentDropdown.popupClick(x,y,button))return true;
        // The door column's view on every page: its bar, then a choice it places (a mode, a valve's target); then the table's scroll.
        double rx=x-leftPos,ry=y-topPos;
        if(doorView.press(rx,ry,button))return true;
        if(button==0&&doorView.inside(rx,ry))for(var column:doorColumns){var action=column.action(rx,doorView.contentY(ry));if(action!=null){if(page==CONTROL)controlAction(action);else deviceAction(action);return true;}}
        if(page!=CONTROL&&scroll.click(x,y,button)){rebuildNext=true;return true;}
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){
        if(componentDropdown!=null&&componentDropdown.popupDrag(y))return true;
        if(doorView.drag(y-topPos))return true;
        if(page!=CONTROL&&scroll.drag(y)){rebuildNext=true;return true;}
        return super.mouseDragged(x,y,button,dx,dy);
    }
    @Override public boolean mouseReleased(double x,double y,int button){if(componentDropdown!=null)componentDropdown.popupRelease();scroll.release();doorView.release();return super.mouseReleased(x,y,button);}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        if(componentDropdown!=null&&componentDropdown.popupScroll(x,y,vertical))return true;
        if(doorView.wheel(x-leftPos,y-topPos,vertical))return true;
        if(page!=CONTROL&&x>=leftPos+tableX()&&scroll.wheel(vertical)){rebuildNext=true;return true;}
        return super.mouseScrolled(x,y,horizontal,vertical);
    }

    /** Shared fluid-screen control positions, including the minimum supported 300px GUI width. */
    public static final class FluidGuiLayout {
        private FluidGuiLayout(){}
        /** The screen's image in a window {@code width} by {@code height} GUI px: 8 px round it, 300 to 790 px wide, at least 250 high. */
        public static int imageWidth(int width){return Math.max(300,Math.min(790,width-16));}
        public static int imageHeight(int height){return Math.max(250,height-16);}
        /** The door columns' view on every page: from y {@code DOOR_TOP} to {@code DOOR_FOOT} px above the image's foot. */
        public static final int DOOR_TOP=74,DOOR_FOOT=52;
        /**
         * The device screen's design height (WindowScale, owner 2026-10-28 option b: one scale for every device screen):
         * its door view is 338 px there, room for a valve's page (323 px) and a pump's (330 px); 2560 by 1440 takes scale 3.
         */
        public static final int HEIGHT=480;
        /** The door view in a window {@code height} GUI px high. */
        public static int doorView(int height){return imageHeight(height)-DOOR_FOOT-DOOR_TOP;}
        /**
         * Whether the door columns fit their view, with no bar, in a window {@code width} by {@code height} GUI px
         * ({@code WindowScale.Fit}); {@code tallest} gives the tallest door column the screen places at an image width.
         */
        public static boolean fits(int width,int height,java.util.function.IntUnaryOperator tallest){
            return tallest.applyAsInt(imageWidth(width))<=doorView(height);
        }
        public record Slot(int x,int width){}
        /** A display key's width: the short labels (Mole, °C, kPa, /min) with room to spare (owner: the wider keys were "too big"). */
        static final int KEY=40;
        /**
         * Close and Apply at the left, then the display keys at the right, {@link #KEY} px each and 6 px apart: the
         * composition and flow basis, the temperature, pressure and flow-time unit. Close and Apply take what is left
         * up to 50 and 60 px, so Apply ends 4 px before the keys' strip ({@link #softKeys}).
         */
        public static List<Slot> footer(int width) {
            if(width<300)throw new IllegalArgumentException("Fluid GUI width below minimum");
            int[] natural={50,60},minimum={38,40};
            int keys=width-10-(4*KEY+3*6),available=keys-8-6-10,total=natural[0]+natural[1],floor=minimum[0]+minimum[1];
            int[] sizes=natural.clone();
            if(available<total)for(int i=0;i<2;i++)sizes[i]=minimum[i]+(natural[i]-minimum[i])*(available-floor)/(total-floor);
            var slots=new ArrayList<Slot>();slots.add(new Slot(10,sizes[0]));slots.add(new Slot(16+sizes[0],sizes[1]));
            for(int i=0;i<4;i++)slots.add(new Slot(keys+i*(KEY+6),KEY));
            return List.copyOf(slots);
        }
        /** The strip the display keys (the footer's basis and units, slots 2 to 5, at {@code height} - 27) sit on: 4 px round them. */
        public static Rect softKeys(int width,int height) {
            var footer=footer(width);var first=footer.get(2);var last=footer.get(5);
            return new Rect(first.x()-4,height-31,last.x()+last.width()+4-(first.x()-4),26);
        }
        /**
         * Every page but the Control tab: the door column's right edge, the column from x 10 (GUI_TOOLKIT_PLAN.md section
         * 14); its bar from here + 3, the divider at + 13, the tables' HMI screen from + 15.
         */
        public static int side(int width) {
            if(width<300)throw new IllegalArgumentException("Fluid GUI width below minimum");
            return Math.clamp(width*3/8,120,190);
        }
        public static List<Slot> tabs(int width,int count) {
            if(width<300||count<1||count>4)throw new IllegalArgumentException("Invalid fluid tabs");
            int size=Math.min(130,(width-20-6*(count-1))/count);var slots=new ArrayList<Slot>();
            for(int i=0;i<count;i++)slots.add(new Slot(10+i*(size+6),size));
            return List.copyOf(slots);
        }
        /**
         * The Control tab's columns (HEAT_GUI_PLAN.md section 2; layout C, GUI_TOOLKIT_PLAN.md section 10): the drawing from
         * x 10 to {@code drawingRight}; the side and the modes from {@code leftX}, {@code leftWidth} wide; the settings, what
         * acts and the notes from {@code rightX}, {@code rightWidth} wide; one 8 px scrollbar at {@code scrollX}. Where the
         * right column would be narrower than {@link #SPLIT_MIN}, one column as before: {@code leftWidth} 0, the rows from
         * {@code rightX}.
         */
        public record ControlSlots(int drawingRight,int leftX,int leftWidth,int rightX,int rightWidth,int scrollX){
            public boolean split(){return leftWidth>0;}
        }
        public static final int SPLIT_MIN=120;
        public static ControlSlots control(int width) {
            if(width<300)throw new IllegalArgumentException("Fluid GUI width below minimum");
            int drawingRight=width*38/100,x=drawingRight+10,area=width-20-x,left=Math.clamp((area-12)*2/5,120,170);
            if(area-left-12>=SPLIT_MIN)return new ControlSlots(drawingRight,x,left,x+left+12,area-left-12,width-16);
            int panel=Math.clamp(width*9/25,150,240),px=width-panel-20;
            return new ControlSlots(px-10,px,0,px,panel,px+panel+4);
        }
    }

    /** Phase fractions from cached amounts only; custom solids have no molecular identity in the material model. */
    public enum PhaseRatioBasis {
        VOLUME,MOLAR,MASS;
        public PhaseRatioBasis next(){return switch(this){case VOLUME->MOLAR;case MOLAR->MASS;case MASS->VOLUME;};}
        public String key(){return switch(this){case VOLUME->"volume";case MOLAR->"mole";case MASS->"mass";};}
        public double[] amounts(double[] volumes,double[][] moles,SolidInventory solids,List<String> components,List<Double> weights) {
            if(volumes.length!=3||moles.length!=3||weights.size()!=components.size())throw new IllegalArgumentException("Invalid phase ratio axis");
            for(double weight:weights)if(!Double.isFinite(weight)||weight<=0)throw new IllegalArgumentException("Invalid molecular weight");
            var values=new double[4];
            for(int p=0;p<3;p++) {
                if(moles[p].length!=weights.size())throw new IllegalArgumentException("Invalid phase ratio axis");
                if(!Double.isFinite(volumes[p])||volumes[p]<0)throw new IllegalArgumentException("Invalid phase volume");
                for(double amount:moles[p])if(!Double.isFinite(amount)||amount<0)throw new IllegalArgumentException("Invalid phase amount");
                if(this==VOLUME)values[p]=volumes[p];
                else for(int c=0;c<weights.size();c++)values[p]+=moles[p][c]*(this==MASS?weights.get(c):1);
            }
            if(this==VOLUME)values[3]=solids.volume();
            else if(this==MASS)values[3]=solids.massKg();
            else for(var population:solids.populations()) {
                var crystal=population.material().crystal();if(crystal==null)continue;
                int component=components.indexOf(crystal.component());
                if(component<0)throw new IllegalArgumentException("Unknown crystal component");
                values[3]+=population.massKg()/weights.get(component);
            }
            for(double value:values)if(!Double.isFinite(value)||value<0)throw new IllegalArgumentException("Invalid phase total");
            return values;
        }
        public boolean excludesCustomSolids(SolidInventory solids){return this==MOLAR&&solids.populations().stream().anyMatch(p->p.material().crystal()==null);}
    }
}
