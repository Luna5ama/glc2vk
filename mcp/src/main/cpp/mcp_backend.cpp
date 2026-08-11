#include "mcp_backend.hpp"

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstdint>
#include <filesystem>
#include <functional>
#include <future>
#include <iostream>
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <utility>

#include "config_document.hpp"
#include "profile_matrix_workflow.hpp"
#include "result_mapper.hpp"
#include "scene_context_resolver.hpp"
#include "job_context.hpp"
#include "source_handler.hpp"
#include "state_error.hpp"
#include "synchronous_job_runner.hpp"
#include "workspace_artifact_link.hpp"
#include "workspace_binding.hpp"
#include "workspace_identity_store.hpp"

namespace vibris::mcp {
namespace {

namespace control = vibris::control::v2;
namespace fs = std::filesystem;
constexpr std::size_t pending_limit = 256;

std::string bounded(std::string value) {
    constexpr std::size_t limit = 512;
    if (value.size() > limit) value.resize(limit);
    return value;
}

Json scene_json(const control::SceneContext& context) {
    return {{"save_id", context.save_id()},
            {"dimension_id", context.dimension_id()},
            {"time_preset_id", context.time_preset_id()},
            {"weather_preset_id", context.weather_preset_id()},
            {"camera_preset_id", context.camera_preset_id()},
            {"fov", context.fov()},
            {"resolution", {{"width", context.resolution().width()},
                            {"height", context.resolution().height()}}},
            {"settings_preset_id", context.settings_preset_id()}};
}

control::SceneContext scene_from_json(const Json& value) {
    control::SceneContext context;
    context.set_save_id(value.at("save_id").get<std::string>());
    context.set_dimension_id(value.at("dimension_id").get<std::string>());
    context.set_time_preset_id(value.at("time_preset_id").get<std::string>());
    context.set_weather_preset_id(value.at("weather_preset_id").get<std::string>());
    context.set_camera_preset_id(value.at("camera_preset_id").get<std::string>());
    context.set_fov(value.at("fov").get<double>());
    context.mutable_resolution()->set_width(value.at("resolution").at("width").get<std::uint32_t>());
    context.mutable_resolution()->set_height(value.at("resolution").at("height").get<std::uint32_t>());
    context.set_settings_preset_id(value.at("settings_preset_id").get<std::string>());
    return context;
}

Json preset_json(const control::ScenePreset& preset) {
    Json tags = Json::array();
    for (const auto& tag : preset.tags()) tags.push_back(tag);
    return {{"preset_id", preset.preset_id()},
            {"version", preset.version()},
            {"display_name", preset.display_name()},
            {"tags", std::move(tags)},
            {"preset_sha256", preset.preset_sha256()}};
}

JobContext config_from_preset(const control::ScenePreset& preset, std::string workspace_id) {
    JobContext config;
    config.workspace_id = std::move(workspace_id);
    config.save_id = preset.context().save_id();
    config.dimension_id = preset.context().dimension_id();
    config.time_preset_id = preset.context().time_preset_id();
    config.camera_preset_id = preset.context().camera_preset_id();
    config.fov = preset.context().fov();
    return config;
}

bool has_all_tags(const Json& preset, const Json& requested) {
    const auto tags = preset.find("tags");
    if (tags == preset.end() || !tags->is_array()) return requested.empty();
    return std::all_of(requested.begin(), requested.end(), [&](const Json& requested_tag) {
        const auto expected = requested_tag.get<std::string>();
        return std::any_of(tags->begin(), tags->end(), [&](const Json& tag) {
            return tag.is_string() && tag.get<std::string>() == expected;
        });
    });
}

void merge_stats(GrpcClientStats& aggregate, const GrpcClientStats& current) {
    aggregate.completion_queue_count += current.completion_queue_count;
    aggregate.peak_pending_requests = std::max(aggregate.peak_pending_requests, current.peak_pending_requests);
    aggregate.pending_requests += current.pending_requests;
    aggregate.worker_threads_started += current.worker_threads_started;
    aggregate.worker_threads_joined += current.worker_threads_joined;
    aggregate.control_connected = aggregate.control_connected || current.control_connected;
}

} // namespace

class McpBackend::Impl final {
private:
    class WorkspaceRuntime final {
    public:
        WorkspaceRuntime(WorkspaceBinding binding, std::string workspace_id, std::string server_address)
            : binding_(std::move(binding)),
              workspace_id_(std::move(workspace_id)),
              server_address_(std::move(server_address)),
              process_id_(detail::generate_uuid()),
              source_handler_(binding_.root),
              artifact_link_(binding_.root, workspace_id_),
              jobs_(binding_.root, workspace_id_,
                  [this](DurableJobStepExecution execution) {
                      return run_durable_step(std::move(execution));
                  }) {}

