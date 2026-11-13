package com.wormzjl.icheme.science.thermo;

/**
 * A trial state the thermodynamics refuses: a state outside the numerical box or the model's own range, a temperature,
 * pressure or composition no model takes, or a model that fails at the state (a non-finite interaction or property, a
 * mechanically unstable root, a non-finite Gibbs energy of a trial split). A numerical guard: the solver that formed the
 * trial rejects it and backtracks, and the equilibrium engine answers a refusal that reaches it as a refused evaluation;
 * it is never a hold. The one refusal type of the kernel and the engine (batch 2026-11-13-codebase-review, S07's Java
 * part). An argument, layout or invariant failure stays a plain JDK exception: it is a defect, not a refusal.
 *
 * <p>It is an {@link IllegalArgumentException}, the type these sites threw before, so every caller that catches them
 * catches it unchanged; its message is the text they formatted. The kernel's two refusals of another type keep it: the
 * missing physical root ({@link PengRobinsonKernel.NoPhysicalRoot}) and the root too close to coalescence to
 * differentiate ({@link PengRobinsonKernel.RootCoalescence}) are {@link IllegalStateException}s, which callers outside the thermodynamics tell apart from this one.</p>
 *
 * <p>It carries no stack trace ({@link #fillInStackTrace()} records none): a refusal is an expected outcome of a trial,
 * thrown and caught within one solve. A message with numbers in it keeps its parts and is built when it is first read.</p>
 */
public final class ThermoRefusal extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    private static final int FIXED = 0, ONE = 1, TWO = 2, PAIR = 3;

    private final int form;
    private final String head, middle, tail, last;
    private final double first, second;
    private final int firstIndex, secondIndex;
    private String message;

    /** A refusal with a fixed message. */
    public ThermoRefusal(String message) {
        this(FIXED, message, null, null, null, Double.NaN, Double.NaN, 0, 0);
    }

    /** A refusal whose message is {@code head + value + tail}. */
    public ThermoRefusal(String head, double value, String tail) {
        this(ONE, head, null, tail, null, value, Double.NaN, 0, 0);
    }

    /** A refusal whose message is {@code head + first + middle + second + tail}. */
    public ThermoRefusal(String head, double first, String middle, double second, String tail) {
        this(TWO, head, middle, tail, null, first, second, 0, 0);
    }

    /** A refusal whose message is {@code head + firstIndex + middle + secondIndex + tail + value + last}. */
    public ThermoRefusal(String head, int firstIndex, String middle, int secondIndex, String tail, double value, String last) {
        this(PAIR, head, middle, tail, last, value, Double.NaN, firstIndex, secondIndex);
    }

    private ThermoRefusal(int form, String head, String middle, String tail, String last, double first, double second,
                          int firstIndex, int secondIndex) {
        super();
        this.form = form;
        this.head = head;
        this.middle = middle;
        this.tail = tail;
        this.last = last;
        this.first = first;
        this.second = second;
        this.firstIndex = firstIndex;
        this.secondIndex = secondIndex;
        if (form == FIXED) message = head;
    }

    /** The message, built from its parts on the first read with the same string conversions as a concatenation. */
    @Override
    public String getMessage() {
        String built = message;
        if (built == null) {
            built = switch (form) {
                case ONE -> head + first + tail;
                case TWO -> head + first + middle + second + tail;
                case PAIR -> head + firstIndex + middle + secondIndex + tail + first + last;
                default -> head;
            };
            message = built;
        }
        return built;
    }

    /** Records no stack trace: see the class comment. */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
