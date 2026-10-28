package com.wormzjl.icheme.client.gui.screens.inventory;

import com.wormzjl.icheme.science.column.v3.*;
import com.wormzjl.icheme.science.column.v3.V3ColumnSpecification.ControlledQuantity;
import java.util.*;

/** Widget-independent draft; one-decimal displays never round untouched physical inputs. */
final class V3EditorDraft {
    private final V3ColumnInput base;
    private final Map<String,String> original=new LinkedHashMap<>(),fields=new LinkedHashMap<>();
    private final Map<String,Double> exact=new HashMap<>();
    private V3CompositionDraft composition;
    private boolean columnChanged;
    private final boolean[] steamAtBottom=new boolean[2];

    V3EditorDraft(V3ColumnInput base){this(base,null);}
    V3EditorDraft(V3ColumnInput base,List<Double> weights){
        this.base=base;
        double[] scalars={Arrays.stream(base.feedComponentMolarFlowsMolPerSecond()).sum()*3.6,
            base.feedTemperatureKelvin()-273.15,base.stageCount()+2,base.feedStageNumber()+1,
            value(base,ControlledQuantity.CONDENSER_OUTLET_TEMPERATURE)-273.15,
            value(base,ControlledQuantity.REBOILER_DUTY)/1e6,value(base,ControlledQuantity.ORGANIC_REFLUX_RATIO),
            base.topPressurePascal()/1e5,base.stagePressureDropPascal()/1000,base.columnDiameterMetres()};
        for(int i=0;i<scalars.length;i++)putNumber("s"+i,scalars[i],i==2||i==3);
        for(int i=0;i<V3ColumnInput.MAX_SIDE_DRAWS;i++){
            var d=i<base.sideDraws().size()?base.sideDraws().get(i):null;
            if(d==null){put("d"+i+"stage","");put("d"+i+"rate","");}
            else{putNumber("d"+i+"stage",d.trayNumber()+1,true);putNumber("d"+i+"rate",d.molarFlowMolPerSecond()*3.6,false);}
        }
        for(int i=0;i<V3ColumnInput.MAX_STEAM_FEEDS;i++){
            var steam=i<base.steamFeeds().size()?base.steamFeeds().get(i):null;
            if(steam==null){put("t"+i+"stage","");put("t"+i+"rate","");put("t"+i+"T","");}
            else{steamAtBottom[i]=steam.stageNumber()==base.stageCount()+1;putNumber("t"+i+"stage",steam.stageNumber()+1,true);putNumber("t"+i+"rate",steam.molarFlowMolPerSecond()*3.6,false);putNumber("t"+i+"T",steam.temperatureKelvin()-273.15,false);}
        }
        for(int i=0;i<V3ColumnInput.MAX_PUMPAROUNDS;i++){
            var c=i<base.pumparounds().size()?base.pumparounds().get(i):null;
            if(c==null){put("c"+i+"draw","");put("c"+i+"return","");put("c"+i+"duty","");}
            else{putNumber("c"+i+"draw",c.drawTray()+1,true);putNumber("c"+i+"return",c.returnTray()+1,true);putNumber("c"+i+"duty",-c.dutyWatts()/1e6,false);}
            put("c"+i+"split",c==null?"UNIFORM":c.split().name());
        }
        if(weights!=null)composition=new V3CompositionDraft(base,weights);
    }
    V3ColumnInput base(){return base;}
    V3CompositionDraft composition(){return composition;}
    void composition(V3ColumnInput preset,List<Double> weights){composition=new V3CompositionDraft(preset,weights);}
    String get(String key){if(key.matches("t[0-1]stage")&&steamAtBottom[Character.digit(key.charAt(1),10)])return fields.get("s2");return fields.get(key);}
    private static boolean temperature(String key){return key.equals("s1")||key.equals("s4")||key.matches("t[0-1]T");}
    String display(String key,boolean kelvin){
        if(!kelvin||!temperature(key)||get(key).isBlank())return get(key);
        try{return String.format(Locale.ROOT,"%.1f",Double.parseDouble(precise(key))+273.15);}
        catch(NumberFormatException invalid){return get(key);}
    }
    void setDisplay(String key,String value,boolean kelvin){
        if(kelvin&&temperature(key)&&!value.isBlank())try{value=Double.toString(Double.parseDouble(value)-273.15);}
        catch(NumberFormatException ignored){/* Preserve incomplete typing. */}
        set(key,value);
    }
    void set(String key,String value){
        if(!fields.containsKey(key))throw new IllegalArgumentException("Unknown input field");
        String previous=fields.put(key,value);
        if(!Objects.equals(previous,value))exact.remove(key);
        if(key.matches("t[0-1]stage"))steamAtBottom[Character.digit(key.charAt(1),10)]=value.equals(fields.get("s2"));

    }
    boolean dirty(){return columnChanged||!fields.equals(original)||composition!=null&&(composition.dirty()||!composition.source().equals(base));}
    private void put(String key,String value){fields.put(key,value);original.put(key,value);}
    private void putNumber(String key,double value,boolean integer){
        put(key,integer?Integer.toString((int)value):String.format(Locale.ROOT,"%.1f",value));exact.put(key,value);
    }
    private String precise(String key){
        if(key.matches("t[0-1]stage")&&steamAtBottom[Character.digit(key.charAt(1),10)])return get(key);
        if(!exact.containsKey(key))return get(key);
        double v=exact.get(key);
        return key.equals("s2")||key.equals("s3")||key.endsWith("stage")||key.endsWith("Stage")||key.endsWith("draw")||key.endsWith("return")
                ?Integer.toString((int)v):Double.toString(v);
    }
    int preview(String key,int fallback,int min,int max){
        try{return Math.clamp(Integer.parseInt(get(key)),min,max);}catch(NumberFormatException e){return fallback;}
    }
    int addDraw(int tray){
        for(int i=0;i<V3ColumnInput.MAX_SIDE_DRAWS;i++)if(get("d"+i+"stage").isBlank()){
            set("d"+i+"stage",Integer.toString(tray));set("d"+i+"rate","1.0");return i;}
        return -1;
    }
    void removeDraw(int i){set("d"+i+"stage","");set("d"+i+"rate","");}
    int addPumparound(int tray){
        for(int i=0;i<V3ColumnInput.MAX_PUMPAROUNDS;i++)if(get("c"+i+"draw").isBlank()){
            set("c"+i+"draw",Integer.toString(Math.max(3,tray)));set("c"+i+"return",Integer.toString(Math.max(2,tray-1)));
            set("c"+i+"duty","0.1");return i;}
        return -1;
    }
    void removePumparound(int i){set("c"+i+"draw","");set("c"+i+"return","");set("c"+i+"duty","");}
    boolean canAddSteam(){return nextSteamTray(2)>=2&&java.util.stream.IntStream.range(0,2).anyMatch(i->get("t"+i+"stage").isBlank());}
    private int nextSteamTray(int requested){
        int count=preview("s2",base.stageCount()+2,2,66);
        if(requested>=2&&requested<=count&&!steamUses(requested))return requested;
        for(int tray=2;tray<=count;tray++)if(!steamUses(tray))return tray;
        return -1;
    }
    private boolean steamUses(int tray){return get("t0stage").equals(Integer.toString(tray))||get("t1stage").equals(Integer.toString(tray));}
    int addSteam(int tray){
        int selected=nextSteamTray(tray);if(selected<2)return -1;
        for(int i=0;i<2;i++)if(get("t"+i+"stage").isBlank()){
            set("t"+i+"stage",Integer.toString(selected));set("t"+i+"rate","1.0");set("t"+i+"T","260.0");return i;
        }
        return -1;
    }
    void removeSteam(int i){set("t"+i+"stage","");set("t"+i+"rate","");set("t"+i+"T","");}
    /** A column preset's fields: every field but the tray drop {@code s8}, which stays the base's (owner choice (a), 2026-10-28). */
    void loadColumn(Map<String,String> data){
        var expected=new HashSet<>(fields.keySet());expected.remove("s8");
        if(!data.keySet().equals(expected))throw new IllegalArgumentException("Invalid column preset fields");
        data.forEach((key,value)->{
            fields.put(key,value);exact.remove(key);
            if(!value.isBlank()&&!key.endsWith("split")){
                double n=Double.parseDouble(value);exact.put(key,n);
                fields.put(key,isInteger(key)?Integer.toString((int)n):String.format(Locale.ROOT,"%.1f",n));
            }
        });for(int i=0;i<2;i++)steamAtBottom[i]=fields.get("t"+i+"stage").equals(fields.get("s2"));columnChanged=true;
    }
    private static boolean isInteger(String key){return key.equals("s2")||key.equals("s3")||key.endsWith("stage")||key.endsWith("draw")||key.endsWith("return");}
    private String index(String key,int delta){
        String value=precise(key);return value.isBlank()?value:Integer.toString(Integer.parseInt(value)+delta);
    }
    V3ColumnInput assemble(){return assemble(true);}
    V3ColumnInput assembleOperating(){return assemble(false);}
    private V3ColumnInput assemble(boolean withComposition){
        if(!dirty())return base;
        List<String> scalar=new ArrayList<>();for(int i=0;i<10;i++)scalar.add(i==2?index("s2",-2):i==3?index("s3",-1):precise("s"+i));
        List<SideDrawDraft.Row> draws=new ArrayList<>();
        for(int i=0;i<V3ColumnInput.MAX_SIDE_DRAWS;i++)draws.add(new SideDrawDraft.Row(index("d"+i+"stage",-1),precise("d"+i+"rate")));
        List<PumparoundDraft.Row> coolers=new ArrayList<>();
        for(int i=0;i<V3ColumnInput.MAX_PUMPAROUNDS;i++)coolers.add(new PumparoundDraft.Row(
            index("c"+i+"draw",-1),index("c"+i+"return",-1),precise("c"+i+"duty"),V3PumparoundSpec.Split.valueOf(get("c"+i+"split"))));
        var parsed=ColumnInputDraft.assemble(base,scalar,draws,
            new SteamFeedDraft.Row("","",""),new SteamFeedDraft.Row("","",""),coolers);
        List<V3SteamFeedSpec> steam=new ArrayList<>();
        for(int i=0;i<2;i++)if(!get("t"+i+"stage").isBlank())steam.add(new V3SteamFeedSpec(Integer.parseInt(index("t"+i+"stage",-1)),Double.parseDouble(precise("t"+i+"rate"))/3.6,Double.parseDouble(precise("t"+i+"T"))+273.15));
        var source=!withComposition||composition==null?base:composition.source();
        double[] flows=parsed.feedComponentMolarFlowsMolPerSecond();
        if(withComposition&&composition!=null&&(composition.dirty()||!source.equals(base))){
            flows=composition.moleFractions();double total=Double.parseDouble(precise("s0"))/3.6;
            for(int i=0;i<flows.length;i++)flows[i]*=total;
        }else if(!columnChanged&&fields.get("s0").equals(original.get("s0")))flows=base.feedComponentMolarFlowsMolPerSecond();
        var precise=new V3ColumnInput(base.schemaVersion(),source.packageId(),source.assayId(),source.componentBasis(),flows,
            !columnChanged&&fields.get("s1").equals(original.get("s1"))?base.feedTemperatureKelvin():parsed.feedTemperatureKelvin(),
            parsed.stageCount(),parsed.feedStageNumber(),unchanged("s7")?base.topPressurePascal():parsed.topPressurePascal(),
            base.stagePressureDropPascal(),
            List.of(new V3ColumnSpecification.CondenserOutletTemperature(unchanged("s4")?value(base,ControlledQuantity.CONDENSER_OUTLET_TEMPERATURE):value(parsed,ControlledQuantity.CONDENSER_OUTLET_TEMPERATURE)),
                new V3ColumnSpecification.OrganicRefluxRatio(unchanged("s6")?value(base,ControlledQuantity.ORGANIC_REFLUX_RATIO):value(parsed,ControlledQuantity.ORGANIC_REFLUX_RATIO)),
                new V3ColumnSpecification.ReboilerDuty(unchanged("s5")?value(base,ControlledQuantity.REBOILER_DUTY):value(parsed,ControlledQuantity.REBOILER_DUTY))),
            parsed.sideDraws(),steam,parsed.pumparounds(),unchanged("s9")?base.columnDiameterMetres():parsed.columnDiameterMetres());
        V3ColumnProblemResolver.validateInput(precise);
        return precise;
    }
    private boolean unchanged(String key){return !columnChanged&&Objects.equals(fields.get(key),original.get(key));}
    static double value(V3ColumnInput input,ControlledQuantity quantity){
        return input.specifications().stream().filter(s->s.controlledQuantity()==quantity).mapToDouble(s->switch(s){
            case V3ColumnSpecification.CondenserOutletTemperature t->t.kelvin();
            case V3ColumnSpecification.OrganicRefluxRatio r->r.ratio();
            case V3ColumnSpecification.ReboilerDuty d->d.watts();
        }).findFirst().orElseThrow();
    }

