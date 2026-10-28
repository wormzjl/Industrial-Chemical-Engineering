package com.wormzjl.icheme.client.gui.screens.inventory;

import com.wormzjl.icheme.client.ClientConfig;
import com.wormzjl.icheme.client.gui.common.AssemblyColumn;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn.Connection;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn.Mark;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn.Metric;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn.Page;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn.View;
import com.wormzjl.icheme.client.gui.common.ComponentDropdown;
import com.wormzjl.icheme.client.gui.common.ComponentSearch;
import com.wormzjl.icheme.client.gui.common.HeatControlColumn;
import com.wormzjl.icheme.client.gui.common.ProcessUi;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.GuiCanvas;
import com.wormzjl.icheme.client.gui.common.kit.KitDoor;
import com.wormzjl.icheme.client.gui.common.kit.KitWidgets;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.common.kit.SkinButton;
import com.wormzjl.icheme.client.gui.common.kit.SkinField;
import com.wormzjl.icheme.client.gui.common.kit.WindowScale;
import com.wormzjl.icheme.network.ColumnV3Network;
import com.wormzjl.icheme.science.column.v3.*;
import com.wormzjl.icheme.science.display.DisplayUnit.TemperatureUnit;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnDesign;
import com.wormzjl.icheme.science.material.MaterialName;
import com.wormzjl.icheme.world.inventory.ColumnCalculatorV3Menu;
import com.wormzjl.icheme.world.level.block.entity.ColumnCalculatorV3BlockEntity.V3State;
import com.wormzjl.icheme.world.level.block.entity.ColumnCalculatorV3BlockEntity.V3Status;
import java.util.*;
import java.util.stream.IntStream;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.glfw.GLFW;

/**
 * The column calculator: an editable process drawing whose accepted results are discarded on any physical draft edit.
 * A panel door on the GUI kit since batch 2026-10-28-gui-toolkit (GUI_REVIEW.md order 5; the door's parts from
 * {@link CalculatorColumn}): the Column page's drawing on the HMI glass, the inputs in function groups on the door, the
 * selected connection's group under them; the Feed page's mixture table on the glass; the Results page's views (profiles,
 * products, heat, a tray, the solver's report) on the glass, chosen on the door; the Equipment page's choices on the door,
 * the derived components on the glass. The status is said once, on the message display, the solver's own line on its
 * hover. Opening replays the server's state; the screen asks for a solve only when the player presses Solve.
 */
public final class ColumnCalculatorV3Screen extends AbstractContainerScreen<ColumnCalculatorV3Menu> {
    private static String tr(String key,Object...args){return Component.translatable("gui.icheme.column."+key,args).getString();}
    /** A text under {@code gui.icheme.}, as the builder names them. */
    private static String lang(String key,Object...args){return Component.translatable("gui.icheme."+key,args).getString();}
    private static final HeatControlColumn.Texts TEXTS=ColumnCalculatorV3Screen::lang;
    private static final CalculatorColumn.Rows EMPTY=new CalculatorColumn.Rows(List.of(),List.of());
    /** A canvas that draws nothing: the glass's inside at a rebuild, before a frame draws it. */
    private static final Canvas NOTHING=new Canvas(){
        @Override public Measure measure(){return Measure.fixed(6);}
        @Override public void fill(int x0,int y0,int x1,int y1,int argb){}
        @Override public void text(String text,int x,int y,int argb){}
        @Override public void text(String text,float x,float y,float scale,int argb){}
        @Override public void clip(Rect area){}
        @Override public void unclip(){}
    };
    private final Map<String,EditBox> editors=new LinkedHashMap<>();
    private final KitDoor door=new KitDoor();
    private final ScrollView glassView=new ScrollView();
    private final KitWidgets glassWidgets=new KitWidgets();
    private V3State state;
    private V3EditorDraft draft;
    private V3ColumnInput candidate;
    private String validation=tr("waiting"),rejection="",notice="",hovered,search="",presetName="";
    private V3ClientPresets library;
    private V3ClientPresets.Kind presetKind;
    private V3ClientPresets.Snapshot presetSnapshot;
    /** The basis shown, the client's (ClientConfig), one switch for every screen. */
    private boolean savingPreset,massRate=ClientConfig.basis()==ClientConfig.Basis.MASS;
    /** Components page: the player's choices (the saved design's until edited), the draft's design or why it cannot be saved. */
    private ColumnComponents.Choices choices=ColumnComponents.Choices.DEFAULT;
    private boolean choicesEdited,savingDesign;
    private ColumnDesign designPreview;
    private String designProblem="";
    private long pendingNonce;
    private boolean conflict,rebuilding,invalidated,awaitingResult,needsRebuild,twoColumns;
    /** The client's temperature unit, one switch for every screen (ClientConfig). */
    private boolean kelvin=ClientConfig.temperature()==TemperatureUnit.KELVIN;
    private Page page=Page.COLUMN;
    private View view=View.PROFILES;
    private Metric metric=Metric.TEMPERATURE;
    private int selectedNode=1,selectedStream,selectedDraw=-1,selectedPA=-1,selectedSteam=-1;
    private SkinButton solve;
    private ComponentDropdown componentDropdown;
    /** What renderBg drew on the glass this frame, for clicks and hovers: the glass's inside, the drawing, a plot and its drops, the templates' list. */
    private Rect glassBox,plotBox,listBox;
    private CalculatorColumn.Drawing drawing;
    private double[] drops;
    private int plotNodes;
    private AssemblyColumn.PartList presetList;
    public ColumnCalculatorV3Screen(ColumnCalculatorV3Menu menu,Inventory inventory,Component title){
        super(menu,inventory,title);imageWidth=840;imageHeight=460;
    }
    private static Skin skin(){return ClientConfig.skin();}
    @Override public void removed(){super.removed();WindowScale.restore(minecraft);}
    @Override protected void init(){
        WindowScale.apply(minecraft,this);
        imageWidth=CalculatorColumn.width(width);imageHeight=CalculatorColumn.height(height);
        super.init();titleLabelY=inventoryLabelY=-1000;
        if(library==null)library=new V3ClientPresets(minecraft.gameDirectory.toPath(),name->{
            try{return minecraft.getResourceManager().getResource(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("icheme",name.substring("assets/icheme/".length()))).orElseThrow().open();}
            catch(java.io.IOException|NoSuchElementException e){return null;}
        });
        ColumnV3Network.setClientStateConsumer(this::receive);
        ColumnV3Network.setClientRejectionConsumer((pos,nonce,reason)->{
            if(!menu.blockPos().equals(pos)||nonce!=pendingNonce)return;
            pendingNonce=0;awaitingResult=false;
            rejection=savingDesign&&reason.startsWith(ColumnV3Network.NOT_SAVED)?tr("design_not_saved",reason.substring(ColumnV3Network.NOT_SAVED.length())):tr("request_rejected");
            savingDesign=false;validate();rebuild();
        });
        ColumnV3Network.sendStateRequest(menu.blockPos());rebuild();
    }
    private V3EditorDraft newDraft(V3ColumnInput input){var next=new V3EditorDraft(input,state.editorCatalog().weightsFor(input));if(massRate)next.composition().toggleBasis();return next;}
    private void receive(net.minecraft.core.BlockPos pos,V3State incoming){
        if(!menu.blockPos().equals(pos)||state!=null&&incoming.stateRevision()<state.stateRevision())return;
        boolean own=pendingNonce!=0&&incoming.clientNonce()==pendingNonce;
        // A saved design leaves the input alone: its acknowledgement keeps the draft the player is editing.
        boolean replace=draft==null||own&&!savingDesign||!draft.dirty()&&!invalidated;
        boolean changed=draft==null||!draft.base().equals(incoming.input());
        if(!replace&&state!=null&&!state.input().equals(incoming.input()))conflict=true;
        state=incoming;
        if(own){pendingNonce=0;rejection="";conflict=false;if(savingDesign){savingDesign=choicesEdited=false;notice=tr("design_saved");}}
        if(!choicesEdited)incoming.design().ifPresent(d->choices=d.components().choices());
        if(replace&&(changed||own))draft=newDraft(incoming.input());
        if(awaitingResult&&pendingNonce==0&&incoming.status()!=V3Status.CALCULATING){
            awaitingResult=false;invalidated=false;
        }
        validate();rebuild();
    }
    @Override public void onClose(){
        ColumnV3Network.setClientStateConsumer((pos,view)->{});
        ColumnV3Network.setClientRejectionConsumer((pos,nonce,reason)->{});super.onClose();
    }
    private boolean busy(){return pendingNonce!=0||state!=null&&state.status()==V3Status.CALCULATING;}
    private boolean editable(){return !busy()&&draft!=null;}
    private void edited(){invalidated=true;rejection="";notice="";validate();}
    private void validate(){
        candidate=null;
        if(draft==null){validation=tr("waiting");return;}
        try{candidate=draft.assemble();validation=invalidated?tr("draft_changed"):tr("ready");}
        catch(IllegalArgumentException e){validation=validationMessage(e);}
        updateControls();
    }
    private void updateControls(){
        if(solve!=null)solve.active=candidate!=null&&!busy()&&!conflict&&state!=null;
        editors.forEach((key,e)->e.active=key.equals("search")||key.equals("presetName")||editable());
    }
    private void submit(){
        validate();if(candidate==null||busy()||conflict||state==null)return;
        pendingNonce=ColumnV3Network.sendCalculate(menu.blockPos(),state.inputRevision(),candidate);
        invalidated=true;awaitingResult=true;rejection="";rebuild();
    }
    /** Sends the draft's design with the choices; the server checks the structure again and stores it (plan section 4.3). */
    private void saveDesign(){
        validate();checkDesign();if(designPreview==null||busy()||conflict||state==null)return;
        pendingNonce=ColumnV3Network.sendSaveDesign(menu.blockPos(),candidate,choices);savingDesign=true;rejection="";notice="";rebuild();
    }
    private void checkDesign(){
        designPreview=null;designProblem="";
        if(candidate==null){designProblem=validation;return;}
        try{designPreview=ColumnDesign.of(candidate,choices);}catch(IllegalArgumentException e){designProblem=Objects.toString(e.getMessage(),"");}
    }
    private void choose(ColumnComponents.Choices next){choices=next;choicesEdited=true;notice="";rebuild();}
    private void changePage(Page target){page=target;presetKind=null;savingPreset=false;glassView.reset();door.reset();rebuild();}
    private int stageCount(){return draft==null?2:draft.preview("s2",draft.base().stageCount()+2,2,66)-2;}
    private int feedTray(){return draft.preview("s3",draft.base().feedStageNumber()+1,2,stageCount()+2)-1;}
    private void toggleBasis(){
        try{if(draft!=null){draft.composition().toggleBasis();massRate=draft.composition().mass();}else massRate=!massRate;rejection="";basisToConfig();rebuild();}
        catch(IllegalArgumentException e){rejection=validationMessage(e);}
    }
    private void basisToConfig(){ClientConfig.basis(massRate?ClientConfig.Basis.MASS:ClientConfig.Basis.MOLE);}
    private String validationMessage(IllegalArgumentException e){
        String message=Objects.toString(e.getMessage(),"").toLowerCase(Locale.ROOT);
        if(message.contains("steam"))return tr("invalid_steam");
        if(message.contains("draw"))return tr("invalid_draw");
        if(message.contains("component")||message.contains("mixture"))return tr("invalid_mixture");
        if(message.contains("heat")||message.contains("cool")||message.contains("pumparound"))return tr("invalid_pa");
        return tr("invalid_input");
    }
    private V3ClientPresets.Kind pageKind(){return page==Page.FEED?V3ClientPresets.Kind.MIXTURE:V3ClientPresets.Kind.COLUMN;}

