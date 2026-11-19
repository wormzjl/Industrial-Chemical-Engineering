package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.runtime.ProcessSolveJobs;
import com.wormzjl.icheme.runtime.fluid.IslandCoordinator.Advance;
import com.wormzjl.icheme.runtime.fluid.IslandCoordinator.Attempt;
import com.wormzjl.icheme.runtime.fluid.IslandCoordinator.Evidence;
import com.wormzjl.icheme.runtime.fluid.IslandCoordinator.Island;
import com.wormzjl.icheme.runtime.fluid.IslandCoordinator.Metrics;
import com.wormzjl.icheme.runtime.fluid.IslandCoordinator.Snapshot;
import com.wormzjl.icheme.science.fluid.SimulationTime;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * An island at rest (plan sections 3.2 and 3.3), moved from {@link IslandCoordinator} unchanged (codebase review R13):
 * the entry evidence after each round's close and the certificate it issues, the materialisation of a certified island
 * by the replay of its certificate (with the horizon's revalidation), a saved certificate's restore at registration,
 * and the certified status line. The certificate itself, its policy and its replay arithmetic are
 * {@link IslandCertificate} and {@link CertificatePolicy}; the certificate state is the island's. Runs on the
 * coordinator's thread only, from the coordinator's steps and its parts'.
 */
final class Certificates {
    private final IslandCoordinator coordinator;
    Certificates(IslandCoordinator coordinator){this.coordinator=coordinator;}

    /**
     * Advances a certified island to {@code target = min(online, nearest fence, hold tick, horizon)} and acts on
     * what it reached: the horizon wakes it to solve one revalidating slice. On the engine's demand only: a fence or event
     * alignment, a read of its snapshot (a save), a control, shaft or port change's wake, its own deadline, a property hold
     * ({@link IslandCoordinator#through}, a coupled side's read and the dated wakes materialise to their tick: {@link
     * #materialise(Island,long)}); never a view
     * (a presentation read takes a {@link IslandCoordinator#presentation preview}). Each advance is reported to the replay listener with
     * the replayed span as the island's last result, so boundary and pump accounting stays exact.
     */
    void materialise(Island island){materialise(island,Long.MAX_VALUE);}
    /**
     * As {@link #materialise(Island)}, but not past {@code cap}: a wake at a fixed tick of the world's ({@link
     * IslandCoordinator#movedAt}) replays to that tick however late it is acted on (codebase review R-ST3), and so do a
     * running column's wait ({@link IslandCoordinator#through}) and a coupled side's read at its slice start (R-ST4).
     */
    void materialise(Island island,long cap) {
        var certificate=island.certificate;if(certificate==null)return;
        long committed=island.clock.committedTick(),online=island.clock.onlineTick();
        long wake=certificate.horizonTick();
        long target=Math.min(Math.min(Math.min(online,cap),island.fence()),Math.min(island.holdTick,wake));
        target=Math.min(target,coordinator.coupling.replayThrough(island.id));
        if(target>committed) {
            // The island keeps its last solved interval as its result (what a save records and a view reads, whose
            // rates replay repeats); the replayed span with its scaled accounting goes to the replay listener only.
            var replay=certificate.replay(committed,target);
            // The replayed graph is the certificate's; the taps are the island's (a restored certificate's graph has none).
            island.clock.rest(target,island.fence());island.graph=IslandCoordinator.observedAs(replay.graph(),IslandCoordinator.observationPoints(island.graph));
            island.pipeSpeed.accepted(committed,target,replay.pipeTransfers());
            coordinator.coupling.booked(island,committed,target,replay,null);
            island.metrics=new Metrics(island.metrics==null?1:Math.addExact(island.metrics.sequence(),1),committed,target,online,online,-1,-1,0,true,Advance.REPLAYED);
            FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.materialisations);
            FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.replayedTicks,target-committed);
            if(coordinator.suspension==null)island.status=certifiedStatus(island);
            // A persisted certificate records its base. A dated dependency limit omits that certificate and retains
            // the materialized state, so the checkpoint then needs a new payload whenever replay advances it.
            if(target==island.fence()||!island.savesCertificate())island.touch();
            var stored=island.snapshot();
            coordinator.replayed.published(List.of(new Snapshot(stored.id(),stored.revision(),stored.graph(),stored.clock(),Optional.of(replay),stored.status(),stored.fences(),stored.certificate(),stored.payloadGeneration())));
        }
        if(coordinator.stopped||coordinator.suspension!=null)return;
        if(target==wake)revalidate(island,certificate);
    }
    /**
     * Ends a certified island's certificate where it stands (its committed tick) because something it solves against changed:
     * fresh solver caches, no evidence, awake from there. The coupling's wakes and {@link IslandCoordinator#movedAt}.
     */
    void end(Island island) {
        island.certificate=null;island.revalidating=null;island.certifiedSince=-1;island.signature=null;island.retained=new RetainedSolver();
        island.lastInterval=null;island.streak=0;island.status="WAITING: an exchanger's other side changed";island.touch();
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.couplingWakes);coordinator.reconsider(island);
    }
    /** A new dependency plan supersedes the previous plan's qualifying intervals and any horizon comparison. */
    void inputsChanged(Island island) {
        if(island.revalidating!=null)island.certifiedSince=-1;
        island.revalidating=null;island.lastInterval=null;island.streak=0;island.evidence=null;
    }
    /** Drop only evidence from before a change that the island has now reached. */
    void inputsChangedAt(Island island,long tick) {
        if(island.revalidating!=null&&island.revalidating.interval().startTick()<tick
                ||island.lastInterval!=null&&island.lastInterval.startTick<tick)inputsChanged(island);
    }
    /** Ends a certificate at its horizon: fresh solver caches, no evidence; the island keeps the certificate its first
     * slice is compared with. */
    private void revalidate(Island island,IslandCertificate certificate) {
        island.certificate=null;island.revalidating=certificate;island.retained=new RetainedSolver();
        island.lastInterval=null;island.streak=0;
        island.status="REVALIDATING: certificate horizon at "+SimulationTime.playSeconds(certificate.horizonTick())+" s of play";island.signature=null;island.touch();FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.certificateWakes);
        coordinator.reconsider(island);
    }
    /**
     * The entry evidence of plan section 3.3, evaluated after each round closure on the server thread. An
     * accepted interval extends the stationary streak
     * when it repeats the previous interval within the tolerance, or starts a new one; once the streak reaches the
     * confirm count, the last interval is certified. A horizon's
     * revalidating slice renews the certificate from its solved state if it repeats the certified interval, and
     * otherwise starts a fresh streak.
     */
    void qualify(Island island,ProcessSolveJobs.FluidIslandSolveResult accepted,PassiveNetwork before,Attempt attempt,boolean currentInputs) {
        var revalidation=island.revalidating;island.revalidating=null;var policy=coordinator.settings.certificates();
        boolean usable=policy.enabled()&&!coordinator.stopped&&coordinator.suspension==null&&accepted!=null;
        if(!usable) {
            island.lastInterval=null;island.streak=0;island.evidence=null;if(revalidation!=null)island.certifiedSince=-1;
            island.refusal=!policy.enabled()?null:accepted==null?"no accepted interval":"stopped or suspended";return;
        }
        if(!currentInputs) {
            inputsChanged(island);
            if(revalidation!=null)island.certifiedSince=-1;
            island.refusal="dependencies changed during the interval";
            return;
        }
        var summary=new IslandCertificate.Summary(new IslandCertificate.Interval(attempt.slice().startTick(),attempt.slice().endTick(),before,accepted.candidate().orElseThrow()),island.model.molecularWeights(),island.model.time());
        if(revalidation!=null) {
            var measured=IslandCertificate.stationarity(revalidation.summary(),summary);
            island.evidence=new Evidence(summary.endTick,measured,summary.drift());
            String refusal=measured.refusal(policy.stationaryTolerance());
            if(refusal==null) {
                var issued=IslandCertificate.issue(summary,policy);
                if(issued.certificate()!=null){certify(island,issued.certificate());FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.certificatesRenewed);return;}
                refusal=issued.refusal();
            }
            island.certifiedSince=-1;island.lastInterval=summary;island.streak=1;island.refusal="revalidation: "+refusal;return;
        }
        var previous=island.lastInterval;
        var measured=previous==null||previous.endTick!=summary.startTick?null:IslandCertificate.stationarity(previous,summary);
        island.evidence=new Evidence(summary.endTick,measured,summary.drift());
        String refusal=previous==null?"first qualifying interval":measured==null?"not consecutive with the last one":measured.refusal(policy.stationaryTolerance());
        island.streak=refusal==null?island.streak+1:1;island.lastInterval=summary;
        int needed=summary.exactZero?policy.confirmIntervals():Math.max(2,policy.confirmIntervals());
        if(island.streak<needed){island.refusal=refusal!=null?refusal:"stationary for "+island.streak+" of "+needed+" intervals";return;}
        var issued=IslandCertificate.issue(summary,policy);
        if(issued.certificate()!=null){island.certifiedSince=-1;certify(island,issued.certificate());}
        else island.refusal=issued.refusal();
    }
    private void certify(Island island,IslandCertificate certificate) {
        island.certificate=certificate;if(island.certifiedSince<0)island.certifiedSince=certificate.baseTick();
        island.signature=IslandCertificate.Signature.of(island.model,coordinator.settings.certificates());island.touch();
        island.lastInterval=null;island.streak=0;island.refusal=null;
        // Only here, after the terminal completion that produced the qualifying interval, so no worker owns the caches.
        island.retained=null;island.status=certifiedStatus(island);FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.certificatesIssued);
    }
    /**
     * A saved certificate (plan section 3.5). The snapshot's graph is already its island at the saved committed tick,
     * materialised from the base without a solve. Restored when certificates are on and its signature is the island's
     * current one: the island is certified again with its saved since tick, keeps no solver caches, and holds only its
     * horizon deadline. Otherwise the certificate is discarded and the island starts awake from that
     * inventory, to solve its debt and qualify again.
     */
    void restore(Island island,IslandCertificate.Saved saved) {
        var restored=IslandCertificate.restore(saved,island.model,coordinator.settings.certificates());
        if(restored.certificate()!=null) {
            island.certificate=restored.certificate();island.certifiedSince=saved.sinceTick();island.signature=saved.signature();island.retained=null;
            FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.certificatesRestored);
        } else {
            // Awake now, with a new status: its unit records neither the certificate nor that status, so it is written afresh.
            island.status="WAITING: saved certificate discarded: "+restored.discarded();island.refusal="saved certificate discarded: "+restored.discarded();island.touch();
            FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.certificatesDiscarded);
        }
    }
    static String certifiedStatus(Island island) {
        var certificate=island.certificate;double since=SimulationTime.playSeconds(island.certifiedSince);
        // One certificate kind (BE_INTEGRATOR_PLAN.md section 4): the line names what it replays, and its recheck if it has one.
        String what=certificate.largestFlow()==0?"no flow":String.format(Locale.ROOT,"replaying %.4g kg/s",certificate.largestFlow());
        String next=certificate.horizonTick()==Long.MAX_VALUE?"":String.format(Locale.ROOT,", next check at %.1f s of play",SimulationTime.playSeconds(certificate.horizonTick()));
        return String.format(Locale.ROOT,"STEADY: %s since %.1f s of play%s",what,since,next)+island.notice;
    }
}
