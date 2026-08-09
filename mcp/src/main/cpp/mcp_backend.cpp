#include "mcp_backend.hpp"

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstdint>
#include <future>
#include <iostream>
#include <string>
#include <utility>

#include "config_document.hpp"
#include "profile_matrix_workflow.hpp"
#include "session_config.hpp"
#include "source_handler.hpp"
#include "result_mapper.hpp"
#include "scene_context_resolver.hpp"
#include "state_error.hpp"
#include "synchronous_job_runner.hpp"
#include "workspace_binding.hpp"
#include "workspace_artifact_link.hpp"
#include "workspace_identity_store.hpp"

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

Json resolved_config_json(const SessionConfig& config, const control::ScenePreset& preset) {
    auto result = config_json(config);
    result["selector"] = {{"kind", "preset"}, {"preset_id", preset.preset_id()}};
    result["resolved_scene_context"] = scene_json(preset.context());
    result["scene_preset"] = preset_json(preset);
    return result;
}

SessionConfig config_from_preset(
    const control::ScenePreset& preset, std::string workspace_id, std::uint32_t default_warmup_frames) {
    SessionConfig config;
    config.workspace_id = std::move(workspace_id);
    config.save_id = preset.context().save_id();
    config.dimension_id = preset.context().dimension_id();
    config.time_preset_id = preset.context().time_preset_id();
    config.camera_preset_id = preset.context().camera_preset_id();
    config.fov = preset.context().fov();
    config.default_warmup_frames = default_warmup_frames;
    return config;
}

std::string lowercase(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char character) {
        return static_cast<char>(std::tolower(character));
    });
    return value;
}

bool has_all_tags(const Json& preset, const Json& requested) {
    const auto tags = preset.find("tags");
    if (tags == preset.end() || !tags->is_array()) return requested.empty();
    return std::all_of(requested.begin(), requested.end(), [&](const Json& requested_tag) {
        const auto expected = lowercase(requested_tag.get<std::string>());
        return std::any_of(tags->begin(), tags->end(), [&](const Json& tag) {
            return tag.is_string() && lowercase(tag.get<std::string>()) == expected;
        });
    });
}

} // namespace

class McpBackend::Impl final {
public:
    explicit Impl(std::string server_address)
        : binding_(resolve_workspace()),
          workspace_id_(WorkspaceIdentityStore(binding_.identity_path).load_or_create()),
          server_address_(std::move(server_address)),
          process_id_(detail::generate_uuid()),
          source_handler_(binding_.root),
          artifact_link_(binding_.root, workspace_id_),
          profile_matrix_(binding_.root, workspace_id_,
              [this](ProfileMatrixCaseExecution execution) {
                  return run_profile_case(std::move(execution));
              }) {}

