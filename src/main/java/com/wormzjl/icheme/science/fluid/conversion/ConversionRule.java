package com.wormzjl.icheme.science.fluid.conversion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.material.JsonFields;
import java.util.*;

/**
 * One fluid conversion rule (batch 2026-11-12-fluid-conversion, FLUID_CONVERSION_PLAN.md 3.3): how a standard (Forge) fluid
 * maps to an IChemE stream and back. Minecraft-free: the Forge fluid is its id ({@code mod:id}) or a tag ({@code #c:tag}).
 *
 * <ul>
 * <li>{@code toIChemE} (forward, Forge to IChemE, optional): the stream a litre of the fluid becomes, its composition by
 * compound id (mole fractions summing to 1; a preset resolved at load), its temperature and pressure (K, Pa), and for a gas
 * rule its moles per litre.</li>
 * <li>{@code toForge} (reverse, IChemE to Forge, optional): the drawn phase a converter turns into the fluid, by component
 * ranges on a mole or mass basis, the most of the unnamed compounds, and its temperature and pressure ranges.</li>
 * </ul>
 * A rule is identified by its file ({@code namespace:path}) and declares its {@code revision} (AGENTS.md, declared
 * revisions); {@code priority} orders the rules, highest first, then by id.
 */
public record ConversionRule(String id,String revision,int priority,String fluid,Phase phase,ToIChemE toIChemE,ToForge toForge) {
    /** The phase the rule maps: a liquid, or a gas (only on the closed, blanketed converter, owner D4). */
    public enum Phase {LIQUID,GAS}
    /** The basis of a reverse rule's component ranges. */
    public enum Basis {MOLE,MASS}
    /** Why a rule is refused at load; each refuses that rule alone. */
    public enum Reason {SYNTAX,REVISION,UNKNOWN_COMPOUND,UNKNOWN_PRESET,FRACTIONS_SUM,EMPTY_RANGE,GAS_WITHOUT_MOLES_PER_LITRE,NO_DIRECTION,TAG_TO_FORGE}
    /** A rule refused at load, with its reason and a message that names the field. */
    public static final class Refusal extends IllegalArgumentException {
        public final Reason reason;
        public Refusal(Reason reason,String message){super(message);this.reason=Objects.requireNonNull(reason);}
    }
    /** The ambient the game sets: 298.15 K and 1 atm ({@code "ambient"} in a rule). */
    public static final double AMBIENT_TEMPERATURE=298.15,AMBIENT_PRESSURE=PhaseLayout.AMBIENT_PRESSURE;
    /** A fractions sum within this of 1 is 1. */
    static final double SUM_TOLERANCE=1e-6;
    /** The unnamed compounds' most, by default. */
    public static final double DEFAULT_OTHERS_MAX=.01;

    /** A closed range, {@code min <= max}. */
    public record Range(double min,double max) {
        public static final Range ANY=new Range(Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY);
        public Range {if(Double.isNaN(min)||Double.isNaN(max))throw new IllegalArgumentException("A range has numbers");}
        public boolean holds(double value){return value>=min&&value<=max;}
        /** The range with each bound moved out by {@code share} of its own magnitude (the hysteresis of 3.3). */
        public Range widened(double share){return new Range(min-share*Math.abs(min),max+share*Math.abs(max));}
    }
    /** What a litre of the fluid becomes: a composition by compound id (mole fractions), at {@code temperature} and {@code pressure}; {@code molesPerLitre} for a gas rule, NaN for a liquid. */
    public record ToIChemE(Map<String,Double> composition,double temperature,double pressure,double molesPerLitre) {
        public ToIChemE {composition=Collections.unmodifiableMap(new LinkedHashMap<>(composition));}
    }
    /** What a drawn phase must be to become the fluid. */
    public record ToForge(Basis basis,Map<String,Range> components,double othersMax,Range temperature,Range pressure) {
        public ToForge {Objects.requireNonNull(basis);components=Collections.unmodifiableMap(new LinkedHashMap<>(components));Objects.requireNonNull(temperature);Objects.requireNonNull(pressure);}
        /** These ranges widened by {@code share} of each bound. */
        public ToForge widened(double share) {
            var wide=new LinkedHashMap<String,Range>();for(var e:components.entrySet())wide.put(e.getKey(),e.getValue().widened(share));
            return new ToForge(basis,wide,othersMax+share*othersMax,temperature.widened(share),pressure.widened(share));
        }
    }
    /** The names a rule may use: the network package's compounds (water included), and its presets as compositions. */
    public interface Names {
        /** The canonical id of compound {@code id}, or null where the package has none. */
        String compound(String id);
        /** Preset {@code id} as mole fractions by compound id, or null where there is none. */
        Map<String,Double> preset(String id);
    }

