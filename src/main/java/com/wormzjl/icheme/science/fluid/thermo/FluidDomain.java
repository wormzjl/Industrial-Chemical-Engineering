package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.Objects;

/**
 * The data ranges of one property package as the fluid network reads them (owner rule of 2026-10-16: leaving a data
 * range never holds a state; every property continues past its data and a notice reports it). Nothing here refuses a
 * state the network evaluates: the only bound a trial meets is the numerical box
 * ({@link com.wormzjl.icheme.science.thermo.NumericalBox}).
 *
 * <p>The package's envelope is the data range of the package as a whole: the hull of its compounds' dataset
 * {@code fluid_domain} ranges and the water model's triple point, derived when the catalog loads. It is the input box a
 * device setting is checked against when the player sets it ({@link #requireInput}, decision 9 of the plan: an input
 * box, not a hold).</p>
 */
public final class FluidDomain {
    private final String packageId;
    private final MaterialCatalog.Validity envelope;

    private FluidDomain(String packageId,MaterialCatalog.Validity envelope) {
        this.packageId=packageId;this.envelope=envelope;
    }

    /** The data ranges of a package the fluid network is built on. A package with a compound whose dataset has no
     * {@code fluid_domain} has no envelope and is refused. */
    public static FluidDomain of(MaterialCatalog catalog,String packageId) {
        Objects.requireNonNull(catalog);Objects.requireNonNull(packageId);
        var envelope=catalog.fluidValidity(packageId).orElseThrow(()->new IllegalArgumentException(
                "Package "+packageId+" has a compound whose dataset declares no fluid_domain; the fluid network cannot read its data range"));
        return new FluidDomain(packageId,envelope);
    }

    public String packageId(){return packageId;}
    /** The package's envelope: the hull of its compounds' {@code fluid_domain} ranges. */
    public MaterialCatalog.Validity envelope(){return envelope;}

    /**
     * A device setting at {@code t} and {@code p} (a source's or a reservoir's temperature and pressure): refused with a
     * plain {@link IllegalArgumentException} outside the package envelope, when the player sets it. An input box, not a
     * hold: a state the network reaches by itself may leave the envelope.
     */
    public void requireInput(double t,double p) {
        if(!Double.isFinite(t)||!Double.isFinite(p))throw new IllegalArgumentException("Temperature and pressure must be finite");
        requireInputTemperature(t);
        requireInputPressure(p);
    }
    /** A temperature a device holds a fluid at (a fired heater's set point): the envelope's temperature range, as {@link #requireInput}. */
    public void requireInputTemperature(double t) {
        if(!Double.isFinite(t))throw new IllegalArgumentException("Temperature is not finite");
        if(t<envelope.minimumTemperature()||t>envelope.maximumTemperature())
            throw new IllegalArgumentException("Temperature "+t+" K outside the package envelope "+envelope.minimumTemperature()+".."
                    +envelope.maximumTemperature()+" K (package "+packageId+")");
    }
    /** A pressure a device holds a fluid at (a valve's regulated inlet): the envelope's pressure range, as {@link #requireInput}. */
    public void requireInputPressure(double p) {
        if(!Double.isFinite(p))throw new IllegalArgumentException("Pressure is not finite");
        if(p<envelope.minimumPressure()||p>envelope.maximumPressure())
            throw new IllegalArgumentException("Pressure "+p+" Pa outside the package envelope "+envelope.minimumPressure()+".."
                    +envelope.maximumPressure()+" Pa (package "+packageId+")");
    }
}
