#pragma once

#include "job_context.hpp"
#include "grpc_client.hpp"
#include "source_handler.hpp"
#include "tool_registry.hpp"
#include "vibris_control.pb.h"

#include <chrono>
#include <cstddef>
#include <functional>
#include <optional>
#include <stop_token>
#include <string>
#include <string_view>

namespace vibris::mcp {

namespace detail {

[[nodiscard]] bool complete_result_provenance(const Json& provenance);

[[nodiscard]] Json normalize_profile_result(
    const Json& terminal,
    const Json& arguments,
    std::size_t default_warmup_frames,
    bool matrix);

[[nodiscard]] Json normalize_matrix_result(const Json& terminal, const Json& arguments);

[[nodiscard]] Json normalize_action_sequence_result(const Json& terminal, std::string_view kind);

[[nodiscard]] Json normalize_load_and_screenshot_result(const Json& terminal, const Json& arguments);

using ProfileAttempt = std::function<ToolOutcome(const Json&, bool)>;

[[nodiscard]] Json retry_profile(
    const Json& arguments,
    bool matrix,
    std::size_t default_warmup_frames,
    const ProfileAttempt& submit);

}

struct SynchronousJobProgress final {
    std::string request_id;
    std::string stage;
    bool accepted = false;
};

using SynchronousJobProgressSink = std::function<void(const SynchronousJobProgress&)>;

struct SynchronousJobControl final {
    std::stop_token stop;
    std::optional<std::string> resume_request_id;
    SynchronousJobProgressSink progress;
};

class SynchronousJobRunner final {
public:
    SynchronousJobRunner(GrpcClient& client, SourceHandler& sources, const JobContext& config,
        std::chrono::milliseconds maximum_wait = std::chrono::milliseconds::zero());

    [[nodiscard]] ToolOutcome run(
        std::string_view tool_name,
        const Json& arguments,
        const ::vibris::control::v2::ServerHello& server,
        const ::vibris::control::v2::SceneContext& context,
        const SynchronousJobControl& control = {});

private:
    [[nodiscard]] ToolOutcome submit_once(
        std::string_view tool_name,
        const Json& arguments,
        const ::vibris::control::v2::ServerHello& server,
        const ::vibris::control::v2::SceneContext& context,
        const SynchronousJobControl& control);

    [[nodiscard]] ToolOutcome resume_once(
        std::string_view request_id,
        const SynchronousJobControl& control);

    [[nodiscard]] ToolOutcome resume_or_submit(
        std::string_view request_id,
        std::string_view tool_name,
        const Json& arguments,
        const ::vibris::control::v2::ServerHello& server,
        const ::vibris::control::v2::SceneContext& context,
        const SynchronousJobControl& control);

    [[nodiscard]] std::optional<bool> job_present(
        std::string_view request_id,
        std::stop_token stop);

    GrpcClient& client_;
    SourceHandler& sources_;
    const JobContext& config_;
    std::chrono::milliseconds maximum_wait_;
};

}