    public ConversionRule {
        Objects.requireNonNull(id);Objects.requireNonNull(revision);Objects.requireNonNull(fluid);Objects.requireNonNull(phase);
        if(toIChemE==null&&toForge==null)throw new Refusal(Reason.NO_DIRECTION,id+": a rule needs to_icheme, to_forge or both");
    }
    /** Whether the rule's fluid is a tag ({@code #ns:path}). */
    public boolean tag(){return fluid.startsWith("#");}
    /** Whether Forge fluid {@code id}, carrying tags {@code tags} (without '#'), is this rule's. */
    public boolean matchesFluid(String id,Set<String> tags){return tag()?tags.contains(fluid.substring(1)):fluid.equals(id);}
    /** The namespace of the rule's fluid or tag. */
    public String fluidNamespace(){var f=tag()?fluid.substring(1):fluid;int colon=f.indexOf(':');return colon<0?"minecraft":f.substring(0,colon);}

    /** Reads rule {@code id} from {@code json}; a refusal names the field. */
    public static ConversionRule parse(String id,JsonObject json,Names names) {
        try {
            String revision;
            try{revision=JsonFields.revision(json,"revision");}catch(IllegalArgumentException e){throw new Refusal(Reason.REVISION,e.getMessage());}
            int priority=json.has("priority")?JsonFields.integer(json,"priority"):0;
            var fluid=JsonFields.text(json,"fluid");
            if(!fluid.matches("#?[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new Refusal(Reason.SYNTAX,"fluid: \""+fluid+"\" is not a fluid id or #tag");
            var phaseText=JsonFields.text(json,"phase");
            Phase phase=switch(phaseText){case "liquid"->Phase.LIQUID;case "gas"->Phase.GAS;default->throw new Refusal(Reason.SYNTAX,"phase: \""+phaseText+"\" is neither liquid nor gas");};
            ToIChemE forward=json.has("to_icheme")?forward(JsonFields.object(json,"to_icheme"),phase,names):null;
            ToForge reverse=json.has("to_forge")?reverse(JsonFields.object(json,"to_forge"),names):null;
            if(reverse!=null&&fluid.startsWith("#"))throw new Refusal(Reason.TAG_TO_FORGE,"fluid: a rule with to_forge names one fluid, not a tag");
            return new ConversionRule(id,revision,priority,fluid,phase,forward,reverse);
        } catch(Refusal refusal) {
            throw new Refusal(refusal.reason,id+": "+refusal.getMessage());
        } catch(IllegalArgumentException | IllegalStateException | ClassCastException e) {
            throw new Refusal(Reason.SYNTAX,id+": "+e.getMessage());
        }
    }
    private static ToIChemE forward(JsonObject o,Phase phase,Names names) {
        Map<String,Double> composition;
        boolean preset=o.has("preset"),listed=o.has("composition");
        if(preset==listed)throw new Refusal(Reason.SYNTAX,"to_icheme: give preset or composition, one of them");
        if(preset) {
            var name=JsonFields.text(o,"preset");var found=names.preset(name);
            if(found==null)throw new Refusal(Reason.UNKNOWN_PRESET,"to_icheme.preset: \""+name+"\" is no preset of the network package");
            composition=found;
        } else {
            composition=new LinkedHashMap<>();double sum=0;
            for(var e:JsonFields.object(o,"composition").entrySet()) {
                var canonical=names.compound(e.getKey());
                if(canonical==null)throw new Refusal(Reason.UNKNOWN_COMPOUND,"to_icheme.composition: \""+e.getKey()+"\" is no compound of the network package");
                double x=number(e.getValue(),"to_icheme.composition."+e.getKey());
                if(!(x>=0&&x<=1))throw new Refusal(Reason.FRACTIONS_SUM,"to_icheme.composition."+e.getKey()+": "+x+" lies outside 0 to 1");
                if(composition.merge(canonical,x,Double::sum)>1)throw new Refusal(Reason.FRACTIONS_SUM,"to_icheme.composition: "+canonical+" named twice");
                sum+=x;
            }
            if(Math.abs(sum-1)>SUM_TOLERANCE)throw new Refusal(Reason.FRACTIONS_SUM,"to_icheme.composition: the fractions sum to "+sum+", not 1");
        }
        double t=ambientOr(o,"temperature",AMBIENT_TEMPERATURE),p=ambientOr(o,"pressure",AMBIENT_PRESSURE);
        if(!(t>0))throw new Refusal(Reason.EMPTY_RANGE,"to_icheme.temperature: "+t+" K");
        if(!(p>0))throw new Refusal(Reason.EMPTY_RANGE,"to_icheme.pressure: "+p+" Pa");
        double perLitre=Double.NaN;
        if(phase==Phase.GAS) {
            if(!o.has("moles_per_litre"))throw new Refusal(Reason.GAS_WITHOUT_MOLES_PER_LITRE,"to_icheme.moles_per_litre: a gas rule needs it");
            perLitre=JsonFields.positive(o,"moles_per_litre");
        }
        return new ToIChemE(composition,t,p,perLitre);
    }
    private static ToForge reverse(JsonObject o,Names names) {
        Basis basis=Basis.MOLE;
        if(o.has("basis")){var b=JsonFields.text(o,"basis");basis=switch(b){case "mole"->Basis.MOLE;case "mass"->Basis.MASS;default->throw new Refusal(Reason.SYNTAX,"to_forge.basis: \""+b+"\" is neither mole nor mass");};}
        var components=new LinkedHashMap<String,Range>();
        for(var e:JsonFields.object(o,"components").entrySet()) {
            var canonical=names.compound(e.getKey());
            if(canonical==null)throw new Refusal(Reason.UNKNOWN_COMPOUND,"to_forge.components: \""+e.getKey()+"\" is no compound of the network package");
            if(!e.getValue().isJsonObject())throw new Refusal(Reason.SYNTAX,"to_forge.components."+e.getKey()+": expected {min, max}");
            var r=range(e.getValue().getAsJsonObject(),"to_forge.components."+e.getKey(),0,1);
            if(r.min()<0||r.max()>1)throw new Refusal(Reason.EMPTY_RANGE,"to_forge.components."+e.getKey()+": a fraction lies within 0 to 1");
            if(components.put(canonical,r)!=null)throw new Refusal(Reason.SYNTAX,"to_forge.components: "+canonical+" named twice");
        }
        if(components.isEmpty())throw new Refusal(Reason.EMPTY_RANGE,"to_forge.components: name at least one compound");
        double others=o.has("others_max")?JsonFields.number(o,"others_max"):DEFAULT_OTHERS_MAX;
        if(!(others>=0&&others<=1))throw new Refusal(Reason.EMPTY_RANGE,"to_forge.others_max: "+others+" lies outside 0 to 1");
        // The named minima and the others' most must leave a composition that sums to 1.
        double least=0,most=others;for(var r:components.values()){least+=r.min();most+=r.max();}
        if(least>1+SUM_TOLERANCE||most<1-SUM_TOLERANCE)throw new Refusal(Reason.FRACTIONS_SUM,"to_forge.components: no composition summing to 1 fits the ranges (minima "+least+", maxima with others "+most+")");
        var t=o.has("temperature")?range(JsonFields.object(o,"temperature"),"to_forge.temperature",Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY):Range.ANY;
        var p=o.has("pressure")?range(JsonFields.object(o,"pressure"),"to_forge.pressure",Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY):Range.ANY;
        return new ToForge(basis,components,others,t,p);
    }
    /** A {@code {min, max}} object; an absent bound is {@code low} or {@code high}. An empty range is refused. */
    private static Range range(JsonObject o,String label,double low,double high) {
        double min=o.has("min")?number(o.get("min"),label+".min"):low,max=o.has("max")?number(o.get("max"),label+".max"):high;
        if(!(min<=max))throw new Refusal(Reason.EMPTY_RANGE,label+": the range "+min+" to "+max+" is empty");
        return new Range(min,max);
    }
    private static double ambientOr(JsonObject o,String key,double ambient) {
        if(!o.has(key))return ambient;var e=o.get(key);
        if(e.isJsonPrimitive()&&e.getAsJsonPrimitive().isString()){if(e.getAsString().equals("ambient"))return ambient;throw new Refusal(Reason.SYNTAX,"to_icheme."+key+": a number or \"ambient\"");}
        return number(e,"to_icheme."+key);
    }
    private static double number(JsonElement e,String label) {
        if(!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber())throw new Refusal(Reason.SYNTAX,label+": expected number");
        double d=e.getAsDouble();if(!Double.isFinite(d))throw new Refusal(Reason.SYNTAX,label+": nonfinite");return d;
    }
}