        ToolOutcome dispatch(std::string_view name, const Json& arguments) {
            if (name == "vibris_list_presets") return list_presets(arguments);
            if (name == "vibris_list_resources") return list_resources(arguments);
            if (name == "vibris_get_status") return get_status(arguments);
            if (name == "vibris_job") return job(arguments);
            if (name == "vibris_artifacts") {
                return ToolFailure{"SERVER_NOT_AVAILABLE",
                    "Managed artifact v2 operations are not available from this runtime.", true};
            }
            if (name == "vibris_run_recipe" || name == "vibris_run_actions" || name == "vibris_run_matrix") {
                const bool durable = arguments.value("execution", std::string("sync")) == "async" ||
                    (name == "vibris_run_recipe" &&
                        (arguments.value("recipe", std::string{}) == "profile_matrix" ||
                         arguments.value("recipe", std::string{}) == "compile_validate"));
                if (durable) return start_durable_job(name, arguments);
                if (jobs_.running()) {
                    return ToolFailure{"JOB_BUSY", "A durable job is active.", true};
                }
                return run_job(name, arguments);
            }
            return ToolFailure{"INTERNAL_ERROR", "The validated tool was not dispatched.", false};
        }

        void attach_scope(ToolOutcome& outcome) const {
            const Json scope{{"worktree_root", binding_.root.string()}, {"workspace_id", workspace_id_}};
            if (auto* payload = std::get_if<Json>(&outcome); payload != nullptr && payload->is_object()) {
                payload->update(scope);
                return;
            }
            if (auto* failure = std::get_if<ToolFailure>(&outcome)) {
                if (!failure->details.is_object()) failure->details = Json::object();
                failure->details.update(scope);
            }
        }

        std::optional<GrpcClientStats> shutdown() {
            jobs_.shutdown();
            source_handler_.clear();
            release_client();
            if (!used_client_) return std::nullopt;
            return aggregate_;
        }

    private:
        using SceneContinuation =
            std::function<ToolOutcome(const JobContext&, const control::ScenePreset&)>;

        template <typename Response, typename Start, typename Map>
        ToolOutcome unary(Start&& start, Map&& map,
            const std::chrono::milliseconds wait = std::chrono::seconds(6)) {
            auto completion = std::make_shared<std::promise<std::pair<grpc::Status, Response>>>();
            auto result = completion->get_future();
            const auto accepted = std::forward<Start>(start)(
                [completion](const grpc::Status& status, const Response& response) {
                    completion->set_value({status, response});
                });
            if (!accepted) return ToolFailure{"QUEUE_FULL", "The bounded gRPC request registry is full.", true};
            if (result.wait_for(wait) != std::future_status::ready) {
                return ToolFailure{"SERVER_OFFLINE", "The local Vibris server did not respond before its deadline.", true};
            }
            auto [status, response] = result.get();
            if (!status.ok()) return ToolFailure{"SERVER_OFFLINE", bounded(status.error_message()), true};
            return std::forward<Map>(map)(response);
        }

