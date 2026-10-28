#define ICHEME_COLUMN_SOLVER_BUILD
#include <jni.h>
#include <limits>
#include <vector>

#include "icheme_column_solver.h"

namespace {
int square_root_residual(const double* variables, size_t variable_count, double* residuals,
                         size_t residual_count, void*) {
    if (variable_count != 1 || residual_count != 1) return 1;
    residuals[0] = variables[0] * variables[0] - 2.0;
    return 0;
}

struct JniResidualContext {
    JNIEnv* env;
    jobject evaluator;
    jmethodID method;
    jdoubleArray variables;
    jdoubleArray residuals;
    jsize size;
};

int jni_residual(const double* variables, size_t variable_count, double* residuals,
                 size_t residual_count, void* opaque) {
    auto* context = static_cast<JniResidualContext*>(opaque);
    if (variable_count != static_cast<size_t>(context->size)
            || residual_count != static_cast<size_t>(context->size)) return 1;
    context->env->SetDoubleArrayRegion(context->variables, 0, context->size, variables);
    if (context->env->ExceptionCheck()) return 1;
    context->env->CallVoidMethod(context->evaluator, context->method,
            context->variables, context->residuals);
    if (context->env->ExceptionCheck()) return 1;
    context->env->GetDoubleArrayRegion(context->residuals, 0, context->size, residuals);
    return context->env->ExceptionCheck() ? 1 : 0;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_wormzjl_icheme_science_column_v3_NativeColumnSolver_nativeVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF("icheme-column-solver-cpp17");
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_wormzjl_icheme_science_column_v3_NativeColumnSolver_nativeSmokeSolve(JNIEnv*, jclass) {
    double value = 1.0;
    const icheme_column_solver_options options{16, 1.0e-12, 1.0e-12, 1.0e-4};
    const icheme_column_solver_result result = icheme_column_solve(
            &value, 1, square_root_residual, nullptr, &options);
    return result.status == ICHEME_COLUMN_CONVERGED ? value : std::numeric_limits<double>::quiet_NaN();
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_wormzjl_icheme_science_column_v3_NativeColumnSolver_nativeSolve(
        JNIEnv* env, jclass, jdoubleArray variables, jint maximum_iterations,
        jdouble residual_tolerance, jdouble step_tolerance, jdouble armijo, jobject evaluator) {
    const jsize size = variables == nullptr ? 0 : env->GetArrayLength(variables);
    jdoubleArray metrics = env->NewDoubleArray(5);
    if (metrics == nullptr || size <= 0 || evaluator == nullptr) return metrics;
    jclass evaluator_class = env->GetObjectClass(evaluator);
    jmethodID method = evaluator_class == nullptr
            ? nullptr : env->GetMethodID(evaluator_class, "evaluate", "([D[D)V");
    if (method == nullptr || env->ExceptionCheck()) return metrics;
    jdoubleArray residuals = env->NewDoubleArray(size);
    if (residuals == nullptr) return metrics;
    std::vector<double> native_variables(static_cast<size_t>(size));
    env->GetDoubleArrayRegion(variables, 0, size, native_variables.data());
    if (env->ExceptionCheck()) return metrics;
    JniResidualContext context{env, evaluator, method, variables, residuals, size};
    const icheme_column_solver_options options{
            maximum_iterations > 0 ? static_cast<size_t>(maximum_iterations) : 0,
            residual_tolerance, step_tolerance, armijo};
    const icheme_column_solver_result result = icheme_column_solve(
            native_variables.data(), static_cast<size_t>(size), jni_residual, &context, &options);
    env->SetDoubleArrayRegion(variables, 0, size, native_variables.data());
    const jdouble values[5] = {
            static_cast<jdouble>(result.status), static_cast<jdouble>(result.iterations),
            static_cast<jdouble>(result.residual_evaluations), result.maximum_scaled_residual,
            result.final_step_norm};
    env->SetDoubleArrayRegion(metrics, 0, 5, values);
    return metrics;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_wormzjl_icheme_science_column_v3_NativeColumnSolver_nativePrBatch(
        JNIEnv* env, jclass, jdoubleArray critical_temperatures, jdoubleArray critical_pressures,
        jdoubleArray acentric_factors, jdoubleArray binary_interactions, jdoubleArray temperatures,
        jdoubleArray pressures, jdoubleArray liquid_compositions, jdoubleArray vapor_compositions) {
    const jsize component_count = critical_temperatures == nullptr ? 0 : env->GetArrayLength(critical_temperatures);
    const jsize node_count = temperatures == nullptr ? 0 : env->GetArrayLength(temperatures);
    if (component_count <= 0 || node_count <= 0 || critical_pressures == nullptr || acentric_factors == nullptr
            || binary_interactions == nullptr || pressures == nullptr || liquid_compositions == nullptr
            || vapor_compositions == nullptr
            || env->GetArrayLength(critical_pressures) != component_count
            || env->GetArrayLength(acentric_factors) != component_count
            || env->GetArrayLength(binary_interactions) != component_count * component_count
            || env->GetArrayLength(pressures) != node_count
            || env->GetArrayLength(liquid_compositions) != node_count * component_count
            || env->GetArrayLength(vapor_compositions) != node_count * component_count) return nullptr;
    const jsize phase_width = component_count + 2;
    const jsize result_length = 1 + node_count * 2 * phase_width;
    jdoubleArray result = env->NewDoubleArray(result_length);
    if (result == nullptr) return nullptr;
    std::vector<double> critical_t(component_count), critical_p(component_count), omega(component_count);
    std::vector<double> interactions(static_cast<size_t>(component_count) * component_count);
    std::vector<double> node_t(node_count), node_p(node_count);
    std::vector<double> liquid(static_cast<size_t>(node_count) * component_count);
    std::vector<double> vapor(static_cast<size_t>(node_count) * component_count);
    env->GetDoubleArrayRegion(critical_temperatures, 0, component_count, critical_t.data());
    env->GetDoubleArrayRegion(critical_pressures, 0, component_count, critical_p.data());
    env->GetDoubleArrayRegion(acentric_factors, 0, component_count, omega.data());
    env->GetDoubleArrayRegion(binary_interactions, 0, component_count * component_count, interactions.data());
    env->GetDoubleArrayRegion(temperatures, 0, node_count, node_t.data());
    env->GetDoubleArrayRegion(pressures, 0, node_count, node_p.data());
    env->GetDoubleArrayRegion(liquid_compositions, 0, node_count * component_count, liquid.data());
    env->GetDoubleArrayRegion(vapor_compositions, 0, node_count * component_count, vapor.data());
    if (env->ExceptionCheck()) return result;
    std::vector<double> liquid_log(static_cast<size_t>(node_count) * component_count);
    std::vector<double> vapor_log(static_cast<size_t>(node_count) * component_count);
    std::vector<double> liquid_h(node_count), vapor_h(node_count), liquid_z(node_count), vapor_z(node_count);
    int status = icheme_column_pr_batch(
            component_count, node_count, critical_t.data(), critical_p.data(), omega.data(), interactions.data(),
            node_t.data(), node_p.data(), liquid.data(), vapor.data(), liquid_log.data(), vapor_log.data(),
            liquid_h.data(), vapor_h.data(), liquid_z.data(), vapor_z.data());
    std::vector<double> packed(static_cast<size_t>(result_length));
    packed[0] = static_cast<double>(status);
    size_t at = 1;
    for (jsize node = 0; node < node_count; ++node) {
        for (jsize component = 0; component < component_count; ++component) packed[at++] = liquid_log[static_cast<size_t>(node) * component_count + component];
        packed[at++] = liquid_h[node];
        packed[at++] = liquid_z[node];
        for (jsize component = 0; component < component_count; ++component) packed[at++] = vapor_log[static_cast<size_t>(node) * component_count + component];
        packed[at++] = vapor_h[node];
        packed[at++] = vapor_z[node];
    }
    env->SetDoubleArrayRegion(result, 0, result_length, packed.data());
    return result;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_wormzjl_icheme_science_column_v3_NativeColumnSolver_nativePrBatchTemperatureDerivatives(
        JNIEnv* env, jclass, jdoubleArray critical_temperatures, jdoubleArray critical_pressures,
        jdoubleArray acentric_factors, jdoubleArray binary_interactions, jdoubleArray temperatures,
        jdoubleArray pressures, jdoubleArray liquid_compositions, jdoubleArray vapor_compositions) {
    const jsize component_count = critical_temperatures == nullptr ? 0 : env->GetArrayLength(critical_temperatures);
    const jsize node_count = temperatures == nullptr ? 0 : env->GetArrayLength(temperatures);
    if (component_count <= 0 || node_count <= 0 || critical_pressures == nullptr || acentric_factors == nullptr
            || binary_interactions == nullptr || pressures == nullptr || liquid_compositions == nullptr
            || vapor_compositions == nullptr
            || env->GetArrayLength(critical_pressures) != component_count
            || env->GetArrayLength(acentric_factors) != component_count
            || env->GetArrayLength(binary_interactions) != component_count * component_count
            || env->GetArrayLength(pressures) != node_count
            || env->GetArrayLength(liquid_compositions) != node_count * component_count
            || env->GetArrayLength(vapor_compositions) != node_count * component_count) return nullptr;
    const jsize phase_width = component_count * 2 + 3;
    const jsize result_length = 1 + node_count * 2 * phase_width;
    jdoubleArray result = env->NewDoubleArray(result_length);
    if (result == nullptr) return nullptr;
    std::vector<double> critical_t(component_count), critical_p(component_count), omega(component_count);
    std::vector<double> interactions(static_cast<size_t>(component_count) * component_count);
    std::vector<double> node_t(node_count), node_p(node_count);
    std::vector<double> liquid(static_cast<size_t>(node_count) * component_count);
    std::vector<double> vapor(static_cast<size_t>(node_count) * component_count);
    env->GetDoubleArrayRegion(critical_temperatures, 0, component_count, critical_t.data());
    env->GetDoubleArrayRegion(critical_pressures, 0, component_count, critical_p.data());
    env->GetDoubleArrayRegion(acentric_factors, 0, component_count, omega.data());
    env->GetDoubleArrayRegion(binary_interactions, 0, component_count * component_count, interactions.data());
    env->GetDoubleArrayRegion(temperatures, 0, node_count, node_t.data());
    env->GetDoubleArrayRegion(pressures, 0, node_count, node_p.data());
    env->GetDoubleArrayRegion(liquid_compositions, 0, node_count * component_count, liquid.data());
    env->GetDoubleArrayRegion(vapor_compositions, 0, node_count * component_count, vapor.data());
    if (env->ExceptionCheck()) return result;
    const size_t phase_size = static_cast<size_t>(node_count) * component_count;
    std::vector<double> liquid_log(phase_size), vapor_log(phase_size), liquid_d_log(phase_size), vapor_d_log(phase_size);
    std::vector<double> liquid_h(node_count), vapor_h(node_count), liquid_z(node_count), vapor_z(node_count);
    std::vector<double> liquid_d_h(node_count), vapor_d_h(node_count);
    const int status = icheme_column_pr_batch_temperature_derivatives(
            component_count, node_count, critical_t.data(), critical_p.data(), omega.data(), interactions.data(),
            node_t.data(), node_p.data(), liquid.data(), vapor.data(), liquid_log.data(), vapor_log.data(),
            liquid_h.data(), vapor_h.data(), liquid_z.data(), vapor_z.data(), liquid_d_log.data(),
            vapor_d_log.data(), liquid_d_h.data(), vapor_d_h.data());
    std::vector<double> packed(static_cast<size_t>(result_length));
    packed[0] = static_cast<double>(status);
    size_t at = 1;
    for (jsize node = 0; node < node_count; ++node) {
        const size_t node_offset = static_cast<size_t>(node) * component_count;
        for (jsize component = 0; component < component_count; ++component) packed[at++] = liquid_log[node_offset + component];
        packed[at++] = liquid_h[node];
        packed[at++] = liquid_z[node];
        for (jsize component = 0; component < component_count; ++component) packed[at++] = liquid_d_log[node_offset + component];
        packed[at++] = liquid_d_h[node];
        for (jsize component = 0; component < component_count; ++component) packed[at++] = vapor_log[node_offset + component];
        packed[at++] = vapor_h[node];
        packed[at++] = vapor_z[node];
        for (jsize component = 0; component < component_count; ++component) packed[at++] = vapor_d_log[node_offset + component];
        packed[at++] = vapor_d_h[node];
    }
    env->SetDoubleArrayRegion(result, 0, result_length, packed.data());
    return result;
}
