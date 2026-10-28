#pragma once

#include <stddef.h>

#if defined(_WIN32)
#  if defined(ICHEME_COLUMN_SOLVER_BUILD)
#    define ICHEME_COLUMN_API __declspec(dllexport)
#  else
#    define ICHEME_COLUMN_API __declspec(dllimport)
#  endif
#else
#  define ICHEME_COLUMN_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

typedef enum icheme_column_status {
    ICHEME_COLUMN_CONVERGED = 0,
    ICHEME_COLUMN_MAX_ITERATIONS = 1,
    ICHEME_COLUMN_INVALID_INPUT = 2,
    ICHEME_COLUMN_SINGULAR = 3,
    ICHEME_COLUMN_CALLBACK_FAILURE = 4
} icheme_column_status;

typedef int (*icheme_column_residual_callback)(
    const double* variables,
    size_t variable_count,
    double* residuals,
    size_t residual_count,
    void* user_data);

typedef struct icheme_column_solver_options {
    size_t maximum_iterations;
    double residual_tolerance;
    double step_tolerance;
    double armijo;
} icheme_column_solver_options;

typedef struct icheme_column_solver_result {
    icheme_column_status status;
    size_t iterations;
    size_t residual_evaluations;
    double maximum_scaled_residual;
    double final_step_norm;
} icheme_column_solver_result;

/**
 * Solves a square residual system with damped Newton steps.
 *
 * The callback is called only from the native solve and must fill one residual per variable. The solver owns all
 * scratch storage and never starts a process. The ABI uses only fixed-width C data so the same library can be loaded
 * as a Windows DLL, Linux SO, or macOS DYLIB.
 */
ICHEME_COLUMN_API icheme_column_solver_result icheme_column_solve(
    double* variables,
    size_t variable_count,
    icheme_column_residual_callback callback,
    void* user_data,
    const icheme_column_solver_options* options);

/**
 * Evaluates classical Peng--Robinson phase properties for a batch of node states. Arrays are row-major by node and
 * component. A phase with a zero composition sum is skipped and receives NaN outputs. This is a migration seam for
 * the V3 inside-out outer loop; the strict Java property model remains the publication authority.
 */
ICHEME_COLUMN_API int icheme_column_pr_batch(
    size_t component_count,
    size_t node_count,
    const double* critical_temperatures_kelvin,
    const double* critical_pressures_pascal,
    const double* acentric_factors,
    const double* binary_interactions,
    const double* temperatures_kelvin,
    const double* pressures_pascal,
    const double* liquid_compositions,
    const double* vapor_compositions,
    double* liquid_log_fugacity_coefficients,
    double* vapor_log_fugacity_coefficients,
    double* liquid_molar_enthalpies_joules_per_mol,
    double* vapor_molar_enthalpies_joules_per_mol,
    double* liquid_compressibility,
    double* vapor_compressibility);

/**
 * Same batch as icheme_column_pr_batch, with a forward temperature derivative for each phase. The derivative
 * outputs are residual-property derivatives (the host adds its ideal-gas package contribution), and the
 * temperature step is chosen by the implementation to match the inside-out outer loop.
 */
ICHEME_COLUMN_API int icheme_column_pr_batch_temperature_derivatives(
    size_t component_count,
    size_t node_count,
    const double* critical_temperatures_kelvin,
    const double* critical_pressures_pascal,
    const double* acentric_factors,
    const double* binary_interactions,
    const double* temperatures_kelvin,
    const double* pressures_pascal,
    const double* liquid_compositions,
    const double* vapor_compositions,
    double* liquid_log_fugacity_coefficients,
    double* vapor_log_fugacity_coefficients,
    double* liquid_molar_enthalpies_joules_per_mol,
    double* vapor_molar_enthalpies_joules_per_mol,
    double* liquid_compressibility,
    double* vapor_compressibility,
    double* liquid_d_log_fugacity_d_temperature,
    double* vapor_d_log_fugacity_d_temperature,
    double* liquid_d_enthalpy_d_temperature,
    double* vapor_d_enthalpy_d_temperature);

#ifdef __cplusplus
}
#endif
