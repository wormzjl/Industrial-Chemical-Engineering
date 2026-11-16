package com.wormzjl.icheme.client.gui.screens.inventory;
import com.wormzjl.icheme.science.display.DisplayUnit.PressureUnit;
import com.wormzjl.icheme.science.display.DisplayUnit;
import com.wormzjl.icheme.science.display.DisplayUnit.FlowRateUnit;
import com.wormzjl.icheme.science.display.DisplayUnit.TemperatureUnit;

import com.wormzjl.icheme.client.ClientConfig;
import com.wormzjl.icheme.client.gui.common.*;
import com.wormzjl.icheme.client.gui.common.DeviceMeters.PhaseRatioBasis;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.GuiCanvas;
import com.wormzjl.icheme.client.gui.common.kit.KitDoor;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.common.kit.SkinButton;
import com.wormzjl.icheme.client.gui.common.kit.SkinField;
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
    private Page page=Page.OVERVIEW;private int phase=2;
    private boolean hasBlanket;
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
    /** The title strip (N1, the owner 2026-11-03: "You can put the equipment name here"): the kind's plate, the name after it, the theme key. */
    private TitleStrip.Strip strip(){return TitleStrip.placeKeys(imageWidth,FluidGuiLayout.titleKeys(imageWidth,tourPage()!=null).size(),title.getString(),GuiCanvas.measure(font));}
    // The help tour (batch 2026-11-12-tutorial-system, P1; P6: every page has one): a ? key beside the theme key. The tour
    // changes no widget and is not in the signature, so a rebuild under it keeps it at its stop.
    private final com.wormzjl.icheme.client.gui.tutorial.TourHost tour=new com.wormzjl.icheme.client.gui.tutorial.TourHost(this::tourPage,this::anchor);
    private List<String> tabIds=List.of();private List<Page> tabPages=List.of();
    /**
     * This page's tour's name (DeviceTour, P6: every tab has one, the Overview one for each door layout), or null before the
     * first view arrived, whose rows its anchors need.
     */
    private String tourPage(){
        if(menu.clientData()==null)return null;int i=tabPages.indexOf(page);
        var name=DeviceTour.page(i<0?"overview":tabIds.get(i),layout());return com.wormzjl.icheme.client.gui.tutorial.HelpTour.of(name)!=null?name:null;
    }
    /** The Overview's door layout (DeviceTour.Layout), by what leads the device column (deviceInput) and what the glass draws. */
    private DeviceTour.Layout layout(){
        var s=still();var kind=menu.clientData().kind();
        if(converter()!=null)return DeviceTour.Layout.CONVERTER;
        if(s!=null)return s.stages()>0&&s.condenser()?DeviceTour.Layout.STILL_COLUMN:DeviceTour.Layout.STILL;
        if(tower())return DeviceTour.Layout.TOWER;if(heat()!=null)return DeviceTour.Layout.HEAT;if(curve())return DeviceTour.Layout.PUMP_CURVE;
        return switch(kind){case PUMP,COMPRESSOR->DeviceTour.Layout.MOVER;case VALVE->DeviceTour.Layout.VALVE;case PIPE->DeviceTour.Layout.PIPE;default->DeviceTour.Layout.VESSEL;};
    }
    /** A tour's anchor on the screen (Tour): a frame place (DeviceTour.frame); the title; a door widget's or meter's slot or a group by its plate, scrolled into view where {@code reveal}. */
    private Rect anchor(String key,boolean reveal){
        var data=menu.clientData();if(data==null)return null;int i=tabPages.indexOf(page);
        boolean legend=page==Page.CONTROL&&heat()!=null&&flowsheet().diagram.parts().stream().anyMatch(HeatDiagram.Part::line);
        var at=DeviceTour.frame(imageWidth,imageHeight,tabIds,i<0?"overview":tabIds.get(i),legend,canEdit()&&(controlsApply()||names()&&!copies()),copies(),templates).get(key);
        if(at!=null)return at.moved(leftPos,topPos);
        if(key.equals("title"))return strip().plate().moved(leftPos,topPos);
        String slot=key;
        if(key.startsWith("reading:")){slot=null;var readings=page==Page.ACCESSORY?List.<DeviceColumn.Meter>of():deviceInput().readings();for(int k=0;k<readings.size();k++)if(readings.get(k).key().equals(key.substring(8)))slot="meter:"+k;}
        var r=slot==null?null:com.wormzjl.icheme.client.gui.common.TourAnchors.find(slot,door.columns(),k->tr(k));
        return r==null?null:com.wormzjl.icheme.client.gui.common.TourAnchors.reveal(door.view(),r,reveal).moved(leftPos,topPos);
    }
    /** The device's name as the client's block holds it (N1): a structure's on its cells, a one-block device's on itself. */
    private String currentName(){var level=minecraft==null?null:minecraft.level;return level==null?"":com.wormzjl.icheme.world.level.block.EquipmentStructure.name(level,menu.position());}
    /** Whether this device takes a name (N1): a structure, or a one-block device with a screen of its own (FluidDeviceBlockEntity.nameable). */
    private boolean nameable(){var level=minecraft==null?null:minecraft.level;if(level==null)return false;
        return level.getBlockEntity(menu.position()) instanceof com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity device?device.nameable():com.wormzjl.icheme.world.level.block.EquipmentStructure.find(level,menu.position())!=null;}
    /** The name's entry (N1, the owner's pick A; NameEntry): the name as typed, sent with Apply where the device takes a name, following the device's name while the player has not changed it. */
    private final NameEntry nameEntry=new NameEntry(this::currentName,this::nameable);
    @Override protected void init(){
        imageWidth=FluidGuiLayout.imageWidth(width);imageHeight=FluidGuiLayout.imageHeight(height);
        super.init();titleLabelY=inventoryLabelY=-1000;
        nameEntry.seed();
        if(library==null)library=new V3ClientPresets(minecraft.gameDirectory.toPath(),name->{
            try{return minecraft.getResourceManager().getResource(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("icheme",name.substring("assets/icheme/".length()))).orElseThrow().open();}
            catch(java.io.IOException|NoSuchElementException e){return null;}
        });
        var vessel=minecraft.level==null?null:com.wormzjl.icheme.world.level.block.EquipmentStructure.find(minecraft.level,menu.position());
        datasheet=vessel==null?null:com.wormzjl.icheme.client.gui.screens.VesselScreen.sheet(vessel);
        refresh();rebuild();
    }
    private boolean generator(){return menu.clientData()!=null&&menu.clientData().kind()==TopologyCompiler.Kind.GENERATOR&&!fixed();}
    /** A gas intake (batch 2026-11-15-fluid-interfaces, W2), told by its block: the atmosphere, which shows its air and flow and takes no setting. */
    private boolean intake(){var level=minecraft==null?null:minecraft.level;return level!=null&&level.getBlockState(menu.position()).getBlock() instanceof com.wormzjl.icheme.world.level.block.FluidDeviceBlock block&&block.ambient();}
    /** A liquid hopper (W4), told by its block: its Forge tank, rule and what it books, and no setting. */
    private boolean hopper(){var level=minecraft==null?null:minecraft.level;return level!=null&&level.getBlockState(menu.position()).getBlock() instanceof com.wormzjl.icheme.world.level.block.FluidDeviceBlock block&&block.hopper();}
    /** A generator block with nothing the player sets: a gas intake or a liquid hopper. */
    private boolean fixed(){return intake()||hopper();}
    private boolean pipe(){return menu.clientData()!=null&&menu.clientData().kind()==TopologyCompiler.Kind.PIPE;}
    private boolean transport(){return menu.clientData()!=null&&menu.clientData().view().pipeInfo()!=null;}
    private com.wormzjl.icheme.science.fluid.network.FlowControl.ValveSource valveSource=com.wormzjl.icheme.science.fluid.network.FlowControl.ValveSource.NONE;
    private boolean canEdit(){return !menu.debug()&&menu.clientData()!=null;}
    /** A tank with contents shows Copy in the footer's second push slot, where Apply would stand. */
    private boolean copies(){var data=menu.clientData();return data!=null&&tank()&&!tower()&&data.view().state()!=null;}
    /** Whether the title strip holds the name's entry: an editor of a device that takes a name. Enter saves it, Apply sends it with the controls. */
    private boolean names(){return canEdit()&&nameable();}
    /** Saves the typed name by the rename path (N1), as Apply does: Enter in the entry; a tank with contents has Copy in Apply's slot. */
    private void saveName(){if(canEdit()&&nameEntry.renames())com.wormzjl.icheme.network.EquipmentNetwork.rename(menu.position(),nameEntry.draft());}
    /** Whether Apply sends this device's controls: every kind but a tank (an induced-draught tower's aside), a filter, and a heat side off its Control tab or with nothing to control. */
    private boolean controlsApply(){var data=menu.clientData();return data!=null&&!fixed()&&(data.kind()!=TopologyCompiler.Kind.RESERVOIR||blanket()||relief()||tower()&&data.controls().tower().draft()==com.wormzjl.icheme.science.fluid.network.CoolingTower.Draft.INDUCED||still()!=null||converter()!=null)&&data.kind()!=TopologyCompiler.Kind.FILTER&&(data.kind()!=TopologyCompiler.Kind.HEAT_SIDE||page==Page.CONTROL&&(fans()||burners()||controllable()));}
    private boolean constructedMover(){var data=menu.clientData();return data!=null&&datasheet!=null&&(data.kind()==TopologyCompiler.Kind.PUMP||data.kind()==TopologyCompiler.Kind.COMPRESSOR);}
    /** An air cooler's side: its fans switch as a mover does (batch 2026-10-27-heat-exchanger-design). */
    private boolean fans(){var data=menu.clientData();return data!=null&&data.view().heat()!=null&&data.view().heat().fans()!=null;}
    /** A fired heater's coil: its burners switch and its set point is a field, both coils at once (W6). */
    private boolean burners(){var data=menu.clientData();return data!=null&&data.view().heat()!=null&&data.view().heat().burners()!=null;}
    /** An exchanger side or an air cooler: its control's mode, set point, low limit and manual share are fields (W4). */
    private boolean controllable(){var data=menu.clientData();return data!=null&&data.controls().heat()!=null;}
    /** The control mode as drafted, OFF while the switch stands at Off; the set points, low limit and bypass are drafts in {@link #fields}. */
    private HeatUnit.Mode heatMode=HeatUnit.Mode.OFF;
    private boolean towerAuto;
    private boolean tower(){return menu.clientData()!=null&&menu.clientData().controls().tower()!=null;}
    // A batch still's pot (batch 2026-11-09-batch-distiller, W6): its Firing, Reflux valve and Fuel lead its door column
    // (StillDoor), its readings join the meters; the switches and set points are drafts until Apply.
    // A fluid converter's process vessel (batch 2026-11-12-fluid-conversion, W5): its Mode, Rate and Alarm levels lead its door
    // column (ConverterDoor), then its tanks, its rule and what it books; the settings are drafts until Apply.
    private com.wormzjl.icheme.runtime.fluid.FluidView.Converter converter(){var data=menu.clientData();return data==null?null:data.view().converter();}
    /** The converter's Mode and the settings it follows (ConverterDrafts); the rate, deadband and alarm levels are drafts in {@link #fields}. */
    private final ConverterDrafts converterDrafts=new ConverterDrafts();
    /** The converter's settings as drafted, queued by its own edit (ConverterDrafts.apply). */
    private void converterApply(){
        try{
            converterDrafts.apply(fields,(mode,rate,band,t)->FluidNetwork.converterEdit(menu,mode,rate,band,t));message=tr("waiting");
        }catch(IllegalArgumentException e){message=tr("invalid_input");}
    }
    /** The converter's groups' input (ConverterDoor), from its view and the player's units: the rate in L per the time basis. */
    private ConverterDoor.Input converterInput(){
        var c=converter();var data=menu.clientData();String unit=flowRateUnit.unit("L");
        java.util.function.DoubleFunction<String> litres=v->value(flowRateUnit.display(v));
        var thresholds=new ArrayList<String>();for(var v:c.thresholds())thresholds.add(v==null?"—":value(100*v));
        var forge=new ConverterDoor.Tank(c.forge(),value(100*c.forge()),tr("converter_tank.forge_amount",number(c.stored()),number(c.capacity())));
        double fill=c.fill()==null?Double.NaN:c.fill();
        var process=new ConverterDoor.Tank(fill,value(100*fill),c.closed()?tr("converter_tank.gas_amount",value(c.kmol()),value(c.kg())):tr("converter_tank.liquid_amount",number(c.litres())));
        var ranges=new ArrayList<ConverterDoor.Range>();
        for(var r:c.ranges()){
            String name,unitText;java.util.function.Function<Double,String> shown;
            switch(r.kind()){
                case "TEMPERATURE"->{name=tr("converter_temperature");unitText=temperatureUnit.symbol();shown=v->temperatureValue(v);}
                case "PRESSURE"->{name=tr("converter_pressure");unitText=pressureUnit.symbol();shown=v->pressureUnit.number(v);}
                case "OTHERS"->{name=tr("converter_others");unitText=tr("converter_basis.mole");shown=v->value(v);}
                default->{name=materialName(data.materialNames().getOrDefault(r.name(),MaterialName.chemical(r.name())));unitText=tr(r.kind().equals("MASS")?"converter_basis.mass":"converter_basis.mole");shown=v->value(v);}
            }
            if(r.kind().equals("OTHERS")&&c.ranges().stream().anyMatch(x->x.kind().equals("MASS")))unitText=tr("converter_basis.mass");
            String bounds=!c.reverse()?tr("converter_bounds.gives"):r.min()!=null&&r.max()!=null?tr("converter_bounds.between",shown.apply(r.min()),shown.apply(r.max())):r.min()!=null?tr("converter_bounds.at_least",shown.apply(r.min())):r.max()!=null?tr("converter_bounds.at_most",shown.apply(r.max())):tr("converter_bounds.any");
            ranges.add(new ConverterDoor.Range(name,r.value()==null?"—":shown.apply(r.value()),bounds,unitText,r.outside()));
        }
        return new ConverterDoor.Input(canEdit(),converterDrafts.mode,unit,litres.apply(c.rated()),litres.apply(c.rate()),value(100*c.deadband()),thresholds,forge,c.fluid().isEmpty()?null:c.fluid(),process,
            c.rule().isEmpty()?null:c.rule(),c.reverse(),ranges,litres.apply(c.booked()),c.direction().toLowerCase(java.util.Locale.ROOT));
    }
    private com.wormzjl.icheme.runtime.fluid.FluidView.Still still(){var data=menu.clientData();return data==null?null:data.view().still();}
    /** The still's Firing switch and Reflux valve and the settings they follow (StillDrafts); the firing and the ratio are drafts in {@link #fields}. */
    private final StillDrafts stillDrafts=new StillDrafts();
    /** The still's groups' input (StillDoor): its heater, the drafts, what a controller holds, a solid still's slot. */
    private StillDoor.Input stillInput(){
        var s=still();var heater=StillDoor.Heater.valueOf(s.heater());
        String heldFiring=s.heldFiring()==null?null:value(100*s.heldFiring());
        String heldReflux=s.heldClosed()?tr("still_reflux.closed"):s.heldReflux()==null?null:value(s.heldReflux());
        StillDoor.Fuel fuel=null;
        if(heater==StillDoor.Heater.SOLID){
            String item=null;
            if(!s.fuel().isEmpty()){var id=net.minecraft.resources.ResourceLocation.tryParse(s.fuel());
                var known=id==null?null:net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id).orElse(null);
                item=known==null?s.fuel():new net.minecraft.world.item.ItemStack(known).getHoverName().getString();}
            // The fire left: the slot's heat and the burning item's, at the heat it fires now over its efficiency.
            double heat=s.fuelCount()*s.fuelEnergy()+s.burning(),draw=s.heat()/com.wormzjl.icheme.science.plant.still.BatchStill.Heater.SOLID.efficiency();
            fuel=new StillDoor.Fuel(item,Integer.toString(s.fuelCount()),draw>0?value(heat/draw/60):"—",value(heat/1e9));
        }
        return new StillDoor.Input(heater,canEdit(),stillDrafts.firing,s.stages()>0&&s.condenser(),stillDrafts.open,heldFiring,heldReflux,fuel);
    }
    /** The target the switch's On holds: the drafted mode while On (OFF: an air cooler's fixed fan speed); for a side loaded Off, the outlet temperature. */
    private HeatUnit.Mode heatTarget=HeatUnit.Mode.OUTLET_TEMPERATURE;
    /** The door column's Off and On switch: a generator's or a built mover's. A heat unit's fans and burners are on its Control tab. */
    private boolean switchable(){return generator()||constructedMover();}
    /**
     * The screen's pages, in the order of the integers they replace (finding C1, batch 2026-11-13-codebase-review). Client
     * state only: no page is saved, sent or read from the menu, and no ordinal is used.
     */
    private enum Page{
        /** The Overview: the door column and the contents' tables, or a pump's curve. */
        OVERVIEW,
        /** A generator's feed: its composition. */
        COMPOSITION,
        /** A generator's feed: its solids. */
        SOLIDS,
        /** A pipe's connections, where it has more than one. */
        CONNECTIONS,
        /** A tank's connections by face. */
        OUTLETS,
        /** A structure's equipment datasheet. */
        DATASHEET,
        /** The Control tab (HEAT_GUI_PLAN.md section 2): every heat unit's sides and a kettle's pool. */
        CONTROL,
        /** A drum's accessory page (batch 2026-11-11-gas-blanketing): its gas blanketing valve and its pressure safety valve; a pipe's safety valve's own screen. */
        ACCESSORY
    }
    private boolean blanket(){return tank()&&menu.clientData().controls().blanket()>0;}
    /** A drum's or a pipe's pressure safety valve. */
    private boolean relief(){return menu.clientData()!=null&&menu.clientData().controls().relief()>0;}
    /** A drum with an accessory page: one with either valve. */
    private boolean accessory(){return tank()&&(blanket()||relief());}
    /** A pipe's pressure safety valve: the pipe's screen is the valve's own, its one page (owner: "PSV on pipe get a standalone GUI"). */
    private boolean valveOnly(){return pipe()&&relief();}
    /** The page a heat unit's screen opens on: the one last chosen this client session (the side selector keeps it). */
    private static Page preferredHeatPage=Page.OVERVIEW;
    private boolean heatPageSet;
    private com.wormzjl.icheme.runtime.fluid.FluidView.Heat heat(){var data=menu.clientData();return data==null?null:data.view().heat();}
    private com.wormzjl.icheme.science.equipment.HeatSides.Unit unitKind(){return com.wormzjl.icheme.science.equipment.HeatSides.Unit.valueOf(heat().unit());}
    private com.wormzjl.icheme.science.equipment.HeatSides.Side sideKind(){return com.wormzjl.icheme.science.equipment.HeatSides.Side.valueOf(heat().side());}
    /** A kettle's shell (W7): its pool, which the tubes heat; it has no control of its own. */
    private boolean pool(){return heat()!=null&&unitKind()==com.wormzjl.icheme.science.equipment.HeatSides.Unit.KETTLE&&sideKind()==com.wormzjl.icheme.science.equipment.HeatSides.Side.SHELL;}
    private String flowUnit(){return flowRateUnit.unit(mass?"kg":"kmol");}
    // Every page but the Control tab: the door column from x 10 to side(), its bar right of it, then the tables' HMI
    // screen, sized from the window at the player's GUI scale (FluidGuiLayout.imageWidth, imageHeight; owner 2026-11-07).
    // A column taller than its view scrolls in its own view (the Control tab's view), as the table area scrolls its rows.
    private int side(){return FluidGuiLayout.side(imageWidth);}
    private int hoverX=-1,hoverY=-1;
    private int tableX(){return side()+24;}
    private int tableWidth(){return imageWidth-tableX()-22;}
    // Page 4 is a tank's "Connections by face": one two-line row per connection in the table area, scrolled like the
    // other tables, so the list is readable at every GUI scale (it was in the device's column, below the tank's values,
    // and did not fit below about 323 scaled pixels of height). Page 5 is an equipment datasheet, scrolled the same way.
    private boolean tank(){return menu.clientData()!=null&&menu.clientData().kind()==TopologyCompiler.Kind.RESERVOIR;}
    // A heat unit's Overview has its stream switch and the stream's line above the phase tabs (HEAT_GUI_PLAN.md section 2).
    private int tableTop(){return page==Page.OVERVIEW?(heat()!=null?164:130):page==Page.COMPOSITION?166:page==Page.CONNECTIONS?116:page==Page.OUTLETS||page==Page.DATASHEET?100:142;}
    private int rowHeight(){return page==Page.OVERVIEW||page==Page.CONNECTIONS?18:page==Page.OUTLETS?24:page==Page.DATASHEET?com.wormzjl.icheme.client.gui.common.DialogColumns.PITCH:25;}
    private int visibleRows(){return Math.max(1,(imageHeight-64-tableTop())/rowHeight());}
    private void refresh(){
        if(menu.messageRevision()!=messageRevision){messageRevision=menu.messageRevision();message=menu.message();}
        var data=menu.clientData();if(data==null)return;
        // A heat unit opens on the page last chosen on one, so the side selector lands on the other side's Control tab.
        if(!heatPageSet&&data.view().heat()!=null){heatPageSet=true;if(page==Page.OVERVIEW&&preferredHeatPage==Page.CONTROL){page=Page.CONTROL;rebuildNext=true;}}
        boolean connected=pipe()&&data.view().pipeInfo().connections().size()>1;
        if(connected!=hasConnections){hasConnections=connected;if(!connected&&page==Page.CONNECTIONS)page=Page.OVERVIEW;rebuildNext=true;}
        // A valve fitted or removed while the screen is open: its tab comes and goes, and its page closes with it.
        boolean fitted=accessory()||valveOnly();
        if(fitted!=hasBlanket){hasBlanket=fitted;if(!hasBlanket&&page==Page.ACCESSORY)page=Page.OVERVIEW;rebuildNext=true;}
        if(valveOnly()&&page!=Page.ACCESSORY){page=Page.ACCESSORY;rebuildNext=true;}
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
        stillDrafts.refresh(data.view().still(),fields,()->rebuildNext=true);converterDrafts.refresh(data.view().converter(),fields,flowRateUnit,()->rebuildNext=true);
        if(data.view().inputRevision()==revision)return;
        revision=data.view().inputRevision();var c=data.controls();
        powerOn=c.power()==FluidNetwork.Power.ON;valveSource=c.valveSource();
        fields.clear();fields.put("temperature",new NumericDraft(c.temperature(),temperatureUnit));
        fields.put("pressure",new NumericDraft(c.pressure(),pressureUnit));fields.put("diameter",new NumericDraft(c.diameter()));
        fields.put("blanket",new NumericDraft(c.blanket()>0?c.blanket():com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec.DEFAULT_BLANKET,pressureUnit));
        fields.put("relief",new NumericDraft(c.relief()>0?c.relief():com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec.DEFAULT_RELIEF,pressureUnit));
        fields.put("volumeFlow",new NumericDraft(c.volumeFlow(),flowRateUnit));fields.put("maximumAddedPressure",new NumericDraft(c.maximumAddedPressure(),pressureUnit));
        if(c.tower()!=null){
            towerAuto=c.tower().mode()==com.wormzjl.icheme.science.fluid.network.CoolingTower.Mode.TARGET_TEMPERATURE;
            fields.put("towerSpeed",new NumericDraft(c.tower().speed()*100));
            fields.put("towerTemperature",new NumericDraft(c.tower().setPoint(),temperatureUnit));
        }
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
        stillDrafts.shown=null;stillDrafts.refresh(data.view().still(),fields,()->rebuildNext=true);converterDrafts.shown=null;converterDrafts.refresh(data.view().converter(),fields,flowRateUnit,()->rebuildNext=true);
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
    private void changePage(Page target){if(heat()!=null&&(target==Page.OVERVIEW||target==Page.CONTROL))preferredHeatPage=target;page=target;templates=false;scroll.reset();door.reset();rebuild();}
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
        var next=flowRateUnit.next();if(convertUnit(next,"volumeFlow","massFlow","converterRate")){flowRateUnit=next;ClientConfig.flowTime(next);rebuild();}
    }
    private void togglePressure(){
        var next=pressureUnit.next();if(convertUnit(next,"pressure","maximumAddedPressure")){pressureUnit=next;ClientConfig.pressure(next);rebuild();}
    }
    private void rebuild(){
        rebuildNext=false;String focused=editors.entrySet().stream().filter(e->e.getValue()==getFocused()).map(Map.Entry::getKey).findFirst().orElse(null);
        int cursor=focused==null?0:editors.get(focused).getCursorPosition();
        boolean dropdownOpen=componentDropdown!=null&&componentDropdown.expanded();
        clearWidgets();editors.clear();nameEntry.forget();labels.clear();componentDropdown=null;
        var data=menu.clientData();
        var tabs=new ArrayList<String>();var pages=new ArrayList<Page>();tabs.add("overview");pages.add(Page.OVERVIEW);
        if(heat()!=null){tabs.add("control");pages.add(Page.CONTROL);}
        if(generator()&&canEdit()){tabs.add("composition");pages.add(Page.COMPOSITION);tabs.add("solids");pages.add(Page.SOLIDS);}
        if(pipe()&&data.view().pipeInfo().connections().size()>1){tabs.add("connections");pages.add(Page.CONNECTIONS);}
        if(tank()){tabs.add("outlets");pages.add(Page.OUTLETS);}
        if(datasheet!=null){tabs.add("datasheet");pages.add(Page.DATASHEET);}
        if(accessory()&&tabs.size()<4){tabs.add("accessory");pages.add(Page.ACCESSORY);}
        if(valveOnly()){tabs.clear();pages.clear();tabs.add("safety_valve");pages.add(Page.ACCESSORY);}
        var tabSlots=FluidGuiLayout.tabs(imageWidth,tabs.size());
        for(int i=0;i<tabs.size();i++){Page target=pages.get(i);var slot=tabSlots.get(i);doorAction(tabs.get(i),slot.x(),29,slot.width(),()->changePage(target)).latched(true).active=page!=target;}
        tabIds=List.copyOf(tabs);tabPages=List.copyOf(pages);
        // The theme key shows the contrast sign; its hover names the panel shown, its message stays for narration.
        String theme=tr(ClientConfig.theme()==ClientConfig.Theme.DARK?"theme.dark":"theme.light");
        var titleKeys=FluidGuiLayout.titleKeys(imageWidth,tourPage()!=null);var themeKey=titleKeys.get(0);
        doorButton(theme,themeKey.x(),themeKey.y(),themeKey.w(),()->{ClientConfig.toggleTheme();rebuild();}).icon(Skin.Icon.THEME)
            .setTooltip(Tooltip.create(Component.literal(theme+"\n").append(Component.translatable("gui.icheme.fluid.theme.hint"))));
        // The help key left of it, where the page has a tour: a sign, not a word (panel rule 7).
        if(titleKeys.size()>1){var help=titleKeys.get(1);String hint=Component.translatable("tutorial.icheme.guide.help").getString();
            doorButton(hint,help.x(),help.y(),help.w(),tour::open).icon(Skin.Icon.HELP);}
        // The name's entry in the title strip, for an editor of a device that takes a name, sent with Apply; a reader sees its text (renderLabels).
        var namePlace=strip().name();if(names()&&namePlace!=null)nameBox(namePlace);
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
        if(copies()){var copy=footer.get(1);doorAction("copy",copy.x(),by,copy.width(),this::copyContents);}
        // A heat side applies its control from its Control tab only.
        // Apply sends the controls, and the name typed in the title strip (N1): a filter or a heat side with nothing to control applies the name alone; a tank with contents shows Copy there, and Enter saves its name.
        if(canEdit()&&(controlsApply()||names()&&!copies())){var apply=footer.get(1);var key=doorAction("apply",apply.x(),by,apply.width(),this::apply).primary(true);if(!controlsApply())key.setTooltip(Tooltip.create(Component.translatable("gui.icheme.fluid.apply_name.hint")));}
        if(page==Page.CONTROL){
            buildControl();
            if(focused!=null&&editors.containsKey(focused)){setFocused(editors.get(focused));editors.get(focused).setCursorPosition(cursor);}
            return;
        }
        buildDevice();
        if(page==Page.OVERVIEW&&curve())configureScroll(0);else if(page==Page.OVERVIEW)buildContents();else if(page==Page.COMPOSITION)buildComposition();else if(page==Page.SOLIDS)buildSolids();
        else if(page==Page.OUTLETS)configureScroll(data.view().outlets().size());else if(page==Page.DATASHEET)configureScroll(datasheetGlass().lines().size());
        // A tank's view has no pipe information: its Accessory page has nothing to scroll.
        else configureScroll(data.view().pipeInfo()==null?0:data.view().pipeInfo().connections().size());
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
        if(page!=Page.OVERVIEW)return null;var list=streams();return stream>=0&&stream<list.size()?list.get(stream):null;
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
            int selected=i;String key=DeviceColumn.phaseKey(i,aqueousWater(),deviceMeters().dense());
            var tab=button(tr((tableWidth()<240?"phase_tab.":"phase.")+key),tableX()+i*(w+4),tabsY,w,()->{phase=selected;phaseSelected=true;scroll.reset();rebuild();});
            tab.active=phase!=i;tab.setTooltip(Tooltip.create(Component.literal(tr("phase."+key))));
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
                replacement.load(mixture.amounts(),mixture.mass());
                // A template's conditions are the set point's draft, as its parts are; the flow here is by volume, which a mixture's kmol/h does not fix.
                mixture.temperature().ifPresent(k->fields.put("temperature",new NumericDraft(k,temperatureUnit)));
                mixture.pressure().ifPresent(bar->fields.put("pressure",new NumericDraft(bar*1e5,pressureUnit)));
                loadMixture(replacement,mixture.solids());
            }));
        }
        return List.copyOf(list);
    }
    /** Copies what this page shows from the last published snapshot, to the one clipboard: it reads the menu's data, sends no packet and solves nothing. */
    private void copyContents(){
        var data=menu.clientData();var state=data==null?null:data.view().state();
        if(state==null)return;
        var pos=menu.position();
        var held=com.wormzjl.icheme.client.gui.flowsheet.SheetClipboard.vessel(trFlowsheet("clipboard.tank",pos.getX(),pos.getY(),pos.getZ()),data.components(),state.phaseMoles(),state.temperature(),state.pressure());
        com.wormzjl.icheme.client.gui.flowsheet.SheetClipboard.copy(held);
        message=trFlowsheet("door.copied",held.amounts().size(),temperatureValue(state.temperature()),temperatureUnit.symbol(),pressureValue(state.pressure()),pressureUnit.symbol());
    }
    private static String trFlowsheet(String key,Object... args){return Component.translatable("gui.icheme.flowsheet."+key,args).getString();}
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
        saveName();
        if(!controlsApply())return;
        if(!canEdit())return;
        // A converter's page sends its settings by their own edit (W5); its accessory page sends the valves as a drum's.
        if(converter()!=null&&page!=Page.ACCESSORY){converterApply();return;}
        // A compressor's ratio outside its bounds is named with them (the bounds Controls validates with), not the generic message.
        if(menu.clientData().kind()==TopologyCompiler.Kind.COMPRESSOR){
            double ratio;try{ratio=fields.get("maximumPressureRatio").value();}catch(IllegalArgumentException e){ratio=Double.NaN;}
            if(!FluidNetwork.pressureRatioAllowed(ratio)){message=tr("invalid_ratio",(Object[])FluidNetwork.pressureRatioBoundsText());return;}
        }
        try{
            var controls=new FluidNetwork.Controls(fields.get("temperature").si(),fields.get("pressure").si(),
                fields.get("diameter").value(),PipeResistance.DEFAULT_ROUGHNESS_METRES,fields.get("volumeFlow").si(),
                fields.get("maximumAddedPressure").si(),composition.moleFractions(),solids(),fields.get("maximumPressureRatio").value(),powerOn?FluidNetwork.Power.ON:FluidNetwork.Power.OFF,valveSource,fields.get("massFlow").si(),
                heatControl(),towerControl()).withBlanket(blanket()?fields.get("blanket").si():0).withRelief(relief()?fields.get("relief").si():0);
            if(still()!=null)controls=controls.withStill(stillDrafts.controls(fields));
            FluidNetwork.sendEdit(menu,revision,controls);message=tr("waiting");
        }catch(IllegalArgumentException e){message=tr("invalid_input");}
    }
    private com.wormzjl.icheme.science.fluid.network.CoolingTower towerControl(){
        if(!tower())return null;var t=menu.clientData().controls().tower();
        return t.withControl(towerAuto?com.wormzjl.icheme.science.fluid.network.CoolingTower.Mode.TARGET_TEMPERATURE:com.wormzjl.icheme.science.fluid.network.CoolingTower.Mode.FAN_SPEED,
            fields.get("towerSpeed").value()/100,fields.get("towerTemperature").si());
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
    // The shown amounts and whether their aqueous liquid is named water, as the door column's meters read them (DeviceMeters).
    private double[][] amounts(){return deviceMeters().amounts();}
    private boolean aqueousWater(){return deviceMeters().aqueousWater();}
    private List<Integer> present(){
        double[] values=amounts()[Math.min(2,phase)];var ids=new ArrayList<Integer>();
        for(int i=0;i<values.length;i++)if(values[i]>0)ids.add(i);return ids;
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
        door.place(leftPos,topPos);
        // An open tour takes the pointer: the page under it shows no hover.
        if(tour.isOpen()){super.render(g,-1,-1,partialTick);tour.render(g,font,width,height,mouseX,mouseY);return;}
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
    /** The box inside every other page's glass, as {@link #renderBg} drew it this frame (a pump's curve, W10d). */
    private Rect pageBox;
    /** Every page but the Control tab: the tables' HMI screen right of the door column, from below the status to the footer's divider. */
    private Rect pageWell(){return new Rect(side()+15,72,imageWidth-side()-20,imageHeight-50-72);}
    @Override protected void renderBg(GuiGraphics g,float partialTick,int mouseX,int mouseY){
        var skin=skin();var canvas=new GuiCanvas(g,font,leftPos,topPos);
        g.pose().pushPose();g.pose().translate(leftPos,topPos,0);
        skin.page(canvas,new Rect(0,0,imageWidth,imageHeight));
        // The HMI screen goes under the widgets that sit on it.
        var data=menu.clientData();
        if(data!=null){if(page==Page.CONTROL&&data.view().heat()!=null)drawingBox=skin.well(canvas,controlWell());else pageBox=skin.well(canvas,pageWell());}
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
        var strip=strip();skin.title(canvas,TitleStrip.TITLE_X,TitleStrip.TITLE_Y,strip.kind());
        if(strip.kind().length()<title.getString().length()&&strip.plate().contains(hoverX,hoverY))hovered=title.getString();
        var nameHover=nameEntry.draw(canvas,skin,strip,font,hoverX,hoverY);if(nameHover!=null)hovered=nameHover;
        var data=menu.clientData();
        if(data==null){doorStatus(canvas,skin,tr("waiting"),MUTED);return;}
        // A refused mover's reason ("ERROR: pump inlet dry ...") and a hold read in the warning colour.
        var view=data.view();
        skin.hDivider(canvas,8,imageWidth-8,imageHeight-49);
        // A message only (waiting, an invalid input); the view's time is gone (owner, 2026-10-28).
        if(!message.isBlank())doorText(canvas,skin,message,10,imageHeight-42,WARN,imageWidth-20);
        if(page==Page.CONTROL&&view.heat()!=null){
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
        // A constructed centrifugal pump's Overview draws its curve on the glass (W10d); every other device its contents.
        if(page==Page.OVERVIEW&&curve()){var box=pageBox!=null?pageBox:skin.well(canvas,pageWell());canvas.clip(box);PumpCurve.draw(canvas,box,curveInput(),FluidDeviceScreen::tr);canvas.unclip();}
        else if(page==Page.OVERVIEW)renderContents(g,mouseX-leftPos,mouseY-topPos);
        else if(page==Page.COMPOSITION)renderComposition(g,mouseX-leftPos,mouseY-topPos);
        else if(page==Page.CONNECTIONS)renderConnections(g,mouseX-leftPos,mouseY-topPos);
        else if(page==Page.OUTLETS)renderOutlets(g,mouseX-leftPos,mouseY-topPos);
        else if(page==Page.DATASHEET)renderDatasheet(g,mouseX-leftPos,mouseY-topPos);
        else if(page==Page.ACCESSORY)renderAccessory(g,mouseX-leftPos,mouseY-topPos);
        else {
            int w=tableWidth();text(g,tr("solid_col.0"),tableX(),128,TEXT,w/2);
            text(g,tr("solid_col.1"),tableX()+w/2,128,TEXT,w/4);text(g,tr("solid_col.2"),tableX()+3*w/4,128,TEXT,w/4);
        }
        // Scrollbars store screen coordinates because all mouse events arrive there.
        g.pose().pushPose();g.pose().translate(-leftPos,-topPos,0);scroll.draw(g);g.pose().popPose();
    }
    private String displayPressure(Double value){return value==null?"—":pressureUnit.format(value);}
    private String quantity(double value,String unit){return DeviceMeters.quantity(value,unit);}
    private String value(double v){return DeviceMeters.value(v);}
    private String temperatureValue(double kelvin){return value(temperatureUnit.display(kelvin));}
    private String pressureValue(Double pascals){return pascals==null?"—":pressureUnit.number(pascals);}
    /** What the door column's meters read (DeviceMeters): this device's data, the Overview's stream, the Phases' basis, the player's units, the dense label. */
    private DeviceMeters deviceMeters(){
        var data=menu.clientData();
        var meters=new DeviceMeters(new DeviceMeters.Data(data.kind(),data.view(),data.components(),data.molecularWeights(),data.controls().power()==FluidNetwork.Power.ON,data.controls().heat(),data.controls().tower()!=null,data.sensors()),
            selectedPort(),phaseBasis,temperatureUnit,pressureUnit,flowRateUnit,mass,FluidDeviceScreen::tr);
        return meters.withDense(dense(meters));
    }
    /** The view and stream the dense label was evaluated on, and its row and weight (S5 of batch 2026-11-15-supercritical-fluids, DenseLabel; the weight colours the share, P2 of 2026-11-16-supercritical-followups). */
    private com.wormzjl.icheme.runtime.fluid.FluidView denseView;private com.wormzjl.icheme.runtime.fluid.FluidView.Port denseStream;private com.wormzjl.icheme.client.gui.common.DenseLabel.Reading dense=com.wormzjl.icheme.client.gui.common.DenseLabel.Reading.NONE;private boolean denseRead;
    /** The shown contents' dense row: one cubic root on the client's catalog when a new view is published or another stream is shown, kept until then. */
    private com.wormzjl.icheme.client.gui.common.DenseLabel.Reading dense(DeviceMeters meters){
        var view=menu.clientData().view();var port=selectedPort();
        if(!denseRead||view!=denseView||port!=denseStream){denseRead=true;denseView=view;denseStream=port;dense=meters.dense(com.wormzjl.icheme.client.gui.common.DenseLabel.model(com.wormzjl.icheme.science.material.MaterialRuntime.active()));}
        return dense;
    }
    /** What the door column reads on a device's page (DeviceColumn): this device's view and drafts, the player's units. */
    private DeviceColumn.Input deviceInput(){
        var data=menu.clientData();var view=data.view();
        var valve=data.kind()!=TopologyCompiler.Kind.VALVE?DeviceColumn.Valve.NONE:data.valve()!=null&&!data.valve().configured()?DeviceColumn.Valve.UNCONFIGURED:switch(valveSource){
            case MASS_FLOW->DeviceColumn.Valve.MASS_FLOW;case VOLUME_FLOW->DeviceColumn.Valve.VOLUME_FLOW;default->DeviceColumn.Valve.PRESSURE;};
        var opening=data.valve()==null?null:new DeviceColumn.Opening(data.valve().opening(),data.valve().status());
        String junction=pipe()&&view.pipeInfo().junction()?tr("junction",view.pipeInfo().connections().size()):null;
        // A paired heat side leads with its side selector, as its Control tab does.
        var lead=converter()!=null?hopper()?ConverterDoor.plant(converterInput(),FluidDeviceScreen::tr,false):ConverterDoor.rows(converterInput(),FluidDeviceScreen::tr):still()!=null?StillDoor.rows(stillInput(),FluidDeviceScreen::tr):tower()?DeviceColumn.towerControl(menu.clientData().controls().tower().draft()==com.wormzjl.icheme.science.fluid.network.CoolingTower.Draft.INDUCED,towerAuto,canEdit(),temperatureUnit.symbol(),FluidDeviceScreen::tr):heat()!=null?HeatControlColumn.sideSelector(controlInput(),FluidDeviceScreen::tr):List.<Column.Row>of();
        var m=deviceMeters();var input=new DeviceColumn.Input(data.kind(),canEdit()&&!fixed(),switchable(),powerOn,pressureUnit.symbol(),temperatureUnit.symbol(),flowRateUnit.suffix(),valve,opening,
            view.filter()!=null&&!view.filter().captured().empty(),m.level(),junction,lead,m.phases(),m.meters(),m.drive(),m.power(),m.suction());
        return data.controls().fixedMover()?input.withFixedDuty(tr(data.kind()==TopologyCompiler.Kind.PUMP?"inline.pump_rating":"inline.compressor_rating",value(data.controls().volumeFlow()*3600))):input;
    }
    /** Whether the Overview's glass draws this pump's curve (W10d): a constructed centrifugal pump with a driver. In-line pumps keep their tables. */
    private boolean curve(){var view=menu.clientData()==null?null:menu.clientData().view();return view!=null&&view.pump()!=null&&view.pump().curve()!=null&&view.drive()!=null;}
    /** The curve's input from the view: the speed share, the suction volume flow and the head as the Readings show them, the NPSH available. */
    private PumpCurve.Input curveInput(){
        var view=menu.clientData().view();var c=view.pump().curve();var state=view.state();
        double density=state!=null&&state.volume()>0?state.mass()/state.volume():Double.NaN,rise=view.devicePressureChange()==null?Double.NaN:view.devicePressureChange();
        var pump=new com.wormzjl.icheme.science.machine.CentrifugalPump(c.ratedFlow(),c.ratedHead(),c.efficiency(),c.ratedSpeed());
        double available=view.pump().npshAvailable()==null?Double.NaN:view.pump().npshAvailable();
        return new PumpCurve.Input(pump,Math.max(0,view.drive().share()),Math.abs(view.massFlow())/density,rise/(density*9.80665),available,flowRateUnit.display(1),flowRateUnit.unit("m³"));
    }
    private static boolean warning(String status){return status.contains("HELD")||status.contains("ERROR");}
    /**
     * A tank's connections by face (plan 4.3), page 4: what each outlet is and which face it leaves by, what it drew over
     * the last interval and its flow; one two-line row per connection in the table area, scrolled when they do not all fit.
     */
    /**
     * The accessory page's glass: the gas blanketing valve, the pressures at which it pads and vents, and what it needs;
     * the pressure safety valve, the pressure it opens at and where it relieves to.
     */
    private void renderAccessory(GuiGraphics g,int mx,int my){
        int x=tableX(),w=tableWidth(),y=82;String unit=pressureUnit.symbol();
        if(blanket()){
            double set=menu.clientData().controls().blanket();
            text(g,tr("accessory.blanket"),x,y,ACCENT,w);if(mx>=x&&mx<x+w&&my>=y-2&&my<y+10)hovered=tr("accessory.blanket.hint");
            text(g,tr("accessory.blanket.pad",pressureValue(set),unit),x,y+18,TEXT,w-4);g.hLine(x,x+w,y+30,LINE);
            text(g,tr("accessory.blanket.vent",pressureValue(com.wormzjl.icheme.science.fluid.network.GasBlanket.ventPressure(set)),unit),x,y+36,TEXT,w-4);g.hLine(x,x+w,y+48,LINE);
            y+=54;for(var line:font.split(Component.literal(tr("accessory.blanket.supply")),w-4)){g.drawString(font,line,x,y,MUTED,false);y+=10;}
            // Two valves, two sections: an accent rule between them.
            if(relief()){g.hLine(x,x+w,y+5,ACCENT);g.hLine(x,x+w,y+6,ACCENT);}
            y+=14;
        }
        if(relief()){
            var reading=menu.clientData().view().relief();
            text(g,tr("accessory.relief"),x,y,ACCENT,w);if(mx>=x&&mx<x+w&&my>=y-2&&my<y+10)hovered=tr("accessory.relief.hint");
            text(g,tr("accessory.relief.opens",pressureValue(menu.clientData().controls().relief()),unit),x,y+18,TEXT,w-4);g.hLine(x,x+w,y+30,LINE);
            text(g,tr(reading==null||reading.open()?"accessory.relief.open":"accessory.relief.piped"),x,y+36,TEXT,w-4);g.hLine(x,x+w,y+48,LINE);
            y+=54;for(var line:font.split(Component.literal(tr("accessory.relief.note")),w-4)){g.drawString(font,line,x,y,MUTED,false);y+=10;}
        }
    }
    /** The accessory page's column: a group per fitted valve. */
    private List<com.wormzjl.icheme.client.gui.common.kit.Column.Row> accessoryRows(){
        var data=menu.clientData();var state=data.view().state();var rows=new ArrayList<com.wormzjl.icheme.client.gui.common.kit.Column.Row>();
        String pressure=pressureValue(state==null?null:state.pressure());
        List<com.wormzjl.icheme.client.gui.common.kit.Column.Row> blanket=blanket()?DeviceColumn.blanketValve(canEdit(),pressureUnit.symbol(),pressureValue(data.controls().blanket()),pressure,FluidDeviceScreen::tr):List.of(),relief=List.of();
        if(relief()){var reading=data.view().relief();
            relief=DeviceColumn.reliefValve(canEdit(),pressureUnit.symbol(),pressureValue(data.controls().relief()),blanket()?null:pressure,
                    reading==null?"—":value(flowRateUnit.display(reading.massFlow())),flowRateUnit.unit("kg"),reading==null||reading.open(),FluidDeviceScreen::tr);}
        rows.addAll(DeviceColumn.accessories(blanket,relief));
        return rows;
    }
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
            case SPRAY,BULK->tr("outlet.side",face);
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
        // A held device beside this network (registry first): named first, in words, before the rest of the status.
        var held=HELD_DEVICE.matcher(status);
        if(held.lookingAt()){
            String words=held.group(4)==null?tr("status.held_device",held.group(1),held.group(2),held.group(3)):tr("status.held_devices",held.group(1),held.group(2),held.group(3),held.group(4));
            String rest=status.substring(held.end());return rest.isBlank()?words:words+" · "+statusText(rest);
        }
        String notice=com.wormzjl.icheme.science.fluid.thermo.DataRangeNotices.suffix(status);
        if(!notice.isEmpty())return statusText(status.substring(0,status.length()-notice.length()))+notice;
        if(status.contains("HELD")||status.contains("ERROR")||status.contains("WAITING")||status.contains("closed")||status.contains("UNBOUND"))return status;
        var words=new ArrayList<String>();for(var clause:status.split(" / "))if(!clause.isBlank())words.add(clauseText(clause.strip()));
        return String.join(" · ",words);
    }
    /** A held low limit and a heater's shortfall, as {@code FluidWorldAuthority} writes them (K). */
    private static final java.util.regex.Pattern LOW_LIMIT=java.util.regex.Pattern.compile("LOW LIMIT: the outlet is held at ([0-9.]+) K"),
        DUTY_SHORT=java.util.regex.Pattern.compile("DUTY INSUFFICIENT: outlet ([0-9.]+) K below the set point"),SINCE=java.util.regex.Pattern.compile("since ([0-9.]+) s"),
        COLUMN=java.util.regex.Pattern.compile("COLUMN: part of the column at (-?[0-9]+), (-?[0-9]+), (-?[0-9]+), running"),
        HELD_DEVICE=java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority.HELD_DEVICE)+"(-?[0-9]+), (-?[0-9]+), (-?[0-9]+)(?: \\(and ([0-9]+) more\\))?(?: / )?");
    private String clauseText(String clause){
        if(clause.startsWith("STEADY: no flow"))return tr("no_flow");
        if(clause.startsWith("STEADY:"))return tr("flowing");
        if(clause.equals("FULL")||clause.equals("READY"))return tr("ready");
        // A unit a running column holds (C7): the column runs it.
        var column=COLUMN.matcher(clause);if(column.matches())return tr("status.column",column.group(1),column.group(2),column.group(3));
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

    /**
     * The door column's view on every page (KitDoor, finding C2): the Control tab's columns, layout C's two
     * (GUI_TOOLKIT_PLAN.md section 10) or one on a narrow screen, or a device's page's column (DeviceColumn); one view, one
     * bar, the kept widgets.
     */
    private final KitDoor door=new KitDoor();
    private HeatFlowsheet flowsheet;private String flowsheetKey="";
    private int controlTop(){return FluidGuiLayout.DOOR_TOP;}
    private int controlBottom(){return imageHeight-FluidGuiLayout.DOOR_FOOT;}
    /** The name entry follows the device's name (another player's rename, this one's answered) while the player has not changed it. */
    @Override protected void containerTick(){super.containerTick();nameEntry.follow();}
    private FluidGuiLayout.ControlSlots controlSlots(){return FluidGuiLayout.control(imageWidth);}
    private int drawingRight(){return controlSlots().drawingRight();}
    private HeatControlColumn.Input controlInput(){return new HeatControlColumn.Input(heat(),menu.clientData().controls().heat(),canEdit(),controllable(),heatMode,powerOn,temperatureUnit.symbol(),deviceMeters().power());}
    /**
     * The Control column on the GUI kit (batch 2026-10-28-gui-toolkit): placed once from the columns' widths, at compact
     * heights; its fields and buttons are kept at their slots and moved as it scrolls, so a scroll rebuilds nothing. Both
     * columns scroll together, in one view with one bar.
     */
    private void buildControl(){
        var slots=controlSlots();
        door.layout(controlAreas(imageWidth),slots.scrollX(),controlRows(imageWidth),GuiCanvas.measure(font));
        keepWidgets(this::controlAction);
    }
    /** The Control tab's columns' areas at an image width: layout C's two, or one on a narrow screen; the door's view spans them. */
    private List<Rect> controlAreas(int width){
        var slots=FluidGuiLayout.control(width);int h=controlBottom()-controlTop();
        if(!slots.split())return List.of(new Rect(slots.rightX(),controlTop(),slots.rightWidth(),h));
        return List.of(new Rect(slots.leftX(),controlTop(),slots.leftWidth(),h),new Rect(slots.rightX(),controlTop(),slots.rightWidth(),h));
    }
    /** The Control tab's columns' rows at an image width: layout C's two, or one on a narrow screen. */
    private List<List<Column.Row>> controlRows(int width){
        var slots=FluidGuiLayout.control(width);var input=controlInput();
        if(!slots.split())return List.of(HeatControlColumn.rows(input,FluidDeviceScreen::tr));
        var two=HeatControlColumn.columns(input,FluidDeviceScreen::tr);
        return List.of(two.left(),two.right());
    }
    /**
     * A device's page's door column (DeviceColumn, GUI_REVIEW.md order 1) in place of the old rail: from x 10 to
     * {@link #side}, its bar right of it. Placed at each rebuild for its widgets and again each frame for its texts
     * ({@link #renderDevice}).
     */
    private void buildDevice(){
        door.layout(List.of(new Rect(10,controlTop(),side()-10,controlBottom()-controlTop())),side()+3,List.of(deviceRows()),GuiCanvas.measure(font));
        keepWidgets(this::deviceAction);
    }
    /** The device's page's column's rows, placed from x 10 to {@code FluidGuiLayout.side}. */
    private List<Column.Row> deviceRows(){
        var state=menu.clientData().view().state();
        var rows=new ArrayList<>(page==Page.ACCESSORY?accessoryRows():DeviceColumn.rows(deviceInput(),FluidDeviceScreen::tr));
        var ids=new ArrayList<>(com.wormzjl.icheme.client.InstrumentRenderer.identities(minecraft.level,menu.position()));
        if(menu.clientData().valve()!=null)ids.add(String.format(Locale.ROOT,"CV-%04d",menu.identity()));
        rows.addAll(DeviceColumn.instrumentIds(ids,FluidDeviceScreen::tr));
        return rows;
    }
    /**
     * The door columns' fields and buttons, kept at their slots and moved as the view scrolls, so a scroll rebuilds
     * nothing; a button acts through {@code action}.
     */
    private void keepWidgets(java.util.function.Consumer<String> action){
        door.keep(this::doorBox,(spec,slot)->{
            // The side shown and the switch's position are latched, as the page's tab is; a display key is flat.
            var button=doorButton(spec.text(),slot.x(),slot.y(),slot.w(),()->action.accept(spec.key())).latched(spec.latched()).soft(spec.soft());
            button.active=spec.active();if(spec.hint()!=null)button.setTooltip(Tooltip.create(Component.literal(spec.hint())));
            return button;
        });
        door.place(leftPos,topPos);
    }
    /**
     * A device's page's action (DeviceColumn's keys): the other side's screen; the solids' recovery, sent at once; the
     * Phases' basis, the display's alone; the switch and a valve's target, drafts until Apply.
     */
    private void deviceAction(String action){
        if(action.startsWith("side:"))FluidNetwork.openPartner(menu);
        else if(action.equals("open_driver"))com.wormzjl.icheme.network.MachineNetwork.openDriver(menu.containerId,menu.position(),menu.identity());
        else if(action.equals("recover")){FluidNetwork.recoverSolids(menu,revision);message=tr("waiting");}
        // A solid still's fuel slot (W6): an item move, sent at once; the page's next bucket shows the slot.
        else if(action.equals("still_fuel:load")||action.equals("still_fuel:unload")){FluidNetwork.stillFuel(menu,action.endsWith(":load"));message=tr("waiting");}
        else{
            switch(action){
                case "tower:manual","tower:auto"->towerAuto=action.endsWith(":auto");
                case "phase_basis"->phaseBasis=preferredPhaseBasis=phaseBasis.next();
                case "power:off","power:on"->powerOn=action.endsWith(":on");
                case "valve:mass"->valveSource=com.wormzjl.icheme.science.fluid.network.FlowControl.ValveSource.MASS_FLOW;
                case "valve:volume"->valveSource=com.wormzjl.icheme.science.fluid.network.FlowControl.ValveSource.VOLUME_FLOW;
                case "still_firing:off","still_firing:on"->stillDrafts.firing=action.endsWith(":on");
                case "still_reflux:closed","still_reflux:open"->stillDrafts.open=action.endsWith(":open");
                case "converter_mode:off","converter_mode:balance","converter_mode:to_icheme","converter_mode:to_forge"->converterDrafts.mode=ConverterDoor.Mode.valueOf(action.substring("converter_mode:".length()).toUpperCase(java.util.Locale.ROOT));
                default->{}
            }
            rebuild();
        }
    }
    /** A device's page's door column, placed again from this frame's view: its texts follow the plant with no rebuild. */
    private void renderDevice(GuiCanvas canvas,Skin skin){
        door.place(deviceRows(),GuiCanvas.measure(font));
        drawDoor(canvas,skin);
    }
    /** The door columns as placed: drawn clipped to their view, the view's bar, a row's hint read on hover. */
    private void drawDoor(GuiCanvas canvas,Skin skin){
        var hover=door.draw(canvas,skin,hoverX,hoverY);if(hover!=null)hovered=hover;
    }
    /** The name's entry in the title strip (N1): the name as typed, at most DeviceName.MAX characters, sent with Apply; its hint while empty. */
    private EditBox nameBox(Rect slot){
        var box=nameEntry.box(font,slot.moved(leftPos,topPos),FluidDeviceScreen::skin,this::saveName);
        editors.put("name",addRenderableWidget(box));return box;
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
    /** A side's lines by their parts: a tee is a bypass, a feed station's own meter (U2) a flow station, a feed valve alone a ratio. */
    private static com.wormzjl.icheme.science.assembly.ExchangerBlueprint.Lines drawnLines(com.wormzjl.icheme.runtime.fluid.FluidView.Heat heat,String side){
        return heat.lines().part(side+"_inlet_tee")!=null?com.wormzjl.icheme.science.assembly.ExchangerBlueprint.Lines.BYPASS
            :heat.lines().part(side+"_feed_meter")!=null?com.wormzjl.icheme.science.assembly.ExchangerBlueprint.Lines.FEED
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
        @Override public String text(AssemblyDiagram.Text text){return Component.translatable(HeatDiagram.KEY+text.key(),text.args().toArray()).getString();}
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
                case COLUMN->{String texts=com.wormzjl.icheme.client.gui.common.HeatControlColumn.columnTexts(unitKind());yield heat.lines()!=null&&"COMPLETE".equals(heat.lines().status())?tr("heat_status."+texts):tr("heat_status."+texts+"_waiting");}
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
    // An open tour takes the typed characters too: none reaches an entry under it.
    @Override public boolean charTyped(char c,int modifiers){return tour.isOpen()||super.charTyped(c,modifiers);}
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(tour.keyPressed(key))return true;
        if(ProcessUi.textKeyPressed(this,key,scan,modifiers))return true;
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        if(tour.mouseClicked(font,width,height,x,y))return true;
        if(componentDropdown!=null&&componentDropdown.popupClick(x,y,button))return true;
        SkinField.clickOutside(this,x,y);
        // The door column's view on every page: its bar, then a choice it places (a mode, a valve's target); then the table's scroll.
        double rx=x-leftPos,ry=y-topPos;
        if(door.press(rx,ry,button))return true;
        if(button==0){var action=door.action(rx,ry);if(action!=null){if(page==Page.CONTROL)controlAction(action);else deviceAction(action);return true;}}
        if(page!=Page.CONTROL&&scroll.click(x,y,button)){rebuildNext=true;return true;}
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){
        if(componentDropdown!=null&&componentDropdown.popupDrag(y))return true;
        if(door.drag(y-topPos))return true;
        if(page!=Page.CONTROL&&scroll.drag(y)){rebuildNext=true;return true;}
        return super.mouseDragged(x,y,button,dx,dy);
    }
    @Override public boolean mouseReleased(double x,double y,int button){if(componentDropdown!=null)componentDropdown.popupRelease();scroll.release();door.release();return super.mouseReleased(x,y,button);}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        if(tour.isOpen())return true;
        if(componentDropdown!=null&&componentDropdown.popupScroll(x,y,vertical))return true;
        if(door.wheel(x-leftPos,y-topPos,vertical))return true;
        if(page!=Page.CONTROL&&x>=leftPos+tableX()&&scroll.wheel(vertical)){rebuildNext=true;return true;}
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
}