    /** Pure draft parsing kept separate from widgets so disabled-row and unit semantics are testable. */
    static final class SideDrawDraft {
        private SideDrawDraft() {}

        static List<V3SideDrawSpec> parse(List<Row> rows, int stageCount, double feedMolPerSecond) {
            if (rows.size() > V3ColumnInput.MAX_SIDE_DRAWS) throw new IllegalArgumentException("Too many side draw rows");
            List<V3SideDrawSpec> draws = new ArrayList<>();
            Set<Integer> trays = new HashSet<>();
            double total = 0.0;
            for (Row row : rows) {
                if (row.rateKmolPerHour().isBlank()) continue;
                double rate = Double.parseDouble(row.rateKmolPerHour().trim()) / 3.6;
                if (rate == 0.0) continue;
                int tray = Integer.parseInt(row.tray().trim());
                if (tray > stageCount || !trays.add(tray)) throw new IllegalArgumentException("Draw trays must be distinct and in range");
                draws.add(new V3SideDrawSpec(tray, rate));
                total += rate;
            }
            if (!Double.isFinite(feedMolPerSecond) || feedMolPerSecond <= 0 || total >= feedMolPerSecond) {
                throw new IllegalArgumentException("Total side draws must be below the feed rate");
            }
            draws.sort(Comparator.comparingInt(V3SideDrawSpec::trayNumber));
            return List.copyOf(draws);
        }