        ToolOutcome list_presets(const Json& arguments) {
            control::ListPresetsRequest request;
            if (arguments.contains("preset_id")) request.set_preset_id(arguments.at("preset_id").get<std::string>());
            for (const auto& tag : arguments.value("tags", Json::array())) {
                request.add_tags(tag.get<std::string>());
            }
            return unary<control::ListPresetsResponse>(
                [this, request = std::move(request)](auto completion) mutable {
                    return client().list_presets(std::move(request), std::move(completion));
                },
                [&arguments](const auto& response) -> ToolOutcome {
                    auto result = ResultMapper::list_presets(response);
                    if (arguments.contains("preset_id") && result.contains("presets") && result["presets"].is_array()) {
                        const auto filter = arguments["preset_id"].get<std::string>();
                        auto& presets = result["presets"];
                        presets.erase(std::remove_if(presets.begin(), presets.end(), [&](const Json& preset) {
                                          return preset.value("preset_id", std::string{}) != filter;
                                      }),
                                      presets.end());
                    }
                    if (arguments.contains("tags") && result.contains("presets") &&
                        result["presets"].is_array()) {
                        auto& presets = result["presets"];
                        presets.erase(std::remove_if(presets.begin(), presets.end(), [&](const Json& preset) {
                                          return !has_all_tags(preset, arguments["tags"]);
                                      }),
                                      presets.end());
                    }
                    return result;
                });
        }

        ToolOutcome with_scene(const Json& arguments, const SceneContinuation& continuation) {
            control::ListPresetsRequest request;
            request.set_preset_id(arguments.at("preset_id").get<std::string>());
            return unary<control::ListPresetsResponse>(
                [this, request = std::move(request)](auto completion) mutable {
                    return client().list_presets(std::move(request), std::move(completion));
                },
                [this, &arguments, &continuation](const auto& presets) -> ToolOutcome {
                    const auto preset = SceneContextResolver::resolve_preset(
                        arguments.at("preset_id").get<std::string>(), presets);
                    const auto config = config_from_preset(preset, workspace_id_);
                    detail::validate_config(config);
                    control::ValidateContextRequest request;
                    *request.mutable_context() = preset.context();
                    return unary<control::ValidateContextResponse>(
                        [this, request = std::move(request)](auto completion) mutable {
                            return client().validate_context(std::move(request), std::move(completion));
                        },
                        [&config, &preset, &continuation](const auto& response) -> ToolOutcome {
                            if (!response.valid()) {
                                return ToolFailure{"INVALID_PRESET",
                                                   "The Vibris server rejected the scene context.", false,
                                                   ResultMapper::validation(response)};
                            }
                            return continuation(config, preset);
                        });
                });
        }

        ToolOutcome list_resources(const Json& arguments) {
            control::ListResourcesRequest request;
            auto* filter = request.mutable_filter();
            for (const auto& kind : arguments.value("kinds", Json::array())) {
                const auto value = kind.get<std::string>();
                if (value == "final_framebuffer") filter->add_kinds(control::RESOURCE_KIND_FINAL_FRAMEBUFFER);
                else if (value == "texture") filter->add_kinds(control::RESOURCE_KIND_TEXTURE);
                else if (value == "buffer") filter->add_kinds(control::RESOURCE_KIND_BUFFER);
                else if (value == "patched_shaders") filter->add_kinds(control::RESOURCE_KIND_PATCHED_SHADERS);
            }
            if (arguments.contains("logical_name")) {
                filter->set_logical_name(arguments.at("logical_name").get<std::string>());
            }
            if (arguments.contains("pass_id")) filter->set_pass_id(arguments.at("pass_id").get<std::string>());
            return unary<control::ListResourcesResponse>(
                [this, request = std::move(request)](auto completion) mutable {
                    return client().list_resources(std::move(request), std::move(completion));
                },
                [](const auto& response) -> ToolOutcome { return ResultMapper::list_resources(response); });
        }

