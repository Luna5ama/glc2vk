#include "source_handler.hpp"
#include "workspace_source_fixture.hpp"

#include <array>
#include <exception>
#include <filesystem>
#include <iostream>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace {

namespace fs = std::filesystem;
namespace proto = ::vibris::control::v2;
using vibris::mcp::Json;
using vibris::mcp::SourceHandler;
using vibris::mcp::test::WorkspaceFixture;
using vibris::mcp::test::require;

proto::ServerHello server(const WorkspaceFixture& fixture) {
    proto::ServerHello hello;
    hello.mutable_status()->set_state(proto::SERVER_STATE_AVAILABLE);
    hello.set_pending_source_root(fixture.pending().string());
    hello.mutable_limits()->set_max_source_bytes(1024 * 1024);
    hello.mutable_limits()->set_max_source_files(128);
    return hello;
}

std::vector<fs::path> prepared_paths(
    const std::vector<proto::PreparedSourceRef>& sources, const WorkspaceFixture& fixture) {
    std::vector<fs::path> paths;
    for (const auto& source : sources) {
        paths.push_back(fixture.pending() / source.source_uuid());
    }
    return paths;
}

proto::ServerMessage accepted(std::string_view id) {
    proto::ServerMessage message;
    message.set_request_id(id);
    message.mutable_job_accepted()->set_request_id(id);
    return message;
}

void single_job_accepted_transfers_ab_sources_once() {
    // Given: one A/B preparation batch containing two independently owned source directories.
    WorkspaceFixture fixture;
    SourceHandler handler(fixture.worktree());
    const Json arguments{
        {"recipe", "ab_compare"},
        {"a", {{"source", {{"kind", "workspace"}}}}},
        {"b", {{"source", {{"kind", "workspace"}}}}},
    };
    handler.prepare("vibris_run_recipe", arguments, server(fixture));
    const auto paths = prepared_paths(handler.bind_latest("job-ab"), fixture);
    require(paths.size() == 2, "A/B fixture did not prepare exactly two sources.");

    // When: the same JobAccepted is observed twice and the handler releases its remaining state.
    handler.observe(accepted("job-ab"));
    handler.observe(accepted("job-ab"));
    handler.clear();

    // Then: one request-level transfer preserves both A/B directories without a partial cleanup.
    require(fs::is_directory(paths[0]) && fs::is_directory(paths[1]),
        "A single JobAccepted did not transfer both A/B sources.");
}

fs::path prepare_bound(
    SourceHandler& handler,
    const WorkspaceFixture& fixture,
    const proto::ServerHello& hello,
    std::string_view request_id) {
    const Json arguments{{"sources", Json::array({{{"id", "source"}, {"kind", "workspace"}}})}};
    handler.prepare("vibris_run_actions", arguments, hello);
    const auto paths = prepared_paths(handler.bind_latest(std::string(request_id)), fixture);
    require(paths.size() == 1, "Ownership fixture did not prepare exactly one source.");
    return paths.front();
}

void job_state_controls_source_ownership() {
    // Given: four prepared requests whose acceptance was ambiguous across a stream disconnect.
    WorkspaceFixture fixture;
    SourceHandler handler(fixture.worktree());
    const auto hello = server(fixture);
    const auto queued = prepare_bound(handler, fixture, hello, "job-queued");
    const auto running = prepare_bound(handler, fixture, hello, "job-running");
    const auto completed = prepare_bound(handler, fixture, hello, "job-completed");
    const auto missing = prepare_bound(handler, fixture, hello, "job-missing");

    // When: v2 JobStateSnapshot finds accepted, running, and completed jobs but omits the unknown request.
    for (const auto& [id, state] : std::array{
             std::pair{"job-queued", proto::JOB_STATE_QUEUED},
             std::pair{"job-running", proto::JOB_STATE_RUNNING},
             std::pair{"job-completed", proto::JOB_STATE_COMPLETED},
         }) {
        proto::ServerMessage response;
        auto* summary = response.mutable_job_state()->mutable_summary();
        summary->set_job_id(id);
        summary->set_request_id(id);
        summary->set_state(state);
        handler.observe(response);
    }
    handler.clear();

    // Then: server-known jobs retain all sources, while NOT_FOUND remains MCP-owned and is deleted.
    require(fs::is_directory(queued), "Accepted request did not transfer source ownership.");
    require(fs::is_directory(running), "Running request did not transfer source ownership.");
    require(fs::is_directory(completed), "Completed request did not transfer source ownership.");
    require(!fs::exists(missing), "Unknown job did not retain MCP source ownership.");
}

using TestCase = std::pair<std::string_view, void (*)()>;
constexpr std::array<TestCase, 2> test_cases{{
    {"SingleJobAcceptedTransfersAbSourcesOnce", single_job_accepted_transfers_ab_sources_once},
    {"JobStateControlsSourceOwnership", job_state_controls_source_ownership},
}};

}

int main(int argc, char** argv) {
    if (argc != 2) {
        std::cerr << "usage: vibris-grpc-source-ownership-tests <scenario>\n";
        return 2;
    }
    for (const auto& [name, test] : test_cases) {
        if (name == argv[1]) {
            try {
                test();
                std::cout << "PASS " << name << '\n';
                return 0;
            } catch (const std::exception& error) {
                std::cerr << "FAIL " << name << ": " << error.what() << '\n';
                return 1;
            }
        }
    }
    std::cerr << "Unknown source ownership scenario: " << argv[1] << '\n';
    return 2;
}