        record Row(String tray, String rateKmolPerHour) {}
    }

    /** Pure player-unit steam parser, kept independent of widgets and server-only pressure validation. */
    static final class SteamFeedDraft {
        private static final double KMOL_PER_HOUR_TO_MOL_PER_SECOND = 1_000.0 / 3_600.0;
        private static final double CELSIUS_TO_KELVIN = 273.15;

        private SteamFeedDraft() {}

        static List<V3SteamFeedSpec> parse(Row sump, Row tray, int stageCount, double feedMolPerSecond) {
            List<V3SteamFeedSpec> result = new ArrayList<>(V3ColumnInput.MAX_STEAM_FEEDS);
            add(result, stageCount + 1, sump.rateKmolPerHour(), sump.temperatureCelsius(), "Sump steam");
            if (!tray.rateKmolPerHour().isBlank() && Double.parseDouble(tray.rateKmolPerHour().trim()) != 0.0) {
                int stage = Integer.parseInt(tray.stage().trim());
                if (stage < 1 || stage > stageCount) throw new IllegalArgumentException("Tray steam stage must be within the tray range");
                add(result, stage, tray.rateKmolPerHour(), tray.temperatureCelsius(), "Tray steam");
            }
            if (result.size() > 1 && result.get(0).stageNumber() == result.get(1).stageNumber()) {
                throw new IllegalArgumentException("Steam feeds must address distinct stages");
            }
            double total = result.stream().mapToDouble(V3SteamFeedSpec::molarFlowMolPerSecond).sum();
            if (!Double.isFinite(feedMolPerSecond) || feedMolPerSecond <= 0.0 || total > feedMolPerSecond) {
                throw new IllegalArgumentException("Total steam rate must not exceed the feed rate");
            }
            return List.copyOf(result);
        }