    ToolOutcome dispatch(std::string_view name, const Json& arguments) {
        try {
            if (name == "vibris_get_config") return get_config();
            if (name == "vibris_list_presets") return list_presets(arguments);
            if (name == "vibris_configure") return configure(arguments);
            if (name == "vibris_get_status") return get_status();
            if (name == "vibris_run_recipe" &&
                arguments.value("recipe", std::string{}) == "profile_matrix") {
                if (arguments.contains("operation")) return profile_matrix_.control(arguments);
                if (!config_) {
                    return ToolFailure{"NOT_CONFIGURED", "Configure this worktree before running jobs.", false};
                }
                return start_profile_matrix(arguments);
            }
            if (name == "vibris_run_recipe" || name == "vibris_run_actions" || name == "vibris_run_matrix") {
                if (profile_matrix_.running()) {
                    return ToolFailure{"PROFILE_MATRIX_BUSY", "A profile matrix workflow is active.", true};
                }
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
        profile_matrix_.shutdown();
        source_handler_.clear();
        release_client();
        if (!used_client_) return std::nullopt;
        return aggregate_;
    }

private:
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

    ToolOutcome get_config() const {
        Json result{{"configured", config_.has_value()}, {"worktree_root", binding_.root.string()}};
        result["workspace_id"] = workspace_id_;
        result["config"] = config_ && configured_preset_
            ? resolved_config_json(*config_, *configured_preset_)
            : Json(nullptr);
        return result;
    }

    ToolOutcome list_presets(const Json& arguments) {
        return unary<control::ListPresetsResponse>(
            [this](auto completion) { return client().list_presets(std::move(completion)); },
            [&arguments](const auto& response) -> ToolOutcome {
                auto result = ResultMapper::list_presets(response);
                if (arguments.contains("filter") && result.contains("presets") && result["presets"].is_array()) {
                    const auto filter = lowercase(arguments["filter"].get<std::string>());
                    auto& presets = result["presets"];
                    presets.erase(std::remove_if(presets.begin(), presets.end(), [&](const Json& preset) {
                                      return lowercase(preset.dump()).find(filter) == std::string::npos;
                                  }),
                                  presets.end());
                }
                if (arguments.contains("filter_tags") && result.contains("presets") &&
                    result["presets"].is_array()) {
                    auto& presets = result["presets"];
                    presets.erase(std::remove_if(presets.begin(), presets.end(), [&](const Json& preset) {
                                      return !has_all_tags(preset, arguments["filter_tags"]);
                                  }),
                                  presets.end());
                }
                return result;
            });
    }

    ToolOutcome configure(const Json& arguments) {
        return unary<control::ListPresetsResponse>(
            [this](auto completion) { return client().list_presets(std::move(completion)); },
            [this, &arguments](const auto& presets) -> ToolOutcome {
                control::ScenePreset preset;
                std::uint32_t default_warmup_frames = 32;
                if (arguments.value("kind", std::string{}) == "preset") {
                    preset = SceneContextResolver::resolve_preset(
                        arguments.at("preset_id").get<std::string>(), presets);
                    default_warmup_frames = arguments.value("default_warmup_frames", std::uint32_t{32});
                } else {
                    auto requested = detail::parse_config(
                        arguments.dump(), detail::ConfigDocumentKind::configure_request);
                    requested.workspace_id = workspace_id_;
                    detail::validate_config(requested);
                    preset = SceneContextResolver::resolve_preset(requested, presets);
                    default_warmup_frames = requested.default_warmup_frames;
                }
                auto config = config_from_preset(preset, workspace_id_, default_warmup_frames);
                detail::validate_config(config);
                control::ValidateContextRequest request;
                *request.mutable_context() = preset.context();
                return unary<control::ValidateContextResponse>(
                    [this, request = std::move(request)](auto completion) mutable {
                        return client().validate_context(std::move(request), std::move(completion));
                    },
                    [this, config = std::move(config), preset = std::move(preset)](
                        const auto& response) mutable -> ToolOutcome {
                        if (!response.valid()) {
                            return ToolFailure{"INVALID_PRESET",
                                               "The Vibris server rejected the scene context.", false,
                                               ResultMapper::validation(response)};
                        }
                        config_ = std::move(config);
                        configured_preset_ = std::move(preset);
                        return resolved_config_json(*config_, *configured_preset_);
                    });
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
                result["workspace_id"] = workspace_id_;
                result["worktree_root"] = binding_.root.string();
                result["profile_matrix_job"] = profile_matrix_.active_status();
                return result;
            });
    }

    ToolOutcome run_profile_case(ProfileMatrixCaseExecution execution) {
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
                    .progress = [progress = execution.progress](const SynchronousJobProgress& event) {
                        progress(event.request_id, event.stage, event.accepted);
                    },
                };
                auto outcome = SynchronousJobRunner(client(), source_handler_, execution.config).run(
                    "vibris_run_recipe", execution.arguments, response.server(), context, control);
                artifact_link_.rewrite(outcome);
                return outcome;
            });
    }

    ToolOutcome start_profile_matrix(const Json& arguments) {
        return unary<control::ListPresetsResponse>(
            [this](auto completion) { return client().list_presets(std::move(completion)); },
            [this, &arguments](const auto& presets) -> ToolOutcome {
                const auto preset = SceneContextResolver::resolve_preset(*config_, presets);
                auto enriched = arguments;
                enriched["__vibris_scene_context"] = scene_json(preset.context());
                enriched["__vibris_preset"] = preset_json(preset);
                return profile_matrix_.start(enriched, *config_);
            });
    }

    ToolOutcome run_job(std::string_view name, const Json& arguments) {
        if (!config_) return ToolFailure{"NOT_CONFIGURED", "Configure this worktree before running jobs.", false};
        return unary<control::ListPresetsResponse>(
            [this](auto completion) { return client().list_presets(std::move(completion)); },
            [this, name, &arguments](const auto& presets) -> ToolOutcome {
                const auto preset = SceneContextResolver::resolve_preset(*config_, presets);
                const auto context = preset.context();
                auto enriched = arguments;
                enriched["__vibris_preset"] = preset_json(preset);
                return unary<control::GetServerInfoResponse>(
                    [this](auto completion) { return client().get_server_info(std::move(completion)); },
                    [this, name, &enriched, &context](const auto& response) -> ToolOutcome {
                        if (!response.has_server()) {
                            throw StateError(
                                "SERVER_NOT_READY", "The local Vibris server did not provide server info.", true);
                        }
                        auto outcome = SynchronousJobRunner(client(), source_handler_, *config_).run(
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
        aggregate_.completion_queue_count = std::max(aggregate_.completion_queue_count, stats.completion_queue_count);
        aggregate_.peak_pending_requests = std::max(aggregate_.peak_pending_requests, stats.peak_pending_requests);
        aggregate_.worker_threads_started += stats.worker_threads_started;
        aggregate_.worker_threads_joined += stats.worker_threads_joined;
        grpc_.reset();
    }

    WorkspaceBinding binding_;
    std::string workspace_id_;
    std::string server_address_;
    std::string process_id_;
    std::optional<SessionConfig> config_;
    std::optional<control::ScenePreset> configured_preset_;
    SourceHandler source_handler_;
    WorkspaceArtifactLink artifact_link_;
    std::unique_ptr<GrpcClient> grpc_;
    ProfileMatrixWorkflow profile_matrix_;
    GrpcClientStats aggregate_{};
    bool used_client_ = false;
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
