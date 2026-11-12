package com.wormzjl.icheme.science.fluid.conversion;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.material.MaterialAxis;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialPresets;
import java.util.*;
import java.util.function.Predicate;

/**
 * The loaded fluid conversion rules (batch 2026-11-12-fluid-conversion, plan 3.3), sorted by {@code priority} descending,
 * then by id. Arithmetic only: a match reads a drawn phase's amounts, never a property model.
 */
public final class ConversionRules {
    /** The share of each bound a rule's ranges widen by while it matched the last slice (plan 3.3). */
    public static final double HYSTERESIS=.02;
    public static final ConversionRules NONE=new ConversionRules(List.of(),List.of());
    /** A rule file that did not load, and why. */
    public record Refused(String id,ConversionRule.Reason reason,String message) {}
    /**
     * A drawn phase as a reverse rule reads it: its amounts (mol) on {@code components} (compound ids, water included),
     * those compounds' molar masses (kg/mol), its temperature (K) and pressure (Pa), and whether it is a gas.
     */
    public record Drawn(List<String> components,double[] moles,double[] molarMasses,double temperature,double pressure,boolean gas) {
        public Drawn {components=List.copyOf(components);moles=moles.clone();molarMasses=molarMasses.clone();
            if(moles.length!=components.size()||molarMasses.length!=components.size())throw new IllegalArgumentException("A drawn phase's amounts and compounds disagree");}
        public double total(){double s=0;for(double n:moles)s+=n;return s;}
        public double mass(){double m=0;for(int i=0;i<moles.length;i++)m+=moles[i]*molarMasses[i];return m;}
        /** Each compound's fraction on {@code basis}, by id. */
        public Map<String,Double> fractions(ConversionRule.Basis basis) {
            double sum=basis==ConversionRule.Basis.MOLE?total():mass();var x=new HashMap<String,Double>();
            if(!(sum>0))return x;
            for(int i=0;i<moles.length;i++)if(moles[i]>0)x.merge(components.get(i),(basis==ConversionRule.Basis.MOLE?moles[i]:moles[i]*molarMasses[i])/sum,Double::sum);
            return x;
        }
    }

    private final List<ConversionRule> rules;
    private final List<Refused> refused;
    private final Map<String,ConversionRule> byId;
    public ConversionRules(List<ConversionRule> rules,List<Refused> refused) {
        var sorted=new ArrayList<>(rules);sorted.sort(Comparator.comparingInt(ConversionRule::priority).reversed().thenComparing(ConversionRule::id));
        this.rules=List.copyOf(sorted);this.refused=List.copyOf(refused);
        var ids=new HashMap<String,ConversionRule>();for(var r:sorted)if(ids.put(r.id(),r)!=null)throw new IllegalArgumentException("Two rules named "+r.id());
        byId=Map.copyOf(ids);
    }
    /** The rules in match order. */
    public List<ConversionRule> rules(){return rules;}
    /** The files refused at load. */
    public List<Refused> refused(){return refused;}
    public ConversionRule rule(String id){return byId.get(id);}

