#include "mcp_backend.hpp"

#include <algorithm>
#include <chrono>
#include <future>
#include <iostream>
#include <string>
#include <utility>

#include "config_document.hpp"
#include "config_store.hpp"
#include "debug_protocol.hpp"
#include "source_handler.hpp"
#include "result_mapper.hpp"
#include "scene_context_resolver.hpp"
#include "state_error.hpp"
#include "synchronous_job_runner.hpp"
#include "workspace_binding.hpp"
#include "worktree_lock.hpp"

namespace vibris::mcp {
namespace {

namespace control = vibris::control::v1;
constexpr std::size_t pending_limit = 256;

Json config_json(const SessionConfig& config) {
    return {{"schema_version", config.schema_version},
            {"workspace_id", config.workspace_id},
            {"shader_directory", config.shader_directory},
            {"save_id", config.save_id},
            {"dimension_id", config.dimension_id},
            {"time_preset_id", config.time_preset_id},
            {"camera_preset_id", config.camera_preset_id},
            {"fov", config.fov},
            {"default_warmup_frames", config.default_warmup_frames}};
}

std::string bounded(std::string value) {
    constexpr std::size_t limit = 512;
    if (value.size() > limit) value.resize(limit);
    return value;
}

} // namespace

class McpBackend::Impl final {
public:
    Impl(std::optional<std::filesystem::path> workspace_root, std::string server_address)
        : binding_(resolve_workspace(std::move(workspace_root))),
          lock_(WorktreeLock::acquire(binding_.root)),
          store_(binding_.config_path),
          server_address_(std::move(server_address)),
          process_id_(detail::generate_uuid()),
          config_(store_.load()),
          source_handler_(binding_.root) {}

    ToolOutcome dispatch(std::string_view name, const Json& arguments) {
        try {
            if (name == "vibris_get_config") return get_config();
            if (name == "vibris_list_presets") return list_presets(arguments);
            if (name == "vibris_configure") return configure(arguments);
            if (name == "vibris_get_status") return get_status();
            if (auto request = DebugProtocol::request(name, arguments)) return debug_control(std::move(*request));
            if (name == "vibris_run_recipe" || name == "vibris_run_actions") {
                return run_job(name, arguments);
            }
            return ToolFailure{"INTERNAL_ERROR", "The validated tool was not dispatched.", false};
        } catch (const StateError& error) {
            return ToolFailure{std::string(error.code()), error.what(), error.retryable()};
        } catch (const std::exception& error) {
            std::cerr << "vibris-mcp tool failure: " << bounded(error.what()) << '\n';
            return ToolFailure{"INTERNAL_ERROR", "Tool execution failed.", false};
        }
    }

    std::optional<GrpcClientStats> shutdown() {
        source_handler_.clear();
        release_client();
        if (!used_client_) return std::nullopt;
        return aggregate_;
    }

private:
    template <typename Response, typename Start, typename Map>
    ToolOutcome unary(Start&& start, Map&& map) {
        auto completion = std::make_shared<std::promise<std::pair<grpc::Status, Response>>>();
        auto result = completion->get_future();
        const auto accepted = std::forward<Start>(start)(
            [completion](const grpc::Status& status, const Response& response) {
                completion->set_value({status, response});
            });
        if (!accepted) return ToolFailure{"QUEUE_FULL", "The bounded gRPC request registry is full.", true};
        if (result.wait_for(std::chrono::seconds(6)) != std::future_status::ready) {
            return ToolFailure{"SERVER_OFFLINE", "The local Vibris server did not respond before its deadline.", true};
        }
        auto [status, response] = result.get();
        if (!status.ok()) return ToolFailure{"SERVER_OFFLINE", bounded(status.error_message()), true};
        return std::forward<Map>(map)(response);
    }

    ToolOutcome get_config() const {
        Json result{{"configured", config_.has_value()}, {"worktree_root", binding_.root.string()}};
        result["workspace_id"] = config_ ? Json(config_->workspace_id) : Json(nullptr);
        result["config"] = config_ ? config_json(*config_) : Json(nullptr);
        return result;
    }

    ToolOutcome list_presets(const Json& arguments) {
        return unary<control::ListPresetsResponse>(
            [this](auto completion) { return client().list_presets(std::move(completion)); },
            [&arguments](const auto& response) -> ToolOutcome {
                auto result = ResultMapper::list_presets(response);
                if (arguments.contains("filter") && result.contains("presets") && result["presets"].is_array()) {
                    const auto& filter = arguments["filter"].get_ref<const std::string&>();
                    auto& presets = result["presets"];
                    // ponytail: serialized substring filter; add indexed fields only if preset catalogs become large.
                    presets.erase(std::remove_if(presets.begin(), presets.end(), [&](const Json& preset) {
                                      return preset.dump().find(filter) == std::string::npos;
                                  }),
                                  presets.end());
                }
                return result;
            });
    }

