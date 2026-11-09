package com.wormzjl.icheme.science.material;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.wormzjl.icheme.science.material.JsonFields.*;

/** Immutable validated SI database. Resource keys are distinct from plain component IDs. */
public final class MaterialCatalog {
    public record Pr78(double criticalTemperature, double criticalPressure, double acentricFactor) {}
    public record NrtlPair(String first, String second, double a12, double b12, double a21,
            double b21, double alpha, double minimumTemperature, double maximumTemperature) {}
    /**
     * {@code idealGasMinimumTemperature} is the lower end of the {@code ideal_gas_cp} fit where it lies above the
     * component's own range (nitrogen's 273.16 K: below it the fluid network holds the fit's heat capacity, owner decision
     * D37, simplification S4); {@code null} for every other record.
     *
     * <p>{@code cp} is empty for a record without {@code ideal_gas_cp}, which only a package with a reference spine may
     * use (D6: its ideal gas comes from the spine; {@code referenceTemperature} is then 298.15). {@code volumeTranslation}
     * is the record's Tr = 0.8 saturated-liquid anchor ({@link VolumeTranslation}, P3), {@code null} when absent.</p>
     */
    public record Property(String id, String component, double molecularWeight, double normalBoilingPoint,
            double density, double standardTemperature, double standardPressure, double minimumTemperature,
            double maximumTemperature, double referenceTemperature, List<Double> cp, Pr78 pr, boolean estimated,
            Double idealGasMinimumTemperature, VolumeTranslation volumeTranslation) {
        public Property { cp = List.copyOf(cp); }
    }
    /**
     * The data range of a component (or, for a package, its envelope) as the fluid network reads it, with the evidence it
     * rests on: a component's is the data range of a coefficient record's ideal-gas fit on the network (a spine has its own),
     * a package's the envelope of its states and the input box of device settings. Leaving either never holds a state
     * (owner rule of 2026-10-16: the models continue and a notice reports it). Read from the {@code fluid_domain} object
     * of a property or package record. It is kept beside the records rather than in them: the records' own ranges are the
     * column's admission bounds. Like every other field, a change to it bumps the declared revision of the record that
     * holds it, the property's or the package's, which moves {@link Package#scientificRevision}.
     */
    public record Validity(double minimumTemperature, double maximumTemperature, double minimumPressure, double maximumPressure,
            String evidence) {
        public Validity {
            if (!(minimumTemperature > 0) || !(maximumTemperature > minimumTemperature) || !Double.isFinite(maximumTemperature)
                    || !(minimumPressure > 0) || !(maximumPressure > minimumPressure) || !Double.isFinite(maximumPressure))
                throw new IllegalArgumentException("fluid_domain: invalid temperature or pressure range");
            Objects.requireNonNull(evidence);
        }
        List<Double> numbers() { return List.of(minimumTemperature, maximumTemperature, minimumPressure, maximumPressure); }
    }
    /**
     * A dataset's freezing point, its optional {@code freezing} sub-record (batch 2026-11-04-refinery-compounds, plan
     * section 3.6): the normal melting point, optionally the triple point and the fusion enthalpy at the melting point,
     * whether the values are estimated, their source and the sub-record's own revision. {@code null} marks an optional
     * value the record does not give.
     *
     * <p>Data for later models only. It feeds no range, floor, seed window or hold: a freezing point is never a validity
     * bound (AGENTS.md, thermo validity). Like a viscosity it has its own revision, and it is in no identity
     * ({@link Package#scientificRevision}, {@link MaterialCatalog#fluidRevision}, {@link MaterialCatalog#physicsContent})
     * until a model reads it, so an edit moves no world, result or neural model. A compound with a crystal record agrees
     * with that record's anchor within {@link #CRYSTAL_ANCHOR_TOLERANCE} (checked at load).</p>
     */
    public record Freezing(double normalMeltingTemperature, Double triplePointTemperature, Double triplePointPressure,
            Double fusionEnthalpy, boolean estimated, String source, String revision) {
        /** The largest gap, K, between a freezing point and the anchor of a crystal record of the same compound. */
        public static final double CRYSTAL_ANCHOR_TOLERANCE = 0.005;
        public Freezing { Objects.requireNonNull(source); Objects.requireNonNull(revision); }
        /** The temperature a crystal anchor is compared with: the triple point when the record gives one, else the melting point. */
        public double anchorTemperature() { return triplePointTemperature != null ? triplePointTemperature : normalMeltingTemperature; }
    }
    /**
     * A dataset's thermochemistry, its optional {@code thermochemistry} sub-record (O9 of batch 2026-11-04-open-thermo-data,
     * owner decision E23, form S2): the standard formation enthalpy of the ideal gas at 298.15 K (J/mol; 0 for an element
     * in its reference state, O2 and N2) and the element formula (atoms of C, H, N, O and S per molecule; a cut's are
     * estimated and need not be whole), whether they are estimated, their source and the sub-record's own revision. The
     * water model may carry one too. Heats of reaction follow by Hess's law from these records alone: the net heat of
     * combustion, the water formed, the oxygen demand and the flue gas (fuel to CO2, H2O, SO2 and N2) are not stored, but
     * the net heat is prebuilt from it into the record's {@link Combustion} sub-record.
     *
     * <p>Data for later models only, as {@link Freezing}: no model reads it, so it has its own revision and is in no
     * identity ({@link Package#scientificRevision}, {@link MaterialCatalog#fluidRevision}, {@link MaterialCatalog#spineRevision},
     * {@link MaterialCatalog#physicsContent}), and an edit moves no world, result or neural model. It is apart from a
     * reference spine's {@code formation_enthalpy}, which is a spine package's ideal-gas source. The formula is not checked
     * against the record's molar mass at load, so an edit of the dataset is never refused for it; the import tools check
     * it, and a data gate checks the shipped records.</p>
     */
    public record Thermochemistry(double formationEnthalpy, Map<String, Double> elements, boolean estimated, String source, String revision) {
        /** The elements a formula may name. */
        public static final Set<String> ELEMENTS = Set.of("C", "H", "N", "O", "S");
        public Thermochemistry {
            Objects.requireNonNull(source); Objects.requireNonNull(revision);
            if (!Double.isFinite(formationEnthalpy)) throw new IllegalArgumentException("formation_enthalpy_j_per_mol: nonfinite");
            elements = Map.copyOf(elements);
            if (elements.isEmpty()) throw new IllegalArgumentException("elements: at least one element required");
            for (var e : elements.entrySet()) {
                if (!ELEMENTS.contains(e.getKey())) throw new IllegalArgumentException("elements: " + e.getKey() + " is not C, H, N, O or S");
                if (!(e.getValue() > 0) || !Double.isFinite(e.getValue())) throw new IllegalArgumentException("elements." + e.getKey() + ": must be positive and finite");
            }
        }
    }
    /**
     * A dataset's flammability limits in air, its optional {@code flammability} sub-record (O9, E23, form S2): the lower
     * and, where known, the upper limit (volume fractions, constant with temperature), whether they are estimated, their
     * source and the sub-record's own revision. A compound or cut that does not burn has none; a cut has no upper limit.
     * Kept apart from {@link Thermochemistry}: the limits come from other sources (NIOSH, Bureau of Mines Bulletin 627,
     * estimates) and are corrected on their own revision, which moves no thermochemistry. Data only, in no identity.
     */
    public record Flammability(double lowerLimit, Double upperLimit, boolean estimated, String source, String revision) {
        public Flammability {
            Objects.requireNonNull(source); Objects.requireNonNull(revision);
            if (!(lowerLimit > 0 && lowerLimit < 1)) throw new IllegalArgumentException("lfl_volume_fraction: must lie in (0, 1)");
            if (upperLimit != null && !(upperLimit > lowerLimit && upperLimit < 1)) throw new IllegalArgumentException("ufl_volume_fraction: must lie in (lfl_volume_fraction, 1)");
        }
    }
    /**
     * A dataset's or water model's lower heating value, its optional {@code combustion} sub-record (batch
     * 2026-11-09-furnace-fuel-power, owner decision F2): J/mol of the ideal gas at 298.15 K burnt to CO2, H2O (gas), SO2
     * and N2, prebuilt from the record's {@link Thermochemistry} by {@code tools/heating-values}; 0 for a record that gives
     * no heat. A fired heater's burners read it ({@link #heatingValues}). It enters no saved state (the burners' fired heat
     * is engine state, rebuilt at every commit), so like {@link Thermochemistry} it is in no identity: an edit changes the
     * next commit's firing, never a world's contents, a column result or a neural model. Its own revision.
     */
    public record Combustion(double lowerHeatingValue, boolean estimated, String source, String revision) {
        public Combustion {
            Objects.requireNonNull(source); Objects.requireNonNull(revision);
            if (!(lowerHeatingValue >= 0) || !Double.isFinite(lowerHeatingValue)) throw new IllegalArgumentException("lower_heating_value_j_per_mol: must be finite and not negative");
        }
    }
    /**
     * The free-water model of a package. {@code ice} is its ice below the melting line (WP4 of batch
     * 2026-09-24-coolprop-low-temperature), null for a record without one (a frozen fixture): the fluid network needs it.
     */
    public record Water(String revision, double molarMass, double triplePoint, double criticalTemperature,
            double criticalPressure, double maximumTemperature, double boilingTemperature,
            double vaporizationEnthalpy, double watsonExponent, List<Double> saturation, List<Double> shomate, Ice ice) {
        public Water { saturation = List.copyOf(saturation); shomate = List.copyOf(shomate); }
        /**
         * The water's ice: its crystal record (a usable {@link CrystalReference} of water anchored at the water's triple
         * point) and the coefficients and exponents of its sublimation pressure, IAPWS R14-08 equation 6
         * ({@code ln(p_sub/p_t) = (T_t/T) sum a_i (T/T_t)^b_i}, at the record's triple point).
         */
        public record Ice(String crystal, List<Double> coefficients, List<Double> exponents) {
            public Ice { Objects.requireNonNull(crystal); coefficients = List.copyOf(coefficients); exponents = List.copyOf(exponents); }
        }
    }
    public record Assay(String id, String basis, List<String> components, List<Double> amounts,
            double volumeScale, double amountTotal) {
        public Assay { components = List.copyOf(components); amounts = List.copyOf(amounts); }
    }
    /**
     * {@code spine} lists the reference-spine record of each component in component order, or is empty for a package
     * without one; {@code crystals} lists the crystal records the package admits.
     *
     * <p>{@code groupContributions} is {@code null} unless the package's interactions record carries a {@code rule}
     * (E-PPR78, P3): it then holds the temperature-dependent pairs resolved from the group matrix and the spines'
     * group counts ({@link GroupContributionInteractions}). For such a pair the constant {@code interactions} entry is
     * 0.0 and is not the pair's kij: a consumer takes kij(T) from {@code groupContributions} for every pair it lists
     * and the constant matrix for the others.</p>
     *
     * <p>Identity (owner decisions of 2026-10-26: nothing is hashed, and every record declares its own revision): data
     * are identified by declared revisions, never by a hash of their content. {@code revision} is the package record's
     * own, for its own fields (its method, its compounds and their datasets, crystals, evidence). A package is a property
     * method and a compound list (batch 2026-11-04-refinery-compounds): its column range is the intersection of its
     * datasets' ranges at the method's pressures, its fluid envelope the hull of their data ranges, its aliases the
     * catalog's for its compounds, its assays every assay whose non-zero compounds it holds. {@code scientificRevision}, the
     * column's identity, is composed at load from the declared revision of every record the package uses for its phase
     * physics, in a fixed order: its method by its physics fields (model, ideal-gas source, missing-pair policy, column
     * pressure range and the liquid-mixture correction's own revision; never the method record's revision, owner decision
     * D7 of 2026-11-04, so a pair library added to the method for other compounds moves nothing), each compound's dataset in the package's order, the pair libraries that give a
     * pair among its compounds (and the group matrix of a rule that resolves one), and the water model. The package record is
     * no part of it (option B of the varying-components review, 2026-11-04): it names the method and the compounds, which
     * the identity lists themselves, so a package record edit alone, or a compound added to the package, moves no identity of
     * a view that does not hold the new compound. An edit bumps the revision of the record it changes, and the identity of
     * every package that uses that record moves with it. What only the fluid network reads is identified apart: the
     * viscosities, each by its own revision, the water's ice and the fluid reference data in
     * {@link MaterialCatalog#fluidRevision}, and the spine and crystal records (the column refuses such packages) record by
     * record in {@link MaterialCatalog#spineRevision}. Assays are compositions, not physics: no identity carries them, and
     * neither do a dataset's freezing, thermochemistry and flammability sub-records ({@link Freezing}, {@link Thermochemistry},
     * {@link Flammability}), which no model reads yet.</p>
     */
    public record Package(String id, String revision, String model, List<String> components,
            List<Property> properties, List<List<Double>> interactions, List<NrtlPair> nrtlPairs,
            Map<String, String> aliases, Map<String, Assay> assays, Water water,
            double minimumTemperature, double maximumTemperature, double minimumPressure,
            double maximumPressure, List<String> evidence, List<String> spine, List<String> crystals,
            GroupContributionInteractions groupContributions, String scientificRevision) {
        public Package {
            components = List.copyOf(components); properties = List.copyOf(properties);
            interactions = interactions.stream().map(List::copyOf).toList(); nrtlPairs = List.copyOf(nrtlPairs);
            aliases = Map.copyOf(aliases); assays = Map.copyOf(assays); evidence = List.copyOf(evidence);
            spine = List.copyOf(spine); crystals = List.copyOf(crystals); Objects.requireNonNull(scientificRevision);
        }
        public String canonicalId(String id) { return aliases.getOrDefault(id, id); }
    }
    /**
     * The longest identity, {@link Package#scientificRevision} or {@link #fluidRevision}: the column's result payloads, the
     * neural models and the fluid saves carry them. A catalog whose identity would be longer is refused at load.
     */
    public static final int MAX_IDENTITY = 16384;
    /** The records packages are built from, kept so a view is built by the loader's own code ({@link #build}). */
    private final Records records;
    /** How each package of this catalog was built; absent for a lens ({@link #inferenceView}), which no view restricts. */
    private final Map<String, Definition> definitions;
    private final SolidMaterialCatalog solids;
    public SolidMaterialCatalog solids(){return solids;}
    private final FluidData fluidData;
    private final Map<String, MaterialName> names;
    private final Map<String, Package> packages;
    private final Map<String, String> resources;
    private final Map<String, Map<ViscosityCorrelation.Phase, ViscosityCorrelation>> viscosities;
    private final Map<String, String> waterModels;
    private final Map<String, LiquidMixtureCorrection> liquidMixtures;
    private final MaterialPresets presets;
    private final Map<String, FluidAppearance> assayAppearances;
    private final Map<String, FluidAppearance> componentAppearances;
    /** Fluid-network validity by property record id and by package id; see {@link Validity}. */
    private final Map<String, Validity> propertyValidity, packageValidity;
    /** Reference-spine and crystal records by record id (kinds {@code spine} and {@code crystals}). */
    private final Map<String, ReferenceSpine> spines;
    private final Map<String, CrystalReference> crystals;
    /** Heating values by record ({@code properties/<id>}, {@code water/<id>}); see {@link Combustion}. */
    private final Map<String, Combustion> combustion;
    /** Each dataset's and water model's formula, from its {@code thermochemistry} sub-record (C, H, N, O, S atoms per molecule), by its record key. */
    private final Map<String, Map<String, Double>> formulas;
    private MaterialCatalog(Map<String, MaterialName> names, Map<String, Package> packages, Map<String, String> resources,
            Map<String, Map<ViscosityCorrelation.Phase, ViscosityCorrelation>> viscosities, Map<String, String> waterModels,
            Map<String, FluidAppearance> assayAppearances, Map<String, FluidAppearance> componentAppearances, Map<String, LiquidMixtureCorrection> liquidMixtures, MaterialPresets presets, FluidData fluidData,
            Map<String, Validity> propertyValidity, Map<String, Validity> packageValidity,
            Map<String, ReferenceSpine> spines, Map<String, CrystalReference> crystals, Map<String, Combustion> combustion, Map<String, Map<String, Double>> formulas, SolidMaterialCatalog solids,
            Records records, Map<String, Definition> definitions) {
        this.propertyValidity = Map.copyOf(propertyValidity); this.packageValidity = Map.copyOf(packageValidity);
        this.spines = Map.copyOf(spines); this.crystals = Map.copyOf(crystals); this.combustion = Map.copyOf(combustion); this.formulas = Map.copyOf(formulas);
        this.records=Objects.requireNonNull(records); this.definitions=Map.copyOf(definitions);
        this.solids=Objects.requireNonNull(solids);
        // A solid population names its record by id (owner decision D23: crystals are solids), a crystal or a custom solid:
        // the two kinds never share an id.
        for(String id:this.crystals.keySet())if(solids.materials().containsKey(id))throw new IllegalArgumentException("crystals: "+id+" is also a solid material record; a solid id names one record");
        this.fluidData=Objects.requireNonNull(fluidData);
        this.liquidMixtures = Map.copyOf(liquidMixtures);
        this.presets=Objects.requireNonNull(presets);
        this.names = Map.copyOf(names); this.packages = Map.copyOf(packages); this.resources = Map.copyOf(resources);
        this.viscosities = Map.copyOf(viscosities); this.waterModels = Map.copyOf(waterModels);
        this.assayAppearances = Map.copyOf(assayAppearances);
        this.componentAppearances = Map.copyOf(componentAppearances);
    }
    /** This catalog with its packages, liquid-mixture corrections and definitions replaced; a view, never published. */
    private MaterialCatalog derived(Map<String, Package> packages, Map<String, LiquidMixtureCorrection> liquidMixtures, Map<String, Definition> definitions) {
        return new MaterialCatalog(names,packages,resources,viscosities,waterModels,assayAppearances,componentAppearances,liquidMixtures,presets,
                fluidData,propertyValidity,packageValidity,spines,crystals,combustion,formulas,solids,records,definitions);
    }
    public Package requirePackage(String id) {
        Package p = packages.get(id);
        if (p == null) throw new IllegalArgumentException("Unknown material package: " + id);
        return p;
    }
    public MaterialName name(String id) { MaterialName n=names.get(id); return n==null?MaterialName.chemical(id):n; }
    public Map<String, Package> packages() { return packages; }
    /** Crystal records by id. */
    public Map<String, CrystalReference> crystals() { return crystals; }
    /** The reference spine of a component in a package that declares one. */
    public ReferenceSpine requireSpine(String packageId, String componentId) {
        var p=requirePackage(packageId);
        if(p.spine().isEmpty())throw new IllegalArgumentException("Package declares no reference spine: "+packageId);
        int i=p.components().indexOf(p.canonicalId(componentId));
        if(i<0)throw new IllegalArgumentException("Component outside package: "+componentId);
        return spines.get(p.spine().get(i));
    }
    public FluidData fluidData(){return fluidData;}
    /** A component's Tr = 0.8 volume anchor in a package: its property record's {@code volume_translation}, if any (P3). */
    public Optional<VolumeTranslation> volumeTranslation(String packageId,String componentId) {
        var p=requirePackage(packageId);int i=p.components().indexOf(p.canonicalId(componentId));
        if(i<0)throw new IllegalArgumentException("Component outside package: "+componentId);
        return Optional.ofNullable(p.properties().get(i).volumeTranslation());
    }
    /**
     * The fluid network's identity of a package's data: its {@link Package#scientificRevision}, then what only the fluid
     * network reads, each by its declared revision: the viscosities of the property records and of the water model
     * ({@code id/phase@revision}, in component order, then the water), the water's ice crystal record and the fluid
     * reference records that hold an entry for one of its compounds or the water (the liquid volume references and the
     * dissolved-solute viscosities), as the pair libraries enter the column's. The column's identity leaves them out, so a
     * viscosity-only edit keeps the column's results and neural models. Nothing is hashed: a changed identity is a changed
     * property basis (a world restates its contents once).
     */
    public String fluidRevision(String packageId) {
        return fluidIdentity(requirePackage(packageId),waterModels.get(packageId),viscosities,crystals,fluidData);
    }
    private static String fluidIdentity(Package p,String waterId,Map<String,Map<ViscosityCorrelation.Phase,ViscosityCorrelation>> viscosities,
            Map<String,CrystalReference> crystals,FluidData fluidData) {
        var transport=new ArrayList<String>();
        for(var property:p.properties())viscosityRevisions(transport,property.id(),viscosities.getOrDefault("properties/"+property.id(),Map.of()));
        viscosityRevisions(transport,waterId,viscosities.getOrDefault("water/"+waterId,Map.of()));
        var ice=p.water().ice();
        var held=new HashSet<>(p.components());held.add("Water");
        return p.scientificRevision()+";viscosity="+String.join(",",transport)
                +(ice==null?"":";ice="+ice.crystal()+"@"+crystals.get(ice.crystal()).revision())+";fluid-data="+fluidData.revision(held);
    }
    /** A record's viscosities, {@code id/liquid@revision} and {@code id/vapor@revision} for the phases it has. */
    private static void viscosityRevisions(List<String> parts,String id,Map<ViscosityCorrelation.Phase,ViscosityCorrelation> viscosities) {
        for(var phase:ViscosityCorrelation.Phase.values()) {
            var viscosity=viscosities.get(phase);
            if(viscosity!=null)parts.add(id+"/"+phase.name().toLowerCase(Locale.ROOT)+"@"+viscosity.revision());
        }
    }
    /**
     * The identity of a package's spine and crystal data: each record's id and declared revision, spines in component
     * order, then crystals; empty for a package without either (and for a projected inference view).
     */
    public String spineRevision(String packageId) {
        var p=requirePackage(packageId);
        if(p.spine().isEmpty()&&p.crystals().isEmpty())return "";
        var parts=new ArrayList<String>();
        for(String id:p.spine())parts.add(id+"@"+spines.get(id).revision());
        for(String id:p.crystals())parts.add(id+"@"+crystals.get(id).revision());
        return "spine:"+String.join(",",parts);
    }
    /** The fluid network's envelope for a package: its {@code fluid_domain}, absent for a package the network cannot be built on. */
    public Optional<Validity> fluidValidity(String packageId) {
        requirePackage(packageId);return Optional.ofNullable(packageValidity.get(packageId));
    }
    /** A component's own fluid-network data range in a package: its property record's {@code fluid_domain}, if it declares one. */
    public Optional<Validity> fluidValidity(String packageId,String componentId) {
        var p=requirePackage(packageId);int i=p.components().indexOf(p.canonicalId(componentId));
        if(i<0)throw new IllegalArgumentException("Component outside package: "+componentId);
        return Optional.ofNullable(propertyValidity.get(p.properties().get(i).id()));
    }
    public MaterialPresets presets(){return presets;}
    public LiquidMixtureCorrection liquidMixture(String packageId) { requirePackage(packageId); return liquidMixtures.get(packageId); }
    /** Immutable source strings for tooling and isolated override tests. */
    public Map<String, String> resources() { return resources; }

