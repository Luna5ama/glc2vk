#include "nsight_analyzer.hpp"
#include "state_error.hpp"
#include "workspace_artifact_link.hpp"

#include <chrono>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <variant>

namespace {

namespace fs = std::filesystem;

using vibris::mcp::Json;
using vibris::mcp::StateError;
using vibris::mcp::ToolOutcome;
using vibris::mcp::WorkspaceArtifactLink;
using vibris::mcp::analyze_nsight_bundle;

void require(const bool condition, const std::string_view message) {
    if (!condition) throw std::runtime_error(std::string(message));
}

class TempTree final {
public:
    TempTree()
        : path_(fs::temp_directory_path() /
              ("vibris-nsight-analyzer-" + std::to_string(
                  std::chrono::steady_clock::now().time_since_epoch().count()))) {
        fs::create_directories(path_);
    }

    TempTree(const TempTree&) = delete;
    TempTree& operator=(const TempTree&) = delete;

    ~TempTree() {
        std::error_code error;
        fs::remove_all(path_, error);
    }

    [[nodiscard]] const fs::path& path() const noexcept { return path_; }

private:
    fs::path path_;
};

void write_file(const fs::path& path, const std::string_view contents) {
    fs::create_directories(path.parent_path());
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    output.write(contents.data(), static_cast<std::streamsize>(contents.size()));
    if (!output) throw std::runtime_error("unable to create Nsight analyzer fixture");
}

struct Fixture final {
    TempTree tree;
    fs::path workspace;
    fs::path descriptor;

