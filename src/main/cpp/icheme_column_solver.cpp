#define ICHEME_COLUMN_SOLVER_BUILD
#include "icheme_column_solver.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <vector>

namespace {
constexpr double kDifferenceScale = 1.0e-7;
constexpr double kGasConstant = 8.31446261815324;
constexpr double kSqrtTwo = 1.4142135623730950488;
constexpr double kPi = 3.14159265358979323846;

bool finite_vector(const double* values, std::size_t count) {
    for (std::size_t i = 0; i < count; ++i) {
        if (!std::isfinite(values[i])) return false;
    }
    return true;
}

double norm_inf(const std::vector<double>& values) {
    double result = 0.0;
    for (double value : values) result = std::max(result, std::abs(value));
    return result;
}

bool solve_linear(std::vector<double>& matrix, std::vector<double>& rhs, std::size_t n) {
    for (std::size_t pivot = 0; pivot < n; ++pivot) {
        std::size_t row = pivot;
        double largest = std::abs(matrix[pivot * n + pivot]);
        for (std::size_t candidate = pivot + 1; candidate < n; ++candidate) {
            double value = std::abs(matrix[candidate * n + pivot]);
            if (value > largest) { largest = value; row = candidate; }
        }
        if (!(largest > std::numeric_limits<double>::epsilon())) return false;
        if (row != pivot) {
            for (std::size_t column = pivot; column < n; ++column)
                std::swap(matrix[pivot * n + column], matrix[row * n + column]);
            std::swap(rhs[pivot], rhs[row]);
        }
        for (std::size_t below = pivot + 1; below < n; ++below) {
            double factor = matrix[below * n + pivot] / matrix[pivot * n + pivot];
            if (factor == 0.0) continue;
            matrix[below * n + pivot] = 0.0;
            for (std::size_t column = pivot + 1; column < n; ++column)
                matrix[below * n + column] -= factor * matrix[pivot * n + column];
            rhs[below] -= factor * rhs[pivot];
        }
    }
    for (std::size_t reverse = n; reverse-- > 0;) {
        double value = rhs[reverse];
        for (std::size_t column = reverse + 1; column < n; ++column)
            value -= matrix[reverse * n + column] * rhs[column];
        rhs[reverse] = value / matrix[reverse * n + reverse];
    }
    return finite_vector(rhs.data(), n);
}

double kappa(double omega) {
    return omega <= 0.491
            ? 0.37464 + 1.54226 * omega - 0.26992 * omega * omega
            : 0.379642 + 1.48503 * omega - 0.164423 * omega * omega
                    + 0.016666 * omega * omega * omega;
}

bool pr_root(double reduced_a, double reduced_b, bool vapor, double* selected) {
    double c2 = -(1.0 - reduced_b);
    double c1 = reduced_a - 3.0 * reduced_b * reduced_b - 2.0 * reduced_b;
    double c0 = -(reduced_a * reduced_b - reduced_b * reduced_b - reduced_b * reduced_b * reduced_b);
    double p = c1 - c2 * c2 / 3.0;
    double q = 2.0 * c2 * c2 * c2 / 27.0 - c2 * c1 / 3.0 + c0;
    double discriminant = q * q / 4.0 + p * p * p / 27.0;
    std::vector<double> roots;
    if (discriminant > 1.0e-14) {
        double square = std::sqrt(discriminant);
        roots.push_back(std::cbrt(-q / 2.0 + square) + std::cbrt(-q / 2.0 - square) - c2 / 3.0);
    } else {
        double radius = 2.0 * std::sqrt(std::max(0.0, -p / 3.0));
        if (radius == 0.0) {
            roots.push_back(-c2 / 3.0);
        } else {
            double argument = (3.0 * q / (2.0 * p)) * std::sqrt(-3.0 / p);
            argument = std::clamp(argument, -1.0, 1.0);
            double angle = std::acos(argument) / 3.0;
            roots.push_back(radius * std::cos(angle) - c2 / 3.0);
            roots.push_back(radius * std::cos(angle - 2.0 * kPi / 3.0) - c2 / 3.0);
            roots.push_back(radius * std::cos(angle - 4.0 * kPi / 3.0) - c2 / 3.0);
        }
    }
    double lower = std::numeric_limits<double>::infinity();
    double upper = -std::numeric_limits<double>::infinity();
    for (double root : roots) {
        if (std::isfinite(root) && root > reduced_b + 1.0e-12) {
            lower = std::min(lower, root);
            upper = std::max(upper, root);
        }
    }
    double value = vapor ? upper : lower;
    if (!std::isfinite(value)) return false;
    *selected = value;
    return true;
}

bool pr_phase(
        std::size_t count, const double* critical_t, const double* critical_p, const double* omega,
        const double* interactions, double temperature, double pressure, const double* composition, bool vapor,
        double* log_phi, double* enthalpy, double* compressibility) {
    if (!std::isfinite(temperature) || !std::isfinite(pressure) || temperature <= 0.0 || pressure <= 0.0) return false;
    double total = 0.0;
    for (std::size_t i = 0; i < count; ++i) {
        if (!std::isfinite(composition[i]) || composition[i] < 0.0) return false;
        total += composition[i];
    }
    if (!(total > 0.0)) {
        std::fill(log_phi, log_phi + count, std::numeric_limits<double>::quiet_NaN());
        *enthalpy = std::numeric_limits<double>::quiet_NaN();
        *compressibility = std::numeric_limits<double>::quiet_NaN();
        return true;
    }
    std::vector<double> x(count), a(count), da_dt(count), sqrt_a(count), q(count), g(count), sum_a(count), b(count);
    for (std::size_t i = 0; i < count; ++i) {
        x[i] = composition[i] / total;
        double ac = 0.45724 * kGasConstant * kGasConstant * critical_t[i] * critical_t[i] / critical_p[i];
        double alpha = 1.0 + kappa(omega[i]) * (1.0 - std::sqrt(temperature / critical_t[i]));
        a[i] = ac * alpha * alpha;
        da_dt[i] = -ac * kappa(omega[i]) * alpha / std::sqrt(temperature * critical_t[i]);
        sqrt_a[i] = std::sqrt(std::max(0.0, a[i]));
        q[i] = x[i] * sqrt_a[i];
        b[i] = 0.07780 * kGasConstant * critical_t[i] / critical_p[i];
    }
    double a_mix = 0.0, da_mix_dt = 0.0, b_mix = 0.0;
    for (std::size_t i = 0; i < count; ++i) {
        b_mix += x[i] * b[i];
        for (std::size_t j = 0; j < count; ++j) {
            double kij = interactions[i * count + j];
            g[i] += (1.0 - kij) * q[j];
        }
        sum_a[i] = sqrt_a[i] * g[i];
        a_mix += q[i] * g[i];
        if (sqrt_a[i] > 0.0) da_mix_dt += x[i] * da_dt[i] / sqrt_a[i] * g[i];
    }
    double reduced_a = a_mix * pressure / (kGasConstant * kGasConstant * temperature * temperature);
    double reduced_b = b_mix * pressure / (kGasConstant * temperature);
    double z = 0.0;
    if (!(a_mix > 0.0) || !(b_mix > 0.0) || !pr_root(reduced_a, reduced_b, vapor, &z)) return false;
    double log_ratio = std::log((z + (1.0 + kSqrtTwo) * reduced_b)
            / (z + (1.0 - kSqrtTwo) * reduced_b));
    double attraction = reduced_a / (2.0 * kSqrtTwo * std::max(reduced_b, 1.0e-300));
    double free_volume_log = std::log(z - reduced_b);
    for (std::size_t i = 0; i < count; ++i) {
        double b_ratio = b[i] / b_mix;
        double attraction_ratio = 2.0 * sum_a[i] / a_mix - b_ratio;
        log_phi[i] = b_ratio * (z - 1.0) - free_volume_log - attraction * attraction_ratio * log_ratio;
    }
    *compressibility = z;
    *enthalpy = kGasConstant * temperature * (z - 1.0)
            + (temperature * da_mix_dt - a_mix) / (2.0 * kSqrtTwo * b_mix) * log_ratio;
    return finite_vector(log_phi, count) && std::isfinite(*enthalpy) && std::isfinite(*compressibility);
}
}

