#pragma once

#include <cstddef>
#include <optional>
#include <utility>
#include <vector>

namespace vibris::mcp {

void record_native_metrics() noexcept;

struct PairedMetricStatistics final {
    double baseline_p50_ns = 0.0;
    double candidate_p50_ns = 0.0;
    double baseline_p95_ns = 0.0;
    double candidate_p95_ns = 0.0;
    double paired_delta_ns = 0.0;
    double paired_delta_mean_ns = 0.0;
    double paired_delta_variance_ns2 = 0.0;
    std::optional<std::pair<double, double>> confidence_interval_95_ns;
    double noise_floor_ns = 0.0;
    double order_effect_ns = 0.0;
    bool direction_reversed = false;
    bool thermal_or_temporal_drift = false;
    bool clears_noise_floor = false;
    bool confidence_excludes_zero = false;
    std::vector<std::size_t> outlier_rounds;
};

[[nodiscard]] PairedMetricStatistics analyze_paired_metric(
    const std::vector<double>& baseline_p50,
    const std::vector<double>& candidate_p50,
    const std::vector<double>& baseline_p95,
    const std::vector<double>& candidate_p95,
    const std::vector<double>& paired_deltas,
    const std::vector<double>& control_deltas,
    const std::vector<double>& first_order_deltas,
    const std::vector<double>& second_order_deltas,
    const std::vector<double>& control_levels);

}
