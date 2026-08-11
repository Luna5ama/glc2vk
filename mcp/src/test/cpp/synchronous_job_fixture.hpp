#pragma once

#include "vibris_control.pb.h"

#include <cstdint>
#include <string>
#include <string_view>

namespace vibris::mcp::test {

namespace proto = ::vibris::control::v2;

inline void fill_provenance(proto::ResultProvenance* provenance, const std::string_view source_uuid,
    const std::string_view source_sha256 = "source-sha", const std::string_view config_sha256 = "config-sha",
    const std::string_view scene_sha256 = "scene-sha") {
    provenance->set_workspace_id("workspace");
    provenance->set_worktree_root("I:/code/worktree");
    provenance->set_branch("main");
    provenance->set_requested_revision("HEAD");
    provenance->set_resolved_revision("0123456789abcdef");
    provenance->set_start_head("0123456789abcdef");
    provenance->set_completion_head("0123456789abcdef");
    provenance->set_head_changed(false);
    provenance->set_stale(false);
    provenance->set_shader_tree_id("shader-tree");
    provenance->set_dirty_shader_delta_sha256("dirty-sha");
    provenance->set_source_snapshot_sha256(source_sha256);
    provenance->set_active_source_uuid(source_uuid);
    provenance->set_config_sha256(config_sha256);
    provenance->set_preset_id("preset");
    provenance->set_preset_sha256("preset-sha");
    provenance->set_scene_sha256(scene_sha256);
    provenance->mutable_effective_settings()->set_settings_sha256(config_sha256);
    provenance->set_shader_loaded_at_unix_ms(1000);
    provenance->set_pass_mapping_sha256("mapping-sha");
    auto* environment = provenance->mutable_environment();
    environment->set_minecraft_version("1.21.11");
    environment->set_iris_version("iris-test");
    environment->set_vibris_version("vibris-test");
    environment->set_java_version("21");
    environment->set_operating_system("Windows");
    environment->set_gpu_vendor("GPU vendor");
    environment->set_gpu_renderer("GPU renderer");
    environment->set_opengl_version("4.6");
    environment->set_driver_version("driver");
}

inline void fill_restoration(proto::RestorationReceipt* restoration) {
    restoration->set_status(proto::RECEIPT_STATUS_OK);
    restoration->set_expected_source_uuid("original");
    restoration->set_actual_source_uuid("original");
    restoration->set_expected_source_sha256("original-sha");
    restoration->set_actual_source_sha256("original-sha");
    restoration->set_expected_settings_sha256("original-settings");
    restoration->set_actual_settings_sha256("original-settings");
    restoration->set_expected_scene_sha256("original-scene");
    restoration->set_actual_scene_sha256("original-scene");
    restoration->set_temporal_state_reset(true);
    restoration->set_verified_at_unix_ms(2000);
}

inline void fill_result_common(proto::JobResult* result, const std::string_view source_uuid = "candidate") {
    auto* timings = result->mutable_timings();
    timings->set_accepted_at_unix_ms(100);
    timings->set_started_at_unix_ms(200);
    timings->set_completed_at_unix_ms(300);
    timings->set_queue_ms(100);
    timings->set_execution_ms(100);
    timings->set_total_ms(200);
    fill_provenance(result->mutable_provenance(), source_uuid);
    fill_restoration(result->mutable_restoration());
    result->set_result_manifest_id("manifest-id");
}

inline proto::ActionReceipt* add_load_receipt(proto::JobResult* result, const std::uint32_t action_index,
    const std::string_view source_uuid, const std::string_view source_sha256) {
    auto* receipt = result->add_prelude_receipts();
    receipt->set_action_index(action_index);
    receipt->set_kind(proto::ACTION_KIND_LOAD_SHADER);
    receipt->set_status(proto::RECEIPT_STATUS_OK);
    auto* mutation = receipt->mutable_runtime_mutation();
    mutation->set_source_uuid(source_uuid);
    mutation->set_source_sha256(source_sha256);
    mutation->mutable_effective_settings()->set_settings_sha256("config-sha");
    mutation->set_scene_sha256("scene-sha");
    mutation->set_completed_at_unix_ms(900 + action_index);
    return receipt;
}

inline proto::ActionReceipt* add_metrics_receipt(proto::JobResult* result, const std::uint32_t action_index,
    const std::uint64_t average_ns = 1000) {
    auto* receipt = result->add_action_receipts();
    receipt->set_action_index(action_index);
    receipt->set_kind(proto::ACTION_KIND_GET_GPU_METRICS);
    receipt->set_status(proto::RECEIPT_STATUS_OK);
    auto* metrics = receipt->mutable_gpu_metrics();
    metrics->set_timing_unit("ns");
    metrics->set_sampled_frames(3);
    auto* metric = metrics->add_metrics();
    metric->set_metric_id("composite_total");
    metric->set_program_id("composite");
    metric->set_pass_id("composite");
    metric->set_average_ns(average_ns);
    metric->set_p50_ns(average_ns - 100);
    metric->set_p95_ns(average_ns + 100);
    metric->add_samples_ns(average_ns - 100);
    metric->add_samples_ns(average_ns);
    metric->add_samples_ns(average_ns + 100);
    return receipt;
}

inline proto::ServerMessage completed_profile_message() {
    proto::ServerMessage message;
    message.set_request_id("request-profile");
    auto* completed = message.mutable_job_completed();
    completed->set_job_id("job-profile");
    completed->set_request_id("request-profile");
    auto* result = completed->mutable_result();
    fill_result_common(result);
    add_load_receipt(result, 0, "candidate", "source-sha");
    add_metrics_receipt(result, 1);
    return message;
}

inline proto::ServerMessage completed_inspection_message() {
    proto::ServerMessage message;
    message.set_request_id("request-inspection");
    auto* completed = message.mutable_job_completed();
    completed->set_job_id("job-inspection");
    completed->set_request_id("request-inspection");
    auto* result = completed->mutable_result();
    fill_result_common(result);
    add_load_receipt(result, 0, "candidate", "source-sha");
    auto* receipt = result->add_action_receipts();
    receipt->set_action_index(1);
    receipt->set_kind(proto::ACTION_KIND_INSPECT_SHADER);
    receipt->set_status(proto::RECEIPT_STATUS_OK);
    auto* catalog = receipt->mutable_shader_inspection()->mutable_catalog();
    catalog->set_mapping_sha256("mapping-sha");
    catalog->set_shader_generation(7);
    auto* program = catalog->add_programs();
    program->set_program_id("composite");
    program->set_pass_id("composite");
    program->set_compile_state(proto::COMPILE_STATE_SUCCEEDED);
    program->set_link_state(proto::COMPILE_STATE_SUCCEEDED);
    return message;
}

inline void add_matrix_case(proto::MatrixResult* matrix, const std::string_view case_id,
    const std::string_view source_uuid, const std::uint64_t average_ns) {
    auto* value = matrix->add_cases();
    value->set_case_id(case_id);
    value->set_status(proto::RECEIPT_STATUS_OK);
    fill_provenance(value->mutable_provenance(), source_uuid);
    auto* receipt = value->add_action_receipts();
    receipt->set_action_index(0);
    receipt->set_kind(proto::ACTION_KIND_GET_GPU_METRICS);
    receipt->set_status(proto::RECEIPT_STATUS_OK);
    auto* metrics = receipt->mutable_gpu_metrics();
    metrics->set_timing_unit("ns");
    metrics->set_sampled_frames(2);
    auto* metric = metrics->add_metrics();
    metric->set_metric_id("composite_total");
    metric->set_program_id("composite");
    metric->set_pass_id("composite");
    metric->set_average_ns(average_ns);
    metric->set_p50_ns(average_ns);
    metric->set_p95_ns(average_ns + 100);
    metric->add_samples_ns(average_ns);
    metric->add_samples_ns(average_ns + 100);
}

inline proto::ServerMessage completed_matrix_message() {
    proto::ServerMessage message;
    message.set_request_id("request-matrix");
    auto* completed = message.mutable_job_completed();
    completed->set_job_id("job-matrix");
    completed->set_request_id("request-matrix");
    auto* result = completed->mutable_result();
    fill_result_common(result);
    auto* matrix = result->mutable_matrix();
    add_matrix_case(matrix, "baseline--default", "baseline", 1200);
    add_matrix_case(matrix, "candidate--default", "candidate", 1000);
    matrix->set_requested_cases(2);
    matrix->set_completed_cases(2);
    matrix->set_failed_cases(0);
    return message;
}

inline proto::ActionReceipt* add_capture_receipt(proto::JobResult* result, const std::uint32_t action_index,
    const std::uint64_t frame_id) {
    auto* receipt = result->add_action_receipts();
    receipt->set_action_index(action_index);
    receipt->set_kind(proto::ACTION_KIND_TAKE_SCREENSHOT);
    receipt->set_status(proto::RECEIPT_STATUS_OK);
    receipt->mutable_capture()->set_frame_id(frame_id);
    return receipt;
}

inline proto::ServerMessage completed_visual_message() {
    proto::ServerMessage message;
    message.set_request_id("request-visual");
    auto* completed = message.mutable_job_completed();
    completed->set_job_id("job-visual");
    completed->set_request_id("request-visual");
    auto* result = completed->mutable_result();
    fill_result_common(result);
    add_load_receipt(result, 0, "baseline", "baseline-sha");
    add_load_receipt(result, 1, "candidate", "candidate-sha");
    add_capture_receipt(result, 2, 41);
    add_capture_receipt(result, 3, 42);
    auto* comparison = result->add_action_receipts();
    comparison->set_action_index(4);
    comparison->set_kind(proto::ACTION_KIND_COMPARE_CAPTURES);
    comparison->set_status(proto::RECEIPT_STATUS_OK);
    comparison->mutable_comparison()->set_passed(true);
    comparison->mutable_comparison()->mutable_metrics()->set_mean_absolute_error(0.0);
    comparison->mutable_comparison()->mutable_metrics()->set_sample_count(100);
    comparison->mutable_comparison()->mutable_metrics()->set_pixel_count(100);

    auto* metrics = result->add_artifacts();
    metrics->set_artifact_id("diff-json");
    metrics->set_relative_path("visual/diff.json");
    metrics->set_kind(proto::ARTIFACT_KIND_BENCHMARK_METRICS);
    metrics->set_format(proto::ARTIFACT_FORMAT_JSON);
    auto* heatmap = result->add_artifacts();
    heatmap->set_artifact_id("diff-heatmap");
    heatmap->set_relative_path("visual/diff.png");
    heatmap->set_kind(proto::ARTIFACT_KIND_HEATMAP);
    heatmap->set_format(proto::ARTIFACT_FORMAT_PNG);
    return message;
}

}