    // ---- The door ----

    /** The page's door rows: the templates while they show; else the page's, none before the first draft. */
    private CalculatorColumn.Rows rows(){
        if(presetKind!=null&&draft!=null)return CalculatorColumn.presets(conflict,savingPreset,TEXTS);
        return switch(page){
            case COLUMN->draft==null?EMPTY:CalculatorColumn.column(columnInput(),TEXTS);
            case FEED->draft==null?EMPTY:CalculatorColumn.feed(conflict,editable(),unit(),TEXTS);
            case RESULTS->CalculatorColumn.results(resultsInput(),TEXTS);
            case EQUIPMENT->draft==null?EMPTY:CalculatorColumn.equipment(equipmentInput(),TEXTS);
        };
    }
    private CalculatorColumn.ColumnInput columnInput(){
        var selected=selectedDraw>=0?Connection.DRAW:selectedPA>=0?Connection.PA:selectedSteam>=0?Connection.STEAM:Connection.NONE;
        int index=Math.max(selectedDraw,Math.max(selectedPA,selectedSteam));
        boolean spread=selectedPA<0||draft.get("c"+selectedPA+"split").equals("UNIFORM");
        boolean addDraw=editable()&&stageCount()>0&&IntStream.range(0,V3ColumnInput.MAX_SIDE_DRAWS).anyMatch(i->draft.get("d"+i+"stage").isBlank());
        boolean addPa=editable()&&stageCount()>1&&IntStream.range(0,4).anyMatch(i->draft.get("c"+i+"draw").isBlank());
        return new CalculatorColumn.ColumnInput(conflict,editable(),unit(),selected,index,spread,addDraw,addPa,editable()&&draft.canAddSteam());
    }
    private List<V3ColumnStreamProperties> streams(){return inspection()==null?List.of():state.displayResult().orElseThrow().streams();}
    private CalculatorColumn.ResultsInput resultsInput(){
        var streams=streams();selectedStream=Math.clamp(selectedStream,0,Math.max(0,streams.size()-1));
        selectedNode=Math.clamp(selectedNode,0,stageCount()+1);
        return new CalculatorColumn.ResultsInput(conflict,view,metric,streams.stream().map(this::streamName).toList(),selectedStream,draft==null?-1:selectedNode,stageCount());
    }
    private CalculatorColumn.EquipmentInput equipmentInput(){
        checkDesign();var fitted=choices.fit(candidate==null?0:candidate.pumparounds().size());
        return new CalculatorColumn.EquipmentInput(conflict,fitted.condenser(),fitted.refluxPump(),fitted.pumparoundPumps(),designPreview!=null&&!busy()&&!conflict&&state!=null);
    }
    /** A push button or a tab at door rect {@code at}. */
    private SkinButton push(Column.Button spec,Rect at){
        return addRenderableWidget(SkinButton.of(spec,at.moved(leftPos,topPos),this::act,ColumnCalculatorV3Screen::skin));
    }
    private void rebuild(){
        if(rebuilding)return;rebuilding=true;needsRebuild=false;
        String focus=editors.entrySet().stream().filter(e->e.getValue()==getFocused()).map(Map.Entry::getKey).findFirst().orElse(null);
        int cursor=focus==null?0:editors.get(focus).getCursorPosition();
        boolean dropdownOpen=componentDropdown!=null&&componentDropdown.expanded();
        clearWidgets();editors.clear();componentDropdown=null;glassWidgets.clear();solve=null;
        var m=GuiCanvas.measure(font);
        var tabs=CalculatorColumn.tabs(page,TEXTS);var tabSlots=CalculatorColumn.tabSlots(imageWidth);
        for(int i=0;i<tabs.size();i++)push(tabs.get(i),tabSlots.get(i));
        // The theme key shows the contrast sign; its hover names the panel shown.
        String theme=lang(ClientConfig.theme()==ClientConfig.Theme.DARK?"fluid.theme.dark":"fluid.theme.light");
        var at=CalculatorColumn.theme(imageWidth);
        var themeKey=new SkinButton(leftPos+at.x(),topPos+at.y(),at.w(),at.h(),Component.literal(theme),b->{ClientConfig.toggleTheme();rebuild();},ColumnCalculatorV3Screen::skin).icon(Skin.Icon.THEME);
        themeKey.setTooltip(Tooltip.create(Component.literal(theme+"\n").append(Component.translatable("gui.icheme.fluid.theme.hint"))));
        addRenderableWidget(themeKey);
        // Templates, Solve and Save; the basis and the temperature unit are display keys on their tray (renderBg).
        var footer=CalculatorColumn.footer(imageWidth,imageHeight);
        var keys=CalculatorColumn.footerButtons(new CalculatorColumn.Footer(presetKind!=null,page==Page.FEED,true,massRate,unit()),TEXTS);
        for(int i=0;i<keys.size();i++){var b=push(keys.get(i),footer.get(i));if(keys.get(i).key().equals("solve"))solve=b.primary(true);}
        var rows=rows();twoColumns=rows.two();
        door.layout(CalculatorColumn.door(imageWidth,imageHeight,twoColumns),CalculatorColumn.bar(imageWidth),rows.at(imageWidth),m);
        door.keep(this::field,(spec,slot)->addRenderableWidget(SkinButton.of(spec,slot,this::act,ColumnCalculatorV3Screen::skin)));
        door.place(leftPos,topPos);
        var box=skin().well(NOTHING,CalculatorColumn.glass(imageWidth,imageHeight,twoColumns));
        if(presetKind!=null&&draft!=null){if(savingPreset)buildSaveForm(box,m);else checkPresets();}
        else if(page==Page.FEED&&draft!=null)buildComposition(box,m);
        glassWidgets.place(glassView,leftPos,topPos);
        if(focus!=null&&editors.containsKey(focus)){setFocused(editors.get(focus));editors.get(focus).setCursorPosition(cursor);}
        if(componentDropdown!=null&&"search".equals(focus))componentDropdown.expanded(dropdownOpen);
        rebuilding=false;updateControls();
    }
    @Override protected void containerTick(){super.containerTick();if(needsRebuild)rebuild();}
    /** An entry on the door: its LCD, the draft's text in the shown unit; the drawing follows the trays and connections it changes. */
    private AbstractWidget field(Column.Field f,Rect slot){
        String key=f.key();
        var e=new SkinField(font,slot.x(),slot.y(),slot.w(),slot.h(),Component.literal(f.label()),ColumnCalculatorV3Screen::skin);
        e.setMaxLength(32);e.setValue(draft.display(key,kelvin));
        e.setResponder(v->{draft.setDisplay(key,v,kelvin);edited();if(key.equals("s2")||key.equals("s3")||key.endsWith("stage")||key.endsWith("draw")||key.endsWith("return"))needsRebuild=true;});
        e.setTooltip(Tooltip.create(Component.literal(f.hint()==null?f.label():f.label()+"\n"+f.hint())));
        editors.put(key,addRenderableWidget(e));
        return e;
    }
    private void act(String key){
        switch(key){
            case "templates"->openPresets(pageKind());
            case "solve"->submit();
            case "save"->openSave(pageKind());
            case "basis"->toggleBasis();
            case "temperature"->{kelvin=!kelvin;ClientConfig.temperature(temperatureUnit());rebuild();}
            case "use_server"->{draft=newDraft(state.input());conflict=false;invalidated=false;validate();rebuild();}
            case "keep_draft"->{conflict=false;validate();rebuild();}
            case "add:draw"->{selectedDraw=draft.addDraw(Math.clamp(selectedNode+1,2,Math.max(2,stageCount()+1)));selectedPA=selectedSteam=-1;edited();rebuild();}
            case "add:pa"->{selectedPA=draft.addPumparound(Math.clamp(selectedNode+1,3,Math.max(3,stageCount()+1)));selectedDraw=selectedSteam=-1;edited();rebuild();}
            case "add:steam"->{selectedSteam=draft.addSteam(Math.clamp(selectedNode+1,2,stageCount()+2));selectedDraw=selectedPA=-1;edited();rebuild();}
            case "remove"->{
                if(selectedDraw>=0){draft.removeDraw(selectedDraw);selectedDraw=-1;}
                else if(selectedPA>=0){draft.removePumparound(selectedPA);selectedPA=-1;}
                else if(selectedSteam>=0){draft.removeSteam(selectedSteam);selectedSteam=-1;}
                edited();rebuild();
            }
            case "split:uniform","split:return"->{if(selectedPA>=0){draft.set("c"+selectedPA+"split",key.equals("split:uniform")?"UNIFORM":"RETURN_TRAY");edited();rebuild();}}
            case "clear"->{draft.composition().clear();edited();rebuild();}
            case "copy_report"->{
                if(state!=null)minecraft.keyboardHandler.setClipboard(String.join("\n",state.diagnostics())+"\n"+state.displayResult().map(Object::toString).orElse(""));
                notice=tr("report_copied");rejection="";
            }
            case "save_design"->saveDesign();
            case "back"->{presetKind=null;savingPreset=false;glassView.reset();rebuild();}
            case "refresh"->{rejection="";notice="";presetSnapshot=library.refresh(presetKind);glassView.reset();rebuild();}
            case "open_folder"->net.minecraft.Util.getPlatform().openPath(library.directory(presetKind));
            case "save_json"->saveJson();
            case "tray-","tray+"->{selectedNode=Math.clamp(selectedNode+(key.equals("tray+")?1:-1),0,stageCount()+1);glassView.reset();rebuild();}
            default->{
                int colon=key.indexOf(':');String head=colon<0?key:key.substring(0,colon),tail=colon<0?"":key.substring(colon+1);
                switch(head){
                    case "tab"->changePage(Page.valueOf(tail.toUpperCase(Locale.ROOT)));
                    case "view"->{view=View.valueOf(tail.toUpperCase(Locale.ROOT));glassView.reset();rebuild();}
                    case "metric"->{metric=Metric.valueOf(tail.toUpperCase(Locale.ROOT));rebuild();}
                    case "stream"->{selectedStream=Integer.parseInt(tail);glassView.reset();rebuild();}
                    case "condenser"->choose(choices.withCondenser(ColumnComponents.Condenser.valueOf(tail.toUpperCase(Locale.ROOT))));
                    case "reflux"->choose(choices.withRefluxPump(ColumnComponents.Pump.valueOf(tail.toUpperCase(Locale.ROOT))));
                    default->{
                        if(head.startsWith("pa")&&candidate!=null){
                            var fitted=choices.fit(candidate.pumparounds().size());
                            choose(fitted.withPumparoundPump(Integer.parseInt(head.substring(2)),ColumnComponents.Pump.valueOf(tail.toUpperCase(Locale.ROOT))));
                        }
                    }
                }
            }
        }
    }
    private String unit(){return temperatureUnit().symbol();}
    private TemperatureUnit temperatureUnit(){return kelvin?TemperatureUnit.KELVIN:TemperatureUnit.CELSIUS;}
    private double temperature(double k){return temperatureUnit().display(k);}