    Fixture() {
        workspace = tree.path() / "worktree";
        const auto request = tree.path() / "server" / "artifacts" / "workspace-id" / "job-id" / "request-id";
        fs::create_directories(workspace);

        write_file(request / "trace.nsight.REPRO_INFO.xls",
            "\xEF\xBB\xBF" "Device Name\tFixture GPU\r\nChip Name\tAD104\r\nDriver Version\t999.0\r\n"
            "API\tOpenGL\r\nProduct Version\t2026.2\r\nProcess File Name\tjava.exe\r\n");
        write_file(request / "trace.nsight.FRAME.xls", "GPU frame time\t10\t11\r\n");
        write_file(request / "trace.nsight.GPUTRACE_FRAME.xls",
            "\xEF\xBB\xBF" "sm__throughput.avg.pct_of_peak_sustained_elapsed\t50\t60\r\n"
            "dramc__throughput.avg.pct_of_peak_sustained_elapsed\t80\t82\r\n"
            "l1tex__t_sector_hit_rate.pct\t70\t72\r\n");
        write_file(request / "trace.nsight.D3DPERF_EVENTS.xls",
            "Event\tFrame 0\tFrame 1\r\n"
            "Copy\t2\t2\r\n"
            "Replay\t5\t6\r\n"
            "        composite\t4\t5\r\n"
            "                composite1\t3\t4\r\n"
            "                composite2\t1\t1\r\n"
            "                composite1\t0.5\t0.5\r\n"
            "UnmarkedTail\t20\t20\r\n");
        write_file(request / "trace.nsight.GPUTRACE_REGIMES.xls",
            "Marker\tsm__throughput.avg.pct_of_peak_sustained_elapsed\tdramc__throughput.avg.pct_of_peak_sustained_elapsed\tl1tex__t_sector_hit_rate.pct\r\n"
            "Replay\t55\t81\t71\r\n"
            "Replay/composite\t57\t83\t69\r\n"
            "Replay/composite/composite1\t60\t85\t65\r\n"
            "Replay/composite/composite2\t40\t50\t90\r\n"
            "Copy\t99\t99\t1\r\n"
            "UnmarkedTail\t99\t99\t1\r\n");

        const Json document{
            {"schema_version", 1},
            {"kind", "vibris_nsight_gpu_trace_bundle"},
            {"bundle_complete", true},
            {"capture_mode", "single"},
            {"pass_id", "composite/composite1"},
            {"replay_backend", "gl"},
            {"architecture", "Ada"},
            {"metric_set_name", "Throughput Metrics"},
            {"files", {
                {"REPRO_INFO.xls", "trace.nsight.REPRO_INFO.xls"},
                {"FRAME.xls", "trace.nsight.FRAME.xls"},
                {"GPUTRACE_FRAME.xls", "trace.nsight.GPUTRACE_FRAME.xls"},
                {"D3DPERF_EVENTS.xls", "trace.nsight.D3DPERF_EVENTS.xls"},
                {"GPUTRACE_REGIMES.xls", "trace.nsight.GPUTRACE_REGIMES.xls"},
            }},
        };
        const auto physical_descriptor = request / "trace.nsight.bundle.json";
        write_file(physical_descriptor, document.dump(2));

        ToolOutcome outcome = Json{{"relative_path", physical_descriptor.string()}};
        WorkspaceArtifactLink(workspace, "workspace-id").rewrite(outcome);
        descriptor = fs::path(std::get<Json>(outcome).at("relative_path").get<std::string>());
    }
};

void honors_replay_evidence_contract() {
    Fixture fixture;
    const auto summary = analyze_nsight_bundle(
        fixture.workspace, fixture.descriptor, Json{{"operation", "summary"}});
    require(summary.at("operation") == "summary", "summary operation was not reported");
    require(summary.at("replay").at("marker_instances") == 2, "Replay marker samples were not isolated");
    require(summary.at("top_passes").size() == 2, "Replay leaves were not selected");
    const auto dump = summary.dump();
    require(dump.find("UnmarkedTail") == std::string::npos, "unmarked tail leaked into shader evidence");
    require(dump.find("\"name\":\"Copy\"") == std::string::npos, "Copy leaked into shader evidence");
    require(summary.at("context_only").at("captured_trace_span_ms") == 21.0,
        "whole-trace timing context was parsed incorrectly");

    const auto stages = analyze_nsight_bundle(
        fixture.workspace, fixture.descriptor, Json{{"operation", "stages"}});
    require(stages.at("stages").size() == 1 && stages.at("stages").front().at("name") == "composite",
        "direct Replay stage grouping was incorrect");

    const auto actions = analyze_nsight_bundle(fixture.workspace, fixture.descriptor,
        Json{{"operation", "actions"}, {"with_metrics", true}});
    require(actions.at("actions").size() == 2, "deepest Replay markers were not returned as actions");
    require(actions.at("actions").front().at("headline_metrics").is_object(),
        "projected action metrics were omitted");
}

void streams_metric_and_diagnostic_queries() {
    Fixture fixture;
    const auto metric = analyze_nsight_bundle(fixture.workspace, fixture.descriptor,
        Json{{"operation", "metric"},
             {"name", "^dramc__throughput\\.avg\\.pct_of_peak_sustained_elapsed$"}});
    require(metric.at("metrics").size() == 1, "exact metric query did not resolve uniquely");
    require(metric.at("metrics").front().at("in_marker").at("avg") == 81.0,
        "metric query did not project the Replay row");

    const auto bandwidth = analyze_nsight_bundle(
        fixture.workspace, fixture.descriptor, Json{{"operation", "bandwidth"}});
    require(bandwidth.at("signals").at("dominant_tier") == "dram_pct",
        "bandwidth diagnostic did not resolve the dominant tier");
    require(bandwidth.at("scope").at("matched_markers") == 1,
        "diagnostic scope was not confined to Replay");

    const auto draws = analyze_nsight_bundle(
        fixture.workspace, fixture.descriptor, Json{{"operation", "draws"}});
    require(draws.at("operation") == "draws", "draw query returned the wrong operation label");

    for (const auto* operation : {
             "stalls", "shader_bound", "texture_cache", "overdraw", "geometry",
         }) {
        const auto diagnostic = analyze_nsight_bundle(
            fixture.workspace, fixture.descriptor, Json{{"operation", operation}});
        require(diagnostic.at("operation") == operation && diagnostic.contains("signals") &&
                diagnostic.contains("verdict"),
            std::string(operation) + " diagnostic returned an incomplete result");
    }
}

void rejects_unmanaged_artifacts() {
    Fixture fixture;
    const auto outside = fixture.tree.path() / "outside.json";
    write_file(outside, "{}");
    try {
        static_cast<void>(analyze_nsight_bundle(
            fixture.workspace, outside, Json{{"operation", "summary"}}));
    } catch (const StateError& error) {
        require(error.code() == "INVALID_NSIGHT_BUNDLE", "unmanaged path returned the wrong error code");
        return;
    }
    throw std::runtime_error("unmanaged artifact path was accepted");
}

} // namespace

int main() {
    try {
        honors_replay_evidence_contract();
        streams_metric_and_diagnostic_queries();
        rejects_unmanaged_artifacts();
        std::cout << "PASS NsightAnalyzerReplayContract\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL NsightAnalyzerReplayContract: " << error.what() << '\n';
        return 1;
    }
}