extern "C" ICHEME_COLUMN_API icheme_column_solver_result icheme_column_solve(
    double* variables, std::size_t variable_count, icheme_column_residual_callback callback,
    void* user_data, const icheme_column_solver_options* options) {
    icheme_column_solver_result result{ICHEME_COLUMN_INVALID_INPUT, 0, 0,
                                       std::numeric_limits<double>::infinity(),
                                       std::numeric_limits<double>::infinity()};
    if (variables == nullptr || callback == nullptr || variable_count == 0 || options == nullptr
            || !finite_vector(variables, variable_count)
            || variable_count > std::numeric_limits<std::size_t>::max() / variable_count
            || options->maximum_iterations == 0 || !std::isfinite(options->residual_tolerance)
            || options->residual_tolerance <= 0.0 || !std::isfinite(options->step_tolerance)
            || options->step_tolerance <= 0.0 || !std::isfinite(options->armijo)
            || options->armijo <= 0.0 || options->armijo >= 1.0) return result;

    std::vector<double> residual(variable_count), trial(variable_count), trial_residual(variable_count);
    std::vector<double> jacobian(variable_count * variable_count), rhs(variable_count), step(variable_count);
    auto evaluate = [&](const double* point, std::vector<double>& target) -> bool {
        ++result.residual_evaluations;
        return callback(point, variable_count, target.data(), variable_count, user_data) == 0
                && finite_vector(target.data(), variable_count);
    };
    if (!evaluate(variables, residual)) { result.status = ICHEME_COLUMN_CALLBACK_FAILURE; return result; }

    for (std::size_t iteration = 0; iteration < options->maximum_iterations; ++iteration) {
        result.iterations = iteration;
        result.maximum_scaled_residual = norm_inf(residual);
        if (result.maximum_scaled_residual <= options->residual_tolerance) {
            result.status = ICHEME_COLUMN_CONVERGED; result.final_step_norm = 0.0; return result;
        }
        for (std::size_t column = 0; column < variable_count; ++column) {
            std::copy(variables, variables + variable_count, trial.begin());
            double delta = kDifferenceScale * std::max(1.0, std::abs(variables[column]));
            trial[column] += delta;
            if (!evaluate(trial.data(), trial_residual)) {
                result.status = ICHEME_COLUMN_CALLBACK_FAILURE; return result;
            }
            for (std::size_t row = 0; row < variable_count; ++row)
                jacobian[row * variable_count + column] = (trial_residual[row] - residual[row]) / delta;
        }
        for (std::size_t row = 0; row < variable_count; ++row) rhs[row] = -residual[row];
        if (!solve_linear(jacobian, rhs, variable_count)) { result.status = ICHEME_COLUMN_SINGULAR; return result; }
        step = rhs;
        double step_norm = norm_inf(step);
        result.final_step_norm = step_norm;
        if (step_norm <= options->step_tolerance) { result.status = ICHEME_COLUMN_MAX_ITERATIONS; return result; }
        double merit = result.maximum_scaled_residual;
        bool accepted = false;
        for (int damping = 0; damping < 12; ++damping) {
            double factor = std::ldexp(1.0, -damping);
            for (std::size_t i = 0; i < variable_count; ++i) trial[i] = variables[i] + factor * step[i];
            if (!evaluate(trial.data(), trial_residual)) {
                result.status = ICHEME_COLUMN_CALLBACK_FAILURE; return result;
            }
            double candidate = norm_inf(trial_residual);
            if (candidate <= (1.0 - options->armijo * factor) * merit) {
                std::copy(trial.begin(), trial.end(), variables);
                residual.swap(trial_residual);
                accepted = true;
                break;
            }
        }
        if (!accepted) { result.status = ICHEME_COLUMN_MAX_ITERATIONS; return result; }
    }
    result.maximum_scaled_residual = norm_inf(residual);
    result.status = ICHEME_COLUMN_MAX_ITERATIONS;
    return result;
}
//waht is the fuck
extern "C" ICHEME_COLUMN_API int icheme_column_pr_batch(
    std::size_t component_count, std::size_t node_count, const double* critical_temperatures_kelvin,
    const double* critical_pressures_pascal, const double* acentric_factors, const double* binary_interactions,
    const double* temperatures_kelvin, const double* pressures_pascal, const double* liquid_compositions,
    const double* vapor_compositions, double* liquid_log_fugacity_coefficients,
    double* vapor_log_fugacity_coefficients, double* liquid_molar_enthalpies_joules_per_mol,
    double* vapor_molar_enthalpies_joules_per_mol, double* liquid_compressibility, double* vapor_compressibility) {
    if (component_count == 0 || node_count == 0 || component_count > std::numeric_limits<std::size_t>::max() / component_count
            || !critical_temperatures_kelvin || !critical_pressures_pascal || !acentric_factors || !binary_interactions
            || !temperatures_kelvin || !pressures_pascal || !liquid_compositions || !vapor_compositions
            || !liquid_log_fugacity_coefficients || !vapor_log_fugacity_coefficients
            || !liquid_molar_enthalpies_joules_per_mol || !vapor_molar_enthalpies_joules_per_mol
            || !liquid_compressibility || !vapor_compressibility) return ICHEME_COLUMN_INVALID_INPUT;
    for (std::size_t node = 0; node < node_count; ++node) {
        std::size_t offset = node * component_count;
        if (!pr_phase(component_count, critical_temperatures_kelvin, critical_pressures_pascal, acentric_factors,
                      binary_interactions, temperatures_kelvin[node], pressures_pascal[node],
                      liquid_compositions + offset, false, liquid_log_fugacity_coefficients + offset,
                      liquid_molar_enthalpies_joules_per_mol + node, liquid_compressibility + node)) return ICHEME_COLUMN_CALLBACK_FAILURE;
        if (!pr_phase(component_count, critical_temperatures_kelvin, critical_pressures_pascal, acentric_factors,
                      binary_interactions, temperatures_kelvin[node], pressures_pascal[node],
                      vapor_compositions + offset, true, vapor_log_fugacity_coefficients + offset,
                      vapor_molar_enthalpies_joules_per_mol + node, vapor_compressibility + node)) return ICHEME_COLUMN_CALLBACK_FAILURE;
    }
    return ICHEME_COLUMN_CONVERGED;
}

