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
namespace proto = ::vibris::control::v1;
using vibris::mcp::Json;
using vibris::mcp::SourceHandler;
using vibris::mcp::test::WorkspaceFixture;
using vibris::mcp::test::require;

proto::ServerHello server(const WorkspaceFixture& fixture) {
    proto::ServerHello hello;
    hello.set_ready(true);
    hello.set_pending_shaders_root(fixture.pending().string());
    hello.mutable_limits()->set_max_source_bytes(1024 * 1024);
    hello.mutable_limits()->set_max_source_files(128);
    return hello;
}

std::vector<fs::path> prepared_paths(
    const std::vector<proto::PreparedSourceRef>& sources, const WorkspaceFixture& fixture) {
    std::vector<fs::path> paths;
    for (const auto& source : sources) {
        paths.push_back(fixture.pending() / source.uuid());
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
    const Json arguments{{"source", {{"kind", "workspace"}}}};
    handler.prepare("vibris_run_actions", arguments, hello);
    const auto paths = prepared_paths(handler.bind_latest(std::string(request_id)), fixture);
    require(paths.size() == 1, "Ownership fixture did not prepare exactly one source.");
    return paths.front();
}

void resume_state_controls_source_ownership() {
    // Given: four prepared requests whose acceptance was ambiguous across a stream disconnect.
    WorkspaceFixture fixture;
    SourceHandler handler(fixture.worktree());
    const auto hello = server(fixture);
    const auto queued = prepare_bound(handler, fixture, hello, "job-queued");
    const auto running = prepare_bound(handler, fixture, hello, "job-running");
    const auto completed = prepare_bound(handler, fixture, hello, "job-completed");
    const auto missing = prepare_bound(handler, fixture, hello, "job-missing");

    // When: ResumeState finds accepted, running, and completed jobs but omits the NOT_FOUND request.
    proto::ServerMessage resume;
    auto* state = resume.mutable_resume_state();
    state->add_jobs()->set_request_id("job-queued");
    state->mutable_jobs(0)->set_state(proto::JOB_STATE_QUEUED);
    state->add_jobs()->set_request_id("job-running");
    state->mutable_jobs(1)->set_state(proto::JOB_STATE_RUNNING);
    state->add_jobs()->set_request_id("job-completed");
    state->mutable_jobs(2)->set_state(proto::JOB_STATE_COMPLETED);
    handler.observe(resume);
    handler.clear();

    // Then: server-known jobs retain all sources, while NOT_FOUND remains MCP-owned and is deleted.
    require(fs::is_directory(queued), "Accepted request did not transfer source ownership.");
    require(fs::is_directory(running), "Running request did not transfer source ownership.");
    require(fs::is_directory(completed), "Completed request did not transfer source ownership.");
    require(!fs::exists(missing), "ResumeState NOT_FOUND did not retain MCP source ownership.");
}

using TestCase = std::pair<std::string_view, void (*)()>;
constexpr std::array<TestCase, 2> test_cases{{
    {"SingleJobAcceptedTransfersAbSourcesOnce", single_job_accepted_transfers_ab_sources_once},
    {"ResumeStateControlsSourceOwnership", resume_state_controls_source_ownership},
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
