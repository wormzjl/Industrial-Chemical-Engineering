package com.wormzjl.icheme.world.level.block.entity;

import com.wormzjl.icheme.registry.ModBlockEntities;
import com.wormzjl.icheme.science.column.v3.V3ColumnDisplayResult;
import com.wormzjl.icheme.science.column.v3.V3ColumnDutyLedger;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3PumparoundSpec;
import com.wormzjl.icheme.science.column.v3.V3SideDrawSpec;
import com.wormzjl.icheme.science.column.v3.V3SteamFeedSpec;
import com.wormzjl.icheme.science.column.v3.V3TrayHydraulicsSummary;
import com.wormzjl.icheme.science.column.v3.V3ColumnProblemResolver;
import com.wormzjl.icheme.science.column.v3.V3ColumnOutcome;
import com.wormzjl.icheme.science.column.v3.V3ColumnSpecification;
import com.wormzjl.icheme.science.column.v3.V3ColumnSpecification.ControlledQuantity;
import com.wormzjl.icheme.science.column.v3.V3NeuralSeed;
import com.wormzjl.icheme.science.column.v3.V3ColumnStreamProperties;
import com.wormzjl.icheme.science.column.v3.V3ComponentBasis;
import com.wormzjl.icheme.science.column.v3.V3ConvergenceEvidence;
import com.wormzjl.icheme.science.column.v3.V3HollandExample32;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.icheme.science.equipment.ColumnDesign;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.world.inventory.ColumnCalculatorV3Menu;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Server-thread-confined V3 calculator state.
 *
 * <p>Only the logical server mutates this object. An active operation contains an immutable input snapshot; workers
 * never retain this block entity, world, player, or menu, and a completion is committed only when its operation still
 * exactly matches.</p>
 */
public final class ColumnCalculatorV3BlockEntity extends BlockEntity implements MenuProvider {
    /** The only readable format; other saved data is refused and replaced by a fresh input (no migration). */
    public static final int DATA_VERSION = 12;
    private static final String TAG_DATA_VERSION = "V3DataVersion";
    private static final String TAG_INPUT_REVISION = "InputRevision";
    private static final String TAG_RESULT_REVISION = "ResultRevision";
    private static final String TAG_STATE_REVISION = "StateRevision";
    private static final String TAG_INPUT = "Input";
    private static final String TAG_RESULT = "Result";
    private static final String TAG_DESIGN = "Design";

    private V3Status status = V3Status.DIRTY;
    private long inputRevision;
    private long resultRevision = -1L;
    private long stateRevision;
    private String detail = "Methane-enriched CDU draft is ready";
    private V3ColumnInput currentInput = freshInput();
    private V3ColumnDisplayResult displayResult;
    /** Process-local numerical cache; never written to the saved facts section. */
    private V3NeuralSeed warmSeed;
    private V3Operation activeOperation;
    private List<String> attemptDetails = List.of();
    /** The design the player saved for an equipment constructor to import; apart from the input, which Calculate replaces. */
    private ColumnDesign savedDesign;

