package com.wormzjl.icheme.client.gui.screens.inventory;
import com.wormzjl.icheme.science.display.DisplayUnit.PressureUnit;
import com.wormzjl.icheme.science.display.DisplayUnit;
import com.wormzjl.icheme.science.display.DisplayUnit.FlowRateUnit;

import com.wormzjl.icheme.client.gui.common.*;
import com.wormzjl.icheme.network.FluidNetwork;
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
    private static TemperatureUnit preferredTemperatureUnit=TemperatureUnit.CELSIUS;
    private TemperatureUnit temperatureUnit=preferredTemperatureUnit;
    private static PressureUnit preferredPressureUnit=PressureUnit.KILOPASCAL;
    private PressureUnit pressureUnit=preferredPressureUnit;
    /** Client UI thread only; keep one time basis when moving between equipment screens this session. */
    private static FlowRateUnit preferredFlowRateUnit=FlowRateUnit.PER_SECOND;
    private FlowRateUnit flowRateUnit=preferredFlowRateUnit;
    /** Ratio basis and table/flow basis are separate client-session preferences. */
    private static PhaseRatioBasis preferredPhaseBasis=PhaseRatioBasis.VOLUME;
    private PhaseRatioBasis phaseBasis=preferredPhaseBasis;
    private static boolean preferredMass;
    private boolean mass=preferredMass;
    private Button phaseBasisButton;
    private String[][] particles=new String[64][3];
    private long revision=Long.MIN_VALUE,messageRevision;
    private int page,phase=2;
    private boolean templates,rebuildNext,hasConnections,powerOn=true,phaseSelected;
    private String search="",message="",hovered;
    private V3ClientPresets library;
    private V3ClientPresets.Snapshot mixtureSnapshot;
    private record Choice(String label,Runnable select){}
    private List<Choice> choices=List.of();
    /** Equipment datasheet (page 5), from the client's synced cells; empty for one-block devices. */
    private List<Component> datasheet=List.of();

    public FluidDeviceScreen(FluidDeviceMenu menu,Inventory inventory,Component title){super(menu,inventory,title);}
    private static String tr(String key,Object...args){return Component.translatable("gui.icheme.fluid."+key,args).getString();}
    @Override protected void init(){
        imageWidth=Math.max(300,Math.min(790,width-16));imageHeight=Math.max(250,height-16);
        super.init();titleLabelY=inventoryLabelY=-1000;
        if(library==null)library=new V3ClientPresets(minecraft.gameDirectory.toPath(),name->{
            try{return minecraft.getResourceManager().getResource(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("icheme",name.substring("assets/icheme/".length()))).orElseThrow().open();}
            catch(java.io.IOException|NoSuchElementException e){return null;}
        });
        var vessel=minecraft.level==null?null:com.wormzjl.icheme.world.level.block.EquipmentStructure.find(minecraft.level,menu.position());
        datasheet=vessel==null?List.of():com.wormzjl.icheme.client.gui.screens.VesselScreen.datasheet(vessel);
        refresh();rebuild();
    }
    private boolean generator(){return menu.clientData()!=null&&menu.clientData().kind()==TopologyCompiler.Kind.GENERATOR;}
    private boolean pipe(){return menu.clientData()!=null&&menu.clientData().kind()==TopologyCompiler.Kind.PIPE;}
    private boolean transport(){return menu.clientData()!=null&&menu.clientData().view().pipeInfo()!=null;}
    private com.wormzjl.icheme.science.fluid.network.FlowControl.ValveSource valveSource=com.wormzjl.icheme.science.fluid.network.FlowControl.ValveSource.NONE;
    private boolean canEdit(){return !menu.debug()&&menu.clientData()!=null;}
    private boolean constructedMover(){var data=menu.clientData();return data!=null&&!datasheet.isEmpty()&&(data.kind()==TopologyCompiler.Kind.PUMP||data.kind()==TopologyCompiler.Kind.COMPRESSOR);}
    /** An air cooler's side: its fans switch as a mover does (batch 2026-10-27-heat-exchanger-design). */
    private boolean fans(){var data=menu.clientData();return data!=null&&data.view().heat()!=null&&data.view().heat().fans()!=null;}
    /** A fired heater's coil: its burners switch and its set point is a field, both coils at once (W6). */
    private boolean burners(){var data=menu.clientData();return data!=null&&data.view().heat()!=null&&data.view().heat().burners()!=null;}
    private boolean switchable(){return generator()||constructedMover()||fans()||burners();}
    private String flowUnit(){return flowRateUnit.unit(mass?"kg":"kmol");}
    private int rail(){return Math.min(210,imageWidth/3);}
    // The rail below "Operating conditions" (its fields, the filter's button and the values) is one column clipped to
    // the area above the status separator and scrolled by its own bar on the rail/table divider when it is longer than
    // that area, as the table area scrolls its rows. At 427x240 or 480x270 scaled pixels (GUI scale 2 at 854x480, the
    // automatic scale at 1920x1080) the screen is 250 GUI px high and a pump's or compressor's column does not fit.
    private static final int RAIL_STEP=10,RAIL_CONTROL=42,RAIL_LINE=24;
    private final ProcessScroll railScroll=new ProcessScroll();
    private int railBuiltOffset,hoverX=-1,hoverY=-1,clipTop=Integer.MIN_VALUE,clipBottom=Integer.MAX_VALUE;
    private int railTop(){return 90;}
    private int railBottom(){return imageHeight-52;}
    private int railOffset(){return railScroll.value()*RAIL_STEP;}
    private boolean inRail(int y,int height){return y>=railTop()&&y+height<=railBottom();}
    private int tableX(){return rail()+24;}
    private int tableWidth(){return imageWidth-tableX()-22;}
    // Page 4 is a tank's "Connections by face": one two-line row per connection in the table area, scrolled like the
    // other tables, so the list is readable at every GUI scale (it was on the rail, below the tank's values, and did
    // not fit below about 323 scaled pixels of height). Page 5 is an equipment datasheet, scrolled the same way.
    private boolean tank(){return menu.clientData()!=null&&menu.clientData().kind()==TopologyCompiler.Kind.RESERVOIR;}
    private int tableTop(){return page==0?130:page==1?166:page==3?116:page==4||page==5?100:142;}
    private int rowHeight(){return page==0||page==3?18:page==4?24:page==5?12:25;}
    private int visibleRows(){return Math.max(1,(imageHeight-64-tableTop())/rowHeight());}
    private void refresh(){
        if(menu.messageRevision()!=messageRevision){messageRevision=menu.messageRevision();message=menu.message();}
        var data=menu.clientData();if(data==null)return;
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
    private EditBox field(String key,String label,int x,int y,int width){
        labels.add(new Label(label,x,y,width));
        var box=new EditBox(font,leftPos+x,topPos+y+12,width,18,Component.literal(label));
        box.setMaxLength(48);box.setValue(fields.get(key).text());box.setResponder(v->fields.get(key).text(v));
        box.setTooltip(Tooltip.create(Component.literal(label)));box.setEditable(canEdit());editors.put(key,addRenderableWidget(box));
        return box;
    }
    /** A field of the scrolled rail: its label is clipped with the rail, the box shows only when it lies wholly inside. */
    private void railField(String key,String label,int y){field(key,label,12,y,rail()-4).visible=inRail(y+12,18);}
    /** Rail controls above the values: the fields a device's page edits, a filter's recover button, an air cooler's fans,
     * and a paired heat side's button to its partner (which a reader has too). */
    private int railControls(){
        var data=menu.clientData();if(data==null)return 0;
        int partner=paired()?1:0;if(!canEdit())return partner;
        return partner+switch(data.kind()){case PUMP,COMPRESSOR->constructedMover()?3:2;case GENERATOR->3;case VALVE->data.valve()!=null&&!data.valve().configured()?0:valveSource==com.wormzjl.icheme.science.fluid.network.FlowControl.ValveSource.MASS_FLOW||valveSource==com.wormzjl.icheme.science.fluid.network.FlowControl.ValveSource.VOLUME_FLOW?2:1;case VOID,PIPE,FILTER->1;case HEAT_SIDE->fans()?1:burners()?2:0;default->0;};
    }
    /** A heat side with a partner (an exchanger's): it opens the partner's screen (batch 2026-10-27-heat-exchanger-design). */
    private boolean paired(){var data=menu.clientData();return data!=null&&data.view().heat()!=null&&data.view().heat().paired();}
    private void changePage(int target){page=target;templates=false;scroll.reset();rebuild();}
    private void toggleBasis(){
        try{if(composition!=null){composition.toggleBasis();mass=composition.mass();}else mass=!mass;preferredMass=mass;rebuild();}
        catch(IllegalArgumentException e){message=tr("invalid_composition");}
    }
    /** Validate all affected drafts first, so a refused unit change loses no input or precision. */
    private boolean convertUnit(DisplayUnit next,String... keys){
        try{
            var converted=new LinkedHashMap<String,NumericDraft>();
            for(var key:keys)if(fields.containsKey(key))converted.put(key,fields.get(key).in(next));
            fields.putAll(converted);return true;
        }catch(IllegalArgumentException e){message=tr("invalid_unit");return false;}
    }
    private void toggleTemperature(){
        var next=temperatureUnit.next();if(convertUnit(next,"temperature")){temperatureUnit=preferredTemperatureUnit=next;rebuild();}
    }
    private void toggleFlowRate(){
        var next=flowRateUnit.next();if(convertUnit(next,"volumeFlow","massFlow")){flowRateUnit=preferredFlowRateUnit=next;rebuild();}
    }
    private void togglePressure(){
        var next=pressureUnit.next();if(convertUnit(next,"pressure","maximumAddedPressure")){pressureUnit=preferredPressureUnit=next;rebuild();}
    }
    private void rebuild(){
        rebuildNext=false;String focused=editors.entrySet().stream().filter(e->e.getValue()==getFocused()).map(Map.Entry::getKey).findFirst().orElse(null);
        int cursor=focused==null?0:editors.get(focused).getCursorPosition();
        boolean dropdownOpen=componentDropdown!=null&&componentDropdown.expanded();
        clearWidgets();editors.clear();labels.clear();componentDropdown=null;phaseBasisButton=null;
        var data=menu.clientData();
        var tabs=new ArrayList<String>();var pages=new ArrayList<Integer>();tabs.add("overview");pages.add(0);
        if(generator()&&canEdit()){tabs.add("composition");pages.add(1);tabs.add("solids");pages.add(2);}
        if(pipe()&&data.view().pipeInfo().connections().size()>1){tabs.add("connections");pages.add(3);}
        if(tank()){tabs.add("outlets");pages.add(4);}
        if(!datasheet.isEmpty()){tabs.add("datasheet");pages.add(5);}
        var tabSlots=FluidGuiLayout.tabs(imageWidth,tabs.size());
        for(int i=0;i<tabs.size();i++){int target=pages.get(i);var slot=tabSlots.get(i);action(tabs.get(i),slot.x(),29,slot.width(),()->changePage(target)).active=page!=target;}
        var footer=FluidGuiLayout.footer(imageWidth);int by=imageHeight-27;boolean compact=imageWidth<500;
        var close=footer.get(0);action("close",close.x(),by,close.width(),this::onClose);
        var basis=footer.get(2);button(tr(compact?(mass?"mass_basis_short":"mole_basis_short"):(mass?"mass_basis":"mole_basis")),basis.x(),by,basis.width(),this::toggleBasis)
            .setTooltip(Tooltip.create(Component.translatable("gui.icheme.fluid.table_basis.hint")));
        var temperature=footer.get(3);button(compact?temperatureUnit.symbol():tr("temperature_unit",temperatureUnit.symbol()),temperature.x(),by,temperature.width(),this::toggleTemperature)
            .setTooltip(Tooltip.create(Component.translatable("gui.icheme.column.temperature_unit.hint")));
        var pressure=footer.get(4);button(compact?pressureUnit.symbol():tr("pressure_unit",pressureUnit.symbol()),pressure.x(),by,pressure.width(),this::togglePressure)
            .setTooltip(Tooltip.create(Component.translatable("gui.icheme.fluid.pressure_unit.hint")));
        var rate=footer.get(5);button(compact?flowRateUnit.suffix():tr("flow_unit",flowRateUnit.suffix()),rate.x(),by,rate.width(),this::toggleFlowRate)
            .setTooltip(Tooltip.create(Component.translatable("gui.icheme.fluid.flow_unit.hint")));
        if(data==null)return;
        phaseBasisButton=button(tr("phase_ratio."+phaseBasis.key()+"_basis"),12,0,rail()-4,()->{
            phaseBasis=preferredPhaseBasis=phaseBasis.next();phaseBasisButton.setMessage(Component.literal(tr("phase_ratio."+phaseBasis.key()+"_basis")));
        });
        phaseBasisButton.setTooltip(Tooltip.create(Component.translatable("gui.icheme.fluid.phase_ratio.basis_hint")));layoutPhaseBasisButton();
        if(canEdit()&&data.kind()!=TopologyCompiler.Kind.RESERVOIR&&data.kind()!=TopologyCompiler.Kind.FILTER&&(data.kind()!=TopologyCompiler.Kind.HEAT_SIDE||fans()||burners())){var apply=footer.get(1);action("apply",apply.x(),by,apply.width(),this::apply);}
        railBuiltOffset=railOffset();int y=railTop()-railBuiltOffset;
        if(canEdit()&&switchable()) {
            var power=action(fans()?(powerOn?"fans_on":"fans_off"):burners()?(powerOn?"burners_on":"burners_off"):(powerOn?"power_on":"power_off"),12,y,rail()-4,()->{powerOn=!powerOn;rebuild();});
            power.visible=inRail(y,20);y+=RAIL_CONTROL;
        }
        if(canEdit())switch(data.kind()){
            case GENERATOR->{railField("pressure",tr("pressure_input",pressureUnit.symbol()),y);railField("temperature",tr("temperature_input",temperatureUnit.symbol()),y+RAIL_CONTROL);}
            case PUMP->{railField("volumeFlow",tr("flow_input",flowRateUnit.unit("m³")),y);railField("maximumAddedPressure",tr("head_input",pressureUnit.symbol()),y+RAIL_CONTROL);}
            case COMPRESSOR->{railField("volumeFlow",tr("flow_input",flowRateUnit.unit("m³")),y);railField("maximumPressureRatio",tr("ratio_input"),y+RAIL_CONTROL);}
            case VOID->railField("pressure",tr("pressure_input",pressureUnit.symbol()),y);
            case VALVE->{
                if(data.valve()==null||data.valve().configured()){
                    boolean flow=valveSource==com.wormzjl.icheme.science.fluid.network.FlowControl.ValveSource.MASS_FLOW||valveSource==com.wormzjl.icheme.science.fluid.network.FlowControl.ValveSource.VOLUME_FLOW;
                    if(flow){boolean massTarget=valveSource==com.wormzjl.icheme.science.fluid.network.FlowControl.ValveSource.MASS_FLOW;
                        railField(massTarget?"massFlow":"volumeFlow",tr("flow_input",flowRateUnit.unit(massTarget?"kg":"m³")),y);
                        var flowBasis=button(massTarget?"Mass flow":"Actual volume flow",12,y+RAIL_CONTROL,rail()-4,()->{valveSource=massTarget?com.wormzjl.icheme.science.fluid.network.FlowControl.ValveSource.VOLUME_FLOW:com.wormzjl.icheme.science.fluid.network.FlowControl.ValveSource.MASS_FLOW;rebuild();});flowBasis.visible=inRail(y+RAIL_CONTROL,20);
                    }else railField("pressure",tr("pressure_input",pressureUnit.symbol()),y);
                }
            }
            case PIPE->railField("diameter",tr("diameter_input"),y);
            case HEAT_SIDE->{if(burners())railField("temperature",tr("set_temperature_input",temperatureUnit.symbol()),y);}
            default->{}
        }
        if(data.kind()==TopologyCompiler.Kind.FILTER&&canEdit()){
            var recover=action("recover",12,y,rail()-4,()->{FluidNetwork.recoverSolids(menu,revision);message=tr("waiting");});
            recover.active=data.view().filter()!=null&&!data.view().filter().captured().empty();recover.visible=inRail(y,18);
        }
        // A unit's heat side opens its partner side's menu (batch 2026-10-27-heat-exchanger-design).
        if(paired())action("partner_side",12,y,rail()-4,()->FluidNetwork.openPartner(menu)).visible=inRail(y,20);
        if(page==0)buildContents();else if(page==1)buildComposition();else if(page==2)buildSolids();
        else if(page==4)configureScroll(data.view().outlets().size());else if(page==5)configureScroll(datasheet.size());
        else configureScroll(data.view().pipeInfo().connections().size());
        if(focused!=null&&editors.containsKey(focused)){setFocused(editors.get(focused));editors.get(focused).setCursorPosition(cursor);}
        if(componentDropdown!=null&&"search".equals(focused))componentDropdown.expanded(dropdownOpen);
    }
    private void buildContents(){
        int w=(tableWidth()-12)/4;
        for(int i=0;i<4;i++){
            int selected=i;var tab=button(tr((tableWidth()<240?"phase_tab.":"phase.")+i),tableX()+i*(w+4),82,w,()->{phase=selected;phaseSelected=true;scroll.reset();rebuild();});
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
                fields.get("maximumAddedPressure").si(),composition.moleFractions(),solids(),fields.get("maximumPressureRatio").value(),powerOn?FluidNetwork.Power.ON:FluidNetwork.Power.OFF,valveSource,fields.get("massFlow").si());
            FluidNetwork.sendEdit(menu,revision,controls);message=tr("waiting");
        }catch(IllegalArgumentException e){message=tr("invalid_input");}
    }
    private double[][] amounts(){
        var data=menu.clientData();var view=data.view();
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
        if(shown.length()<value.length()&&hoverX>=x&&hoverX<x+width&&hoverY>=Math.max(y,clipTop)&&hoverY<Math.min(y+9,clipBottom))hovered=value;
    }
    private void railLine(GuiGraphics g,String key,String value,int y){text(g,tr(key),12,y,MUTED,rail()-2);text(g,value,12,y+10,TEXT,rail()-2);}
    private boolean railHover(int mx,int my,int y0,int y1){return mx>=12&&mx<rail()+10&&my>=Math.max(y0,railTop())&&my<Math.min(y1,railBottom());}
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partialTick){
        ProcessUi.restoreCursor(minecraft,this);refresh();if(rebuildNext)rebuild();hovered=null;
        layoutPhaseBasisButton();
        super.render(g,mouseX,mouseY,partialTick);
        if(hovered!=null&&(componentDropdown==null||!componentDropdown.popupContains(mouseX,mouseY))){
            var lines=new ArrayList<net.minecraft.util.FormattedCharSequence>();
            for(var line:hovered.split("\\n"))lines.addAll(font.split(Component.literal(line),Math.max(160,width/2)));
            g.renderTooltip(font,lines,mouseX,mouseY);
        }
        if(componentDropdown!=null)componentDropdown.renderSuggestions(g,mouseX,mouseY);
    }
    private int readingsTop(){return railTop()-railOffset()+(railControls()==0?4:railControls()*RAIL_CONTROL)+(pipe()&&menu.clientData().view().pipeInfo().junction()?18:0);}
    private void layoutPhaseBasisButton() {
        if(phaseBasisButton==null)return;
        int y=readingsTop()+readings().size()*RAIL_LINE+2;
        phaseBasisButton.setPosition(leftPos+12,topPos+y);phaseBasisButton.visible=inRail(y,18);
    }
    @Override protected void renderBg(GuiGraphics g,float partialTick,int mouseX,int mouseY){
        g.fill(leftPos,topPos,leftPos+imageWidth,topPos+imageHeight,BG);
        g.renderOutline(leftPos,topPos,imageWidth,imageHeight,LINE);
    }
    @Override protected void renderLabels(GuiGraphics g,int mouseX,int mouseY){
        hoverX=mouseX-leftPos;hoverY=mouseY-topPos;
        text(g,title.getString(),10,10,TEXT,imageWidth-20);
        var data=menu.clientData();
        if(data==null){text(g,tr("waiting"),10,62,MUTED,imageWidth-20);return;}
        // A refused mover's reason ("ERROR: pump inlet not liquid ...") and a hold read in the warning colour.
        // Every device repeats the full status on its scrollable rail (renderStatus).
        var view=data.view();text(g,statusText(view.status()),10,59,warning(view.status())?WARN:MUTED,imageWidth-20);
        g.vLine(rail()+14,78,imageHeight-52,LINE);g.hLine(8,imageWidth-8,imageHeight-49,LINE);
        text(g,tr("operating"),12,77,ACCENT,rail());
        for(var label:labels)if(label.x()>=tableX())text(g,label.text(),label.x(),label.y(),MUTED,label.width());
        // The rail column (see railTop): drawn clipped to its area, its length sets the rail's scrollbar.
        int top=railTop(),bottom=railBottom(),start=top-railOffset();
        g.enableScissor(leftPos+1,topPos+top,leftPos+rail()+10,topPos+bottom);clipTop=top;clipBottom=bottom;
        for(var label:labels)if(label.x()<tableX())text(g,label.text(),label.x(),label.y(),MUTED,label.width());
        int end=renderDeviceRail(g);
        g.disableScissor();clipTop=Integer.MIN_VALUE;clipBottom=Integer.MAX_VALUE;
        int visible=Math.max(1,(bottom-top)/RAIL_STEP),overflow=end-start-(bottom-top);
        railScroll.configure(leftPos+rail()+10,topPos+top,bottom-top,visible,visible+(overflow>0?(overflow+RAIL_STEP-1)/RAIL_STEP:0));
        if(railOffset()!=railBuiltOffset)rebuildNext=true;
        if(page==0)renderContents(g,mouseX-leftPos,mouseY-topPos);
        else if(page==1)renderComposition(g,mouseX-leftPos,mouseY-topPos);
        else if(page==3)renderConnections(g,mouseX-leftPos,mouseY-topPos);
        else if(page==4)renderOutlets(g,mouseX-leftPos,mouseY-topPos);
        else if(page==5)renderDatasheet(g,mouseX-leftPos,mouseY-topPos);
        else {
            int w=tableWidth();text(g,tr("solid_col.0"),tableX(),128,TEXT,w/2);
            text(g,tr("solid_col.1"),tableX()+w/2,128,TEXT,w/4);text(g,tr("solid_col.2"),tableX()+3*w/4,128,TEXT,w/4);
        }
        String status=message.isBlank()?tr("view_time",number(view.onlineTick()/20.0)):message;
        text(g,status,10,imageHeight-42,WARN,imageWidth-20);
        // Scrollbars store screen coordinates because all mouse events arrive there.
        g.pose().pushPose();g.pose().translate(-leftPos,-topPos,0);scroll.draw(g);railScroll.draw(g);g.pose().popPose();
    }
    private record Reading(String key,String value,String hint){
        Reading(String key,String value){this(key,value,null);}
    }
    private String displayPressure(Double value){return value==null?"—":pressureUnit.format(value);}
    private String quantity(double value,String unit){return (Double.isFinite(value)?number(value):"—")+" "+unit;}
    /** One row plan drives both drawing and the phase selector's scroll position for every equipment kind. */
    private List<Reading> readings(){
        var data=menu.clientData();var view=data.view();var state=view.state();var rows=new ArrayList<Reading>();
        if(switchable())rows.add(new Reading(fans()?"fans_state":burners()?"burners_state":"power_state",tr(data.controls().power()==FluidNetwork.Power.ON?"state_on":"state_off")));
        if(tank()){
            var vessel=view.tank();boolean empty=vessel!=null&&state==null;
            rows.add(new Reading("temperature",state==null?"—":quantity(temperatureUnit.display(state.temperature()),temperatureUnit.symbol())));
            rows.add(new Reading("top_pressure",displayPressure(vessel==null?(state==null?null:state.pressure()):vessel.topPressure()),tr("top_pressure.hint")));
            rows.add(new Reading("bottom_pressure",displayPressure(vessel==null?null:vessel.bottomPressure()),tr("bottom_pressure.hint")));
            double capacity=vessel!=null?vessel.capacity():state==null?Double.NaN:state.capacity();
            String volume=(state!=null?number(state.volume()*1000):empty?number(0):"—")+" / "+(Double.isFinite(capacity)?number(capacity*1000):"—")+" L";
            rows.add(new Reading("volume",volume,tr("volume_capacity.hint")+"\n"+volume));
            rows.add(new Reading("fill",quantity(state!=null?state.fill()*100:empty?0:Double.NaN,"%")));
            rows.add(new Reading("mass",quantity(state!=null?state.mass():empty?0:Double.NaN,"kg")));
        }else if(!pipe()&&data.kind()!=TopologyCompiler.Kind.FILTER){
            boolean side=data.kind()==TopologyCompiler.Kind.HEAT_SIDE;
            if(data.kind()!=TopologyCompiler.Kind.VOID)rows.add(new Reading(side?"inlet_temperature":"temperature",state==null?"—":quantity(temperatureUnit.display(state.temperature()),temperatureUnit.symbol())));
            String key=data.kind()==TopologyCompiler.Kind.PUMP||data.kind()==TopologyCompiler.Kind.COMPRESSOR?"suction_pressure":data.kind()==TopologyCompiler.Kind.VALVE||side?"inlet_pressure":"pressure";
            rows.add(new Reading(key,displayPressure(state==null?null:state.pressure())));
        }
        rows.add(new Reading(tank()?"net_flow":"throughput",quantity(displayedFlow(),flowUnit()),tr(tank()?"net_flow.hint":"throughput.hint")));
        if(pipe()){
            var info=view.pipeInfo();
            rows.add(new Reading("velocity",metricRange(info.connections(),com.wormzjl.icheme.runtime.fluid.FluidView.PipeConnection::velocityMetresPerSecond," m/s"),tr("velocity.hint")));
            rows.add(new Reading("gradient",pressureRange(info.connections()),tr("gradient.hint")));
        }
        if(data.kind()==TopologyCompiler.Kind.FILTER){
            var filter=view.filter();rows.add(new Reading("filter_load",quantity(filter==null?Double.NaN:filter.loading()*100,"%")));
            rows.add(new Reading("captured",quantity(filter==null?Double.NaN:filter.captured().massKg(),"kg")));
        }
        if(view.devicePressureChange()!=null)rows.add(new Reading("pressure_change",displayPressure(view.devicePressureChange())));
        if(view.heat()!=null){var heat=view.heat();
            rows.add(new Reading("outlet_temperature",quantity(temperatureUnit.display(heat.outletTemperature()),temperatureUnit.symbol())));
            rows.add(new Reading("duty",quantity(Math.abs(heat.heatWatt())/1000,"kW"),tr(heat.burners()!=null?"duty.fired":heat.heatWatt()<0?"duty.cooled":"duty.heated")));
            if(heat.fanPowerWatt()!=null)rows.add(new Reading("fan_power",quantity(heat.fanPowerWatt()/1000,"kW"),tr("fan_power.hint")));
            if(heat.setPoint()!=null)rows.add(new Reading("set_temperature",quantity(temperatureUnit.display(heat.setPoint()),temperatureUnit.symbol())));
            if(heat.maximumWatt()!=null)rows.add(new Reading("duty_available",quantity(heat.maximumWatt()/1000,"kW"),tr("duty_available.hint")));
        }
        if(data.kind()==TopologyCompiler.Kind.COMPRESSOR&&view.devicePressureChange()!=null&&state!=null&&state.pressure()>0)
            rows.add(new Reading("pressure_ratio",number((state.pressure()+view.devicePressureChange())/state.pressure())));
        if(data.valve()!=null){rows.add(new Reading("controller",data.valve().status()));rows.add(new Reading("valve_opening",quantity(100*data.valve().opening(),"%")));}
        for(var sensor:data.sensors()){
            String key=switch(sensor.kind()){case PRESSURE->"pressure_probe";case TEMPERATURE->"temperature_probe";case FLOW->"flowmeter";case LEVEL->sensor.mount().equals("level_boot")?"boot_level":"body_level";};
            boolean available=sensor.availability()!=com.wormzjl.icheme.runtime.instrument.Instrument.Availability.UNAVAILABLE;
            String value=available?switch(sensor.kind()){
                case PRESSURE->pressureUnit.format(sensor.value());
                case TEMPERATURE->quantity(temperatureUnit.display(sensor.value()),temperatureUnit.symbol())+(sensor.availability()==com.wormzjl.icheme.runtime.instrument.Instrument.Availability.NO_FLOW?" (no flow)":"");
                case FLOW->quantity(flowRateUnit.display(sensor.value()),flowRateUnit.unit("kg"))+" / "+quantity(flowRateUnit.display(sensor.secondary()),flowRateUnit.unit("m³"));
                case LEVEL->quantity(sensor.value(),"m")+" / "+quantity(sensor.secondary(),"%")+(sensor.value()<0||sensor.value()>sensor.span()?" (overrange)":"");
            }:"Unavailable";
            rows.add(new Reading(key,value));
        }
        return List.copyOf(rows);
    }
    private int renderDeviceRail(GuiGraphics g){
        var view=menu.clientData().view();int y=readingsTop();
        if(pipe()&&view.pipeInfo().junction())text(g,tr("junction",view.pipeInfo().connections().size()),12,y-16,ACCENT,rail());
        for(var row:readings()){
            railLine(g,row.key(),row.value(),y);
            if(row.hint()!=null&&railHover(hoverX,hoverY,y,y+RAIL_LINE-1))hovered=row.hint();
            y+=RAIL_LINE;
        }
        y=renderPhaseRatio(g,hoverX,hoverY,y+24);
        var status=pipe()&&view.pipeInfo().changedDirection()?tr("reversed_interval")+" ":"";
        return renderStatus(g,hoverX,hoverY,y,status+statusText(view.status()),view.status());
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
    /** A drum's datasheet, page 5: its geometry and nozzles from the client's cells, one line per row; a cut line reads in full on hover. */
    private void renderDatasheet(GuiGraphics g,int mx,int my){
        int x=tableX(),w=tableWidth();
        text(g,tr("datasheet"),x,82,ACCENT,w);if(mx>=x&&mx<x+w&&my>=80&&my<92)hovered=tr("datasheet.hint");
        configureScroll(datasheet.size());
        for(int i=scroll.value();i<Math.min(datasheet.size(),scroll.value()+visibleRows());i++)
            text(g,datasheet.get(i).getString(),x,tableTop()+(i-scroll.value())*rowHeight(),i==0?MUTED:TEXT,w-4);
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
    /** A shared phase block: accepted passing material, stored vessel contents, or configured generator feed. */
    private int renderPhaseRatio(GuiGraphics g,int mx,int my,int y){
        var data=menu.clientData();var view=data.view();var state=view.state();var info=view.pipeInfo();
        double[] volumes=info!=null?info.contents().phaseVolumes():state!=null?state.phaseVolumes():new double[3];
        var solids=info!=null?info.contents().solids():state!=null?state.solids():SolidInventory.EMPTY;
        var values=phaseBasis.amounts(volumes,amounts(),solids,data.components(),data.molecularWeights());
        boolean excludes=phaseBasis.excludesCustomSolids(solids);
        text(g,tr(phaseBasis==PhaseRatioBasis.VOLUME?"phase_ratio":"phase_ratio."+phaseBasis.key()),12,y,MUTED,rail());y+=14;
        double total=Arrays.stream(values).sum();int at=12;int[] colors={0xffd6a65a,0xff549bd2,0xffb3c0ca,0xffa28565};
        int[] edges=new int[5];edges[0]=12;double accumulated=0;
        for(int i=0;i<4;i++){accumulated+=values[i];edges[i+1]=12+(total>0?(int)Math.round((rail()-4)*accumulated/total):0);if(total>0)g.fill(edges[i],y,edges[i+1],y+5,colors[i]);}
        y+=10;
        for(int i=0;i<4;i++){
            int col=i%2,row=i/2;String label=i==3&&phaseBasis==PhaseRatioBasis.MOLAR?tr("phase_ratio.crystals"):tr("phase_short."+i);
            text(g,label+" "+(total>0?number(100*values[i]/total)+"%":"—"),12+col*rail()/2,y+row*13,TEXT,rail()/2-4);
        }
        String hint=tr(generator()?"phase_ratio.generator_hint":tank()?"phase_ratio.tank_hint":"phase_ratio.hint");
        if(phaseBasis==PhaseRatioBasis.MOLAR)hint+="\n"+tr("phase_ratio.mole_hint");
        if(railHover(mx,my,y-24,y+26))hovered=hint;
        y+=34;
        if(excludes){text(g,tr("phase_ratio.custom_solids_excluded"),12,y,WARN,rail()-2);if(railHover(mx,my,y,y+10))hovered=tr("phase_ratio.mole_hint");y+=14;}
        return y;
    }
    /** The "Status / errors" block from y: every line of the shown text (the rail scrolls); hover reads the raw engine status. */
    private int renderStatus(GuiGraphics g,int mx,int my,int y,String shown,String status){
        text(g,tr("pipe_status"),12,y,ACCENT,rail());y+=14;
        var lines=font.split(Component.literal(shown),rail()-4);
        for(int i=0;i<lines.size();i++)g.drawString(font,lines.get(i),12,y+i*10,warning(status)?WARN:MUTED,false);
        if(railHover(mx,my,y-14,y+lines.size()*10))hovered=status;
        return y+Math.max(1,lines.size())*10-1;
    }
    /** The engine status in words; a data-range notice (an advisory line, never a hold) follows the island's own status. */
    private String statusText(String status){
        String notice=com.wormzjl.icheme.science.fluid.thermo.DataRangeNotices.suffix(status);
        if(!notice.isEmpty())return statusText(status.substring(0,status.length()-notice.length()))+notice;
        if(status.contains("HELD")||status.contains("ERROR")||status.contains("WAITING")||status.contains("closed")||status.contains("UNBOUND"))return status;
        if(status.startsWith("STEADY: no flow"))return tr("no_flow");
        if(status.startsWith("STEADY:"))return tr("flowing");
        if(status.equals("FULL")||status.equals("READY"))return tr("ready");
        return status;
    }
    private String metricRange(List<com.wormzjl.icheme.runtime.fluid.FluidView.PipeConnection> metrics,
            java.util.function.ToDoubleFunction<com.wormzjl.icheme.runtime.fluid.FluidView.PipeConnection> value,String unit){
        if(metrics.isEmpty())return "—";
        double min=metrics.stream().mapToDouble(value).min().orElse(0),max=metrics.stream().mapToDouble(value).max().orElse(0);
        return (Math.abs(max-min)<1e-9?number(max):number(min)+"–"+number(max))+unit;
    }
    private String pressureRange(List<com.wormzjl.icheme.runtime.fluid.FluidView.PipeConnection> metrics){
        if(metrics.isEmpty())return "—";
        String min=pressureUnit.number(metrics.stream().mapToDouble(com.wormzjl.icheme.runtime.fluid.FluidView.PipeConnection::pressureDropPascalPerMetre).min().orElse(0));
        String max=pressureUnit.number(metrics.stream().mapToDouble(com.wormzjl.icheme.runtime.fluid.FluidView.PipeConnection::pressureDropPascalPerMetre).max().orElse(0));
        return (min.equals(max)?min:min+"–"+max)+" "+pressureUnit.symbol()+"/m";
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
        var data=menu.clientData();boolean rates=transport();double[] values=amounts()[phase];
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
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(ProcessUi.textKeyPressed(this,key,scan,modifiers))return true;
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        if(componentDropdown!=null&&componentDropdown.popupClick(x,y,button))return true;
        if(scroll.click(x,y,button)||railScroll.click(x,y,button)){rebuildNext=true;return true;}return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){
        if(componentDropdown!=null&&componentDropdown.popupDrag(y))return true;
        if(scroll.drag(y)||railScroll.drag(y)){rebuildNext=true;return true;}return super.mouseDragged(x,y,button,dx,dy);
    }
    @Override public boolean mouseReleased(double x,double y,int button){if(componentDropdown!=null)componentDropdown.popupRelease();scroll.release();railScroll.release();return super.mouseReleased(x,y,button);}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        if(componentDropdown!=null&&componentDropdown.popupScroll(x,y,vertical))return true;
        if(x>=leftPos+tableX()&&scroll.wheel(vertical)){rebuildNext=true;return true;}
        // The wheel over the rail column scrolls it (three steps of 10 px: one value line).
        if(x<leftPos+tableX()&&y>=topPos+railTop()&&y<topPos+railBottom()&&railScroll.wheel(vertical)){rebuildNext=true;return true;}
        return super.mouseScrolled(x,y,horizontal,vertical);
    }

    /** Shared fluid-screen control positions, including the minimum supported 300px GUI width. */
    public static final class FluidGuiLayout {
        private FluidGuiLayout(){}
        public record Slot(int x,int width){}
        /** Close, Apply, composition/flow basis, temperature, pressure and flow-time unit. */
        public static List<Slot> footer(int width) {
            if(width<300)throw new IllegalArgumentException("Fluid GUI width below minimum");
            int[] natural={50,60,90,70,80,60},minimum={38,40,44,32,38,32};
            int available=width-50,total=Arrays.stream(natural).sum(),floor=Arrays.stream(minimum).sum();
            int[] sizes=natural.clone();
            if(available<total)for(int i=0;i<sizes.length;i++)sizes[i]=minimum[i]+(natural[i]-minimum[i])*(available-floor)/(total-floor);
            var slots=new ArrayList<Slot>();slots.add(new Slot(10,sizes[0]));slots.add(new Slot(16+sizes[0],sizes[1]));
            int x=width-10;for(int i=2;i<6;i++)x-=sizes[i];x-=18;
            for(int i=2;i<6;i++){slots.add(new Slot(x,sizes[i]));x+=sizes[i]+6;}
            return List.copyOf(slots);
        }
        public static List<Slot> tabs(int width,int count) {
            if(width<300||count<1||count>4)throw new IllegalArgumentException("Invalid fluid tabs");
            int size=Math.min(130,(width-20-6*(count-1))/count);var slots=new ArrayList<Slot>();
            for(int i=0;i<count;i++)slots.add(new Slot(10+i*(size+6),size));
            return List.copyOf(slots);
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
