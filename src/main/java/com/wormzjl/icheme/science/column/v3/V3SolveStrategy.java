package com.wormzjl.icheme.science.column.v3;

/**
 * Warm-start policies for repeated column requests.
 *
 * <p>{@link #DRIFT_SPEC} follows the conservative specification drift route: the first cold solve keeps the
 * authored reboiler duty as its heat anchor and feature ramps introduce product rates afterwards. A compatible
 * accepted state is reused on the next request. {@link #AGGRESSIVE} tries the same warm state directly with a small
 * local-block correction and only falls back to the complete continuation chain when the strict audit rejects it.</p>
 */
public enum V3SolveStrategy {
    DRIFT_SPEC,
    AGGRESSIVE
}