    // ---- The templates ----

    private void openPresets(V3ClientPresets.Kind kind){
        rejection="";notice="";presetKind=kind;savingPreset=false;presetSnapshot=library.refresh(kind);glassView.reset();rebuild();
    }
    private void openSave(V3ClientPresets.Kind kind){presetKind=kind;savingPreset=true;presetName="";rebuild();}
    private void checkPresets(){
        if(presetSnapshot!=null&&!presetSnapshot.problems().isEmpty())rejection=tr("preset_errors",presetSnapshot.problems().size(),presetSnapshot.problems().getFirst().file());
    }
    private void saveJson(){
        try{
            String json=presetKind==V3ClientPresets.Kind.COLUMN?V3ClientPresets.columnJson(presetName,draft.assembleOperating()):V3ClientPresets.mixtureJson(presetName,draft.composition());
            var path=library.save(presetKind,presetName,json);notice=tr("saved",path.getFileName().toString());rejection="";presetKind=null;savingPreset=false;rebuild();
        }catch(java.io.IOException|IllegalArgumentException e){rejection=tr("save_failed");}
    }
    private String library(){return tr(presetKind==V3ClientPresets.Kind.COLUMN?"column_library":"mixture_library");}
    private CalculatorColumn.SaveForm saveForm(Rect box,Measure m){return CalculatorColumn.saveForm(library(),tr("preset_filename"),tr("preset_filename_hint"),box,m);}
    /** The new template's name: a text field on the glass, under the library and its label. */
    private void buildSaveForm(Rect box,Measure m){
        var at=saveForm(box,m).name();
        var name=new EditBox(font,leftPos+at.x(),topPos+at.y(),at.w(),at.h(),Component.literal(tr("preset_filename")));
        name.setMaxLength(64);name.setValue(presetName);name.setResponder(v->presetName=v);editors.put("presetName",addRenderableWidget(name));
    }
    private List<V3ClientPresets.Entry> presetEntries(){return presetSnapshot==null?List.of():presetSnapshot.entries();}
    private String presetName(V3ClientPresets.Preset preset){return preset.translationKey().isBlank()?preset.name():Component.translatable(preset.translationKey()).getString();}
    private String presetHint(V3ClientPresets.Entry entry){
        return tr(presetKind==V3ClientPresets.Kind.COLUMN?"column_templates.hint":"mixture_templates.hint");
    }
    private void loadPreset(V3ClientPresets.Preset preset){
        if(busy())return;
        try{
            if(preset instanceof V3ClientPresets.Column column){
                var replacement=newDraft(draft.base());replacement.loadColumn(column.fields());replacement.assembleOperating();
                draft.loadColumn(column.fields());
            }else if(preset instanceof V3ClientPresets.Mixture mixture){
                var source=state.editorCatalog().templates().values().stream().filter(i->i.packageId().equals(mixture.packageId())).findFirst().orElseThrow();
                var comp=new V3CompositionDraft(source,state.editorCatalog().weightsFor(source));comp.load(mixture.amounts(),mixture.mass());
                draft.composition(source,state.editorCatalog().weightsFor(source));draft.composition().load(mixture.amounts(),mixture.mass());massRate=mixture.mass();basisToConfig();
            }
            selectedDraw=selectedPA=selectedSteam=-1;presetKind=null;glassView.reset();edited();rebuild();
        }catch(IllegalArgumentException|NoSuchElementException e){rejection=tr("preset_incompatible");}
    }