extern "C" ICHEME_COLUMN_API int icheme_column_pr_batch_temperature_derivatives(
    std::size_t component_count, std::size_t node_count, const double* critical_temperatures_kelvin,
    const double* critical_pressures_pascal, const double* acentric_factors, const double* binary_interactions,
    const double* temperatures_kelvin, const double* pressures_pascal, const double* liquid_compositions,
    const double* vapor_compositions, double* liquid_log_fugacity_coefficients,
    double* vapor_log_fugacity_coefficients, double* liquid_molar_enthalpies_joules_per_mol,
    double* vapor_molar_enthalpies_joules_per_mol, double* liquid_compressibility, double* vapor_compressibility,
    double* liquid_d_log_fugacity_d_temperature, double* vapor_d_log_fugacity_d_temperature,
    double* liquid_d_enthalpy_d_temperature, double* vapor_d_enthalpy_d_temperature) {
    if (component_count == 0 || node_count == 0
            || component_count > std::numeric_limits<std::size_t>::max() / component_count
            || !critical_temperatures_kelvin || !critical_pressures_pascal || !acentric_factors
            || !binary_interactions || !temperatures_kelvin || !pressures_pascal || !liquid_compositions
            || !vapor_compositions || !liquid_log_fugacity_coefficients || !vapor_log_fugacity_coefficients
            || !liquid_molar_enthalpies_joules_per_mol || !vapor_molar_enthalpies_joules_per_mol
            || !liquid_compressibility || !vapor_compressibility || !liquid_d_log_fugacity_d_temperature
            || !vapor_d_log_fugacity_d_temperature || !liquid_d_enthalpy_d_temperature
            || !vapor_d_enthalpy_d_temperature) return ICHEME_COLUMN_INVALID_INPUT;

    std::vector<double> liquid_log_a(component_count), vapor_log_b(component_count);
    for (std::size_t node = 0; node < node_count; ++node) {
        const std::size_t offset = node * component_count;
        const double temperature = temperatures_kelvin[node];
        const double step = std::max(1.0, std::min(5.0, 1.0e-3 * std::max(temperature, 1.0)));
        const double ahead_temperature = temperature + step;
        double liquid_h_ahead = 0.0, vapor_h_ahead = 0.0;
        double liquid_z_ahead = 0.0, vapor_z_ahead = 0.0;
        if (!pr_phase(component_count, critical_temperatures_kelvin, critical_pressures_pascal, acentric_factors,
                      binary_interactions, temperature, pressures_pascal[node], liquid_compositions + offset,
                      false, liquid_log_fugacity_coefficients + offset,
                      liquid_molar_enthalpies_joules_per_mol + node, liquid_compressibility + node)
                || !pr_phase(component_count, critical_temperatures_kelvin, critical_pressures_pascal,
                             acentric_factors, binary_interactions, temperature, pressures_pascal[node],
                             vapor_compositions + offset, true, vapor_log_fugacity_coefficients + offset,
                             vapor_molar_enthalpies_joules_per_mol + node, vapor_compressibility + node)
                || !pr_phase(component_count, critical_temperatures_kelvin, critical_pressures_pascal,
                             acentric_factors, binary_interactions, ahead_temperature, pressures_pascal[node],
                             liquid_compositions + offset, false, liquid_log_a.data(), &liquid_h_ahead,
                             &liquid_z_ahead)
                || !pr_phase(component_count, critical_temperatures_kelvin, critical_pressures_pascal,
                             acentric_factors, binary_interactions, ahead_temperature, pressures_pascal[node],
                             vapor_compositions + offset, true, vapor_log_b.data(), &vapor_h_ahead,
                             &vapor_z_ahead)) return ICHEME_COLUMN_CALLBACK_FAILURE;
        (void) liquid_z_ahead;
        (void) vapor_z_ahead;
        for (std::size_t component = 0; component < component_count; ++component) {
            liquid_d_log_fugacity_d_temperature[offset + component] =
                    (liquid_log_a[component] - liquid_log_fugacity_coefficients[offset + component]) / step;
            vapor_d_log_fugacity_d_temperature[offset + component] =
                    (vapor_log_b[component] - vapor_log_fugacity_coefficients[offset + component]) / step;
        }
        liquid_d_enthalpy_d_temperature[node] =
                (liquid_h_ahead - liquid_molar_enthalpies_joules_per_mol[node]) / step;
        vapor_d_enthalpy_d_temperature[node] =
                (vapor_h_ahead - vapor_molar_enthalpies_joules_per_mol[node]) / step;
    }
    return ICHEME_COLUMN_CONVERGED;
}
