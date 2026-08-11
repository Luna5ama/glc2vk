#include "native_metrics.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <iterator>
#include <limits>
#include <numeric>

#ifdef VIBRIS_SANITIZER_BUILD

#include <sanitizer/allocator_interface.h>
#include <windows.h>

#include <atomic>
#include <chrono>
#include <cstdlib>
#include <fstream>
#include <memory>

namespace vibris::mcp {

void record_native_metrics() noexcept {
    char* raw_path = nullptr;
    std::size_t path_size = 0;
    if (_dupenv_s(&raw_path, &path_size, "VIBRIS_SOAK_METRICS") != 0) return;
    const std::unique_ptr<char, decltype(&std::free)> path(raw_path, &std::free);
    if (!path || path_size <= 1) return;

    DWORD handles = 0;
    if (!GetProcessHandleCount(GetCurrentProcess(), &handles)) return;

    static std::atomic_uint64_t sequence{0};
    const auto now = std::chrono::system_clock::now().time_since_epoch();
    const auto unix_ms = std::chrono::duration_cast<std::chrono::milliseconds>(now).count();
    try {
        std::ofstream output(path.get(), std::ios::app);
        output << "{\"sequence\":" << sequence.fetch_add(1, std::memory_order_relaxed)
               << ",\"unix_ms\":" << unix_ms
               << ",\"handle_count\":" << handles
               << ",\"heap_allocated_bytes\":" << __sanitizer_get_current_allocated_bytes()
               << ",\"heap_size_bytes\":" << __sanitizer_get_heap_size() << "}\n";
    } catch (...) {
    }
}

}

#else

namespace vibris::mcp {

void record_native_metrics() noexcept {}

}

#endif

namespace vibris::mcp {
namespace {

double quantile(std::vector<double> values, const double probability) {
    if (values.empty()) return std::numeric_limits<double>::quiet_NaN();
    std::sort(values.begin(), values.end());
    const auto position = probability * static_cast<double>(values.size() - 1);
    const auto lower = static_cast<std::size_t>(std::floor(position));
    const auto upper = static_cast<std::size_t>(std::ceil(position));
    if (lower == upper) return values[lower];
    const auto fraction = position - static_cast<double>(lower);
    return values[lower] + (values[upper] - values[lower]) * fraction;
}

double median(const std::vector<double>& values) {
    return quantile(values, 0.5);
}

double sample_variance(const std::vector<double>& values) {
    if (values.size() < 2) return 0.0;
    const auto mean = std::accumulate(values.begin(), values.end(), 0.0) /
        static_cast<double>(values.size());
    double squared = 0.0;
    for (const auto value : values) squared += (value - mean) * (value - mean);
    return squared / static_cast<double>(values.size() - 1);
}

double t_critical_95(const std::size_t samples) {
    constexpr std::array values{
        0.0, 12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262,
        2.228, 2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093,
        2.086, 2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042,
    };
    const auto degrees = samples - 1;
    return degrees < values.size() ? values[degrees] : 1.96;
}

std::vector<std::size_t> outlier_rounds(const std::vector<double>& values) {
    std::vector<std::size_t> result;
    if (values.size() < 4) return result;
    const auto q1 = quantile(values, 0.25);
    const auto q3 = quantile(values, 0.75);
    const auto iqr = q3 - q1;
    const auto lower = q1 - 1.5 * iqr;
    const auto upper = q3 + 1.5 * iqr;
    for (std::size_t index = 0; index < values.size(); ++index) {
        if (values[index] < lower || values[index] > upper) result.push_back(index + 1);
    }
    return result;
}

} // namespace

PairedMetricStatistics analyze_paired_metric(
    const std::vector<double>& baseline_p50,
    const std::vector<double>& candidate_p50,
    const std::vector<double>& baseline_p95,
    const std::vector<double>& candidate_p95,
    const std::vector<double>& paired_deltas,
    const std::vector<double>& control_deltas,
    const std::vector<double>& first_order_deltas,
    const std::vector<double>& second_order_deltas,
    const std::vector<double>& control_levels) {
    PairedMetricStatistics result;
    result.baseline_p50_ns = median(baseline_p50);
    result.candidate_p50_ns = median(candidate_p50);
    result.baseline_p95_ns = median(baseline_p95);
    result.candidate_p95_ns = median(candidate_p95);
    result.paired_delta_ns = median(paired_deltas);
    result.paired_delta_mean_ns = std::accumulate(paired_deltas.begin(), paired_deltas.end(), 0.0) /
        static_cast<double>(paired_deltas.size());
    result.paired_delta_variance_ns2 = sample_variance(paired_deltas);

    std::vector<double> absolute_noise;
    absolute_noise.reserve(control_deltas.size());
    std::ranges::transform(control_deltas, std::back_inserter(absolute_noise), [](const double value) {
        return std::abs(value);
    });
    result.noise_floor_ns = quantile(absolute_noise, 0.95);
    result.clears_noise_floor = std::abs(result.paired_delta_ns) > result.noise_floor_ns;

    if (paired_deltas.size() >= 2) {
        const auto mean = result.paired_delta_mean_ns;
        const auto margin = t_critical_95(paired_deltas.size()) *
            std::sqrt(result.paired_delta_variance_ns2 / static_cast<double>(paired_deltas.size()));
        result.confidence_interval_95_ns = std::pair{mean - margin, mean + margin};
        result.confidence_excludes_zero = result.confidence_interval_95_ns->first > 0.0 ||
            result.confidence_interval_95_ns->second < 0.0;
    }

    const auto first_order = median(first_order_deltas);
    const auto second_order = median(second_order_deltas);
    result.order_effect_ns = std::abs(second_order - first_order);
    result.direction_reversed = first_order * second_order < 0.0 &&
        std::abs(first_order) > result.noise_floor_ns && std::abs(second_order) > result.noise_floor_ns;

    if (control_levels.size() >= 2) {
        const auto split = control_levels.size() / 2;
        const std::vector<double> early(control_levels.begin(), control_levels.begin() + split);
        const std::vector<double> late(control_levels.end() - split, control_levels.end());
        result.thermal_or_temporal_drift = std::abs(median(late) - median(early)) > result.noise_floor_ns;
    }
    result.outlier_rounds = outlier_rounds(paired_deltas);
    return result;
}

} // namespace vibris::mcp
