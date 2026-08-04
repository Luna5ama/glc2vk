#pragma once

#include "vibris_control.grpc.pb.h"

#include <grpcpp/grpcpp.h>

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <memory>
#include <stdexcept>
#include <string>
#include <utility>

namespace vibris::mcp::test {

namespace fs = std::filesystem;
namespace proto = ::vibris::control::v1;

class TerminalJobService final : public proto::VibrisControl::Service {
public:
    TerminalJobService(proto::ServerHello hello, fs::path artifact_root, bool expected_actions)
        : hello_(std::move(hello)), artifact_root_(std::move(artifact_root)), expected_actions_(expected_actions) {}

    [[nodiscard]] bool valid_submit() const noexcept { return valid_submit_.load(); }
    [[nodiscard]] std::size_t submit_jobs() const noexcept { return submit_jobs_.load(); }
    [[nodiscard]] std::size_t terminal_writes() const noexcept { return terminal_writes_.load(); }

private:
    grpc::Status Control(grpc::ServerContext*,
        grpc::ServerReaderWriter<proto::ServerMessage, proto::ClientMessage>* stream) override {
        bool greeted = false;
        proto::ClientMessage request;
        while (stream->Read(&request)) {
            if (!greeted) {
                if (!request.has_client_hello()) return {grpc::StatusCode::INVALID_ARGUMENT, "CLIENT_HELLO_REQUIRED"};
                greeted = true;
                proto::ServerMessage response;
                response.mutable_protocol_version()->set_major(1);
                response.set_message_id(request.message_id());
                response.set_workspace_id(request.workspace_id());
                response.mutable_server_hello()->CopyFrom(hello_);
                if (!stream->Write(response)) return {grpc::StatusCode::UNAVAILABLE, "hello write failed"};
                continue;
            }
            if (!request.has_submit_job()) return {grpc::StatusCode::INVALID_ARGUMENT, "SUBMIT_JOB_REQUIRED"};
            ++submit_jobs_;
            const auto& job = request.submit_job();
            const auto source_path = job.sources_size() == 1
                ? fs::path(hello_.pending_shaders_root()) / job.sources(0).uuid()
                : fs::path{};
            const bool execution_matches = job.has_actions() &&
                job.actions().actions_size() == (expected_actions_ ? 2 : 4) &&
                job.actions().actions(0).has_load_shader() &&
                (!expected_actions_ || job.actions().actions(1).has_get_shader_status());
            valid_submit_.store(!source_path.empty() && execution_matches &&
                job.context().weather_preset_id() == "clear" &&
                job.context().settings_preset_id() == "quality" && job.context().resolution().width() == 1920 &&
                job.context().resolution().height() == 1080 && job.context().fov() == 72.5 &&
                fs::is_directory(source_path));

            proto::ServerMessage accepted;
            accepted.set_request_id(request.request_id());
            accepted.mutable_job_accepted()->set_request_id(request.request_id());
            if (!stream->Write(accepted)) return {grpc::StatusCode::UNAVAILABLE, "accepted write failed"};
            proto::ServerMessage progress;
            progress.set_request_id(request.request_id());
            progress.mutable_job_progress()->set_request_id(request.request_id());
            progress.mutable_job_progress()->set_stage(proto::JOB_STAGE_WARMING_UP);
            if (!stream->Write(progress)) return {grpc::StatusCode::UNAVAILABLE, "progress write failed"};

            fs::remove_all(source_path);
            proto::ServerMessage completed;
            completed.set_request_id(request.request_id());
            auto* result = completed.mutable_job_completed()->mutable_result();
            result->set_kind(proto::JOB_RESULT_KIND_ACTION_SEQUENCE);
            result->set_manifest_path((artifact_root_ / "manifest.json").string());
            result->add_frame_ids(901);
            result->mutable_timings()->set_total_ms(17);
            if (expected_actions_) {
                auto* action_result = result->add_action_results();
                action_result->set_action_index(1);
                action_result->set_kind(proto::JOB_ACTION_KIND_GET_SHADER_STATUS);
                action_result->set_json(R"({"loaded":true})");
            }
            auto* artifact = result->add_artifacts();
            artifact->set_artifact_id("runtime-artifact");
            artifact->set_file_name("capture.png");
            artifact->set_kind(proto::ARTIFACT_KIND_SCREENSHOT);
            artifact->set_format(proto::ARTIFACT_FORMAT_PNG);
            artifact->set_path((artifact_root_ / "capture.png").string());
            if (!stream->Write(completed)) return {grpc::StatusCode::UNAVAILABLE, "terminal write failed"};
            ++terminal_writes_;
            return grpc::Status::OK;
        }
        return grpc::Status::OK;
    }

    proto::ServerHello hello_;
    fs::path artifact_root_;
    bool expected_actions_;
    std::atomic<bool> valid_submit_ = false;
    std::atomic<std::size_t> submit_jobs_ = 0;
    std::atomic<std::size_t> terminal_writes_ = 0;
};

class TerminalJobServer final {
public:
    TerminalJobServer(const fs::path& pending_root, const fs::path& artifact_root, bool expected_actions)
        : hello_(hello(pending_root, artifact_root)), service_(hello_, artifact_root, expected_actions) {
        grpc::ServerBuilder builder;
        builder.AddListeningPort("127.0.0.1:0", grpc::InsecureServerCredentials(), &port_);
        builder.RegisterService(&service_);
        server_ = builder.BuildAndStart();
        if (!server_ || port_ == 0) throw std::runtime_error("failed to bind terminal job fixture server");
    }

    TerminalJobServer(const TerminalJobServer&) = delete;
    TerminalJobServer& operator=(const TerminalJobServer&) = delete;
    ~TerminalJobServer() { shutdown(); }

    [[nodiscard]] int port() const noexcept { return port_; }
    [[nodiscard]] const proto::ServerHello& server_hello() const noexcept { return hello_; }
    [[nodiscard]] bool valid_submit() const noexcept { return service_.valid_submit(); }
    [[nodiscard]] std::size_t submit_jobs() const noexcept { return service_.submit_jobs(); }
    [[nodiscard]] std::size_t terminal_writes() const noexcept { return service_.terminal_writes(); }

    void shutdown() {
        if (!server_) return;
        server_->Shutdown();
        server_->Wait();
        server_.reset();
    }

private:
    static proto::ServerHello hello(const fs::path& pending_root, const fs::path& artifact_root) {
        proto::ServerHello result;
        result.mutable_protocol_version()->set_major(1);
        result.set_ready(true);
        result.set_pending_shaders_root(fs::absolute(pending_root).string());
        result.set_artifact_root(fs::absolute(artifact_root).string());
        result.mutable_limits()->set_max_source_bytes(1024 * 1024);
        result.mutable_limits()->set_max_source_files(128);
        return result;
    }

    int port_ = 0;
    proto::ServerHello hello_;
    TerminalJobService service_;
    std::unique_ptr<grpc::Server> server_;
};

}