    public ColumnCalculatorV3BlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.COLUMN_CALCULATOR_V3.get(), pos, state);
    }

    /**
     * Atomically freezes a validated server-resolved input for one worker operation.
     *
     * <p>Must run on the logical server thread. A stale editor revision or existing operation is
     * rejected without changing the block state.</p>
     */
    public Optional<V3Operation> tryBegin(long expectedInputRevision, long operationId, V3ColumnInput input) {
        if (!(level instanceof ServerLevel) || expectedInputRevision != inputRevision || activeOperation != null) {
            return Optional.empty();
        }
        input = Objects.requireNonNull(input, "input");
        long nextInputRevision;
        try {
            nextInputRevision = Math.incrementExact(inputRevision);
            stateRevision = Math.incrementExact(stateRevision);
        } catch (ArithmeticException overflow) {
            status = V3Status.FAILED;
            detail = "V3 revision space is exhausted";
            setChanged();
            return Optional.empty();
        }
        currentInput = input;
        displayResult = null;
        attemptDetails = List.of();
        inputRevision = nextInputRevision;
        activeOperation = new V3Operation(operationId, nextInputRevision, input);
        status = V3Status.CALCULATING;
        detail = "Calculating server-validated V3 input";
        setChanged();
        return Optional.of(activeOperation);
    }

    /** Commits the terminal worker outcome only when it belongs to the exact current operation. */
    public boolean finishOperation(V3Operation operation, V3ColumnOutcome outcome, String terminalDetail) {
        if (!Objects.equals(activeOperation, operation)) return false;
        activeOperation = null;
        if (outcome instanceof V3ColumnOutcome.Success success) {
            displayResult = V3ColumnDisplayResult.fromAccepted(success);
            warmSeed = success.result().warmSeed().orElse(null);
            resultRevision = Math.incrementExact(resultRevision);
            status = V3Status.SUCCESS;
            detail = V3HollandExample32.isPackage(operation.input().packageId())
                    ? "Success: Holland oracle and V3 audit agree; seven printed-table conflicts remain advisory"
                    : successDetail(success);
            refreshMaterialFreshness();
        } else if (outcome instanceof V3ColumnOutcome.Failure failure) {
            status = V3Status.FAILED;
            String failureText = failure.code().name() + ": " + failure.summary();
            detail = bounded(failureText);
            var evidence = new java.util.ArrayList<String>();
            for(int at=256;at<failureText.length();at+=256)
                evidence.add(failureText.substring(at,Math.min(at+256,failureText.length())));
            failure.diagnostics().events().stream().filter(event -> {
                String lower=event.toLowerCase(java.util.Locale.ROOT);
                return lower.contains("liquid")||lower.contains("draw")||lower.contains("pressure")||lower.contains("wet");
            }).limit(20).map(ColumnCalculatorV3BlockEntity::bounded).forEach(evidence::add);
            attemptDetails=List.copyOf(evidence);
        } else {
            status = V3Status.FAILED;
            detail = bounded(terminalDetail);
        }
        stateRevision = Math.incrementExact(stateRevision);
        setChanged();
        return true;
    }

    /** An accepted column keeps its residual in the detail; an audit warning, when there is one, leads the line. */
    private static String successDetail(V3ColumnOutcome.Success success) {
        String residual = "accepted residual " + success.diagnostics().maximumScaledResidual();
        for (String advisory : success.diagnostics().acceptanceAudit().advisoryEvidence()) {
            if (advisory.startsWith("Warning: ")) return bounded("Success with warning: " + advisory.substring("Warning: ".length()) + " (" + residual + ")");
        }
        return "Success: " + residual;
    }

    /** Records a terminal service failure; starting the attempt already cleared its prior result. */
    public boolean failOperation(V3Operation operation, String failureDetail) {
        if (!Objects.equals(activeOperation, operation)) return false;
        activeOperation = null;
        status = V3Status.FAILED;
        detail = bounded(failureDetail);
        stateRevision = Math.incrementExact(stateRevision);
        setChanged();
        return true;
    }

    /** Replaces the idle draft with one server-owned preset and invalidates any prior display certificate. */
    public boolean tryLoadPreset(long expectedInputRevision, V3ColumnInput preset, String presetDetail) {
        if (!(level instanceof ServerLevel) || expectedInputRevision != inputRevision || activeOperation != null) {
            return false;
        }
        preset = Objects.requireNonNull(preset, "preset");
        try {
            inputRevision = Math.incrementExact(inputRevision);
            stateRevision = Math.incrementExact(stateRevision);
        } catch (ArithmeticException overflow) {
            status = V3Status.FAILED;
            detail = "V3 revision space is exhausted";
            setChanged();
            return false;
        }
        currentInput = preset;
        displayResult = null;
        warmSeed = null;
        attemptDetails = List.of();
        status = V3Status.DIRTY;
        detail = bounded(presetDetail);
        setChanged();
        return true;
    }

    /** The state the engine's presentation last published for this block, with no client nonce; never saved. */
    private V3State published;
    /** What an opening screen may replay at once: the last published state, a cached read; empty until a bucket published one. */
    public Optional<V3State> published() { return Optional.ofNullable(published); }

    /** The stored column input: a read, nothing is solved or published. */
    public V3ColumnInput currentInput() { return currentInput; }

    /** Returns the last accepted numerical state for a repeat-request predictor. Server thread only. */
    public V3NeuralSeed warmSeed() { return warmSeed; }
    /** The design the player saved, which an equipment constructor imports: a read. */
    public Optional<ColumnDesign> savedDesign() { return Optional.ofNullable(savedDesign); }

    /** Stores a checked design ({@link ColumnDesign#of}) in place of the saved one; the input and the result stay. */
    public void saveDesign(ColumnDesign design) {
        savedDesign = Objects.requireNonNull(design, "design");
        stateRevision = Math.incrementExact(stateRevision);
        setChanged();
    }

    /**
     * Unloaded or broken (a chunk unload calls this after {@code onChunkUnloaded}): the running solve is cancelled. The
     * operation is not saved, so no calculator could take its result; the cancel frees the worker and this block's solve
     * slot, which a reloaded calculator's next Calculate needs (review 2026-10-26-chunk-loading-review, D3).
     */
    @Override
    public void setRemoved() {
        super.setRemoved();
        if (activeOperation != null && level instanceof ServerLevel serverLevel && serverLevel.getServer().isSameThread())
            com.wormzjl.icheme.runtime.ProcessSolveServices.cancelRequest(serverLevel.getServer(), activeOperation.operationId());
    }
    /** Recorded by the presentation bucket that published {@code state}. */
    public void markPublished(V3State state) {
        if (Objects.requireNonNull(state).clientNonce() != 0L) throw new IllegalArgumentException("A published snapshot acknowledges no client");
        published = state;
    }

    /** Immutable view for a requester or broadcast viewer; it excludes numerical workspaces and carries only accepted inspection data. */
    public V3State state(long clientNonce) {
        refreshMaterialFreshness();
        java.util.Map<String, com.wormzjl.icheme.science.material.MaterialName> names = new java.util.LinkedHashMap<>();
        for (String id : currentInput.componentBasis().componentIds()) names.put(id,
                com.wormzjl.icheme.science.material.MaterialRuntime.name(currentInput.packageId(), id));
        if (displayResult != null) for (var stream : displayResult.streams()) for (var fraction : stream.moleFractions())
            names.put(fraction.componentId(), com.wormzjl.icheme.science.material.MaterialRuntime.name(currentInput.packageId(), fraction.componentId()));
        var catalog = com.wormzjl.icheme.science.material.MaterialRuntime.current();
        var editorCatalog = com.wormzjl.icheme.science.column.v3.V3EditorCatalog.from(catalog,currentInput);
        for(var template:editorCatalog.templates().values())for(String id:template.componentBasis().componentIds())
            if(names.size()<2*V3ColumnStreamProperties.MAX_COMPONENTS)names.putIfAbsent(id,com.wormzjl.icheme.science.material.MaterialRuntime.name(template.packageId(),id));
        var diagnostics = new java.util.ArrayList<String>();diagnostics.add(detail);diagnostics.addAll(attemptDetails);
        return new V3State(
                clientNonce,
                stateRevision,
                activeOperation == null ? 0L : activeOperation.operationId(),
                inputRevision,
                resultRevision,
                status,
                currentInput,
                Optional.ofNullable(displayResult),
                diagnostics, names, editorCatalog, Optional.ofNullable(savedDesign));
    }

    private void refreshMaterialFreshness() {
        if (displayResult == null || currentInput == null || activeOperation != null
                || V3HollandExample32.isPackage(currentInput.packageId())) return;
        if (!com.wormzjl.icheme.science.material.MaterialRuntime.isCurrent(currentInput.packageId(),displayResult.datasetRevision())) {
            if (status != V3Status.STALE) {
                status = V3Status.STALE;
                stateRevision = Math.incrementExact(stateRevision);
                setChanged();
            }
            detail = "Material properties changed; recalculate this result";
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt(TAG_DATA_VERSION, DATA_VERSION);
        tag.putLong(TAG_INPUT_REVISION, inputRevision);
        tag.putLong(TAG_RESULT_REVISION, resultRevision);
        tag.putLong(TAG_STATE_REVISION, stateRevision);
        tag.put(TAG_INPUT, writeInput(currentInput));
        if (displayResult != null) tag.put(TAG_RESULT, writeDisplayResult(displayResult));
        if (savedDesign != null) tag.put(TAG_DESIGN, ColumnDesignTags.write(savedDesign));
        // Active operation identity, queue state, timings, and any warm numerical state are intentionally not saved.
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        activeOperation = null;
        attemptDetails = List.of();
        displayResult = null;
        inputRevision = 0L;
        resultRevision = -1L;
        stateRevision = 0L;
        currentInput = freshInput();
        savedDesign = null;
        // The saved design stands apart from the input: a design this build cannot read is dropped alone.
        if (tag.contains(TAG_DESIGN, Tag.TAG_COMPOUND))
            try {
                savedDesign = ColumnDesignTags.read(tag.getCompound(TAG_DESIGN));
            } catch (IllegalArgumentException invalid) {
                com.wormzjl.icheme.IChemE.LOGGER.warn("Column calculator at {}: saved design refused ({})", worldPosition, invalid.getMessage());
            }
        if (!tag.contains(TAG_DATA_VERSION)) {
            status = V3Status.DIRTY;
            detail = "No V3 persisted state";
            return;
        }
        try {
            if (!tag.contains(TAG_DATA_VERSION, Tag.TAG_INT) || tag.getInt(TAG_DATA_VERSION) != DATA_VERSION)
                throw new IllegalArgumentException("saved data version is not " + DATA_VERSION);
            if (!tag.contains(TAG_INPUT, Tag.TAG_COMPOUND)) throw new IllegalArgumentException("saved input is missing");
            currentInput = requireCurrent(readInput(tag.getCompound(TAG_INPUT)),com.wormzjl.icheme.science.material.MaterialRuntime.current());
            inputRevision = nonNegative(requiredLong(tag, TAG_INPUT_REVISION));
            resultRevision = requiredLong(tag, TAG_RESULT_REVISION);
            if (resultRevision < -1L) throw new IllegalArgumentException("Invalid V3 result revision");
            stateRevision = nonNegative(requiredLong(tag, TAG_STATE_REVISION));
            if (tag.contains(TAG_RESULT, Tag.TAG_COMPOUND)) {
                displayResult = readDisplayResult(tag.getCompound(TAG_RESULT));
                if (resultRevision < 0L) throw new IllegalArgumentException("V3 result lacks revision");
                status = V3Status.STALE;
                detail = "Persisted V3 result is presentation-only; recalculate to refresh it";
            } else {
                status = V3Status.DIRTY;
                detail = "Persisted V3 input is ready to calculate";
            }
        } catch (IllegalArgumentException invalid) {
            // No compatibility path: data this build cannot read is refused and the block starts from a fresh input,
            // which the next save writes in the current format.
            currentInput = freshInput();
            displayResult = null;
            inputRevision = 0L;
            resultRevision = -1L;
            stateRevision = 0L;
            status = V3Status.DIRTY;
            detail = bounded("Saved column data refused (" + invalid.getMessage() + "); a fresh input replaces it");
        }
    }

    private static long requiredLong(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_LONG)) throw new IllegalArgumentException("saved " + key + " is missing");
        return tag.getLong(key);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.icheme.column_calculator_v3");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        if (level == null) return null;
        return new ColumnCalculatorV3Menu(
                containerId, inventory, ContainerLevelAccess.create(level, worldPosition), worldPosition);
    }

    /** Immutable operation identity retained only until its terminal completion is handled on the server thread. */
    public record V3Operation(long operationId, long inputRevision, V3ColumnInput input) {
        public V3Operation {
            if (operationId <= 0L || inputRevision <= 0L) {
                throw new IllegalArgumentException("V3 operation identity must be positive");
            }
            input = Objects.requireNonNull(input, "input");
        }
    }

    /** Bounded immutable state transmitted only after server-side validation. */
    public record V3State(
            long clientNonce,
            long stateRevision,
            long operationId,
            long inputRevision,
            long resultRevision,
            V3Status status,
            V3ColumnInput input,
            Optional<V3ColumnDisplayResult> displayResult,
            List<String> diagnostics,
            java.util.Map<String, com.wormzjl.icheme.science.material.MaterialName> materialNames,
            com.wormzjl.icheme.science.column.v3.V3EditorCatalog editorCatalog,
            Optional<ColumnDesign> design) {
        public V3State {
            Objects.requireNonNull(editorCatalog,"editorCatalog");
            Objects.requireNonNull(design,"design");
            materialNames = java.util.Map.copyOf(materialNames);
            if (materialNames.size() > 2 * V3ColumnStreamProperties.MAX_COMPONENTS
                    || materialNames.keySet().stream().anyMatch(id -> !id.matches("[A-Za-z][A-Za-z0-9_.:-]{0,63}")))
                throw new IllegalArgumentException("Material naming descriptors exceed state bounds");
            if (clientNonce < 0L || stateRevision < 0L || operationId < 0L || inputRevision < 0L
                    || resultRevision < -1L) {
                throw new IllegalArgumentException("V3 state revisions are invalid");
            }
            status = Objects.requireNonNull(status, "status");
            input = Objects.requireNonNull(input, "input");
            displayResult = Objects.requireNonNull(displayResult, "displayResult");
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
            if (diagnostics.size() > 32 || diagnostics.stream().anyMatch(value -> value == null || value.length() > 256)) {
                throw new IllegalArgumentException("V3 diagnostics exceed the bounded state contract");
            }
        }
    }

    /** Sent over the wire by {@code name()}; never saved. */
    public enum V3Status { CALCULATING, SUCCESS, FAILED, STALE, DIRTY }

    /**
     * Exact current-format identity check of a saved input: its component axis must equal the registered package
     * axis. No legacy aliases, additive padding or saved-axis migration.
     */
    public static V3ColumnInput requireCurrent(V3ColumnInput input, MaterialCatalog catalog) {
        if (V3HollandExample32.isPackage(input.packageId())) return input;
        if (!catalog.requirePackage(input.packageId()).components().equals(input.componentBasis().componentIds()))
            throw new IllegalArgumentException("Unsupported persisted component axis");
        return input;
    }

    private static V3ColumnInput freshInput() {
        var catalog=com.wormzjl.icheme.science.material.MaterialRuntime.current();
        return catalog.presets().column(catalog.presets().network().defaultColumnPreset()).input(catalog);
    }

    private static CompoundTag writeInput(V3ColumnInput input) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Schema", input.schemaVersion());
        tag.putString("Package", input.packageId());
        tag.putString("Assay", input.assayId());
        ListTag axis = new ListTag();
        for (String componentId : input.componentBasis().componentIds()) axis.add(StringTag.valueOf(componentId));
        tag.put("Axis", axis);
        ListTag feedFlows = new ListTag();
        for (double flow : input.feedComponentMolarFlowsMolPerSecond()) feedFlows.add(DoubleTag.valueOf(flow));
        tag.put("FeedFlows", feedFlows);
        tag.putDouble("FeedTemperature", input.feedTemperatureKelvin());
        tag.putInt("StageCount", input.stageCount());
        tag.putInt("FeedStage", input.feedStageNumber());
        tag.putDouble("TopPressure", input.topPressurePascal());
        tag.putDouble("PressureDrop", input.stagePressureDropPascal());
        tag.putDouble("ColumnDiameter", input.columnDiameterMetres());
        ListTag specifications = new ListTag();
        for (V3ColumnSpecification specification : input.specifications()) {
            CompoundTag specificationTag = new CompoundTag();
            specificationTag.putString("Kind", specification.controlledQuantity().name());
            specificationTag.putDouble("Value", specificationValue(specification));
            specifications.add(specificationTag);
        }
        tag.put("Specifications", specifications);
        ListTag draws = new ListTag();
        for (V3SideDrawSpec draw : input.sideDraws()) {
            CompoundTag drawTag = new CompoundTag();
            drawTag.putInt("Stage", draw.trayNumber());
            drawTag.putDouble("Rate", draw.molarFlowMolPerSecond());
            draws.add(drawTag);
        }
        tag.put("SideDraws", draws);
        ListTag steamFeeds = new ListTag();
        for (V3SteamFeedSpec steam : input.steamFeeds()) {
            CompoundTag steamTag = new CompoundTag();
            steamTag.putInt("Stage", steam.stageNumber());
            steamTag.putDouble("Rate", steam.molarFlowMolPerSecond());
            steamTag.putDouble("Temperature", steam.temperatureKelvin());
            steamFeeds.add(steamTag);
        }
        tag.put("SteamFeeds", steamFeeds);
        ListTag pumparounds = new ListTag();
        for (V3PumparoundSpec pumparound : input.pumparounds()) {
            CompoundTag pumparoundTag = new CompoundTag();
            pumparoundTag.putInt("Return", pumparound.returnTray());
            pumparoundTag.putInt("Draw", pumparound.drawTray());
            // The signed duty is stored exactly as authored; a cooling pumparound stays negative.
            pumparoundTag.putDouble("DutyWatts", pumparound.dutyWatts());
            pumparoundTag.putString("Split", pumparound.split().name());
            pumparounds.add(pumparoundTag);
        }
        tag.put("Pumparounds", pumparounds);
        return tag;
    }

    private static V3ColumnInput readInput(CompoundTag tag) {
        if (!tag.contains("ColumnDiameter", Tag.TAG_DOUBLE)) throw new IllegalArgumentException("Missing column diameter");
        if (tag.getInt("Schema") != V3ColumnInput.SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported V3 input schema");
        }
        ListTag axisTag = tag.getList("Axis", Tag.TAG_STRING);
        if (axisTag.isEmpty() || axisTag.size() > V3ComponentBasis.MAX_COMPONENTS) {
            throw new IllegalArgumentException("Invalid V3 component axis");
        }
        List<String> axis = new ArrayList<>(axisTag.size());
        for (int index = 0; index < axisTag.size(); index++) axis.add(axisTag.getString(index));
        ListTag specificationTags = tag.getList("Specifications", Tag.TAG_COMPOUND);
        if (specificationTags.size() != 3) throw new IllegalArgumentException("Invalid V3 specification count");
        List<V3ColumnSpecification> specifications = new ArrayList<>(specificationTags.size());
        for (int index = 0; index < specificationTags.size(); index++) {
            CompoundTag specification = specificationTags.getCompound(index);
            specifications.add(specification(ControlledQuantity.valueOf(specification.getString("Kind")),
                    specification.getDouble("Value")));
        }
        ListTag flowTags = tag.getList("FeedFlows", Tag.TAG_DOUBLE);
        if (flowTags.size() != axis.size()) throw new IllegalArgumentException("Invalid V3 feed-flow axis");
        double[] feedFlows = new double[flowTags.size()];
        for (int index = 0; index < feedFlows.length; index++) feedFlows[index] = flowTags.getDouble(index);
        if (!tag.contains("SideDraws", Tag.TAG_LIST)) {
            throw new IllegalArgumentException("Invalid V3 side draw list");
        }
        ListTag drawTags = tag.getList("SideDraws", Tag.TAG_COMPOUND);
        if (tag.get("SideDraws") instanceof ListTag stored && !stored.isEmpty() && stored.getElementType() != Tag.TAG_COMPOUND) {
            throw new IllegalArgumentException("Invalid V3 side draw entries");
        }
        if (drawTags.size() > V3ColumnInput.MAX_SIDE_DRAWS) throw new IllegalArgumentException("Too many V3 side draws");
        List<V3SideDrawSpec> draws = new ArrayList<>(drawTags.size());
        for (int index = 0; index < drawTags.size(); index++) {
            CompoundTag draw = drawTags.getCompound(index);
            draws.add(new V3SideDrawSpec(draw.getInt("Stage"), draw.getDouble("Rate")));
        }
        if (!tag.contains("SteamFeeds", Tag.TAG_LIST)) {
            throw new IllegalArgumentException("Invalid V3 steam feed list");
        }
        ListTag steamTags = tag.getList("SteamFeeds", Tag.TAG_COMPOUND);
        if (tag.get("SteamFeeds") instanceof ListTag stored && !stored.isEmpty()
                && stored.getElementType() != Tag.TAG_COMPOUND) {
            throw new IllegalArgumentException("Invalid V3 steam feed entries");
        }
        if (steamTags.size() > V3ColumnInput.MAX_STEAM_FEEDS) throw new IllegalArgumentException("Too many V3 steam feeds");
        List<V3SteamFeedSpec> steamFeeds = new ArrayList<>(steamTags.size());
        for (int index = 0; index < steamTags.size(); index++) {
            CompoundTag steam = steamTags.getCompound(index);
            steamFeeds.add(new V3SteamFeedSpec(steam.getInt("Stage"), steam.getDouble("Rate"),
                    steam.getDouble("Temperature")));
        }
        if (!tag.contains("Pumparounds", Tag.TAG_LIST)) {
            throw new IllegalArgumentException("Invalid V3 pumparound list");
        }
        ListTag pumparoundTags = tag.getList("Pumparounds", Tag.TAG_COMPOUND);
        if (tag.get("Pumparounds") instanceof ListTag stored && !stored.isEmpty()
                && stored.getElementType() != Tag.TAG_COMPOUND) {
            throw new IllegalArgumentException("Invalid V3 pumparound entries");
        }
        if (pumparoundTags.size() > V3ColumnInput.MAX_PUMPAROUNDS) {
            throw new IllegalArgumentException("Too many V3 pumparounds");
        }
        List<V3PumparoundSpec> pumparounds = new ArrayList<>(pumparoundTags.size());
        for (int index = 0; index < pumparoundTags.size(); index++) {
            CompoundTag pumparound = pumparoundTags.getCompound(index);
            pumparounds.add(new V3PumparoundSpec(pumparound.getInt("Return"), pumparound.getInt("Draw"),
                    pumparound.getDouble("DutyWatts"), V3PumparoundSpec.Split.valueOf(pumparound.getString("Split"))));
        }
        V3ColumnInput input = new V3ColumnInput(
                tag.getInt("Schema"), tag.getString("Package"), tag.getString("Assay"), new V3ComponentBasis(axis),
                feedFlows, tag.getDouble("FeedTemperature"), tag.getInt("StageCount"),
                tag.getInt("FeedStage"), tag.getDouble("TopPressure"), tag.getDouble("PressureDrop"), specifications, draws,
                steamFeeds, pumparounds,
                tag.getDouble("ColumnDiameter"));
        if(com.wormzjl.icheme.science.material.MaterialRuntime.current().packages().containsKey(input.packageId())) {
            var current=com.wormzjl.icheme.science.material.MaterialRuntime.current().requirePackage(input.packageId());
            if(!current.components().equals(input.componentBasis().componentIds()))throw new IllegalArgumentException("Unsupported persisted component axis");
        }
        V3ColumnProblemResolver.validateInput(input);
        return input;
    }

    private static CompoundTag writeDisplayResult(V3ColumnDisplayResult result) {
        CompoundTag tag = new CompoundTag();
        tag.putByteArray("Inspection", com.wormzjl.icheme.network.V3InspectionCodec.encode(result.inspection()));
        tag.putString("Digest", result.inputDigest());
        tag.putString("Formulation", result.formulationRevision());
        tag.putString("Assumptions", result.assumptionsRevision());
        tag.putString("Dataset", result.datasetRevision());
        tag.putInt("NewtonIterations", result.newtonIterations());
        tag.putDouble("MaximumResidual", result.maximumScaledResidual());
        tag.putInt("AcceptanceChecks", result.acceptanceCheckCount());
        ListTag streams = new ListTag();
        for (V3ColumnStreamProperties stream : result.streams()) {
            CompoundTag streamTag = new CompoundTag();
            streamTag.putString("Id", stream.streamId());
            streamTag.putString("Name", stream.displayName());
            streamTag.putString("Phase", stream.phase());
            streamTag.putDouble("Flow", stream.molarFlowMolPerSecond());
            streamTag.putDouble("MassFlow", stream.massFlowKgPerSecond());
            streamTag.putDouble("Temperature", stream.temperatureKelvin());
            streamTag.putDouble("Pressure", stream.pressurePascal());
            streamTag.putDouble("VaporMoleFraction", stream.vaporMoleFraction());
            ListTag composition = new ListTag();
            for (V3ColumnStreamProperties.ComponentFraction fraction : stream.moleFractions()) {
                CompoundTag fractionTag = new CompoundTag();
                fractionTag.putString("Component", fraction.componentId());
                fractionTag.putDouble("Fraction", fraction.moleFraction());
                fractionTag.putDouble("MassFraction", fraction.massFraction());
                composition.add(fractionTag);
            }
            streamTag.put("Composition", composition);
            streams.add(streamTag);
        }
        tag.put("Streams", streams);
        result.dutyLedger().ifPresent(ledger -> tag.put("DutyLedger", writeDutyLedger(ledger)));
        tag.putDouble("ClosureTolerance", result.closureTolerance());
        result.trayHydraulics().ifPresent(hydraulics -> tag.put("Hydraulics", writeTrayHydraulics(hydraulics)));
        return tag;
    }

    /** Trailing optional block, like the duty ledger: a result without tray hydraulics simply omits it. */
    private static CompoundTag writeTrayHydraulics(V3TrayHydraulicsSummary hydraulics) {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("Diameter", hydraulics.columnDiameterMetres());
        tag.putDouble("TotalDrop", hydraulics.totalPressureDropPascal());
        tag.putDouble("MeanTrayDrop", hydraulics.meanTrayPressureDropPascal());
        tag.putDouble("MaximumFlood", hydraulics.maximumFloodFraction());
        tag.putInt("MaximumFloodTray", hydraulics.maximumFloodTray());
        tag.putBoolean("CorrectionApplied", hydraulics.correctionApplied());
        tag.putDouble("ResidualMismatch", hydraulics.residualMismatchFraction());
        tag.putDouble("WorstTrayDry", hydraulics.worstTrayDryPascal());
        tag.putDouble("WorstTrayLiquid", hydraulics.worstTrayLiquidPascal());
        return tag;
    }

    private static V3TrayHydraulicsSummary readTrayHydraulics(CompoundTag tag) {
        return new V3TrayHydraulicsSummary(tag.getDouble("Diameter"), tag.getDouble("TotalDrop"),
                tag.getDouble("MeanTrayDrop"), tag.getDouble("MaximumFlood"), tag.getInt("MaximumFloodTray"),
                tag.getBoolean("CorrectionApplied"), tag.getDouble("ResidualMismatch"),
                tag.getDouble("WorstTrayDry"), tag.getDouble("WorstTrayLiquid"));
    }

    /** Signed duties, positive into the column, are stored exactly as calculated. */
    private static CompoundTag writeDutyLedger(V3ColumnDutyLedger ledger) {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("Condenser", ledger.condenserWatts());
        tag.putDouble("Reboiler", ledger.reboilerWatts());
        tag.putDouble("StageHeatTotal", ledger.stageHeatTotalWatts());
        tag.putDouble("FeedEnthalpy", ledger.feedEnthalpyWatts());
        tag.putDouble("SteamEnthalpy", ledger.steamEnthalpyWatts());
        ListTag stageDuties = new ListTag();
        for (V3ColumnDutyLedger.StageDuty duty : ledger.stageDuties()) {
            CompoundTag dutyTag = new CompoundTag();
            dutyTag.putInt("Tray", duty.trayNumber());
            dutyTag.putDouble("DutyWatts", duty.dutyWatts());
            stageDuties.add(dutyTag);
        }
        tag.put("StageDuties", stageDuties);
        return tag;
    }

    private static V3ColumnDutyLedger readDutyLedger(CompoundTag tag) {
        if (tag.contains("StageDuties") && !tag.contains("StageDuties", Tag.TAG_LIST)) {
            throw new IllegalArgumentException("Invalid V3 stage duty list");
        }
        ListTag dutyTags = tag.getList("StageDuties", Tag.TAG_COMPOUND);
        if (tag.get("StageDuties") instanceof ListTag stored && !stored.isEmpty()
                && stored.getElementType() != Tag.TAG_COMPOUND) {
            throw new IllegalArgumentException("Invalid V3 stage duty entries");
        }
        if (dutyTags.size() > V3ColumnDutyLedger.MAX_STAGE_DUTIES) {
            throw new IllegalArgumentException("Persisted V3 stage duty count exceeds the display contract");
        }
        List<V3ColumnDutyLedger.StageDuty> duties = new ArrayList<>(dutyTags.size());
        for (int index = 0; index < dutyTags.size(); index++) {
            CompoundTag dutyTag = dutyTags.getCompound(index);
            duties.add(new V3ColumnDutyLedger.StageDuty(dutyTag.getInt("Tray"), dutyTag.getDouble("DutyWatts")));
        }
        return new V3ColumnDutyLedger(tag.getDouble("Condenser"), tag.getDouble("Reboiler"),
                tag.getDouble("StageHeatTotal"), duties, tag.getDouble("FeedEnthalpy"),
                tag.getDouble("SteamEnthalpy"));
    }

    private static V3ColumnDisplayResult readDisplayResult(CompoundTag tag) {
        if (!tag.contains("Inspection", Tag.TAG_BYTE_ARRAY)) throw new IllegalArgumentException("Missing inspection");
        java.util.Optional<com.wormzjl.icheme.science.column.v3.V3ColumnInspection> inspection;
        try { inspection = com.wormzjl.icheme.network.V3InspectionCodec.decode(tag.getByteArray("Inspection")); }
        catch (io.netty.handler.codec.DecoderException invalid) { throw new IllegalArgumentException("Invalid saved inspection", invalid); }
        ListTag streamTags = tag.getList("Streams", Tag.TAG_COMPOUND);
        if (streamTags.size() > V3ColumnStreamProperties.MAX_STREAMS) {
            throw new IllegalArgumentException("Persisted V3 stream count exceeds the display contract");
        }
        List<V3ColumnStreamProperties> streams = new ArrayList<>(streamTags.size());
        for (int streamIndex = 0; streamIndex < streamTags.size(); streamIndex++) {
            CompoundTag streamTag = streamTags.getCompound(streamIndex);
            ListTag fractionTags = streamTag.getList("Composition", Tag.TAG_COMPOUND);
            if (fractionTags.isEmpty() || fractionTags.size() > V3ColumnStreamProperties.MAX_COMPONENTS) {
                throw new IllegalArgumentException("Persisted V3 stream composition exceeds the display contract");
            }
            List<V3ColumnStreamProperties.ComponentFraction> fractions = new ArrayList<>(fractionTags.size());
            for (int fractionIndex = 0; fractionIndex < fractionTags.size(); fractionIndex++) {
                CompoundTag fractionTag = fractionTags.getCompound(fractionIndex);
                fractions.add(new V3ColumnStreamProperties.ComponentFraction(
                        fractionTag.getString("Component"), fractionTag.getDouble("Fraction"),
                        fractionTag.getDouble("MassFraction")));
            }
            streams.add(new V3ColumnStreamProperties(
                    streamTag.getString("Id"), streamTag.getString("Name"), streamTag.getString("Phase"),
                    streamTag.getDouble("Flow"), streamTag.getDouble("MassFlow"), streamTag.getDouble("Temperature"),
                    streamTag.getDouble("Pressure"), streamTag.getDouble("VaporMoleFraction"), fractions));
        }
        if (tag.contains("DutyLedger") && !tag.contains("DutyLedger", Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Invalid V3 duty ledger");
        }
        Optional<V3ColumnDutyLedger> ledger = tag.contains("DutyLedger", Tag.TAG_COMPOUND)
                ? Optional.of(readDutyLedger(tag.getCompound("DutyLedger"))) : Optional.empty();
        if (!tag.contains("ClosureTolerance", Tag.TAG_DOUBLE)) throw new IllegalArgumentException("Missing closure tolerance");
        double closure = tag.getDouble("ClosureTolerance");
        if (tag.contains("Hydraulics") && !tag.contains("Hydraulics", Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Invalid V3 tray hydraulics summary");
        }
        Optional<V3TrayHydraulicsSummary> hydraulics = tag.contains("Hydraulics", Tag.TAG_COMPOUND)
                ? Optional.of(readTrayHydraulics(tag.getCompound("Hydraulics"))) : Optional.empty();
        return new V3ColumnDisplayResult(
                tag.getString("Digest"), tag.getString("Formulation"), tag.getString("Assumptions"),
                tag.getString("Dataset"), tag.getInt("NewtonIterations"), tag.getDouble("MaximumResidual"),
                tag.getInt("AcceptanceChecks"), streams, ledger, closure, hydraulics, inspection);
    }

    private static V3ColumnSpecification specification(ControlledQuantity quantity, double value) {
        return switch (quantity) {
            case CONDENSER_OUTLET_TEMPERATURE -> new V3ColumnSpecification.CondenserOutletTemperature(value);
            case ORGANIC_REFLUX_RATIO -> new V3ColumnSpecification.OrganicRefluxRatio(value);
            case REBOILER_DUTY -> new V3ColumnSpecification.ReboilerDuty(value);
        };
    }

    private static double specificationValue(V3ColumnSpecification specification) {
        return switch (specification) {
            case V3ColumnSpecification.CondenserOutletTemperature temperature -> temperature.kelvin();
            case V3ColumnSpecification.OrganicRefluxRatio reflux -> reflux.ratio();
            case V3ColumnSpecification.ReboilerDuty duty -> duty.watts();
        };
    }

    private static long nonNegative(long value) {
        if (value < 0L) throw new IllegalArgumentException("V3 persisted revision is negative");
        return value;
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) return "V3 operation did not provide a detail";
        return value.length() <= 256 ? value : value.substring(0, 256);
    }
}