        private static void add(List<V3SteamFeedSpec> result, int stage, String rateText, String temperatureText, String label) {
            if (rateText.isBlank()) return;
            double rate = Double.parseDouble(rateText.trim()) * KMOL_PER_HOUR_TO_MOL_PER_SECOND;
            if (rate == 0.0) return;
            if (temperatureText.isBlank()) throw new IllegalArgumentException(label + " temperature is required when rate is set");
            result.add(new V3SteamFeedSpec(stage, rate, Double.parseDouble(temperatureText.trim()) + CELSIUS_TO_KELVIN));
        }

        record Row(String stage, String rateKmolPerHour, String temperatureCelsius) {}
    }

    /**
     * Pure pumparound-row parsing kept separate from widgets so the sign convention stays testable.
     *
     * <p>The editor authors a cooler as a <em>positive</em> number of megawatts removed, while the science contract
     * stores a signed watt duty that is positive when heat is added to the column. Cooling therefore crosses this
     * boundary as a negation, and this class is the only place that performs it.</p>
     */
    static final class PumparoundDraft {
        static final double MEGAWATT_TO_WATT = 1_000_000.0;

        private PumparoundDraft() {}

        /** Player-authored cooling in MW to the signed science duty in W; cooling is always negative. */
        static double coolingMegawattsToDutyWatts(double coolingMegawatts) {
            return -coolingMegawatts * MEGAWATT_TO_WATT;
        }