        ToolOutcome get_status(const Json& arguments) {
            control::GetStatusRequest request;
            const auto detail = arguments.value("detail", std::string("summary"));
            request.set_detail(detail == "full" ? control::STATUS_DETAIL_FULL :
                detail == "jobs" ? control::STATUS_DETAIL_JOBS : control::STATUS_DETAIL_SUMMARY);
            if (arguments.contains("wait_until")) {
                request.set_wait_until(arguments.at("wait_until") == "job_terminal"
                    ? control::STATUS_WAIT_CONDITION_JOB_TERMINAL
                    : control::STATUS_WAIT_CONDITION_CAN_START_JOB);
            }
            if (arguments.contains("job_id")) request.set_job_id(arguments.at("job_id").get<std::string>());
            request.set_timeout_ms(arguments.value("timeout_ms", std::uint64_t{0}));
            return unary<control::GetStatusResponse>(
                [this, request = std::move(request)](auto completion) mutable {
                    return client().get_status(std::move(request), std::move(completion));
                },
                [](const auto& response) -> ToolOutcome {
                    auto mapped = ResultMapper::status(response);
                    return mapped.contains("status") && mapped["status"].is_object() ? mapped["status"] : mapped;
                });
        }

        ToolOutcome job(const Json& arguments) {
            return jobs_.control(arguments);
        }

        ToolOutcome run_durable_step(DurableJobStepExecution execution) {
            const auto context = scene_from_json(execution.arguments.at("__vibris_scene_context"));
            return unary<control::GetServerInfoResponse>(
                [this](auto completion) { return client().get_server_info(std::move(completion)); },
                [this, &execution, &context](const auto& response) -> ToolOutcome {
                    if (!response.has_server()) {
                        throw StateError(
                            "SERVER_NOT_READY", "The local Vibris server did not provide server info.", true);
                    }
                    SynchronousJobControl control{
                        .stop = execution.stop,
                        .resume_request_id = execution.resume_request_id,
                        .progress = [progress = execution.progress](
                                        const SynchronousJobProgress& event) {
                            progress(event.request_id, event.stage, event.accepted);
                        },
                    };
                    auto outcome = SynchronousJobRunner(client(), source_handler_, execution.config).run(
                        "vibris_run_recipe", execution.arguments, response.server(), context, control);
                    artifact_link_.rewrite(outcome);
                    return outcome;
                });
        }

        ToolOutcome start_durable_job(std::string_view name, const Json& arguments) {
            return with_scene(arguments, [this, name, &arguments](const auto& config, const auto& preset) -> ToolOutcome {
                auto enriched = arguments;
                enriched["__vibris_scene_context"] = scene_json(preset.context());
                enriched["__vibris_preset"] = preset_json(preset);
                return jobs_.start(name, enriched, config);
            });
        }

        ToolOutcome run_job(std::string_view name, const Json& arguments) {
            if (name == "vibris_run_recipe" &&
                arguments.value("recipe", std::string{}) == "recover_runtime") {
                JobContext config;
                config.workspace_id = workspace_id_;
                control::SceneContext context;
                return unary<control::GetServerInfoResponse>(
                    [this](auto completion) { return client().get_server_info(std::move(completion)); },
                    [this, &arguments, &context, &config](const auto& response) -> ToolOutcome {
                        if (!response.has_server()) {
                            throw StateError(
                                "SERVER_NOT_READY", "The local Vibris server did not provide server info.", true);
                        }
                        return SynchronousJobRunner(client(), source_handler_, config).run(
                            "vibris_run_recipe", arguments, response.server(), context);
                    });
            }
            return with_scene(arguments,
                [this, name, &arguments](const auto& config, const auto& preset) -> ToolOutcome {
                    const auto context = preset.context();
                    auto enriched = arguments;
                    enriched["__vibris_preset"] = preset_json(preset);
                    return unary<control::GetServerInfoResponse>(
                        [this](auto completion) { return client().get_server_info(std::move(completion)); },
                        [this, name, &enriched, &context, &config](const auto& response) -> ToolOutcome {
                            if (!response.has_server()) {
                                throw StateError(
                                    "SERVER_NOT_READY", "The local Vibris server did not provide server info.", true);
                            }
                            auto outcome = SynchronousJobRunner(client(), source_handler_, config).run(
                                name, enriched, response.server(), context);
                            artifact_link_.rewrite(outcome);
                            return outcome;
                        });
                });
        }