    // ---- The feed's mixture ----

    /** The search field over the table, the table's rows below it, scrolled in their view. */
    private Rect feedArea(Rect box){return new Rect(box.x(),box.y()+24,box.w()-ScrollView.BAR-2,0);}
    private Rect feedViewport(Rect box){return new Rect(box.x(),box.y()+22,box.w(),box.h()-22);}
    private CalculatorColumn.FeedTable feedTable(Rect box,Measure m,boolean values){
        double[] fractions=null;double total=Double.NaN;
        if(values)try{fractions=draft.composition().displayFractions();total=Arrays.stream(draft.assembleOperating().feedComponentMolarFlowsMolPerSecond()).sum();}catch(IllegalArgumentException ignored){}
        var rows=new ArrayList<CalculatorColumn.FeedRow>();
        for(int id:draft.composition().rows()){
            String share="",flow="",other="";
            if(values){
                share=fractions==null?"—":percent(fractions[id]*100);
                flow=fractions==null||!Double.isFinite(total)?"—":percent(draft.composition().componentFlow(id,total,massRate));
                other=fractions==null||!Double.isFinite(total)?"—":percent(draft.composition().componentFlow(id,total,!massRate));
            }
            rows.add(new CalculatorColumn.FeedRow(id,material(draft.composition().source().componentBasis().componentId(id)),share,flow,other));
        }
        var header=List.of(tr("component"),tr("relative_amount"),tr(draft.composition().mass()?"mass_percent":"mole_percent"),tr(massRate?"mass_rate":"molar_rate"),tr(massRate?"molar_rate":"mass_rate"));
        return CalculatorColumn.feedTable(header,rows,feedArea(box),m);
    }
    private void buildComposition(Rect box,Measure m){
        var options=new ArrayList<ComponentSearch.Option>();
        var basis=draft.composition().source().componentBasis();
        for(int i=0;i<basis.componentCount();i++)if(!draft.composition().contains(i))
            options.add(new ComponentSearch.Option(i,material(basis.componentId(i)),basis.componentId(i)));
        componentDropdown=new ComponentDropdown(font,leftPos+box.x(),topPos+box.y(),Math.min(360,box.w()),box.h(),
            options,search,value->search=value,id->{
                if(!editable())return;draft.composition().add(id);search="";edited();rebuild();
            });
        editors.put("search",addRenderableWidget(componentDropdown));
        var table=feedTable(box,m,false);
        glassView.layout(feedViewport(box),box.right()-ScrollView.BAR,table.height());
        for(var entry:table.entries()){
            int id=entry.row().id();var a=entry.amount();
            var e=new EditBox(font,a.x(),a.y(),a.w(),a.h(),Component.literal(tr("relative_amount")));
            e.setMaxLength(24);e.setValue(draft.composition().get(id));e.setTooltip(Tooltip.create(Component.literal(tr("relative_amount.hint"))));
            e.setResponder(v->{draft.composition().set(id,v);edited();});editors.put("comp"+id,addRenderableWidget(e));
            glassWidgets.keep(e,a);
            var r=entry.remove();
            var remove=Button.builder(Component.literal("×"),b->{if(!editable())return;draft.composition().remove(id);edited();rebuild();}).bounds(r.x(),r.y(),r.w(),r.h()).build();
            remove.setTooltip(Tooltip.create(Component.literal(tr("remove_component.hint"))));
            glassWidgets.keep(addRenderableWidget(remove),r);
        }
    }