        /** Signed science duty in W back to displayed cooling in MW; a server-authored heater shows negative. */
        static double dutyWattsToCoolingMegawatts(double dutyWatts) {
            return -dutyWatts / MEGAWATT_TO_WATT;
        }

        /**
         * Parses the editor rows in display order.
         *
         * <p>A row whose draw and return tray are both blank is switched off and contributes no specification;
         * every other row must be complete, in range, and cooling-signed.</p>
         */
        static List<V3PumparoundSpec> parse(List<Row> rows, int stageCount) {
            if (rows.size() > V3ColumnInput.MAX_PUMPAROUNDS) {
                throw new IllegalArgumentException("At most " + V3ColumnInput.MAX_PUMPAROUNDS + " coolers are supported");
            }
            List<V3PumparoundSpec> coolers = new ArrayList<>(rows.size());
            for (int index = 0; index < rows.size(); index++) {
                V3PumparoundSpec cooler = parseRow(rows.get(index), index + 1, stageCount);
                if (cooler == null) continue;
                for (V3PumparoundSpec existing : coolers) {
                    if (existing.returnTray() == cooler.returnTray() && existing.drawTray() == cooler.drawTray()) {
                        throw invalid(index + 1, "repeats an existing draw and return tray pair");
                    }
                }
                coolers.add(cooler);
            }
            return List.copyOf(coolers);
        }

        private static V3PumparoundSpec parseRow(Row row, int number, int stageCount) {
            String draw = row.drawTray().trim();
            String returned = row.returnTray().trim();
            String cooling = row.coolingMegawatts().trim();
            if (draw.isBlank() && returned.isBlank()) return null;
            if (draw.isBlank() || returned.isBlank()) throw invalid(number, "needs both a draw tray and a return tray");
            int drawTray = integer(draw, number, "draw tray");
            int returnTray = integer(returned, number, "return tray");
            if (returnTray < 1 || drawTray > stageCount) throw invalid(number, "must stay within trays 1.." + stageCount);
            if (returnTray > drawTray) throw invalid(number, "must return above the tray it draws from");
            if (cooling.isBlank()) throw invalid(number, "needs a cooling duty in MW");
            double megawatts;
            try {
                megawatts = Double.parseDouble(cooling);
            } catch (NumberFormatException notANumber) {
                throw invalid(number, "cooling must be a number of MW");
            }
            if (!Double.isFinite(megawatts) || megawatts <= 0.0) {
                throw invalid(number, "cooling must be a positive number of MW");
            }
            return new V3PumparoundSpec(returnTray, drawTray, coolingMegawattsToDutyWatts(megawatts), row.split());
        }