    /** Liquid visual settings for a component, including package aliases and the water model. */
    public FluidAppearance componentAppearance(String packageId, String componentId) {
        Package p = requirePackage(packageId);
        String id = p.canonicalId(Objects.requireNonNull(componentId, "componentId"));
        if (!p.components().contains(id) && !id.equals("Water"))
            throw new IllegalArgumentException("Component is outside package: " + componentId);
        return Objects.requireNonNull(componentAppearances.get(id), "No appearance for component " + id);
    }

    /** Whole-feed liquid visual settings; no component-color mixing rule is implied. */
    public FluidAppearance assayAppearance(String packageId, String assayId) {
        if (!requirePackage(packageId).assays().containsKey(assayId))
            throw new IllegalArgumentException("Assay is outside package: " + assayId);
        // An assay is one composition by compound, the same on every package that holds it, so its appearance is too.
        return Objects.requireNonNull(assayAppearances.get(assayId), "No appearance for assay " + assayId);
    }

    /** Selected pure-component viscosity data, absent when that phase has no dataset. No mixing rule is implied. */
    public Optional<ViscosityCorrelation> viscosity(String packageId, String componentId, ViscosityCorrelation.Phase phase) {
        Objects.requireNonNull(phase, "phase");
        Package p = requirePackage(packageId);
        String id = p.canonicalId(Objects.requireNonNull(componentId, "componentId"));
        int index = p.components().indexOf(id);
        String key;
        if (index >= 0) key = "properties/" + p.properties().get(index).id();
        else if (id.equals("Water")) key = "water/" + waterModels.get(packageId);
        else throw new IllegalArgumentException("Component is outside package: " + componentId);
        return Optional.ofNullable(viscosities.getOrDefault(key, Map.of()).get(phase));
    }