    ToolOutcome configure(const Json& arguments) {
        control::ValidateContextRequest request;
        auto& context = *request.mutable_context();
        context.set_save_id(arguments["save_id"].get<std::string>());
        context.set_dimension_id(arguments["dimension_id"].get<std::string>());
        context.set_time_preset_id(arguments["time_preset_id"].get<std::string>());
        context.set_camera_preset_id(arguments["camera_preset_id"].get<std::string>());
        context.set_fov(arguments["fov"].get<double>());
        return unary<control::ValidateContextResponse>(
            [this, request = std::move(request)](auto completion) mutable {
                return client().validate_context(std::move(request), std::move(completion));
            },
            [this, &arguments](const auto& response) -> ToolOutcome {
                if (!response.valid()) {
                    return ToolFailure{"INVALID_PRESET", "The Vibris server rejected the scene context.", false,
                                       ResultMapper::validation(response)};
                }
                config_ = store_.save_json(arguments.dump());
                release_client();
                return config_json(*config_);
            });
    }

    ToolOutcome get_status() {
        return unary<control::GetStatusResponse>(
            [this](auto completion) { return client().get_status(std::move(completion)); },
            [this](const auto& response) -> ToolOutcome {
                auto mapped = ResultMapper::status(response);
                Json result = mapped.contains("status") && mapped["status"].is_object() ? mapped["status"] : mapped;
                result["ready"] = mapped.value("ready", false);
                if (mapped.contains("errors")) result["errors"] = mapped["errors"];
                result["configured"] = config_.has_value();
                result["worktree_root"] = binding_.root.string();
                return result;
            });
    }

    ToolOutcome debug_control(control::DebugControlRequest request) {
        return unary<control::DebugControlResponse>(
            [this, request = std::move(request)](auto completion) mutable {
                return client().debug_control(std::move(request), std::move(completion));
            },
            [](const auto& response) -> ToolOutcome { return DebugProtocol::response(response); });
    }

    ToolOutcome run_job(std::string_view name, const Json& arguments) {
        if (!config_) return ToolFailure{"NOT_CONFIGURED", "Configure this worktree before running jobs.", false};
        return unary<control::ListPresetsResponse>(
            [this](auto completion) { return client().list_presets(std::move(completion)); },
            [this, name, &arguments](const auto& presets) -> ToolOutcome {
                const auto context = SceneContextResolver::resolve(*config_, presets);
                return unary<control::GetServerInfoResponse>(
                    [this](auto completion) { return client().get_server_info(std::move(completion)); },
                    [this, name, &arguments, &context](const auto& response) -> ToolOutcome {
                        if (!response.has_server()) {
                            throw StateError(
                                "SERVER_NOT_READY", "The local Vibris server did not provide server info.", true);
                        }
                        return SynchronousJobRunner(client(), source_handler_, *config_).run(
                            name, arguments, response.server(), context);
                    });
            });
    }

    GrpcClient& client() {
        if (!grpc_) {
            grpc_ = std::make_unique<GrpcClient>(GrpcClientOptions{
                .target = server_address_,
                .workspace_id = config_ ? config_->workspace_id : process_id_,
                .mcp_version = "0.1.0",
                .process_instance_uuid = process_id_,
                .pending_request_limit = pending_limit,
            });
            grpc_->start();
            used_client_ = true;
        }
        return *grpc_;
    }

    void release_client() {
        if (!grpc_) return;
        grpc_->shutdown();
        const auto stats = grpc_->stats();
        aggregate_.completion_queue_count = std::max(aggregate_.completion_queue_count, stats.completion_queue_count);
        aggregate_.peak_pending_requests = std::max(aggregate_.peak_pending_requests, stats.peak_pending_requests);
        aggregate_.worker_threads_started += stats.worker_threads_started;
        aggregate_.worker_threads_joined += stats.worker_threads_joined;
        grpc_.reset();
    }

    WorkspaceBinding binding_;
    WorktreeLock lock_;
    ConfigStore store_;
    std::string server_address_;
    std::string process_id_;
    std::optional<SessionConfig> config_;
    SourceHandler source_handler_;
    std::unique_ptr<GrpcClient> grpc_;
    GrpcClientStats aggregate_{};
    bool used_client_ = false;
};

McpBackend::McpBackend(std::optional<std::filesystem::path> workspace_root, std::string server_address)
    : impl_(std::make_unique<Impl>(std::move(workspace_root), std::move(server_address))) {}

McpBackend::~McpBackend() = default;

ToolOutcome McpBackend::dispatch(std::string_view name, const Json& arguments) {
    return impl_->dispatch(name, arguments);
}

std::optional<GrpcClientStats> McpBackend::shutdown() {
    return impl_->shutdown();
}

} // namespace vibris::mcp
