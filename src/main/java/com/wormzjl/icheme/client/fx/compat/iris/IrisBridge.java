package com.wormzjl.icheme.client.fx.compat.iris;

import com.wormzjl.icheme.client.fx.FxCompat;
import com.wormzjl.icheme.client.fx.FxGuard;
import net.irisshaders.iris.api.v0.IrisApi;

/**
 * The one class that names Iris types (CUSTOM_SHADER_SYSTEM_PLAN.md section 4.8). Loaded by name through
 * {@link FxCompat#load} only when Iris is present. Every call goes through one {@link FxGuard}: the first
 * {@code LinkageError} or {@code RuntimeException} turns the integration off for the session, after which
 * {@link #known()} is false and the effects take the COMPATIBLE path.
 */
public final class IrisBridge implements FxCompat.Iris {
    /**
     * The v0 API's minor revision this build compiles against: Iris 1.8.12 for 1.21.1 returns 2 from
     * {@code IrisApiV0Impl.getMinorApiRevision()} (IrisShaders/Iris, branch 1.21.1, read 2026-11-10). An older Iris may
     * lack a method we call.
     */
    public static final int MIN_MINOR_REVISION = 2;

    private final FxGuard guard = new FxGuard("Iris", () -> FxCompat.version(FxCompat.IRIS));

    public IrisBridge() {
    }

    private int revision() {
        return guard.call(() -> IrisApi.getInstance().getMinorApiRevision(), -1);
    }

    @Override
    public boolean compatible() {
        // FxCompat.load logs a refusal with status(), so the gate itself does not log.
        return revision() >= MIN_MINOR_REVISION && !guard.disabled();
    }

    @Override
    public boolean packInUse() {
        return guard.test(() -> IrisApi.getInstance().isShaderPackInUse(), false);
    }

    @Override
    public boolean shadowPass() {
        return guard.test(() -> IrisApi.getInstance().isRenderingShadowPass(), false);
    }

    @Override
    public boolean known() {
        return !guard.disabled();
    }

    @Override
    public String status() {
        String base = "Iris " + FxCompat.version(FxCompat.IRIS) + ", API v0 revision " + revision();
        return guard.disabled() ? base + ", off: " + guard.failure() : base;
    }
}