        GrpcClient& client() {
            if (!grpc_) {
                grpc_ = std::make_unique<GrpcClient>(GrpcClientOptions{
                    .target = server_address_,
                    .workspace_id = workspace_id_,
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
            aggregate_.completion_queue_count =
                std::max(aggregate_.completion_queue_count, stats.completion_queue_count);
            aggregate_.peak_pending_requests =
                std::max(aggregate_.peak_pending_requests, stats.peak_pending_requests);
            aggregate_.worker_threads_started += stats.worker_threads_started;
            aggregate_.worker_threads_joined += stats.worker_threads_joined;
            grpc_.reset();
        }

        WorkspaceBinding binding_;
        std::string workspace_id_;
        std::string server_address_;
        std::string process_id_;
        SourceHandler source_handler_;
        WorkspaceArtifactLink artifact_link_;
        std::unique_ptr<GrpcClient> grpc_;
        DurableJobWorkflow jobs_;
        GrpcClientStats aggregate_{};
        bool used_client_ = false;
    };

public:
    explicit Impl(std::string server_address) : server_address_(std::move(server_address)) {}

    ToolOutcome dispatch(std::string_view name, const Json& arguments) {
        try {
            const auto root = arguments.find("worktree_root");
            if (root == arguments.end() || !root->is_string()) {
                throw StateError(kInvalidWorktreeCode, "Every Vibris request requires worktree_root.");
            }
            const auto binding = resolve_workspace(fs::path(root->get<std::string>()));
            auto scoped = arguments;
            scoped.erase("worktree_root");
            auto& runtime = workspace(binding);
            auto outcome = runtime.dispatch(name, scoped);
            runtime.attach_scope(outcome);
            return outcome;
        } catch (const StateError& error) {
            return ToolFailure{std::string(error.code()), error.what(), error.retryable()};
        } catch (const std::exception& error) {
            std::cerr << "vibris-mcp tool failure: " << bounded(error.what()) << '\n';
            return ToolFailure{"INTERNAL_ERROR", "Tool execution failed.", false};
        }
    }

    std::optional<GrpcClientStats> shutdown() {
        if (shutdown_) return std::nullopt;
        shutdown_ = true;
        std::optional<GrpcClientStats> result;
        for (auto& [root, runtime] : workspaces_) {
            static_cast<void>(root);
            if (const auto stats = runtime->shutdown()) {
                if (!result) {
                    result.emplace();
                    result->completion_queue_count = 0;
                }
                merge_stats(*result, *stats);
            }
        }
        workspaces_.clear();
        return result;
    }

private:
    WorkspaceRuntime& workspace(const WorkspaceBinding& binding) {
        for (auto& [root, runtime] : workspaces_) {
            std::error_code error;
            if (fs::equivalent(root, binding.root, error) && !error) return *runtime;
        }
        auto runtime = std::make_unique<WorkspaceRuntime>(
            binding, WorkspaceIdentityStore(binding.identity_path).load_or_create(), server_address_);
        auto* const result = runtime.get();
        workspaces_.emplace(binding.root, std::move(runtime));
        return *result;
    }

    std::string server_address_;
    std::map<fs::path, std::unique_ptr<WorkspaceRuntime>> workspaces_;
    bool shutdown_ = false;
};

McpBackend::McpBackend(std::string server_address)
    : impl_(std::make_unique<Impl>(std::move(server_address))) {}

McpBackend::~McpBackend() = default;

ToolOutcome McpBackend::dispatch(std::string_view name, const Json& arguments) {
    return impl_->dispatch(name, arguments);
}

std::optional<GrpcClientStats> McpBackend::shutdown() {
    return impl_->shutdown();
}

} // namespace vibris::mcp