    private static JsonObject propertyScience(Property property) {
        var json=new Gson().toJsonTree(property).getAsJsonObject();json.remove("id");return json;
    }
    /**
     * {@link #physicsContent(Package, List)} of package {@code packageId}: what tests compare packages by across catalogs.
     */
    String physicsContent(String packageId,List<String> axis) { return physicsContent(requirePackage(packageId),axis); }
    /**
     * The numbers package {@code p} holds on {@code axis}, as canonical JSON: the model, the axis's property constants,
     * interactions and NRTL pairs, the water model's numbers and any group-contribution pairs. Assays, names, transport,
     * record ids, declared revisions and the column's admission bounds are left out. Two packages have the same physics on
     * an axis when their contents are equal; the content is compared directly, never hashed (owner decision 2026-10-26).
     */
    static String physicsContent(Package p,List<String> axis) {
        new MaterialAxis(axis);
        var indices=axis.stream().map(id->{int i=p.components().indexOf(id);if(i<0)throw new IllegalArgumentException("Component outside package: "+id);return i;}).toList();
        var properties=indices.stream().map(i->propertyScience(p.properties().get(i))).toList();
        var matrix=indices.stream().map(i->indices.stream().map(j->p.interactions().get(i).get(j)).toList()).toList();
        var nrtl=p.nrtlPairs().stream().filter(pair->axis.contains(pair.first())&&axis.contains(pair.second()))
                .sorted(Comparator.comparing(NrtlPair::first).thenComparing(NrtlPair::second)).toList();
        // The ice is left out with the revision: the column never reaches the water's melting line.
        var water=new Gson().toJsonTree(p.water()).getAsJsonObject();water.remove("revision");water.remove("ice");
        var content=new ArrayList<Object>(List.of(p.model(),axis,properties,matrix,nrtl,water));
        if(p.groupContributions()!=null)content.add(p.groupContributions().science(p.components(),axis));
        return new Gson().toJson(content);
    }

    /**
     * This catalog with package {@code packageId} replaced by its view on the compounds {@code ids} (batch
     * 2026-11-04-refinery-compounds, V1): the package's compounds of {@code ids}, in the package's order, built from the
     * package's own records by the loader's code ({@link #build}). It has the same method, datasets, water model and
     * liquid-mixture correction; pairs among its compounds only; the spines and crystals of its compounds; the assays
     * whose non-zero compounds it holds. It keeps the package's column range and fluid envelope, so no state is judged
     * differently. Its identity names only its compounds' records and the pair libraries that give a pair among them.
     * Every mixing sum of the model runs over the compounds present, so a state that holds none of the other compounds has
     * the same numbers on the view as on the package. The view of every compound is the package itself.
     */
    public MaterialCatalog restrict(String packageId,List<String> ids) {
        var parent=requirePackage(packageId);var definition=definitions.get(packageId);
        if(definition==null)throw new IllegalArgumentException("Package "+packageId+" is a lens of one inference; it has no view");
        var subset=new HashSet<>(ids);
        if(subset.isEmpty()||subset.size()!=ids.size())throw new IllegalArgumentException("restrict: expected distinct compounds, got "+ids);
        for(String id:ids)if(!parent.components().contains(id))throw new IllegalArgumentException("restrict: "+id+" is outside package "+packageId);
        if(subset.size()==parent.components().size())return this;
        var view=new Definition(definition.id(),definition.revision(),definition.head(),definition.method(),
                definition.entries().stream().filter(e->subset.contains(e.component())).toList(),
                definition.crystals().stream().filter(c->subset.contains(records.crystals().get(c).component())).toList(),definition.evidence());
        var built=build(records,view,parent,records.assays());
        var packs=new HashMap<>(packages);packs.put(packageId,built.pack());
        var mixtures=new HashMap<>(liquidMixtures);mixtures.put(packageId,built.liquidMixture());
        var defs=new HashMap<>(definitions);defs.put(packageId,view);
        return derived(packs,mixtures,defs);
    }

    /**
     * The lens a model trained on {@code reference} reads a request on package {@code packageId} through (plan section
     * 3.3 of batch 2026-11-04-refinery-compounds; by value since O7 of batch 2026-11-04-open-thermo-data, owner decision
     * E8): this catalog with {@code packageId} replaced by a PR78 package on the reference's compound list. The compounds of
     * {@code active} (the request's non-zero feed) take the request's records; each other compound the request package's
     * record when the package holds it, else the catalog's default record of the compound. A pair takes the request's value
     * when both its compounds are active, else the trained value. It keeps the request's column range, water, evidence and
     * identity: it is a lens for one inference, never published. Empty when the request's values on {@code active} differ
     * from the trained ones beyond the tolerances ({@link TrainedPhysics#matches}, on the request's view {@link #restrict}),
     * when that view resolves a pair by a group-contribution rule (the lens carries constant pairs only), or when a compound
     * of the model has no record with PR78 constants and an ideal-gas fit. No record id, revision or package id takes part.
     * A request whose view on {@code active} admits a crystal is refused (P6): it is never projected fluid-only.
     */
    public Optional<MaterialCatalog> inferenceView(String packageId,List<String> active,TrainedPhysics reference) {
        var source=requirePackage(packageId);var axis=reference.components();
        if(!axis.containsAll(active)||!source.components().containsAll(active))
            throw new IllegalArgumentException("Inference compounds outside the request package or the model: "+active);
        var request=restrict(packageId,active).requirePackage(packageId);
        if(!request.crystals().isEmpty())throw new CrystalPhasesUnavailable(packageId,request.crystals(),"a neural inference projection");
        // The lens carries constant pairs only.
        if(!request.model().equals(reference.model())||request.groupContributions()!=null&&!request.groupContributions().pairs().isEmpty())
            return Optional.empty();
        if(!reference.matches(request,active))return Optional.empty();
        var properties=new ArrayList<Property>();var interactions=new ArrayList<List<Double>>();
        for(int a=0;a<axis.size();a++) {
            int i=source.components().indexOf(axis.get(a));
            String fallback=i>=0?null:records.defaults().get(axis.get(a));
            var property=i>=0?source.properties().get(i):fallback==null?null:records.properties().get(fallback);
            if(property==null||property.pr()==null||property.cp().size()!=6)return Optional.empty();
            properties.add(property);
            boolean fed=active.contains(axis.get(a));
            var row=new ArrayList<Double>();
            for(int b=0;b<axis.size();b++)
                row.add(fed&&active.contains(axis.get(b))?source.interactions().get(i).get(source.components().indexOf(axis.get(b)))
                        :a==b?0.0:reference.kij(axis.get(a),axis.get(b)));
            interactions.add(row);
        }
        var lens=new Package(source.id(),source.revision(),source.model(),axis,properties,
                interactions,List.of(),Map.of(),Map.of(),source.water(),source.minimumTemperature(),
                source.maximumTemperature(),source.minimumPressure(),source.maximumPressure(),source.evidence(),List.of(),List.of(),null,
                source.scientificRevision());
        var packs=new HashMap<>(packages);packs.put(packageId,lens);
        var defs=new HashMap<>(definitions);defs.remove(packageId);
        return Optional.of(derived(packs,liquidMixtures,defs));
    }

