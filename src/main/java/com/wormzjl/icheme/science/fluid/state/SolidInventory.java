package com.wormzjl.icheme.science.fluid.state;

import com.wormzjl.icheme.science.material.SolidMaterialCatalog.SolidMaterial;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;

/** Immutable exact conserved populations. Trace masking never deletes an entry or frees a slot. */
public record SolidInventory(List<Population> populations) {
    public static final int MAXIMUM_POPULATIONS = 64;
    public static final SolidInventory EMPTY = new SolidInventory(List.of());
    public record Key(String material, ParticleSize size) implements Comparable<Key> {
        public Key { Objects.requireNonNull(material); Objects.requireNonNull(size); }
        @Override public int compareTo(Key other) {
            int result = material.compareTo(other.material);
            return result == 0 ? size.compareTo(other.size) : result;
        }
    }
    public record Population(SolidMaterial material, ParticleSize size, double massKg) {
        public Population {
            Objects.requireNonNull(material); Objects.requireNonNull(size);
            if (!Double.isFinite(massKg) || massKg < 0) throw new IllegalArgumentException("Invalid particle mass");
        }
        public Key key() { return new Key(material.id(), size); }
        public double volume() { return massKg / material.density(); }
    }
    public record Moments(double mass, double volume, double heatCapacity) {
        public static final Moments ZERO = new Moments(0, 0, 0);
        public Moments {
            if (!Double.isFinite(mass) || !Double.isFinite(volume) || !Double.isFinite(heatCapacity)
                    || mass < 0 || volume < 0 || heatCapacity < 0)
                throw new IllegalArgumentException("Invalid solid moments");
        }
        public double internalEnergy(double temperature) { return heatCapacity * (temperature - SolidMaterial.REFERENCE_TEMPERATURE); }
        public double enthalpy(double temperature, double pressure) { return internalEnergy(temperature) + pressure * volume; }
        public double[] values() { return new double[]{mass, volume, heatCapacity}; }
    }
    public SolidInventory {
        Objects.requireNonNull(populations);
        var combined = new TreeMap<Key, Population>();
        for (var population : populations) {
            Objects.requireNonNull(population);
            // A mass below the smallest normal double is roundoff zero: it has no volume or heat capacity a double can state
            // (t56's trace of cold seed 163: a junction's crystal buffer, decaying behind a carried closure, reached
            // 1.5e-321 kg, its volume underflowed to 0, and the guard below refused every trial step).
            if (population.massKg() < Double.MIN_NORMAL) continue;
            combined.merge(population.key(), population, (a, b) -> {
                if (!a.material().equals(b.material())) throw new IllegalArgumentException("Incompatible solid properties: " + a.material().id());
                return new Population(a.material(), a.size(), a.massKg() + b.massKg());
            });
            if (combined.size() > MAXIMUM_POPULATIONS) throw new IllegalArgumentException("Solid population limit exceeded (64)");
        }
        populations = List.copyOf(combined.values());
        double mass = 0, volume = 0, capacity = 0;
        for (var p : populations) { mass += p.massKg(); volume += p.volume(); capacity += p.massKg() * p.material().heatCapacity(); }
        if (!Double.isFinite(mass) || !Double.isFinite(volume) || !Double.isFinite(capacity)
                || mass > 0 && (volume == 0 || capacity == 0))
            throw new IllegalArgumentException("Solid inventory overflow/underflow");
    }
    public boolean empty() { return populations.isEmpty(); }
    public Moments moments() {
        double mass = 0, volume = 0, capacity = 0;
        for (var p : populations) { mass += p.massKg(); volume += p.volume(); capacity += p.massKg() * p.material().heatCapacity(); }
        return new Moments(mass, volume, capacity);
    }
    public double massKg() { return moments().mass(); }
    public double volume() { return moments().volume(); }
    public double mass(Key key) { for (var p : populations) if (p.key().equals(key)) return p.massKg(); return 0; }
    /**
     * The stock's size of one solid (owner decision D23, amended 2026-10-15: one size per solid in each stock): its
     * population's own size when it holds one, else the mass-weighted mean diameter {@code sum(m d) / sum(m)} of its
     * populations in key order, rounded to {@link #SIZE_DIGITS} significant digits from the exact double
     * ({@link BigDecimal}, so every JDK gives the same key); {@code null} when the stock holds none of it. The outlet
     * check reads this size ({@code SolidMobility.donor}), so a merge ({@link #oneSizePerSolid}) never changes an outlet
     * decision.
     */
    public ParticleSize size(String material) {
        Population only = null; double mass = 0, moment = 0; int count = 0;
        for (var p : populations) if (p.material().id().equals(material)) {only = p; count++; mass += p.massKg(); moment += p.massKg() * p.size().diameterMetres();}
        if (count == 0) return null;
        if (count == 1) return only.size();
        return new ParticleSize(new BigDecimal(moment / mass).round(new MathContext(SIZE_DIGITS, RoundingMode.HALF_EVEN)).toString());
    }
    /** The significant digits of a merged stock size ({@link #size}). */
    public static final int SIZE_DIGITS = 9;
    /**
     * The stock with one population per solid (owner decision D23, amended): each solid holding several populations is
     * merged into one of their total mass at the stock's size ({@link #size}); a solid holding one population is left as
     * it is, so a stock already holding one size per solid is returned itself, bit for bit. Mass is kept exactly per
     * solid; volume and heat capacity, its mass times the solid's constants, to rounding.
     */
    public SolidInventory oneSizePerSolid() {
        String previous = null; boolean merge = false;
        for (var p : populations) {if (p.material().id().equals(previous)) {merge = true; break;} previous = p.material().id();}
        if (!merge) return this;
        var merged = new ArrayList<Population>(); var masses = new LinkedHashMap<String, Double>(); var materials = new HashMap<String, SolidMaterial>();
        for (var p : populations) {masses.merge(p.material().id(), p.massKg(), Double::sum); materials.putIfAbsent(p.material().id(), p.material());}
        for (var entry : masses.entrySet()) merged.add(new Population(materials.get(entry.getKey()), size(entry.getKey()), entry.getValue()));
        return new SolidInventory(merged);
    }
    /** The populations of crystals ({@link SolidMaterial#crystal()} not null), in key order. */
    public List<Population> crystals() {
        var crystals = new ArrayList<Population>();
        for (var p : populations) if (p.material().crystal() != null) crystals.add(p);
        return crystals;
    }
    /** This stock without its crystal populations. */
    public SolidInventory withoutCrystals() {
        if (crystals().isEmpty()) return this;
        var inert = new ArrayList<Population>();
        for (var p : populations) if (p.material().crystal() == null) inert.add(p);
        return new SolidInventory(inert);
    }
    public SolidInventory plus(SolidInventory other) {
        var merged = new ArrayList<>(populations); merged.addAll(other.populations);
        return new SolidInventory(merged);
    }
    /** This stock less {@code taken} (which came from it), population by population, never below empty. */
    public SolidInventory less(SolidInventory taken) {
        if (taken.empty()) return this;
        var left = new ArrayList<Population>();
        for (var p : populations) left.add(new Population(p.material(), p.size(), Math.max(0, p.massKg() - taken.mass(p.key()))));
        return new SolidInventory(left);
    }
    public SolidInventory scale(double fraction) {
        if (!Double.isFinite(fraction) || fraction < 0) throw new IllegalArgumentException("Invalid solid scaling");
        if (fraction == 0 || empty()) return EMPTY;
        return new SolidInventory(populations.stream().map(p -> new Population(p.material(), p.size(), p.massKg() * fraction)).toList());
    }
    /** Worker-local signed accumulation; validation and population bounds apply to the completed stock. */
    public static final class Accumulator {
        private final Map<Key,Population> basis=new TreeMap<>();
        private final Map<Key,Double> masses=new TreeMap<>();
        public void add(SolidInventory stock,double factor) {
            if(!Double.isFinite(factor))throw new IllegalArgumentException("Invalid solid weight");
            for(var p:stock.populations){var old=basis.putIfAbsent(p.key(),p);if(old!=null&&!old.material().equals(p.material()))throw new IllegalArgumentException("Incompatible solid properties");masses.merge(p.key(),factor*p.massKg(),Double::sum);}
        }
        public SolidInventory finish() {var result=new ArrayList<Population>();for(var entry:masses.entrySet()){var p=basis.get(entry.getKey());result.add(new Population(p.material(),p.size(),entry.getValue()));}return new SolidInventory(result);}
    }
    public record Portion(SolidInventory delivered, SolidInventory remaining) {}
    public Portion takeFraction(double fraction) {
        if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1) throw new IllegalArgumentException("Invalid solid withdrawal");
        var delivered = new ArrayList<Population>(); var remaining = new ArrayList<Population>();
        for (var p : populations) {
            double mass = p.massKg() * fraction;
            delivered.add(new Population(p.material(), p.size(), mass));
            remaining.add(new Population(p.material(), p.size(), p.massKg() - mass));
        }
        return new Portion(new SolidInventory(delivered), new SolidInventory(remaining));
    }
}