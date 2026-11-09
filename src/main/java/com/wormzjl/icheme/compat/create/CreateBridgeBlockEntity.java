package com.wormzjl.icheme.compat.create;

import com.simibubi.create.content.kinetics.base.*;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.runtime.fluid.*;
import com.wormzjl.icheme.science.machine.CreateCoupling;
import com.wormzjl.icheme.world.level.block.*;

import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Native Create endpoint. Kinetic callbacks queue observations; engine decisions publish finite
 * shaft capacity.
 */
public abstract class CreateBridgeBlockEntity extends GeneratingKineticBlockEntity
        implements ShaftParts.Coupling {
    private float impact, generated, supplied;
    private double sentRpm = Double.NaN,
            sentStress = Double.NaN,
            sentDemand = Double.NaN,
            sentActual = Double.NaN;
    private boolean sentConnection;

    protected CreateBridgeBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    protected abstract boolean connected();

    @Override
    public double nativeSpeed() {
        return Math.abs(getSpeed());
    }

    @Override
    public float getGeneratedSpeed() {
        return generated;
    }

    @Override
    public float calculateAddedStressCapacity() {
        lastCapacityProvided = supplied;
        return supplied;
    }

    @Override
    public float calculateStressApplied() {
        lastStressApplied = impact;
        return impact;
    }

    @Override
    public void initialize() {
        super.initialize();
        report();
    }

    @Override
    public void onSpeedChanged(float previous) {
        super.onSpeedChanged(previous);
        report();
    }

    @Override
    public void updateFromNetwork(float maximum, float used, int members) {
        super.updateFromNetwork(maximum, used, members);
        report();
    }

    public final void connectionsChanged() {
        report();
    }

    @Override
    public void onChunkUnloaded() {
        output(0, 0);
        super.onChunkUnloaded();
        queue(0, 0, 0, false, 0);
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean packet) {
        super.write(tag, registries, packet);
        tag.putFloat("EngineSpeed", generated);
        tag.putFloat("EngineCapacity", supplied);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean packet) {
        super.read(tag, registries, packet);
        impact = Math.max(0, lastStressApplied);
        if (packet) {
            generated = tag.getFloat("EngineSpeed");
            supplied = tag.getFloat("EngineCapacity");
        }
    }

    private double watts() {
        return CreateCoupling.wattsPerStress(IChemE.joulesPerFE(), IChemE.stressPerFE());
    }

    private void queue(double rpm, double spare, double demand, boolean connected, double actual) {
        if (level instanceof ServerLevel server)
            FluidWorldAuthority.find(server.getServer())
                    .ifPresent(
                            w ->
                                    w.shafts()
                                            .queue(
                                                    new ShaftTrains.CreateInput(
                                                            new PhysicalFluidTopology.Position(
                                                                    level.dimension()
                                                                            .location()
                                                                            .toString(),
                                                                    worldPosition.getX(),
                                                                    worldPosition.getY(),
                                                                    worldPosition.getZ()),
                                                            rpm,
                                                            spare,
                                                            demand,
                                                            connected,
                                                            actual)));
    }

    private void report() {
        if (level == null || level.isClientSide()) return;
        boolean link = connected();
        double rpm = Math.abs(getSpeed()), theoretical = Math.abs(getTheoreticalSpeed());
        double spare =
                !IRotate.StressImpact.isEnabled()
                        ? CreateCoupling.UNLIMITED_STRESS
                        : hasNetwork()
                                ? Math.max(0, (double) capacity - stress + impact * theoretical)
                                : 0;
        // Create shares a network's demand across its active sources in proportion to their
        // capacities.
        double contribution = supplied * Math.abs(generated),
                share = capacity > 0 ? Math.min(1, contribution / capacity) : hasSource() ? 0 : 1;
        double demand =
                generated != 0 ? Math.max(0, stress - impact * theoretical) * share * watts() : 0;
        double actual = rpm > 0 ? demand : 0;
        if (Math.abs(actual - sentActual) <= Math.max(1, Math.abs(sentActual)) * .001
                && !CreateCoupling.moved(sentRpm, sentStress, rpm, spare)
                && Math.abs(demand - sentDemand) <= Math.max(1, Math.abs(sentDemand)) * .001
                && link == sentConnection) return;
        sentRpm = rpm;
        sentStress = spare;
        sentDemand = demand;
        sentActual = actual;
        sentConnection = link;
        queue(rpm, spare, demand, link, actual);
    }

    @Override
    public void stressImpact(double suPerRpm) {
        if (level == null || level.isClientSide()) return;
        double next = Math.max(0, suPerRpm), rpm = Math.abs(getTheoreticalSpeed());
        if (rpm > 0 && IRotate.StressImpact.isEnabled() && hasNetwork())
            next =
                    Math.min(
                            next,
                            Math.max(
                                    0,
                                    ((double) capacity - stress) / rpm * CreateCoupling.SPARE_SHARE
                                            + impact));
        if ((float) next == impact) return;
        impact = (float) next;
        lastStressApplied = impact;
        if (hasNetwork()) getOrCreateNetwork().updateStressFor(this, impact);
        setChanged();
    }

    @Override
    public void output(double rpm, double power) {
        if (level == null || level.isClientSide()) return;
        float speed = rpm > 0 ? (float) rpm : 0;
        float available = speed != 0 ? (float) (power / (watts() * Math.abs(speed))) : 0;
        if (generated == speed && supplied == available) return;
        generated = speed;
        supplied = available;
        if (speed != 0) stressImpact(0);
        updateGeneratedRotation();
        report();
    }
}