    /** Parse a complete winning resource set; never publishes partially validated data. */
    public static MaterialCatalog parse(Map<String, String> resources) {
        var groups = new HashMap<String, Map<String, JsonObject>>();
        var origins = new IdentityHashMap<JsonObject, String>();
        for (var entry : new TreeMap<>(resources).entrySet()) {
            try {
                JsonObject o = JsonParser.parseString(entry.getValue()).getAsJsonObject();
                if (number(o,"schema_version") != 1) throw new IllegalArgumentException("schema_version must be 1");
                String path = entry.getKey(); int start = path.indexOf("materials/");
                if (start < 0) throw new IllegalArgumentException("Expected materials resource path");
                String kind = path.substring(start + 10).split("/")[0];
                if (!Set.of("components","properties","interactions","methods","packages","assays","water","transport","presets","networks","solids","spine","crystals","group_interactions").contains(kind))
                    throw new IllegalArgumentException("Unknown record kind: " + kind + (kind.equals("bases") ? " (a package names its compounds and may extend another package)" : ""));
                String id = text(o, "id");
                if (!kind.equals("components") && !id.matches("[a-z][a-z0-9_.:-]{0,127}"))
                    throw new IllegalArgumentException("id: expected lowercase stable identifier");
                if (!id.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}")) throw new IllegalArgumentException("Invalid id");
                if (groups.computeIfAbsent(kind, unused -> new LinkedHashMap<>()).putIfAbsent(id,o) != null)
                    throw new IllegalArgumentException("Duplicate " + kind + " id: " + id);
                origins.put(o,path);
            } catch (RuntimeException e) { throw error(entry.getKey(), e); }
        }
        var names = new HashMap<String, MaterialName>();
        // A compound's default dataset (the property record a package takes unless it names another) and the old ids that
        // resolve to it, catalog-wide.
        var defaults = new HashMap<String, String>();
        var aliases = new HashMap<String, String>();
        var properties = new HashMap<String, Property>();
        var waters = new HashMap<String, Water>();
        var packages = new HashMap<String, Package>();
        var viscosities = new HashMap<String, Map<ViscosityCorrelation.Phase, ViscosityCorrelation>>();
        var waterModels = new HashMap<String, String>();
        var liquidMixtures = new HashMap<String, LiquidMixtureCorrection>();
        var assayAppearances = new HashMap<String, FluidAppearance>();
        var componentAppearances = new HashMap<String, FluidAppearance>();
        var propertyValidity = new HashMap<String, Validity>();
        var packageValidity = new HashMap<String, Validity>();
        var spines = new HashMap<String, ReferenceSpine>();
        var crystals = new HashMap<String, CrystalReference>();
        var combustion = new HashMap<String, Combustion>();
        var formulas = new HashMap<String, Map<String, Double>>();
        var propertyRevisions = new HashMap<String, String>();
        for (var o : group(groups,"components").values()) checked(origins,o,() -> {
            JsonObject cut = o.has("cut") ? object(o,"cut") : null;
            String id=text(o,"id"), kind=text(o,"kind");
            componentAppearances.put(id, readAppearance(o));
            if (kind.equals("petroleum_fraction")) {
                if (cut == null || !cut.has("lower_kelvin") && !cut.has("upper_kelvin")) throw new IllegalArgumentException("cut boundaries required");
                text(cut,"derivation"); text(cut,"series");
            }
            names.put(id,new MaterialName(id,text(o,"translation_key"),text(o,"fallback"),kind,
                    cut != null && cut.has("lower_kelvin") ? number(cut,"lower_kelvin") : null,
                    cut != null && cut.has("upper_kelvin") ? number(cut,"upper_kelvin") : null,
                    cut != null && bool(cut,"estimated")));
            if (o.has("properties")) defaults.put(id,text(o,"properties"));
            if (o.has("aliases")) for (String alias : strings(o,"aliases")) {
                if (!alias.matches("[A-Za-z][A-Za-z0-9_.:-]{0,63}")) throw new IllegalArgumentException("aliases: invalid id "+alias);
                if (aliases.putIfAbsent(alias,id) != null) throw new IllegalArgumentException("aliases: "+alias+" names two components");
            }
            // Identifiers (CAS number, a database's index, a formula) are provenance: read as text, in no identity.
            if (o.has("identifiers")) {
                var identifiers=object(o,"identifiers");
                try { for (String key : identifiers.keySet()) text(identifiers,key); }
                catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("identifiers."+invalid.getMessage(),invalid); }
            }
        });
        for (var alias : aliases.entrySet()) if (names.containsKey(alias.getKey()))
            throw error(origins.get(group(groups,"components").get(alias.getValue())), new IllegalArgumentException("aliases: "+alias.getKey()+" is itself a component id"));
        for (var o : group(groups,"spine").values()) checked(origins,o,() -> {
            var spine=ReferenceSpine.read(o); require(names,spine.component(),"component"); spines.put(spine.id(),spine);
        });
        for (var o : group(groups,"crystals").values()) checked(origins,o,() -> {
            var crystal=CrystalReference.read(o); require(names,crystal.component(),"component"); crystals.put(crystal.id(),crystal);
        });
        var matrices = new HashMap<String, GroupInteractionMatrix>();
        for (var o : group(groups,"group_interactions").values()) checked(origins,o,() -> {
            var matrix=GroupInteractionMatrix.read(o); matrices.put(matrix.id(),matrix);
        });
        for (var o : group(groups,"properties").values()) checked(origins,o,() -> {
            String id=text(o,"id"), component=text(o,"component"); require(names,component,"component");
            viscosities.put("properties/" + id, readViscosities(o));
            if(o.has("liquid_mixture_descriptor"))throw new IllegalArgumentException("liquid_mixture_descriptor: the compounds of a liquid-mixture term are listed in its method's liquid_mixture.components");
            propertyRevisions.put(id,revision(o,"revision")); text(o,"source");
            // ideal_gas_cp is optional: a record without it is usable only in a package that declares a reference
            // spine (D6, one ideal-gas source per species; checked per package below). Its cp is then empty.
            JsonObject cp=o.has("ideal_gas_cp")?object(o,"ideal_gas_cp"):null;
            List<Double> coefficients=List.of(); double reference=298.15;
            if (cp!=null) {
                if (!text(cp,"type").equals("shifted_polynomial_5")) throw new IllegalArgumentException("Unsupported ideal_gas_cp.type");
                coefficients=counted(cp,"coefficients",6); reference=positive(cp,"reference_kelvin");
                if (reference != 298.15) throw new IllegalArgumentException("shifted_polynomial_5.reference_kelvin must be 298.15");
            }
            JsonObject models=object(o,"models"); Pr78 pr=null;
            for (String model : models.keySet()) if (!model.equals("pr78")) throw new IllegalArgumentException("Unsupported pure-component model: " + model);
            if (models.has("pr78")) {
                JsonObject p=object(models,"pr78");
                pr=new Pr78(positive(p,"critical_temperature_kelvin"),positive(p,"critical_pressure_pascal"),number(p,"acentric_factor"));
            }
            double min=positive(o,"temperature_min_kelvin"), max=positive(o,"temperature_max_kelvin"); range(min,max,"temperature");
            Double floor=null;
            if (cp!=null && cp.has("temperature_min_kelvin")) {
                floor=positive(cp,"temperature_min_kelvin");
                if (floor<=min || floor>reference) throw new IllegalArgumentException("ideal_gas_cp.temperature_min_kelvin must lie in (temperature_min_kelvin, 298.15]");
            }
            VolumeTranslation translation=o.has("volume_translation")?VolumeTranslation.read(o,pr):null;
            properties.put(id,new Property(id,component,positive(o,"molecular_weight_kg_per_mol"),positive(o,"normal_boiling_point_kelvin"),
                    positive(o,"standard_liquid_density_kg_per_m3"),positive(o,"standard_temperature_kelvin"),positive(o,"standard_pressure_pascal"),
                    min,max,reference,coefficients,pr,bool(o,"estimated_heavy_residue"),floor,translation));
            if (o.has("fluid_domain")) propertyValidity.put(id,validity(object(o,"fluid_domain")));
            if (o.has("freezing")) {
                var f=readFreezing(object(o,"freezing"));
                // The compound's crystal records are anchored at its triple point: the freezing data must agree with them.
                for (var crystal : new TreeMap<>(crystals).values()) if (crystal.component().equals(component)
                        && !(Math.abs(f.anchorTemperature()-crystal.anchor().temperature())<=Freezing.CRYSTAL_ANCHOR_TOLERANCE))
                    throw new IllegalArgumentException("freezing: "+id+" gives "+f.anchorTemperature()+" K and crystal record "+crystal.id()
                            +" is anchored at "+crystal.anchor().temperature()+" K; they must agree within "+Freezing.CRYSTAL_ANCHOR_TOLERANCE+" K");
            }
            // Data for later models: each sub-record is validated as it is read; the catalog keeps the formula (a flare's flame
            // temperature, batch 2026-11-09-flare-stack) and the heating value (the burners), nothing else.
            if (o.has("thermochemistry")) formulas.put("properties/"+id,Map.copyOf(readThermochemistry(object(o,"thermochemistry")).elements()));
            if (o.has("combustion")) combustion.put("properties/"+id,readCombustion(object(o,"combustion")));
            if (o.has("flammability")) readFlammability(object(o,"flammability"));
        });
        for (var o : group(groups,"water").values()) checked(origins,o,() -> {
            viscosities.put("water/" + text(o,"id"), readViscosities(o));
            if (!text(o,"type").equals("iapws_shomate_watson")) throw new IllegalArgumentException("Unsupported water.type");
            text(o,"source");
            Water.Ice ice=null;
            if(o.has("ice")) {
                // The water's ice (WP4): a usable crystal record of water anchored at the water's own triple point, and its
                // sublimation pressure, IAPWS R14-08 equation 6 at that point.
                JsonObject i=object(o,"ice"),sublimation=object(i,"sublimation_pressure");
                var crystal=require(crystals,text(i,"crystal"),"ice.crystal");
                if(!crystal.component().equals("Water"))throw new IllegalArgumentException("ice.crystal: "+crystal.id()+" is made of "+crystal.component()+", not Water");
                if(!crystal.usable())throw new IllegalArgumentException("ice.crystal: "+crystal.id()+" is not usable, missing "+crystal.missing());
                if(crystal.anchor().temperature()!=positive(o,"triple_point"))
                    throw new IllegalArgumentException("ice.crystal: "+crystal.id()+" is anchored at "+crystal.anchor().temperature()+" K, not the water's triple point");
                if(!text(sublimation,"type").equals("iapws_r14_08"))throw new IllegalArgumentException("Unsupported ice.sublimation_pressure.type");
                text(sublimation,"source");
                ice=new Water.Ice(crystal.id(),counted(sublimation,"coefficients",3),counted(sublimation,"exponents",3));
            }
            Water w=new Water(revision(o,"revision"),positive(o,"molar_mass"),positive(o,"triple_point"),positive(o,"critical_temperature"),
                    positive(o,"critical_pressure"),positive(o,"max_enthalpy_temperature"),positive(o,"reference_boiling_temperature"),
                    positive(o,"reference_vaporization_enthalpy"),positive(o,"watson_exponent"),counted(o,"saturation_coefficients",6),counted(o,"shomate_coefficients",7),ice);
            range(w.triplePoint(),w.boilingTemperature(),"water boiling"); range(w.boilingTemperature(),w.criticalTemperature(),"water critical");
            range(w.triplePoint(),w.maximumTemperature(),"water enthalpy"); require(names,"Water","component"); waters.put(text(o,"id"),w);
            if (o.has("thermochemistry")) formulas.put("water/"+text(o,"id"),Map.copyOf(readThermochemistry(object(o,"thermochemistry")).elements()));
            if (o.has("combustion")) combustion.put("water/"+text(o,"id"),readCombustion(object(o,"combustion")));
        });
        for (var o : group(groups,"interactions").values()) checked(origins,o,() -> validateInteractions(o,names,matrices));
        // A compound's default dataset is one of its own property records.
        for (var entry : defaults.entrySet()) checked(origins,group(groups,"components").get(entry.getKey()),() -> {
            Property p=require(properties,entry.getValue(),"properties");
            if(!p.component().equals(entry.getKey()))throw new IllegalArgumentException("properties: "+p.id()+" is a dataset of "+p.component()+", not of "+entry.getKey());
        });
        var methods = new HashMap<String, Method>();
        for (var o : group(groups,"methods").values()) checked(origins,o,() -> {
            var method=Method.read(o,group(groups,"interactions"),waters); methods.put(method.id(),method);
            for(String component:method.mixture().components())require(names,component,"liquid_mixture.components");
        });
        // The fluid reference records come before the packages: each package's fluid identity is bounded at load.
        var fluidData=FluidData.read(group(groups,"transport"),origins,names.keySet());
        var assayRecords=AssayRecord.read(group(groups,"assays"),origins,names,assayAppearances);
        var entries=Entries.resolve(group(groups,"packages"),origins,defaults);
        var spinesByComponent=new HashMap<String,List<String>>();
        for(var spine:spines.values())spinesByComponent.computeIfAbsent(spine.component(),unused->new ArrayList<>()).add(spine.id());
        var records=new Records(names,properties,propertyRevisions,waters,group(groups,"interactions"),matrices,spines,
                spinesByComponent,crystals,aliases,assayRecords,propertyValidity,viscosities,fluidData,defaults);
        var definitions=new HashMap<String,Definition>();
        for (var o : group(groups,"packages").values()) checked(origins,o,() -> {
            // A package is a property method and a list of compounds (batch 2026-11-04-refinery-compounds): every number comes
            // from the method, the compounds' datasets and the pair libraries.
            String id=text(o,"id");
            for(String moved:List.of("model","basis","properties","interactions","missing_interactions","water_model","temperature_min_kelvin",
                    "temperature_max_kelvin","pressure_min_pascal","pressure_max_pascal","fluid_domain","liquid_mixture","aliases","spine"))
                if(o.has(moved))throw new IllegalArgumentException(moved+": a package names its method and its compounds; this field belongs to the method, a compound or its dataset");
            var method=require(methods,text(o,"method"),"method");
            var evidence=new ArrayList<>(method.evidence()); if(o.has("advisory_evidence"))evidence.addAll(strings(o,"advisory_evidence"));
            if(evidence.size()>32 || evidence.stream().anyMatch(e->e.length()>256))throw new IllegalArgumentException("advisory_evidence: exceeds reporting bounds");
            List<String> crystalIds=o.has("crystals")?strings(o,"crystals"):List.of();
            if(new HashSet<>(crystalIds).size()!=crystalIds.size())throw new IllegalArgumentException("crystals: duplicate crystal record");
            String revision=revision(o,"revision");
            var definition=new Definition(id,revision,List.of(method.identity()),method,entries.get(id),crystalIds,evidence);
            var built=build(records,definition,null,assayRecords);
            packages.put(id,built.pack()); definitions.put(id,definition);
            liquidMixtures.put(id,built.liquidMixture()); waterModels.put(id,method.water());
            if(built.envelope()!=null)packageValidity.put(id,built.envelope());
        });
        for(var record:assayRecords.values())if(packages.values().stream().noneMatch(p->p.assays().containsKey(record.id())))
            throw new IllegalArgumentException(record.origin()+": no package holds every compound of assay "+record.id());
        if(packages.isEmpty()) throw new IllegalArgumentException("Material catalog has no packages");
        var result=new MaterialCatalog(names,packages,resources,viscosities,waterModels,assayAppearances,componentAppearances,liquidMixtures,MaterialPresets.read(group(groups,"presets"),group(groups,"networks"),origins,packages,names.keySet()),fluidData,
                propertyValidity,packageValidity,spines,crystals,combustion,formulas,SolidMaterialCatalog.parse(resources),records,definitions);
        for(var row:group(groups,"presets").values())if(text(row,"kind").equals("column"))checked(origins,row,()->
                MaterialRuntime.with(result,text(row,"package"),()->{com.wormzjl.icheme.science.column.v3.V3ColumnProblemResolver.validateInput(result.presets().column(text(row,"id")).input(result));return null;}));
        for(var row:group(groups,"networks").values())checked(origins,row,()->{
            var config=result.presets().network();var network=result.requirePackage(config.packageId());
            for(String id:config.fluidPresets()) {
                var preset=result.presets().fluid(id);
                if(!preset.component().isEmpty()) {
                    if(!preset.component().equals("Water")&&!network.components().contains(preset.component()))throw new IllegalArgumentException("fluid_presets: component outside network "+preset.component());
                } else if(!network.assays().containsKey(preset.assayId()))
                    throw new IllegalArgumentException("fluid_presets: assay "+preset.assayId()+" holds compounds outside the network package "+network.id());
            }
            for(var phase:ViscosityCorrelation.Phase.values())if(result.viscosity(network.id(),"Nitrogen",phase).isEmpty())throw new IllegalArgumentException("package: nitrogen transport is required");
            if(result.fluidValidity(network.id()).isEmpty())throw new IllegalArgumentException("package: every compound of the network package needs a dataset with a fluid_domain");
        });
        // The fluid network freezes free water below its melting line on every package (WP4), so the network package's
        // water model needs its ice: refused here, with the water record's file and the field, so a /reload of such data is
        // refused before publication (owner decision of 2026-10-19) rather than holding a running network at its model build.
        if(!group(groups,"networks").isEmpty()) {
            var network=result.requirePackage(result.presets().network().packageId());
            if(network.water().ice()==null)throw new IllegalArgumentException(origins.get(group(groups,"water").get(result.waterModels.get(network.id())))
                    +": ice: required, the fluid network's package "+network.id()+" freezes its free water as this record's ice");
        }
        return result;
    }

    /** Every component and assay record carries its appearance; there is no default tint. */
    private static FluidAppearance readAppearance(JsonObject record) {
        try {
            JsonObject o = object(record, "appearance");
            String color = text(o, "color");
            if (!color.matches("#[0-9a-fA-F]{6}"))
                throw new IllegalArgumentException("color must be #RRGGBB (alpha is specified by transparency)");
            return new FluidAppearance(Integer.parseInt(color.substring(1), 16), number(o, "transparency"), bool(o, "estimated"));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("appearance: " + invalid.getMessage(), invalid);
        }
    }

    private static Map<ViscosityCorrelation.Phase, ViscosityCorrelation> readViscosities(JsonObject record) {
        if (!record.has("viscosity")) return Map.of();
        var result = new EnumMap<ViscosityCorrelation.Phase, ViscosityCorrelation>(ViscosityCorrelation.Phase.class);
        for (var entry : object(record, "viscosity").entrySet()) {
            try {
                var phase = switch (entry.getKey()) {
                    case "liquid" -> ViscosityCorrelation.Phase.LIQUID;
                    case "vapor" -> ViscosityCorrelation.Phase.VAPOR;
                    default -> throw new IllegalArgumentException("unknown phase");
                };
                JsonObject o = entry.getValue().getAsJsonObject();
                var model = switch (text(o,"type")) {
                    case "andrade" -> ViscosityCorrelation.Model.ANDRADE;
                    case "sutherland" -> ViscosityCorrelation.Model.SUTHERLAND;
                    case "power_sum" -> ViscosityCorrelation.Model.POWER_SUM;
                    case "log_table" -> ViscosityCorrelation.Model.LOG_TABLE;
                    default -> throw new IllegalArgumentException("unsupported type");
                };
                if (model == ViscosityCorrelation.Model.ANDRADE && phase != ViscosityCorrelation.Phase.LIQUID
                        || model == ViscosityCorrelation.Model.SUTHERLAND && phase != ViscosityCorrelation.Phase.VAPOR)
                    throw new IllegalArgumentException("correlation type does not match phase");
                int count = model == ViscosityCorrelation.Model.POWER_SUM || model == ViscosityCorrelation.Model.LOG_TABLE
                        ? array(o,"coefficients").size() : 2;
                result.put(phase, new ViscosityCorrelation(model, positive(o,"temperature_min_kelvin"), positive(o,"temperature_max_kelvin"),
                        positive(o,"pressure_min_pascal"), positive(o,"pressure_max_pascal"), positive(o,"reference_temperature_kelvin"),
                        counted(o,"coefficients",count), o.has("exponents") ? counted(o,"exponents",count) : List.of(),
                        revision(o,"revision"), text(o,"source"), bool(o,"estimated"),
                        o.has("temperatures_kelvin") ? counted(o,"temperatures_kelvin",count) : List.of()));
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("viscosity." + entry.getKey() + ": " + error.getMessage(), error);
            }
        }
        return Map.copyOf(result);
    }
    /** A dataset's {@code freezing} sub-record ({@link Freezing}); a refusal names the field. */
    static Freezing readFreezing(JsonObject o) {
        try {
            return new Freezing(positive(o,"normal_melting_kelvin"),
                    o.has("triple_point_kelvin")?positive(o,"triple_point_kelvin"):null,
                    o.has("triple_point_pascal")?positive(o,"triple_point_pascal"):null,
                    o.has("fusion_enthalpy_j_per_mol")?positive(o,"fusion_enthalpy_j_per_mol"):null,
                    bool(o,"estimated"),text(o,"source"),revision(o,"revision"));
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("freezing."+invalid.getMessage(),invalid); }
    }
    /** A dataset's or water model's {@code thermochemistry} sub-record ({@link Thermochemistry}); a refusal names the field. */
    static Thermochemistry readThermochemistry(JsonObject o) {
        try {
            JsonObject formula=object(o,"elements");var elements=new HashMap<String,Double>();
            for(String element:formula.keySet())elements.put(element,number(formula,element));
            return new Thermochemistry(number(o,"formation_enthalpy_j_per_mol"),elements,bool(o,"estimated"),text(o,"source"),revision(o,"revision"));
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("thermochemistry."+invalid.getMessage(),invalid); }
    }
    /** A dataset's or water model's {@code combustion} sub-record ({@link Combustion}); a refusal names the field. */
    static Combustion readCombustion(JsonObject o) {
        try {
            return new Combustion(number(o,"lower_heating_value_j_per_mol"),bool(o,"estimated"),text(o,"source"),revision(o,"revision"));
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("combustion."+invalid.getMessage(),invalid); }
    }
    /**
     * Package {@code packageId}'s lower heating values, J/mol, in the fluid network's conserved order (its components, then
     * the water; {@link Combustion}): 0 for a record without a {@code combustion} sub-record, which burns as an inert.
     */
    public double[] heatingValues(String packageId) {
        var p=requirePackage(packageId);var out=new double[p.components().size()+1];
        for(int i=0;i<p.properties().size();i++){var c=combustion.get("properties/"+p.properties().get(i).id());out[i]=c==null?0:c.lowerHeatingValue();}
        var water=combustion.get("water/"+waterModels.get(packageId));out[out.length-1]=water==null?0:water.lowerHeatingValue();
        return out;
    }
    /**
     * Package {@code packageId}'s formulas in the fluid network's conserved order (its components, then the water): atoms of
     * C, H, N, O and S per molecule ({@link #FORMULA_ELEMENTS}), from each record's {@code thermochemistry}; all 0 for a
     * record without one. A flare's flame temperature reads them (batch 2026-11-09-flare-stack).
     */
    public double[][] formulas(String packageId) {
        var p=requirePackage(packageId);var out=new double[p.components().size()+1][];
        for(int i=0;i<p.properties().size();i++)out[i]=atoms(formulas.get("properties/"+p.properties().get(i).id()));
        out[out.length-1]=atoms(formulas.get("water/"+waterModels.get(packageId)));
        return out;
    }
    /** The elements {@link #formulas} counts, in its order. */
    public static final List<String> FORMULA_ELEMENTS=List.of("C","H","N","O","S");
    private static double[] atoms(Map<String,Double> formula) {
        var out=new double[FORMULA_ELEMENTS.size()];
        if(formula!=null)for(int e=0;e<out.length;e++)out[e]=formula.getOrDefault(FORMULA_ELEMENTS.get(e),0.);
        return out;
    }
    /** A dataset's {@code flammability} sub-record ({@link Flammability}); a refusal names the field. */
    static Flammability readFlammability(JsonObject o) {
        try {
            return new Flammability(number(o,"lfl_volume_fraction"),o.has("ufl_volume_fraction")?number(o,"ufl_volume_fraction"):null,
                    bool(o,"estimated"),text(o,"source"),revision(o,"revision"));
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("flammability."+invalid.getMessage(),invalid); }
    }
    private static void validateInteractions(JsonObject o,Map<String,MaterialName> names,Map<String,GroupInteractionMatrix> matrices) {
        String model=text(o,"model"); text(o,"source"); revision(o,"revision");
        if(!Set.of("pr78","nrtl").contains(model)) throw new IllegalArgumentException("Unknown interaction model");
        if(o.has("rule")) {
            // A group-contribution rule resolves, per package, every pair without an explicit constant (P3 E-PPR78).
            if(!model.equals("pr78"))throw new IllegalArgumentException("rule: a group-contribution kij rule needs model pr78");
            JsonObject rule=object(o,"rule");String type=text(rule,"type");
            if(!type.equals(GroupContributionInteractions.RULE_EPPR78))throw new IllegalArgumentException("rule.type: expected "+GroupContributionInteractions.RULE_EPPR78+", got "+type);
            require(matrices,text(rule,"group_interactions"),"rule.group_interactions");
        }
        var seen=new HashSet<String>();
        for(var value:array(o,"pairs")) {
            JsonObject p=value.getAsJsonObject(); String a=text(p,"first"),b=text(p,"second"); require(names,a,"first"); require(names,b,"second");
            if(a.equals(b) || !seen.add(pairKey(a,b))) throw new IllegalArgumentException("Duplicate/self interaction pair");
            if(model.equals("pr78")) number(p,"kij"); else nrtl(p);
        }
    }
    private static Validity validity(JsonObject o) {
        try {
            String evidence=text(o,"evidence");
            double tmin=positive(o,"temperature_min_kelvin"),tmax=positive(o,"temperature_max_kelvin");range(tmin,tmax,"temperature");
            double pmin=positive(o,"pressure_min_pascal"),pmax=positive(o,"pressure_max_pascal");range(pmin,pmax,"pressure");
            return new Validity(tmin,tmax,pmin,pmax,evidence);
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("fluid_domain: "+invalid.getMessage(),invalid); }
    }
    private static NrtlPair nrtl(JsonObject p) {
        double min=positive(p,"temperature_min_kelvin"),max=positive(p,"temperature_max_kelvin"); range(min,max,"NRTL temperature");
        double alpha=number(p,"alpha"); if(alpha<0 || alpha>1) throw new IllegalArgumentException("NRTL alpha must be in [0,1]");
        return new NrtlPair(text(p,"first"),text(p,"second"),number(p,"a12"),number(p,"b12_kelvin"),number(p,"a21"),number(p,"b21_kelvin"),alpha,min,max);
    }
    private static String pairKey(String a,String b) { return a.compareTo(b)<0?a+"/"+b:b+"/"+a; }
    private static Map<String,JsonObject> group(Map<String,Map<String,JsonObject>> groups,String key) { return groups.getOrDefault(key,Map.of()); }
    private static <T> T require(Map<String,T> map,String id,String field) { T v=map.get(id); if(v==null)throw new IllegalArgumentException(field+": unresolved "+id);return v; }
    private static void range(double a,double b,String field) { if(a>=b)throw new IllegalArgumentException(field+": invalid range"); }
    /** An array of exactly {@code count} finite numbers. */
    private static List<Double> counted(JsonObject o,String key,int count) {
        var r=numbers(o,key);
        if(r.size()!=count)throw new IllegalArgumentException(key+": expected "+count+" coefficients/amounts");return r;
    }
    private static void checked(Map<JsonObject,String> origins,JsonObject o,Runnable action) { try{action.run();}catch(RuntimeException e){throw error(origins.get(o),e);} }
    private static IllegalArgumentException error(String path,RuntimeException e) { return new IllegalArgumentException(path+": "+e.getMessage(),e); }
    /** Classpath loader shared by standalone tools and regression tests. */
    public static MaterialCatalog bundled() { return Bundled.INSTANCE; }
    private static final class Bundled {
        static final MaterialCatalog INSTANCE=load();
        static MaterialCatalog load() {
            var resources=new LinkedHashMap<String,String>();
            for(var path:JsonParser.parseString(read("materials-index.json")).getAsJsonArray()) {
                String key=path.getAsString();
                if(!key.matches("data/[a-z0-9_.-]+/materials/[a-z0-9_./-]+\\.json")||key.contains("..")||resources.putIfAbsent(key,read(key))!=null)
                    throw new IllegalArgumentException("Invalid or duplicate material index entry: "+key);
            }
            return parse(resources);
        }
        static String read(String path) {
            try(InputStream in=MaterialCatalog.class.getClassLoader().getResourceAsStream(path)) {
                if(in==null)throw new IllegalArgumentException("Missing bundled material resource "+path);
                return new String(in.readAllBytes(),StandardCharsets.UTF_8);
            }catch(IOException e){throw new UncheckedIOException(path,e);}
        }
    }
    /**
     * A property method (kind {@code methods}): the model, the ideal-gas source ({@code fit}, the datasets'
     * {@code shifted_polynomial_5}, or {@code spine}, the reference spines), the pair libraries in priority order, the
     * policy for a pair none of them names, the water model, the column's pressure range, the liquid-mixture correction
     * and advisory evidence. Any package may combine it with any compounds.
     */
    private record Method(String id,String revision,String model,boolean spine,List<String> interactions,String missing,
            String water,double minimumPressure,double maximumPressure,List<String> evidence,LiquidMixtureCorrection.Rule mixture) {
        Method { interactions=List.copyOf(interactions); evidence=List.copyOf(evidence); }
        /**
         * The method's token in an identity: its physics fields, never its id or its record's revision (owner decision D7,
         * 2026-11-04), so adding a pair library for other compounds moves no package. The libraries that give a pair, the
         * datasets and the water model are tokens of their own.
         */
        String identity() {
            return "method=model:"+model+",ideal-gas:"+(spine?"spine":"fit")+",missing-interactions:"+missing
                    +",pressure-pascal:"+plain(minimumPressure)+".."+plain(maximumPressure)
                    +(mixture.revision()==null?"":",liquid-mixture:"+mixture.revision());
        }
        private static String plain(double value) { return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
        static Method read(JsonObject o,Map<String,JsonObject> libraries,Map<String,Water> waters) {
            String id=text(o,"id"), model=text(o,"model"), policy=text(o,"missing_interactions"), idealGas=text(o,"ideal_gas");
            if (!Set.of("pr78","nrtl").contains(model)) throw new IllegalArgumentException("Unsupported model: " + model);
            if (!Set.of("zero","error").contains(policy) || model.equals("nrtl") && !policy.equals("error"))
                throw new IllegalArgumentException("Invalid missing_interactions policy");
            if (!Set.of("fit","spine").contains(idealGas)) throw new IllegalArgumentException("ideal_gas: expected fit or spine, got "+idealGas);
            var ids=strings(o,"interactions");
            if (new HashSet<>(ids).size()!=ids.size()) throw new IllegalArgumentException("interactions: a pair library is named twice");
            int rules=0;
            for (String library : ids) {
                var record=require(libraries,library,"interactions");
                if (!model.equals(text(record,"model"))) throw new IllegalArgumentException("Interaction model mismatch: "+library);
                if (record.has("rule")) {
                    rules++;
                    if (!idealGas.equals("spine")) throw new IllegalArgumentException("interactions: "+library+" carries a group-contribution rule, which decomposes each compound by its reference spine's groups, and this method takes no spines");
                }
            }
            if (rules>1) throw new IllegalArgumentException("interactions: at most one pair library may carry a group-contribution rule");
            String water=text(o,"water_model"); require(waters,water,"water_model");
            double pmin=positive(o,"pressure_min_pascal"), pmax=positive(o,"pressure_max_pascal"); range(pmin,pmax,"pressure");
            var evidence=o.has("advisory_evidence")?strings(o,"advisory_evidence"):List.<String>of();
            // The record's own revision() accessor hides the statically imported reader; name it by its class. The
            // liquid-mixture term declares its own revision, which the identity names.
            return new Method(id,JsonFields.revision(o,"revision"),model,idealGas.equals("spine"),ids,policy,water,pmin,pmax,evidence,
                    LiquidMixtureCorrection.Rule.read(o));
        }
    }
    /** One compound of a package after {@code extends}: its dataset and, for a method with spines, the spine it names. */
    private record Entry(String component,String properties,String spine) {}
    /** Package compound lists: a package's own after its {@code extends} parent's, each with its dataset. */
    private static final class Entries {
        private Entries() {}
        static Map<String,List<Entry>> resolve(Map<String,JsonObject> packages,Map<JsonObject,String> origins,Map<String,String> defaults) {
            var resolved=new HashMap<String,List<Entry>>();
            for(String id:packages.keySet())resolve(id,packages,resolved,new LinkedHashSet<>(),origins,defaults);
            return resolved;
        }
        private static List<Entry> resolve(String id,Map<String,JsonObject> packages,Map<String,List<Entry>> resolved,
                Set<String> visiting,Map<JsonObject,String> origins,Map<String,String> defaults) {
            if(resolved.containsKey(id))return resolved.get(id);
            var row=packages.get(id); if(row==null)throw new IllegalArgumentException("extends: missing package "+id);
            if(!visiting.add(id))throw new IllegalArgumentException(origins.get(row)+": extends: cycle "+visiting+" -> "+id);
            try {
                var entries=new ArrayList<Entry>();
                if(row.has("extends"))entries.addAll(resolve(text(row,"extends"),packages,resolved,visiting,origins,defaults));
                for(var value:array(row,"components")) {
                    Entry entry;
                    if(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString()) {
                        String component=value.getAsString(); entry=new Entry(component,defaults.get(component),null);
                    } else if(value.isJsonObject()) {
                        var o=value.getAsJsonObject(); String component=text(o,"id");
                        entry=new Entry(component,o.has("properties")?text(o,"properties"):defaults.get(component),o.has("spine")?text(o,"spine"):null);
                    } else throw new IllegalArgumentException("components: expected a compound id or an object with its id");
                    if(entry.properties()==null)throw new IllegalArgumentException("components: "+entry.component()+" has no default dataset; name its properties");
                    entries.add(entry);
                }
                var result=List.copyOf(entries); resolved.put(id,result); return result;
            } catch(RuntimeException invalid) { throw new IllegalArgumentException(origins.get(row)+": "+invalid.getMessage(),invalid); }
            finally { visiting.remove(id); }
        }
    }
    /** The records a package is built from, after validation: what {@link #build} reads. */
    private record Records(Map<String,MaterialName> names,Map<String,Property> properties,Map<String,String> propertyRevisions,
            Map<String,Water> waters,Map<String,JsonObject> libraries,
            Map<String,GroupInteractionMatrix> matrices,Map<String,ReferenceSpine> spines,Map<String,List<String>> spinesByComponent,
            Map<String,CrystalReference> crystals,Map<String,String> aliases,Map<String,AssayRecord> assays,
            Map<String,Validity> propertyValidity,Map<String,Map<ViscosityCorrelation.Phase,ViscosityCorrelation>> viscosities,
            FluidData fluidData,Map<String,String> defaults) {
        Records {
            defaults=Map.copyOf(defaults); names=Map.copyOf(names); properties=Map.copyOf(properties); propertyRevisions=Map.copyOf(propertyRevisions);
            waters=Map.copyOf(waters); libraries=Map.copyOf(libraries); matrices=Map.copyOf(matrices);
            spines=Map.copyOf(spines); crystals=Map.copyOf(crystals); aliases=Map.copyOf(aliases); assays=Map.copyOf(assays);
            propertyValidity=Map.copyOf(propertyValidity); viscosities=Map.copyOf(viscosities);
            var byComponent=new HashMap<String,List<String>>(); spinesByComponent.forEach((k,v)->byComponent.put(k,List.copyOf(v)));
            spinesByComponent=Map.copyOf(byComponent);
        }
    }
    /**
     * What a package is built from: its id and own revision, the leading token of its identity (its method's physics
     * fields; neither a package record's nor a method record's own revision is in any identity), its method, its compounds with their datasets, the crystals it admits and
     * its evidence. A restricted view
     * ({@link #restrict}) keeps the package's definition with fewer compounds.
     */
    private record Definition(String id,String revision,List<String> head,Method method,List<Entry> entries,List<String> crystals,List<String> evidence) {
        Definition { head=List.copyOf(head); entries=List.copyOf(entries); crystals=List.copyOf(crystals); evidence=List.copyOf(evidence); }
    }
    /** A built package with its liquid-mixture correction and its fluid envelope (null when a compound's dataset declares none). */
    private record Built(Package pack,LiquidMixtureCorrection liquidMixture,Validity envelope) {}
    /**
     * Builds the package of {@code d} from the records: the one place a package, its pairs and its identity are made, for
     * the loader and a restricted view. {@code parent}, when given, is the package {@code d} restricts:
     * the view keeps its column range (the fluid envelope stays the parent's by its id). {@code assays} are the assay
     * records to apply.
     */
    private static Built build(Records r,Definition d,Package parent,Map<String,AssayRecord> assays) {
        String id=d.id(); Method method=d.method(); var resolved=d.entries();
        List<String> ids=resolved.stream().map(Entry::component).toList(), refs=resolved.stream().map(Entry::properties).toList();
        // A package is at most the world list (the axis less water): an island of the network package carries at most 64 of them.
        if (ids.isEmpty() || ids.size()>MaterialAxis.MAX_CONSERVED_COMPONENTS-1 || new HashSet<>(ids).size()!=ids.size())
            throw new IllegalArgumentException("components: expected 1 to "+(MaterialAxis.MAX_CONSERVED_COMPONENTS-1)+" distinct compounds, got "+ids);
        var ps=new ArrayList<Property>();
        // The column's admission range: the temperatures every member dataset covers, at the method's pressures.
        double tmin=0,tmax=Double.POSITIVE_INFINITY;
        for(int i=0;i<ids.size();i++) {
            require(r.names(),ids.get(i),"component"); Property p=require(r.properties(),refs.get(i),"property");
            if(!p.component().equals(ids.get(i)) || method.model().equals("pr78") && p.pr()==null) throw new IllegalArgumentException("Property/model does not match component " + ids.get(i));
            // D6, one ideal-gas source per species: a method with a reference spine takes every compound's ideal gas from
            // it, a method without one from the dataset's shifted_polynomial_5 fit.
            if(p.cp().isEmpty() && !method.spine())
                throw new IllegalArgumentException("Property "+p.id()+" has no ideal_gas_cp: a method without a reference spine needs a shifted_polynomial_5 fit for "+ids.get(i));
            if(!p.cp().isEmpty() && method.spine())
                throw new IllegalArgumentException("Property "+p.id()+" carries ideal_gas_cp in a method with a reference spine: one ideal-gas source per species (D6), "+ids.get(i));
            tmin=Math.max(tmin,p.minimumTemperature()); tmax=Math.min(tmax,p.maximumTemperature());
            ps.add(p);
        }
        if(parent!=null) { tmin=parent.minimumTemperature(); tmax=parent.maximumTemperature(); }
        else if(!(tmax>tmin))throw new IllegalArgumentException("temperature: the member datasets share no temperature range");
        double pmin=parent==null?method.minimumPressure():parent.minimumPressure(),pmax=parent==null?method.maximumPressure():parent.maximumPressure();
        var spineIds=new ArrayList<String>(); var spineRecords=new ArrayList<ReferenceSpine>();
        for(int i=0;i<ids.size();i++) {
            String named=resolved.get(i).spine();
            if(!method.spine()) {
                if(named!=null)throw new IllegalArgumentException("spine: method "+method.id()+" takes the datasets' fits, not the reference spine "+named);
                continue;
            }
            String spineId=named;
            if(spineId==null) {
                var candidates=r.spinesByComponent().getOrDefault(ids.get(i),List.of());
                if(candidates.size()!=1)throw new IllegalArgumentException("spine: "+ids.get(i)+" has "+candidates.size()+" reference-spine records; name one");
                spineId=candidates.getFirst();
            }
            var spine=require(r.spines(),spineId,"spine");
            if(!spine.component().equals(ids.get(i)))throw new IllegalArgumentException("spine: "+spine.id()+" is for "+spine.component()+", not component "+ids.get(i));
            double difference=Math.abs(spine.molarMass()/ps.get(i).molecularWeight()-1);
            if(difference>1e-4)throw new IllegalArgumentException("spine: molar mass of "+spine.id()+" ("+spine.molarMass()+" kg/mol) differs from property "
                    +ps.get(i).id()+" ("+ps.get(i).molecularWeight()+" kg/mol) by "+difference+" relative, above 1e-4");
            spineIds.add(spine.id()); spineRecords.add(spine);
        }
        var liquidMixture=method.mixture().on(ids);
        // The method's pair libraries in order: the first that names a pair gives its value; pairs outside the package are
        // skipped. Only libraries that give a pair on this axis enter the identity, so a library for other compounds moves
        // nothing here.
        double[][] matrix=new double[ids.size()][ids.size()]; var nrtl=new ArrayList<NrtlPair>(); var seen=new HashSet<String>();
        boolean[][] constant=new boolean[ids.size()][ids.size()];
        var contributing=new ArrayList<String>(); JsonObject ruleLibrary=null;
        for(String library:method.interactions()) {
            JsonObject interaction=r.libraries().get(library); boolean contributed=false;
            for(var value:array(interaction,"pairs")) {
                JsonObject pair=value.getAsJsonObject(); String a=text(pair,"first"), b=text(pair,"second");
                int i=ids.indexOf(a),j=ids.indexOf(b); if(i<0 || j<0 || !seen.add(pairKey(a,b))) continue;
                constant[i][j]=constant[j][i]=true; contributed=true;
                if(method.model().equals("pr78")) matrix[i][j]=matrix[j][i]=number(pair,"kij");
                else {
                    NrtlPair n=nrtl(pair); if(tmin<n.minimumTemperature() || tmax>n.maximumTemperature()) throw new IllegalArgumentException("NRTL temperature validity: "+a+" / "+b+" in "+library); nrtl.add(n);
                }
            }
            if(interaction.has("rule"))ruleLibrary=interaction;
            if(contributed)contributing.add(library+"@"+text(interaction,"revision"));
        }
        GroupContributionInteractions groupContributions=null; String groupToken=null;
        if(ruleLibrary!=null) {
            // Every pair without an explicit constant is resolved by the group-contribution rule from the spines' group
            // counts; its constant matrix entry stays 0.0 and is not its kij (see Package).
            JsonObject rule=object(ruleLibrary,"rule"); var groupMatrix=require(r.matrices(),text(rule,"group_interactions"),"rule.group_interactions");
            groupContributions=GroupContributionInteractions.resolve(text(rule,"type"),groupMatrix,ids,spineRecords,constant);
            for(var pair:groupContributions.pairs())seen.add(pairKey(ids.get(pair.first()),ids.get(pair.second())));
            if(!groupContributions.pairs().isEmpty()) {
                String token=text(ruleLibrary,"id")+"@"+text(ruleLibrary,"revision"); if(!contributing.contains(token))contributing.add(token);
                groupToken=groupMatrix.id()+"@"+groupMatrix.revision();
            }
        }
        if(method.missing().equals("error")) for(int i=0;i<ids.size();i++) for(int j=i+1;j<ids.size();j++)
            if(!seen.contains(pairKey(ids.get(i),ids.get(j)))) throw new IllegalArgumentException("Missing interaction: " + ids.get(i)+" / "+ids.get(j));
        Map<String,String> packageAliases=new HashMap<>();
        for(var alias:r.aliases().entrySet())if(ids.contains(alias.getValue()))packageAliases.put(alias.getKey(),alias.getValue());
        Water water=require(r.waters(),method.water(),"water_model");
        // The package's envelope on the fluid network is the hull of its members' data ranges and the water model's triple
        // point, absent when a member declares none. Leaving it, or a component's range, never holds a state (owner rule
        // of 2026-10-16).
        var memberDomains=ps.stream().map(p->r.propertyValidity().get(p.id())).toList();
        Validity envelope=memberDomains.stream().allMatch(Objects::nonNull)?envelope(ids,memberDomains,water):null;
        var packageAssays=new HashMap<String,Assay>();
        for(var record:assays.values()) { var assay=record.on(ids,ps,id); if(assay!=null)packageAssays.put(record.id(),assay); }
        var rows=new ArrayList<List<Double>>(); for(double[] row:matrix) rows.add(Arrays.stream(row).boxed().toList());
        var crystalIds=d.crystals();
        // A crystal is anchored at its triple point to the package family's own liquid, on either ideal-gas source (E27).
        for(String crystalId:crystalIds) {
            var crystal=require(r.crystals(),crystalId,"crystals");
            if(!ids.contains(crystal.component()))throw new IllegalArgumentException("crystals: "+crystalId+" is for "+crystal.component()+", outside the package components");
        }
        // The column's identity: every record the package names for its phase physics, each by its declared revision,
        // checked as a token where the record was read (see Package).
        var identity=new ArrayList<>(d.head());
        identity.add("properties="+String.join(",",refs.stream().map(ref->ref+"@"+r.propertyRevisions().get(ref)).toList()));
        identity.add("interactions="+String.join(",",contributing));
        if(groupToken!=null)identity.add("group-interactions="+groupToken);
        identity.add("water="+method.water()+"@"+water.revision());
        var built=new Package(id,d.revision(),method.model(),ids,ps,rows,nrtl,packageAliases,packageAssays,water,tmin,tmax,pmin,pmax,d.evidence(),
                spineIds,crystalIds,groupContributions,String.join(";",identity));
        int longest=fluidIdentity(built,method.water(),r.viscosities(),r.crystals(),r.fluidData()).length();
        if(longest>MAX_IDENTITY)throw new IllegalArgumentException("the package's fluid identity is "+longest+" characters, above "+MAX_IDENTITY);
        return new Built(built,liquidMixture,envelope);
    }
    /**
     * An assay (kind {@code assays}): a composition by compound, independent of any package. It applies to every package
     * that holds its compounds with a non-zero amount; a compound it gives as zero may be absent.
     */
    private record AssayRecord(String id,String basis,Map<String,Double> amounts,double volumeScale,double amountTotal,
            double standardTemperature,double standardPressure,String origin) {
        AssayRecord { amounts=Map.copyOf(amounts); }
        static Map<String,AssayRecord> read(Map<String,JsonObject> records,Map<JsonObject,String> origins,Map<String,MaterialName> names,
                Map<String,FluidAppearance> appearances) {
            var result=new HashMap<String,AssayRecord>();
            for(var a:records.values()) checked(origins,a,() -> {
                String id=text(a,"id");
                if(a.has("package"))throw new IllegalArgumentException("package: an assay is a composition by compound and applies to every package holding its compounds; remove the field");
                if(a.has("components")||a.has("amounts"))throw new IllegalArgumentException("components/amounts: give the composition as amounts_by_component");
                var sparse=object(a,"amounts_by_component"); var amounts=new LinkedHashMap<String,Double>(); double total=0;
                for(String component:sparse.keySet()) {
                    if(!names.containsKey(component))throw new IllegalArgumentException("amounts_by_component: unknown component "+component);
                    double amount=number(sparse,component); if(amount<0)throw new IllegalArgumentException("amounts_by_component: negative amount of "+component);
                    amounts.put(component,amount); total+=amount;
                }
                if(!(total>0)||!Double.isFinite(total))throw new IllegalArgumentException("Invalid assay components/amounts");
                String basis=text(a,"basis"); if(!Set.of("mole","mass","standard_liquid_volume").contains(basis)) throw new IllegalArgumentException("Unknown assay basis");
                boolean volume=basis.equals("standard_liquid_volume");
                appearances.put(id,readAppearance(a));
                result.put(id,new AssayRecord(id,basis,amounts,a.has("volume_scale")?positive(a,"volume_scale"):1,a.has("amount_total")?positive(a,"amount_total"):1,
                        volume?number(a,"standard_temperature_kelvin"):Double.NaN,volume?number(a,"standard_pressure_pascal"):Double.NaN,origins.get(a)));
            });
            return result;
        }
        /** The assay on a package's axis, or null when the package lacks one of its non-zero compounds. */
        Assay on(List<String> axis,List<Property> properties,String packageId) {
            for(var amount:amounts.entrySet())if(amount.getValue()>0&&!axis.contains(amount.getKey()))return null;
            var ordered=new ArrayList<Double>();
            for(int i=0;i<axis.size();i++) {
                double amount=amounts.getOrDefault(axis.get(i),0.0); ordered.add(amount);
                var p=properties.get(i);
                if(amount>0&&basis.equals("standard_liquid_volume")&&(standardTemperature!=p.standardTemperature()||standardPressure!=p.standardPressure()))
                    throw new IllegalArgumentException(origin+": assay "+id+": density reference conditions differ from "+p.id()+" on package "+packageId);
            }
            return new Assay(id,basis,axis,ordered,volumeScale,amountTotal);
        }
    }
    /**
     * A package's envelope on the fluid network: the hull of its members' data ranges, its lowest temperature no higher than
     * the water model's triple point, with evidence naming where each end comes from.
     */
    private static Validity envelope(List<String> ids,List<Validity> members,Water water) {
        double tmin=water.triplePoint(),tmax=0,pmin=Double.POSITIVE_INFINITY,pmax=0; String low="the water model's triple point",high="";
        for(int i=0;i<members.size();i++) {
            var v=members.get(i);
            if(v.minimumTemperature()<tmin){tmin=v.minimumTemperature();low=ids.get(i);}
            if(v.maximumTemperature()>tmax){tmax=v.maximumTemperature();high=ids.get(i);}
            pmin=Math.min(pmin,v.minimumPressure()); pmax=Math.max(pmax,v.maximumPressure());
        }
        return new Validity(tmin,tmax,pmin,pmax,"The hull of the member data ranges: from "+tmin+" K ("+low+") to "+tmax+" K ("+high
                +"), "+pmin+" Pa to "+pmax+" Pa; derived at load, never authored");
    }
    /** Validated, immutable reference data used by fluid EOS volume translation and dissolved-solute transport. */
    public static final class FluidData {
        public record VolumeReference(double temperatureKelvin,double pressurePascal,double molarVolumeCubicMetres) {}
        private final Map<String,VolumeReference> volumes;
        private final Map<String,ViscosityCorrelation> conditional;
        /** Each record's {@code type@revision} and the compounds it holds an entry for. */
        private final Map<String,Set<String>> revisions;
        private FluidData(Map<String,VolumeReference> volumes,Map<String,ViscosityCorrelation> conditional,Map<String,Set<String>> revisions) {
            this.volumes=Map.copyOf(volumes);this.conditional=Map.copyOf(conditional);
            var held=new TreeMap<String,Set<String>>();revisions.forEach((token,ids)->held.put(token,Set.copyOf(ids)));this.revisions=Collections.unmodifiableMap(held);
        }
        public Map<String,VolumeReference> volumeReferences(){return volumes;}
        public Map<String,ViscosityCorrelation> conditionalLiquidViscosities(){return conditional;}
        /**
         * The declared revisions of the fluid reference records that hold an entry for one of {@code compounds},
         * {@code type@revision} in sorted order: a record for other compounds moves nothing on them.
         */
        public String revision(Collection<String> compounds){
            return String.join(",",revisions.entrySet().stream().filter(e->e.getValue().stream().anyMatch(compounds::contains)).map(Map.Entry::getKey).toList());
        }
        static FluidData read(Map<String,JsonObject> records,Map<JsonObject,String> origins,Set<String> components) {
            var volumes=new HashMap<String,VolumeReference>();var curves=new HashMap<String,ViscosityCorrelation>();var revisions=new HashMap<String,Set<String>>();
            for(var row:records.values())try {
                String type=text(row,"type"),revision=text(row,"revision"),source=text(row,"source");
                var held=revisions.computeIfAbsent(type+"@"+token(revision,"revision"),unused->new HashSet<>());
                if(type.equals("liquid_volume_reference_points")) {
                    for(var entry:array(row,"points")) {
                        var point=entry.getAsJsonObject();String id=component(point,components);held.add(id);
                        text(point,"source");
                        var value=new VolumeReference(positive(point,"temperatureKelvin"),positive(point,"pressurePascal"),positive(point,"molarVolumeCubicMetres"));
                        if(volumes.putIfAbsent(id,value)!=null)throw new IllegalArgumentException("points: duplicate component "+id);
                    }
                } else if(type.equals("conditional_solute_log_tables")) {
                    double pressure=positive(row,"reference_pressure_pascal");
                    for(var entry:array(row,"curves")) {
                        var curve=entry.getAsJsonObject();String id=component(curve,components);held.add(id);
                        var t=numbers(curve,"temperatures_kelvin");var mu=numbers(curve,"viscosities_pascal_seconds");
                        if(t.size()<2||t.size()!=mu.size())throw new IllegalArgumentException("curves: temperature/value count mismatch");
                        var value=new ViscosityCorrelation(ViscosityCorrelation.Model.LOG_TABLE,t.getFirst(),t.getLast(),pressure,pressure,t.getFirst(),mu,List.of(),revision,source,true,t);
                        if(curves.putIfAbsent(id,value)!=null)throw new IllegalArgumentException("curves: duplicate component "+id);
                        if(curve.has("checks"))for(var check:array(curve,"checks")) {
                            var sample=check.getAsJsonObject();double temperature=positive(sample,"temperature_kelvin");positive(sample,"viscosity_pascal_seconds");
                            if(temperature<t.getFirst()||temperature>t.getLast())throw new IllegalArgumentException("checks: temperature outside sampled domain");
                        }
                    }
                } else throw new IllegalArgumentException("type: unsupported fluid reference record "+type);
            } catch(RuntimeException invalid) {throw new IllegalArgumentException(origins.get(row)+": "+invalid.getMessage(),invalid);}
            return new FluidData(volumes,curves,revisions);
        }
        private static String component(JsonObject row,Set<String> components){String id=text(row,"component");if(!components.contains(id))throw new IllegalArgumentException("component: unknown "+id);return id;}
    }
    /** Liquid render tint, independent of thermodynamic and optical properties. */
    public record FluidAppearance(int rgb, double transparency, boolean estimated) {
        public FluidAppearance {
            if (rgb < 0 || rgb > 0xFFFFFF) throw new IllegalArgumentException("appearance.color must be 24-bit RGB");
            if (!Double.isFinite(transparency) || transparency < 0 || transparency > 1)
                throw new IllegalArgumentException("appearance.transparency must be finite and between 0 and 1");
        }

        /** Packed AARRGGBB tint. Transparency 0 is opaque; 1 is invisible. */
        public int argb() { return ((int) Math.round((1 - transparency) * 255) << 24) | rgb; }

    }
}