        private static int integer(String value, int number, String field) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException notAnInteger) {
                throw invalid(number, field + " must be a whole tray number");
            }
        }

        private static IllegalArgumentException invalid(int number, String reason) {
            return new IllegalArgumentException("Cooler " + number + " " + reason);
        }

        record Row(String drawTray, String returnTray, String coolingMegawatts, V3PumparoundSpec.Split split) {}
    }

    /** Pure parser for the ten scalar GUI fields, with field-specific diagnostics and SI conversion. */
    static final class ColumnScalarDraft {
        private static final double KMOL_PER_HOUR_TO_MOL_PER_SECOND = 1_000.0 / 3_600.0;
        private static final double CELSIUS_TO_KELVIN = 273.15;
        static final int FIELD_COUNT = 10;

        private ColumnScalarDraft() {}

        static Values parse(List<String> fields) {
            if (fields.size() != FIELD_COUNT) throw new IllegalArgumentException("V3 scalar draft requires ten fields");
            double feed = number(fields, 0, "Feed flow") * KMOL_PER_HOUR_TO_MOL_PER_SECOND;
            double feedTemperature = number(fields, 1, "Feed temperature") + CELSIUS_TO_KELVIN;
            int stages = integer(fields, 2, "Stage count");
            int feedStage = integer(fields, 3, "Feed stage");
            double condenserTemperature = number(fields, 4, "Condenser temperature") + CELSIUS_TO_KELVIN;
            double duty = number(fields, 5, "Reboiler duty") * 1_000_000.0;
            double reflux = number(fields, 6, "Reflux ratio");
            double pressure = number(fields, 7, "Top pressure") * 100_000.0;
            double pressureDrop = number(fields, 8, "Pressure drop") * 1_000.0;
            double diameter = number(fields, 9, "Column diameter");
            if (!(feed > 0.0)) throw invalid("Feed flow", "must be positive");
            if (!(feedTemperature > 0.0)) throw invalid("Feed temperature", "must be above absolute zero");
            if (stages < V3ColumnInput.MIN_STAGE_COUNT || stages > V3ColumnInput.MAX_STAGE_COUNT) {
                throw invalid("Stage count", "is outside " + V3ColumnInput.MIN_STAGE_COUNT + ".." + V3ColumnInput.MAX_STAGE_COUNT);
            }
            if (feedStage < 1 || feedStage > stages + 1) throw invalid("Feed stage", "must be within the column");
            if (!(condenserTemperature > 0.0)) throw invalid("Condenser temperature", "must be above absolute zero");
            if (duty < 0.0) throw invalid("Reboiler duty", "must be nonnegative");
            if (reflux < 0.0) throw invalid("Reflux ratio", "must be nonnegative");
            if (!(pressure > 0.0)) throw invalid("Top pressure", "must be positive");
            if (pressureDrop < 0.0) throw invalid("Pressure drop", "must be nonnegative");
            // A column has a diameter, and the tray hydraulics give its pressure profile (owner choice (b), 2026-10-28,
            // as the constructor's ColumnDesign); the input's zero diameter, the prescribed drop, stays for the solver's tests.
            if (!(diameter > 0.0) || diameter > V3ColumnInput.MAX_COLUMN_DIAMETER_METRES) {
                throw invalid("Column diameter", "must be above 0 and at most " + V3ColumnInput.MAX_COLUMN_DIAMETER_METRES + " m");
            }
            return new Values(feed, feedTemperature, stages, feedStage, condenserTemperature,
                    duty, reflux, pressure, pressureDrop, diameter);
        }

        private static double number(List<String> fields, int index, String name) {
            try {
                double value = Double.parseDouble(fields.get(index));
                if (!Double.isFinite(value)) throw invalid(name, "must be finite");
                return value;
            } catch (NumberFormatException invalid) {
                throw invalid(name, "must be numeric");
            }
        }

        private static int integer(List<String> fields, int index, String name) {
            try {
                return Integer.parseInt(fields.get(index));
            } catch (NumberFormatException invalid) {
                throw invalid(name, "must be an integer");
            }
        }

        private static IllegalArgumentException invalid(String name, String reason) {
            return new IllegalArgumentException(name + " " + reason);
        }

        record Values(
                double feedMolPerSecond, double feedTemperatureKelvin, int stageCount, int feedStage,
                double condenserTemperatureKelvin, double reboilerDutyWatts, double refluxRatio,
                double topPressurePascal, double pressureDropPascal, double columnDiameterMetres) {}
    }

    /**
     * Widget-free assembly of one candidate {@link V3ColumnInput} from the editor drafts.
     *
     * <p>The screen owns only the text; every unit conversion, diagnostic message and contract check lives here so
     * the exact input the Run button would send can be pinned by a unit test. The feed composition is never edited
     * in the GUI: the authored total rescales the server-owned component vector.</p>
     */
    static final class ColumnInputDraft {
        private ColumnInputDraft() {}

        static V3ColumnInput assemble(
                V3ColumnInput base,
                List<String> scalarFields,
                List<SideDrawDraft.Row> sideDraws,
                SteamFeedDraft.Row sumpSteam,
                SteamFeedDraft.Row traySteam,
                List<PumparoundDraft.Row> coolers) {
            ColumnScalarDraft.Values scalar = ColumnScalarDraft.parse(scalarFields);
            double[] scaledFlows = scaledFeed(base, scalar.feedMolPerSecond());
            List<V3SideDrawSpec> draws;
            try {
                draws = SideDrawDraft.parse(sideDraws, scalar.stageCount(), scalar.feedMolPerSecond());
            } catch (IllegalArgumentException invalidDraws) {
                throw new IllegalArgumentException("Side draws: " + invalidDraws.getMessage(), invalidDraws);
            }
            List<V3SteamFeedSpec> steam;
            try {
                steam = SteamFeedDraft.parse(sumpSteam, traySteam, scalar.stageCount(), scalar.feedMolPerSecond());
            } catch (IllegalArgumentException invalidSteam) {
                throw new IllegalArgumentException("Steam: " + invalidSteam.getMessage(), invalidSteam);
            }
            List<V3PumparoundSpec> pumparounds;
            try {
                pumparounds = PumparoundDraft.parse(coolers, scalar.stageCount());
            } catch (IllegalArgumentException invalidCoolers) {
                throw new IllegalArgumentException("Heat: " + invalidCoolers.getMessage(), invalidCoolers);
            }
            return new V3ColumnInput(
                    V3ColumnInput.SCHEMA_VERSION, base.packageId(), base.assayId(), base.componentBasis(), scaledFlows,
                    scalar.feedTemperatureKelvin(), scalar.stageCount(), scalar.feedStage(),
                    scalar.topPressurePascal(), scalar.pressureDropPascal(), List.of(
                            new V3ColumnSpecification.CondenserOutletTemperature(scalar.condenserTemperatureKelvin()),
                            new V3ColumnSpecification.OrganicRefluxRatio(scalar.refluxRatio()),
                            new V3ColumnSpecification.ReboilerDuty(scalar.reboilerDutyWatts())),
                    draws, steam, pumparounds, scalar.columnDiameterMetres());
        }

        private static double[] scaledFeed(V3ColumnInput base, double feedMolPerSecond) {
            double[] flows = base.feedComponentMolarFlowsMolPerSecond();
            double total = 0.0;
            for (double flow : flows) total += flow;
            if (!Double.isFinite(total) || total <= 0.0) {
                throw new IllegalArgumentException("Server feed composition cannot be rescaled");
            }
            double scale = feedMolPerSecond / total;
            for (int index = 0; index < flows.length; index++) flows[index] *= scale;
            return flows;
        }
    }
}