    /**
     * The forward rule (Forge to IChemE) for Forge fluid {@code fluid} carrying {@code tags} (without '#'), or null: the
     * first with a {@code to_icheme}; a gas rule only where {@code closed} (owner D4: the open converter refuses one).
     */
    public ConversionRule forward(String fluid,Set<String> tags,boolean closed) {
        for(var r:rules)if(r.toIChemE()!=null&&(closed||r.phase()==ConversionRule.Phase.LIQUID)&&r.matchesFluid(fluid,tags))return r;
        return null;
    }
    /**
     * The reverse rule (IChemE to Forge) for {@code drawn}, or null: rule {@code last} (the one that matched the last slice,
     * or null) while the phase holds inside its ranges widened by {@link #HYSTERESIS}, else the first in order whose ranges
     * hold. A rule's phase is the drawn phase's; a gas rule only where {@code closed}.
     */
    public ConversionRule reverse(Drawn drawn,String last,boolean closed) {
        if(!(drawn.total()>0))return null;
        var previous=last==null?null:byId.get(last);
        if(previous!=null&&usable(previous,drawn,closed)&&holds(previous.toForge().widened(HYSTERESIS),drawn))return previous;
        for(var r:rules)if(usable(r,drawn,closed)&&holds(r.toForge(),drawn))return r;
        return null;
    }
    private static boolean usable(ConversionRule r,Drawn drawn,boolean closed) {
        return r.toForge()!=null&&(r.phase()==ConversionRule.Phase.GAS)==drawn.gas()&&(closed||r.phase()==ConversionRule.Phase.LIQUID);
    }
    /** Whether {@code drawn} lies inside ranges {@code f}. */
    public static boolean holds(ConversionRule.ToForge f,Drawn drawn) {
        if(!f.temperature().holds(drawn.temperature())||!f.pressure().holds(drawn.pressure()))return false;
        var x=drawn.fractions(f.basis());double others=1;
        for(var e:f.components().entrySet()){double v=x.getOrDefault(e.getKey(),0.);if(!e.getValue().holds(v))return false;others-=v;}
        return others<=f.othersMax()+1e-12;
    }

    /**
     * Parses rule files {@code files} (id {@code namespace:path} to its JSON text), each refused alone with its reason;
     * a rule whose fluid's namespace {@code present} rejects (its mod is absent) is skipped silently (plan 3.3).
     */
    public static ConversionRules parse(Map<String,String> files,ConversionRule.Names names,Predicate<String> present) {
        var rules=new ArrayList<ConversionRule>();var refused=new ArrayList<Refused>();
        for(var e:new TreeMap<>(files).entrySet()) {
            try {
                var element=JsonParser.parseString(e.getValue());
                if(!element.isJsonObject())throw new ConversionRule.Refusal(ConversionRule.Reason.SYNTAX,e.getKey()+": expected an object");
                var rule=ConversionRule.parse(e.getKey(),element.getAsJsonObject(),names);
                if(!rule.tag()&&!present.test(rule.fluidNamespace()))continue;
                rules.add(rule);
            } catch(ConversionRule.Refusal r) {
                refused.add(new Refused(e.getKey(),r.reason,r.getMessage()));
            } catch(RuntimeException r) {
                refused.add(new Refused(e.getKey(),ConversionRule.Reason.SYNTAX,e.getKey()+": "+r.getMessage()));
            }
        }
        return new ConversionRules(rules,refused);
    }
    /** One rule from {@code json}. */
    public static ConversionRule parse(String id,JsonObject json,ConversionRule.Names names){return ConversionRule.parse(id,json,names);}

    /**
     * The names of {@code catalog}'s network package {@code packageId}: its compounds and water (aliases read as their
     * canonical id), and its fluid presets as mole fractions (an assay on the package's own data). A preset is named by its
     * id, with or without the {@code icheme:} namespace.
     */
    public static ConversionRule.Names names(MaterialCatalog catalog,String packageId) {
        var p=catalog.requirePackage(packageId);var ids=new ArrayList<>(p.components());ids.add("Water");var known=Set.copyOf(ids);
        return new ConversionRule.Names() {
            public String compound(String id){var c=p.canonicalId(id);return known.contains(c)?c:null;}
            public Map<String,Double> preset(String id) {
                MaterialPresets.Fluid preset=null;
                for(var name:List.of(id,"icheme:"+id))try{preset=catalog.presets().fluid(name);break;}catch(IllegalArgumentException absent){}
                if(preset==null)return null;
                var x=new LinkedHashMap<String,Double>();
                if(!preset.component().isEmpty()){var c=compound(preset.component());if(c==null)return null;x.put(c,1.);return x;}
                var axis=new MaterialAxis(p.components());var fractions=MaterialPresets.moleFractions(p,preset.assayId());
                for(int i=0;i<fractions.length;i++)if(fractions[i]>0)x.put(axis.ids().get(i),fractions[i]);
                return x;
            }
        };
    }
}