    // ---- The status ----

    /** Persisted results are replayed as STALE until the server accepts a fresh solve; never draw that old cache as current. */
    private V3ColumnInspection inspection(){
        return invalidated||conflict||state==null||state.status()!=V3Status.SUCCESS ? null
                : state.displayResult().flatMap(V3ColumnDisplayResult::inspection).orElse(null);
    }
    private boolean failed(){return !invalidated&&state!=null&&state.status()==V3Status.FAILED;}
    private CalculatorColumn.Status status(){
        String engine=state==null?null:tr("diagnostic_status",tr("status."+state.status().name().toLowerCase(Locale.ROOT)))
            +(state.diagnostics().isEmpty()?"":"\n"+state.diagnostics().getFirst());
        return CalculatorColumn.status(new CalculatorColumn.StatusInput(draft==null,conflict,rejection,notice,busy(),savingDesign,failed(),
            draft!=null&&candidate==null?validation:null,inspection()!=null,invalidated,engine),TEXTS);
    }

    // ---- Drawing ----

    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partialTick){
        ProcessUi.restoreCursor(minecraft,this);
        hovered=null;
        door.place(leftPos,topPos);glassWidgets.place(glassView,leftPos,topPos);
        super.render(g,mouseX,mouseY,partialTick);
        if(componentDropdown==null||!componentDropdown.popupContains(mouseX,mouseY))KitDoor.hint(g,font,hovered,mouseX,mouseY);
        if(componentDropdown!=null)componentDropdown.renderSuggestions(g,mouseX,mouseY);
    }
    @Override protected void renderBg(GuiGraphics g,float partialTick,int mouseX,int mouseY){
        var skin=skin();var canvas=new GuiCanvas(g,font,leftPos,topPos);
        g.pose().pushPose();g.pose().translate(leftPos,topPos,0);
        skin.page(canvas,new Rect(0,0,imageWidth,imageHeight));
        skin.hDivider(canvas,8,imageWidth-8,imageHeight-CalculatorColumn.DIVIDER);
        skin.softKeys(canvas,CalculatorColumn.keys(imageWidth,imageHeight));
        for(int x:CalculatorColumn.dividers(imageWidth,imageHeight,twoColumns))skin.vDivider(canvas,x,CalculatorColumn.TOP,imageHeight-CalculatorColumn.FOOT);
        // The glass goes under the widgets that sit on it (the feed's entries, a template's name).
        var box=skin.well(canvas,CalculatorColumn.glass(imageWidth,imageHeight,twoColumns));
        glassBox=box;drawing=null;plotBox=null;drops=null;presetList=null;listBox=null;
        canvas.clip(box);glass(canvas,box);canvas.unclip();
        glassView.drawBar(canvas,skin);
        g.pose().popPose();
    }
    /** What the glass shows: the templates, the drawing, the mixture, a result, the components; a note before the first draft. */
    private void glass(Canvas c,Rect box){
        var m=c.measure();
        if(presetKind!=null&&draft!=null){
            if(savingPreset){
                var form=saveForm(box,m);glassView.layout(box,box.right()-ScrollView.BAR,0);
                CalculatorColumn.draw(c,form.head(),box,0);CalculatorColumn.draw(c,form.hint(),new Rect(box.x(),form.hintY(),box.w(),box.bottom()-form.hintY()),0);
                return;
            }
            var head=new CalculatorColumn.SheetBuilder(box.w(),m).note(library(),Palette.MUTED).build();
            CalculatorColumn.draw(c,head,box,0);
            var area=new Rect(box.x(),box.y()+head.height(),box.w(),box.h()-head.height());
            var lines=new Rect(area.x(),area.y(),area.w()-ScrollView.BAR-2,area.h());
            var parts=new ArrayList<AssemblyColumn.Part>();
            for(var entry:presetEntries())parts.add(new AssemblyColumn.Part(presetName(entry.preset()),tr(entry.bundled()?"bundled":"custom"),entry.bundled()?Palette.ACCENT:Palette.TEXT));
            var list=AssemblyColumn.parts(parts,List.of(tr("bundled"),tr("custom")),lines.w(),m);
            glassView.layout(area,area.right()-ScrollView.BAR,list.height());
            c.clip(area);AssemblyColumn.draw(c,list,lines,glassView.offset(),-1);c.unclip();
            presetList=list;listBox=lines;
            return;
        }
        if(draft==null&&page!=Page.RESULTS){sheet(c,box,new CalculatorColumn.SheetBuilder(sheetWidth(box),m).note(tr("waiting"),Palette.MUTED));return;}
        switch(page){
            case COLUMN->columnDrawing(c,box,m);
            case FEED->{
                var table=feedTable(box,m,true);
                glassView.layout(feedViewport(box),box.right()-ScrollView.BAR,table.height());
                var viewport=feedViewport(box);c.clip(viewport);
                if(table.entries().isEmpty())CalculatorColumn.draw(c,new CalculatorColumn.SheetBuilder(box.w(),m).note(tr("empty_mixture"),Palette.MUTED).build(),new Rect(box.x(),table.top()+CalculatorColumn.PITCH+4,box.w(),box.h()),0);
                CalculatorColumn.draw(c,table,viewport,glassView.offset());
                c.unclip();
            }
            case RESULTS->results(c,box,m);
            case EQUIPMENT->sheet(c,box,equipment(box,m));
        }
    }
    /** A sheet of readouts (built {@link #sheetWidth} wide) in its scrolled view, its numbers clear of the bar. */
    private void sheet(Canvas c,Rect box,CalculatorColumn.SheetBuilder builder){
        var sheet=builder.build();
        glassView.layout(box,box.right()-ScrollView.BAR,sheet.height());
        CalculatorColumn.draw(c,sheet,new Rect(box.x(),box.y(),sheetWidth(box),box.h()),glassView.offset());
    }
    /** The sheets' width: the glass less its bar. */
    private static int sheetWidth(Rect box){return box.w()-ScrollView.BAR-2;}
    private void columnDrawing(Canvas c,Rect box,Measure m){
        int n=stageCount();selectedNode=Math.clamp(selectedNode,0,n+1);
        var draws=new ArrayList<Mark>();var pas=new ArrayList<Mark>();var steams=new ArrayList<Mark>();
        for(int i=0;i<V3ColumnInput.MAX_SIDE_DRAWS;i++)if(!draft.get("d"+i+"stage").isBlank())draws.add(new Mark(i,draft.preview("d"+i+"stage",2,2,n+2)-1,0));
        for(int i=0;i<4;i++)if(!draft.get("c"+i+"draw").isBlank())pas.add(new Mark(i,draft.preview("c"+i+"draw",3,2,n+2)-1,draft.preview("c"+i+"return",2,2,n+2)-1));
        for(int i=0;i<2;i++)if(!draft.get("t"+i+"stage").isBlank())steams.add(new Mark(i,draft.preview("t"+i+"stage",n+2,2,n+2)-1,0));
        var view=inspection();double[] shares=null;
        if(view!=null&&view.nodes().size()==n+2){
            double min=view.nodes().stream().mapToDouble(V3ColumnInspection.Node::temperatureKelvin).min().orElse(0),max=view.nodes().stream().mapToDouble(V3ColumnInspection.Node::temperatureKelvin).max().orElse(1);
            shares=new double[n+2];for(int i=0;i<n+2;i++)shares[i]=(view.nodes().get(i).temperatureKelvin()-min)/Math.max(1e-9,max-min);
        }
        var selected=selectedDraw>=0?Connection.DRAW:selectedPA>=0?Connection.PA:selectedSteam>=0?Connection.STEAM:Connection.NONE;
        var in=new CalculatorColumn.DrawingInput(n,feedTray(),draws,pas,steams,selectedNode,selected,Math.max(selectedDraw,Math.max(selectedPA,selectedSteam)),shares);
        glassView.layout(box,box.right()-ScrollView.BAR,CalculatorColumn.drawingHeight(n,box.h()));
        var d=CalculatorColumn.drawing(in,box,glassView.offset(),m,TEXTS,node->trayHint(node,n,view));
        CalculatorColumn.draw(c,d,TEXTS);
        drawing=d;
    }
    private String trayHint(int node,int n,V3ColumnInspection view){
        String hint=tr(node==0?"inspect_top":node==n+1?"inspect_bottom":"inspect_tray",node+1);
        if(view==null||node>=view.nodes().size())return hint;
        var at=view.nodes().get(node);
        return hint+"\n"+tr("tray_reading",fmt(temperature(at.temperatureKelvin())),unit(),fmt(at.pressurePascal()/1000));
    }
    private void results(Canvas c,Rect box,Measure m){
        var view=inspection();
        switch(this.view){
            case PROFILES->{
                if(view==null){sheet(c,box,new CalculatorColumn.SheetBuilder(sheetWidth(box),m).note(tr("solve_profiles"),Palette.MUTED));return;}
                glassView.layout(box,box.right()-ScrollView.BAR,0);
                int nodes=view.nodes().size();
                if(metric==Metric.DROP){
                    int head=CalculatorColumn.heading(c,box,tr("column_dp",fmt(totalDrop(view))),List.of(),m);
                    var plot=CalculatorColumn.plot(box,head);
                    var values=new double[nodes];for(int i=2;i<=view.input().stageCount();i++)values[i]=drop(view,i);
                    CalculatorColumn.drops(c,plot,values);plotBox=plot;drops=values;plotNodes=nodes;
                    return;
                }
                var legend=metric!=Metric.TRAFFIC?List.<CalculatorColumn.Legend>of():List.of(new CalculatorColumn.Legend(tr("traffic.organic"),CalculatorColumn.TRAFFIC[0]),
                    new CalculatorColumn.Legend(tr("traffic.vapor"),CalculatorColumn.TRAFFIC[1]),new CalculatorColumn.Legend(tr("traffic.water"),CalculatorColumn.TRAFFIC[2]),
                    new CalculatorColumn.Legend(tr("traffic.aqueous"),CalculatorColumn.TRAFFIC[3]));
                String title=switch(metric){case TEMPERATURE->tr("temperature",unit());case PRESSURE->tr("pressure");default->tr("traffic");};
                int head=CalculatorColumn.heading(c,box,title,legend,m);
                var plot=CalculatorColumn.plot(box,head);
                if(plot.h()<12)return;
                int series=metric==Metric.TRAFFIC?4:1;var values=new double[series][nodes];
                for(int s=0;s<series;s++)for(int i=0;i<nodes;i++)values[s][i]=profileValue(view.nodes().get(i),s);
                if(!CalculatorColumn.profile(c,plot,values,metric==Metric.TRAFFIC?CalculatorColumn.TRAFFIC:new int[]{Palette.ACCENT}))
                    CalculatorColumn.draw(c,new CalculatorColumn.SheetBuilder(box.w(),m).note(tr("absent_phase"),Palette.WARN).build(),new Rect(box.x(),box.y()+head,box.w(),box.h()-head),0);
                else{plotBox=plot;plotNodes=nodes;}
            }
            case PRODUCTS->sheet(c,box,products(box,m));
            case HEAT->sheet(c,box,heat(box,m));
            case TRAY->sheet(c,box,tray(box,m));
            case SOLVER->sheet(c,box,solver(box,m));
        }
    }
    private double profileValue(V3ColumnInspection.Node node,int series){
        return switch(metric){
            case TEMPERATURE->temperature(node.temperatureKelvin());
            case PRESSURE->node.pressurePascal()/1000;
            case TRAFFIC->switch(series){case 0->node.liquidMolPerSecond()*3.6;case 1->node.vaporMolPerSecond()*3.6;
                case 2->node.waterVaporMolPerSecond()*3.6;default->node.freeWaterMolPerSecond()*3.6;};
            case DROP->throw new IllegalStateException("A drop is a bar, not a profile");};
    }
    private CalculatorColumn.SheetBuilder products(Rect box,Measure m){
        int width=sheetWidth(box);var out=new CalculatorColumn.SheetBuilder(width,m);
        var products=streams();
        if(products.isEmpty()){out.note(tr("solve_products"),Palette.MUTED);return out;}
        var stream=products.get(Math.clamp(selectedStream,0,products.size()-1));
        out.pair(tr("field.flow"),fmt(stream.molarFlowMolPerSecond()*3.6)+" kmol/h").pair(tr("reading.mass_flow"),fmt(stream.massFlowKgPerSecond()*3600)+" kg/h")
            .pair(tr("field.temperature"),fmt(temperature(stream.temperatureKelvin()))+" "+unit()).pair(tr("reading.pressure"),fmt(stream.pressurePascal()/1000)+" kPa").blank();
        // The basis shown first; the other one too where the glass has room.
        List<String> header=massRate?List.of(tr("component"),tr("mass_percent"),tr("mass_rate"),tr("mole_percent"),tr("molar_rate"))
            :List.of(tr("component"),tr("mole_percent"),tr("molar_rate"),tr("mass_percent"),tr("mass_rate"));
        var rows=new ArrayList<List<String>>();
        for(var c:stream.moleFractions()){
            String molePercent=percent(c.moleFraction()*100),massPercent=percent(c.massFraction()*100);
            String moles=percent(stream.molarFlowMolPerSecond()*c.moleFraction()*3.6),mass=percent(stream.massFlowKgPerSecond()*c.massFraction()*3600);
            rows.add(massRate?List.of(material(c.componentId()),massPercent,mass,molePercent,moles):List.of(material(c.componentId()),molePercent,moles,massPercent,mass));
        }
        // Both bases where the names keep 80 px beside the numbers.
        if(CalculatorColumn.SheetBuilder.fits(header,rows,width-80+m.width(header.getFirst()),m))return out.table(header,rows);
        return out.table(header.subList(0,3),rows.stream().map(r->r.subList(0,3)).toList());
    }
    private CalculatorColumn.SheetBuilder tray(Rect box,Measure m){
        var out=new CalculatorColumn.SheetBuilder(sheetWidth(box),m);var view=inspection();
        if(draft==null){out.note(tr("waiting"),Palette.MUTED);return out;}
        int n=stageCount();selectedNode=Math.clamp(selectedNode,0,n+1);
        out.note(nodeName(selectedNode,n),Palette.TEXT);
        if(view==null||selectedNode>=view.nodes().size()){out.note(tr("solve_to_inspect"),Palette.MUTED);return out;}
        var node=view.nodes().get(selectedNode);
        out.pair(tr("field.temperature"),fmt(temperature(node.temperatureKelvin()))+" "+unit()).pair(tr("reading.pressure"),fmt(node.pressurePascal()/1000)+" kPa")
            .pair(tr("tray_dp"),selectedNode>0&&selectedNode<=n?fmt(drop(view,selectedNode))+" kPa":"—")
            .pair(tr("hc_liquid"),fmt(node.liquidMolPerSecond()*3.6)+" kmol/h").pair(tr("hc_vapor"),fmt(node.vaporMolPerSecond()*3.6)+" kmol/h")
            .pair(tr("water_vapor"),fmt(node.waterVaporMolPerSecond()*3.6)+" kmol/h").pair(tr("free_water"),fmt(node.freeWaterMolPerSecond()*3.6)+" kmol/h").blank();
        var rows=new ArrayList<List<String>>();
        for(int c=0;c<view.input().componentBasis().componentCount();c++)rows.add(List.of(material(view.input().componentBasis().componentId(c)),
            node.liquidMolPerSecond()==0?"—":percent(node.liquidFractions().get(c)*100),node.vaporMolPerSecond()==0?"—":percent(node.vaporFractions().get(c)*100)));
        return out.table(List.of(tr("component"),tr("liquid_percent"),tr("vapor_percent")),rows);
    }
    private CalculatorColumn.SheetBuilder heat(Rect box,Measure m){
        var out=new CalculatorColumn.SheetBuilder(sheetWidth(box),m).note(tr("signed_heat"),Palette.MUTED);
        if(inspection()!=null)state.displayResult().ifPresent(result->result.dutyLedger().ifPresent(d->{
            out.note(tr("tray_duty",1,fmt(d.condenserWatts()/1e6)),Palette.TEXT).note(tr("tray_duty",stageCount()+2,fmt(d.reboilerWatts()/1e6)),Palette.TEXT)
                .note(tr("stage_heat",fmt(d.stageHeatTotalWatts()/1e6)),Palette.TEXT)
                .note(tr("feed_enthalpy",fmt(d.feedEnthalpyWatts()/1e6)),Palette.TEXT).note(tr("steam_enthalpy",fmt(d.steamEnthalpyWatts()/1e6)),Palette.TEXT);
            for(var stage:d.stageDuties())out.note(tr("tray_duty",stage.trayNumber()+1,fmt(stage.dutyWatts()/1e6)),Palette.TEXT);
        }));
        return out;
    }
    /** The solver's report: the latest attempt, the result's checks (wet trays, drop, flooding, warnings), why a solve failed, the numerical evidence. */
    private CalculatorColumn.SheetBuilder solver(Rect box,Measure m){
        var out=new CalculatorColumn.SheetBuilder(sheetWidth(box),m);
        out.note(tr("diagnostic_status",state==null?tr("waiting"):tr("status."+state.status().name().toLowerCase(Locale.ROOT))),Palette.TEXT);
        if(!rejection.isBlank())out.note(rejection,Palette.WARN);
        var view=inspection();
        if(view!=null){
            List<String> wet=new ArrayList<>();
            for(int i=1;i<=view.input().stageCount();i++)if(view.nodes().get(i).freeWaterMolPerSecond()>0)wet.add(Integer.toString(i+1));
            out.note(tr("wet_trays",wet.isEmpty()?tr("none"):String.join(", ",wet)),Palette.TEXT).note(tr("column_dp",fmt(totalDrop(view))),Palette.TEXT);
            state.displayResult().orElseThrow().trayHydraulics().ifPresent(h->{
                out.note(tr(h.floods()?"flooding":"below_flood",h.maximumFloodTray()+1),h.floods()?Palette.WARN:Palette.TEXT).note(tr("worst_flood",fmt(h.maximumFloodFraction()*100)),Palette.TEXT);
                if(!h.correctionApplied()&&h.residualMismatchFraction()>0.01)out.note(tr("uncorrected_pressure"),Palette.WARN);
            });
            if(view.audit().advisoryEvidence().stream().anyMatch(a->a.contains("dew point")||a.contains("dew-point")))out.note(tr("dew_warning"),Palette.WARN);
        }else if(failed()){
            String raw=String.join(" ",state.diagnostics()).toLowerCase(Locale.ROOT);
            var draw=java.util.regex.Pattern.compile("side draw on authored tray ([0-9]+) requests ([0-9.eE+-]+) kmol/h; final internal liquid ([0-9.eE+-]+)").matcher(raw);
            if(draw.find()){
                out.note(tr("draw_balance",Integer.parseInt(draw.group(1)),draw.group(2),draw.group(3)),Palette.WARN);
                if(Double.parseDouble(draw.group(2))>=Double.parseDouble(draw.group(3)))out.note(tr("liquid_depletion"),Palette.WARN);
            }else if(raw.contains("exhaust")||raw.contains("deplet"))out.note(tr("liquid_depletion"),Palette.WARN);
            else if(raw.contains("pressure"))out.note(tr("pressure_failure"),Palette.WARN);
        }
        if(!invalidated&&state!=null)state.displayResult().ifPresent(r->{
            out.note(tr("iterations",r.newtonIterations()),Palette.TEXT).note(tr("residual",fraction(r.maximumScaledResidual())),Palette.TEXT)
                .note(tr("closure",fraction(r.closureTolerance())),Palette.TEXT);
            r.inspection().ifPresent(v->{for(var check:v.audit().checks())out.note(tr("audit_value",check.family(),fraction(check.value()),fraction(check.limit())),Palette.TEXT);});
            out.note(tr("dataset",com.wormzjl.icheme.science.material.MaterialCatalog.Package.label(r.datasetRevision())),Palette.TEXT)
                .note(tr("formulation",r.formulationRevision()),Palette.TEXT).note(tr("digest",r.inputDigest()),Palette.TEXT);
        });
        if(state!=null&&state.status()==V3Status.FAILED)out.note(tr("solver_failed_hint"),Palette.MUTED);
        return out;
    }
    /** The design's state, then the derived and fixed components (plan section 4.1), then how the saved design differs. */
    private CalculatorColumn.SheetBuilder equipment(Rect box,Measure m){
        checkDesign();
        var out=new CalculatorColumn.SheetBuilder(sheetWidth(box),m);
        var saved=state==null?Optional.<ColumnDesign>empty():state.design();
        if(designPreview==null)out.note(tr("design_not_saved",designProblem),Palette.WARN);
        else if(saved.isPresent()&&saved.get().equals(designPreview))out.note(tr("design_saved"),Palette.ACCENT);
        else out.note(tr("design_ready",designPreview.trays().trays()+2,fmt(designPreview.diameter())),Palette.TEXT);
        out.blank();
        out.pair(tr("component_ejector"),candidate==null?"—":ColumnComponents.ejectorRequired(candidate.topPressurePascal())
                ?tr("ejector_required",fmt(candidate.topPressurePascal()/1000)):tr("ejector_none",fmt(candidate.topPressurePascal()/1000)))
            .pair(tr("component_kettle"),candidate==null?"—":reboilerDuty(candidate)>0?tr("kettle_required",fmt(reboilerDuty(candidate)/1e6)):tr("kettle_none"))
            .pair(tr("component_receiver"),tr("receiver")).pair(tr("component_reflux_line"),tr("reflux_line"))
            .pair(tr("component_valves"),candidate==null?"—":tr("valves",candidate.steamFeeds().size(),candidate.sideDraws().size())).blank();
        if(saved.isPresent()&&!saved.get().equals(designPreview))out.note(tr("design_differs",saved.get().trays().trays()+2,fmt(saved.get().diameter())),Palette.MUTED);
        else if(saved.isEmpty())out.note(tr("design_none"),Palette.MUTED);
        return out;
    }
    private static double reboilerDuty(V3ColumnInput input){
        return input.specifications().stream().filter(V3ColumnSpecification.ReboilerDuty.class::isInstance)
            .mapToDouble(s->((V3ColumnSpecification.ReboilerDuty)s).watts()).findFirst().orElse(0);
    }
    @Override protected void renderLabels(GuiGraphics g,int mouseX,int mouseY){
        int x=mouseX-leftPos,y=mouseY-topPos;
        var skin=skin();var canvas=new GuiCanvas(g,font,leftPos,topPos);
        String name=title.getString(),shown=font.plainSubstrByWidth(name,CalculatorColumn.title(imageWidth));
        skin.title(canvas,10,10,shown);
        if(shown.length()<name.length()&&x>=6&&x<CalculatorColumn.title(imageWidth)+14&&y>=6&&y<22)hovered=name;
        // The status once, on the message display; the solver's own line on hover.
        var status=status();var line=CalculatorColumn.display(imageWidth);
        String words=font.plainSubstrByWidth(status.text(),line.w());
        skin.status(canvas,line,words,status.palette());
        if(new Rect(line.x()-4,line.y()-5,line.w()+8,15).contains(x,y))
            hovered=words.length()<status.text().length()?status.text()+(status.hint()==null?"":"\n"+status.hint()):status.hint();
        var hint=door.draw(canvas,skin,x,y);
        if(hint!=null)hovered=hint;
        var glass=glassHint(x,y);
        if(glass!=null)hovered=glass;
    }
    /** What a point on the glass reads: a part of the drawing, a template, a plot's tray. */
    private String glassHint(double x,double y){
        if(glassBox==null||!glassBox.contains(x,y))return null;
        if(drawing!=null){var hit=drawing.at(x,y);return hit==null?null:hit.hint();}
        if(presetList!=null&&listBox!=null&&listBox.contains(x,y)){
            int i=AssemblyColumn.entry(presetList,listBox,glassView.offset(),y);
            return i<0||i>=presetEntries().size()?null:presetHint(presetEntries().get(i));
        }
        if(plotBox!=null){
            if(drops!=null){
                if(!plotBox.contains(x,y))return y<plotBox.y()?tr("terminal_no_dp"):null;
                int tray=CalculatorColumn.dropNode(plotBox,drops,y);
                return tray<0?null:tr("dp_click.hint",tray+1,fmt(drops[tray]));
            }
            return plotBox.contains(x,y)?tr("profile_click.hint"):null;
        }
        return null;
    }
    /** A click on the glass: a part of the drawing, a template, a plot's tray. */
    private boolean glassClick(double x,double y){
        if(drawing!=null){
            var hit=drawing.at(x,y);if(hit==null)return false;
            String a=hit.action();int colon=a.indexOf(':'),at=colon<0?0:Integer.parseInt(a.substring(colon+1));
            if(a.equals("feed"))changePage(Page.FEED);
            else if(a.startsWith("tray:")){selectedNode=at;}
            else if(a.startsWith("draw:")){selectedDraw=at;selectedPA=selectedSteam=-1;rebuild();}
            else if(a.startsWith("pa:")){selectedPA=at;selectedDraw=selectedSteam=-1;rebuild();}
            else if(a.startsWith("steam:")){selectedSteam=at;selectedDraw=selectedPA=-1;rebuild();}
            return true;
        }
        if(presetList!=null&&listBox!=null&&listBox.contains(x,y)){
            int i=AssemblyColumn.entry(presetList,listBox,glassView.offset(),y);
            if(i>=0&&i<presetEntries().size())loadPreset(presetEntries().get(i).preset());
            return true;
        }
        if(plotBox!=null&&plotBox.contains(x,y)){
            int node=drops!=null?CalculatorColumn.dropNode(plotBox,drops,y):CalculatorColumn.node(plotBox,plotNodes,y);
            if(node<0)return false;
            selectedNode=node;view=View.TRAY;glassView.reset();rebuild();return true;
        }
        return false;
    }
    private static double drop(V3ColumnInspection view,int tray){
        if(tray<=1||tray>view.input().stageCount())return 0;
        return (view.nodes().get(tray).pressurePascal()-view.nodes().get(tray-1).pressurePascal())/1000;
    }
    private static double totalDrop(V3ColumnInspection view){int n=view.input().stageCount();return n<2?0:(view.nodes().get(n).pressurePascal()-view.nodes().get(1).pressurePascal())/1000;}
    private String streamName(V3ColumnStreamProperties stream){
        if(stream.streamId().startsWith("side_liquid_tray_"))return tr("stream.side_draw",Integer.parseInt(stream.streamId().substring("side_liquid_tray_".length()))+1);
        return tr("stream."+stream.streamId());
    }
    private String material(String id){return state==null?id:ProcessUi.materialName(state.materialNames().getOrDefault(id,MaterialName.chemical(id)));}
    private static String percent(double value){return value!=0&&Math.abs(value)<0.05?String.format(Locale.ROOT,"%.2g",value):fmt(value);}
    private static String fmt(double value){return String.format(Locale.ROOT,"%.1f",value);}
    private static String fraction(double value){return String.format(Locale.ROOT,"%.5g",value);}
    private static String nodeName(int node,int stages){return tr(node==0?"top_tray":node==stages+1?"bottom_tray":"tray",node+1);}

    // ---- Input ----

    @Override public boolean mouseScrolled(double mx,double my,double horizontal,double vertical){
        if(componentDropdown!=null&&componentDropdown.popupScroll(mx,my,vertical))return true;
        double x=mx-leftPos,y=my-topPos;
        return door.wheel(x,y,vertical)||glassView.wheel(x,y,vertical)||super.mouseScrolled(mx,my,horizontal,vertical);
    }
    @Override public boolean mouseClicked(double mx,double my,int button){
        if(componentDropdown!=null&&componentDropdown.popupClick(mx,my,button))return true;
        double x=mx-leftPos,y=my-topPos;
        if(door.press(x,y,button)||glassView.press(x,y,button))return true;
        // A widget on the glass (an entry, a name) wins over the glass under it.
        if(button==0&&children().stream().noneMatch(w->w.isMouseOver(mx,my))&&glassClick(x,y))return true;
        return super.mouseClicked(mx,my,button);
    }
    @Override public boolean mouseDragged(double mx,double my,int button,double dx,double dy){
        if(componentDropdown!=null&&componentDropdown.popupDrag(my))return true;
        return door.drag(my-topPos)||glassView.drag(my-topPos)||super.mouseDragged(mx,my,button,dx,dy);
    }
    @Override public boolean mouseReleased(double mx,double my,int button){
        if(componentDropdown!=null)componentDropdown.popupRelease();
        door.release();glassView.release();
        return super.mouseReleased(mx,my,button);
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(ProcessUi.textKeyPressed(this,key,scan,modifiers))return true;
        boolean trays=presetKind==null&&(page==Page.COLUMN||page==Page.RESULTS&&view==View.TRAY);
        if(trays&&draft!=null&&!(getFocused() instanceof EditBox)&&(key==GLFW.GLFW_KEY_UP||key==GLFW.GLFW_KEY_DOWN)){
            selectedNode=Math.clamp(selectedNode+(key==GLFW.GLFW_KEY_UP?-1:1),0,stageCount()+1);
            if(page==Page.RESULTS)rebuild();
            return true;
        }
        return super.keyPressed(key,scan,modifiers);
    }
}
