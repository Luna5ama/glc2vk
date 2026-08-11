#pragma once

#include "job_context.hpp"
#include "tool_registry.hpp"

#include <filesystem>
#include <functional>
#include <mutex>
#include <optional>
#include <stop_token>
#include <string>
#include <string_view>
#include <thread>

namespace vibris::mcp {

struct ProfileMatrixCaseExecution final {
    Json arguments;
    JobContext config;
    std::optional<std::string> resume_request_id;
    std::stop_token stop;
    std::function<void(std::string_view request_id, std::string_view stage, bool accepted)> progress;
};

using ProfileMatrixCaseExecutor = std::function<ToolOutcome(ProfileMatrixCaseExecution)>;

class ProfileMatrixWorkflow final {
public:
    ProfileMatrixWorkflow(
        std::filesystem::path workspace_root,
        std::string workspace_id,
        ProfileMatrixCaseExecutor executor);
    ~ProfileMatrixWorkflow();

    ProfileMatrixWorkflow(const ProfileMatrixWorkflow&) = delete;
    ProfileMatrixWorkflow& operator=(const ProfileMatrixWorkflow&) = delete;

    [[nodiscard]] ToolOutcome start(const Json& arguments, const JobContext& config);
    [[nodiscard]] ToolOutcome control(const Json& arguments);
    [[nodiscard]] Json active_status() const;
    [[nodiscard]] bool running() const;
    void shutdown();

private:
    [[nodiscard]] Json create_checkpoint(const Json& arguments, const JobContext& config) const;
    [[nodiscard]] Json load(std::string_view job_id) const;
    void save(const Json& document) const;
    [[nodiscard]] Json result(const Json& document) const;
    [[nodiscard]] ToolOutcome begin(std::string job_id, bool asynchronous);
    void execute(std::string job_id, std::stop_token stop) noexcept;
    void finish_active(std::string_view job_id) noexcept;
    void reap_finished();

    std::filesystem::path workspace_root_;
    std::filesystem::path state_directory_;
    std::string workspace_id_;
    ProfileMatrixCaseExecutor executor_;
    mutable std::mutex store_mutex_;
    mutable std::mutex worker_mutex_;
    std::jthread worker_;
    std::string active_job_id_;
    bool worker_running_ = false;
};

}
