#include "vibris_control.grpc.pb.h"

#include <grpcpp/grpcpp.h>
#include <nlohmann/json.hpp>

#include <charconv>
#include <chrono>
#include <cstdint>
#include <iostream>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <thread>

namespace {
namespace proto = vibris::control::v1;
using Json = nlohmann::json;

struct Options {
    std::string host;
    std::uint16_t port = 0;
    std::string workspace_id;
    std::string instance_id;
    std::uint32_t timeout_seconds = 60;
};
template <typename T>
std::optional<T> parse_number(const std::string_view value) {
    T parsed{};
    const auto [end, error] = std::from_chars(value.data(), value.data() + value.size(), parsed);
    if (error != std::errc{} || end != value.data() + value.size()) return std::nullopt;
    return parsed;
}
std::optional<Options> parse_options(const int argc, char** argv) {
    Options options;
    for (int index = 1; index < argc; index += 2) {
        if (index + 1 >= argc) return std::nullopt;
        const std::string_view name(argv[index]);
        const std::string_view value(argv[index + 1]);
        if (name == "--host") options.host = value;
        else if (name == "--workspace-id") options.workspace_id = value;
        else if (name == "--instance-id") options.instance_id = value;
        else if (name == "--port") {
            const auto port = parse_number<std::uint32_t>(value);
            if (!port || *port == 0 || *port > 65535) return std::nullopt;
            options.port = static_cast<std::uint16_t>(*port);
        } else if (name == "--timeout-seconds") {
            const auto timeout = parse_number<std::uint32_t>(value);
            if (!timeout || *timeout == 0) return std::nullopt;
            options.timeout_seconds = *timeout;
        } else return std::nullopt;
    }
    if (options.host.empty() || options.port == 0 || options.workspace_id.empty() || options.instance_id.empty()) {
        return std::nullopt;
    }
    return options;
}
proto::ClientMessage envelope(const Json& command, const Options& options) {
    proto::ClientMessage message;
    message.mutable_protocol_version()->set_major(1);
    message.mutable_protocol_version()->set_minor(0);
    message.set_message_id(command.at("message_id").get<std::string>());
    message.set_workspace_id(options.workspace_id);
    if (command.contains("request_id")) message.set_request_id(command.at("request_id").get<std::string>());
    return message;
}
void set_context(const Json& value, proto::SceneContext* context) {
    context->set_save_id(value.at("save_id").get<std::string>());
    context->set_dimension_id(value.at("dimension_id").get<std::string>());
    context->set_time_preset_id(value.at("time_preset_id").get<std::string>());
    context->set_weather_preset_id(value.at("weather_preset_id").get<std::string>());
    context->set_camera_preset_id(value.at("camera_preset_id").get<std::string>());
    context->set_fov(value.at("fov").get<double>());
    context->set_settings_preset_id(value.at("settings_preset_id").get<std::string>());
    const auto& resolution = value.at("resolution");
    context->mutable_resolution()->set_width(resolution.at("width").get<std::uint32_t>());
    context->mutable_resolution()->set_height(resolution.at("height").get<std::uint32_t>());
}
proto::ArtifactFormat artifact_format(const std::string& value) {
    if (value == "png") return proto::ARTIFACT_FORMAT_PNG;
    if (value == "exr") return proto::ARTIFACT_FORMAT_EXR;
    if (value == "raw") return proto::ARTIFACT_FORMAT_RAW;
    if (value == "bin") return proto::ARTIFACT_FORMAT_BIN;
    throw std::invalid_argument("unsupported artifact format: " + value);
}
void add_action(const Json& value, proto::ActionSequence* sequence) {
    auto* action = sequence->add_actions();
    const auto type = value.at("type").get<std::string>();
    if (type == "reset_temporal_state") {
        action->mutable_reset_temporal_state();
    } else if (type == "wait_frames") {
        action->mutable_wait_frames()->set_frame_count(value.at("frames").get<std::uint32_t>());
    } else if (type == "capture_screenshot") {
        auto* capture = action->mutable_capture_screenshot();
        capture->set_artifact_name(value.value("artifact_name", std::string{"beauty"}));
        capture->set_format(artifact_format(value.value("format", std::string{"png"})));
    } else if (type == "dump_texture") {
        auto* capture = action->mutable_dump_texture();
        capture->set_logical_name(value.at("name").get<std::string>());
        capture->set_mip_level(value.value("mip_level", std::uint32_t{}));
        capture->set_layer(value.value("layer", std::uint32_t{}));
        capture->set_artifact_name(value.value("artifact_name", capture->logical_name()));
        capture->set_format(artifact_format(value.value("format", std::string{"raw"})));
    } else if (type == "dump_buffer") {
        auto* capture = action->mutable_dump_buffer();
        capture->set_logical_name(value.at("name").get<std::string>());
        capture->set_artifact_name(value.value("artifact_name", capture->logical_name()));
        capture->set_format(artifact_format(value.value("format", std::string{"bin"})));
    } else {
        throw std::invalid_argument("unsupported action: " + type);
    }
}
proto::ClientMessage make_submit(const Json& command, const Options& options) {
    auto message = envelope(command, options);
    auto* job = message.mutable_submit_job();
    job->set_request_id(message.request_id());
    job->set_workspace_id(options.workspace_id);
    set_context(command.at("context"), job->mutable_context());
    for (const auto& value : command.at("sources")) {
        auto* source = job->add_sources();
        source->set_uuid(value.at("uuid").get<std::string>());
        source->set_file_count(value.at("file_count").get<std::uint64_t>());
        source->set_total_bytes(value.at("total_bytes").get<std::uint64_t>());
        source->mutable_origin()->mutable_workspace()->set_display_name(options.workspace_id);
    }
    if (command.contains("actions")) {
        for (const auto& action : command.at("actions")) add_action(action, job->mutable_actions());
    } else if (command.contains("wait_frames")) {
        job->mutable_actions()->add_actions()->mutable_wait_frames()->set_frame_count(
            command.at("wait_frames").get<std::uint32_t>());
    } else {
        job->mutable_actions();
    }
    if (command.contains("timeouts")) {
        const auto& value = command.at("timeouts");
        job->mutable_timeouts()->set_queue_timeout_ms(value.value("queue_timeout_ms", std::uint64_t{}));
        job->mutable_timeouts()->set_execution_timeout_ms(value.value("execution_timeout_ms", std::uint64_t{}));
        job->mutable_timeouts()->set_total_timeout_ms(value.value("total_timeout_ms", std::uint64_t{}));
    }
    return message;
}
proto::ClientMessage make_command(const Json& command, const Options& options) {
    const std::string operation = command.at("op").get<std::string>();
    if (operation == "submit") return make_submit(command, options);
    auto message = envelope(command, options);
    if (operation == "cancel") {
        message.mutable_cancel_job()->set_request_id(message.request_id());
        message.mutable_cancel_job()->set_reason(command.value("reason", std::string{}));
    } else if (operation == "resume") {
        for (const auto& id : command.at("request_ids")) {
            message.mutable_resume_request()->add_request_ids(id.get<std::string>());
        }
    } else {
        throw std::invalid_argument("unsupported operation: " + operation);
    }
    return message;
}

Json common_json(const proto::ServerMessage& message, const std::string_view type) {
    return {{"type", type}, {"message_id", message.message_id()}, {"request_id", message.request_id()},
        {"workspace_id", message.workspace_id()}};
}

Json server_json(const proto::ServerMessage& message) {
    if (message.has_server_hello()) {
        const auto& value = message.server_hello();
        auto output = common_json(message, "ServerHello");
        output["protocol_major"] = value.protocol_version().major();
        output["protocol_minor"] = value.protocol_version().minor();
        output["ready"] = value.ready();
        output["pending_shaders_root"] = value.pending_shaders_root();
        output["artifact_root"] = value.artifact_root();
        return output;
    }
    if (message.has_job_accepted()) {
        auto output = common_json(message, "JobAccepted");
        output["queue_position"] = message.job_accepted().queue_position();
        return output;
    }
    if (message.has_job_progress()) {
        auto output = common_json(message, "JobProgress");
        output["stage"] = proto::JobStage_Name(message.job_progress().stage());
        output["percent"] = message.job_progress().percent();
        output["detail"] = message.job_progress().detail();
        return output;
    }
    if (message.has_job_completed()) {
        const auto& value = message.job_completed().result();
        auto output = common_json(message, "JobCompleted");
        output["result"] = {{"kind", proto::JobResultKind_Name(value.kind())},
            {"manifest_path", value.manifest_path()}, {"frame_ids", value.frame_ids()}};
        output["result"]["artifacts"] = Json::array();
        for (const auto& artifact : value.artifacts()) {
            const auto& resource = artifact.resource();
            output["result"]["artifacts"].push_back({
                {"path", artifact.path()},
                {"file_name", artifact.file_name()},
                {"kind", proto::ArtifactKind_Name(artifact.kind())},
                {"format", proto::ArtifactFormat_Name(artifact.format())},
                {"media_type", artifact.media_type()},
                {"byte_size", artifact.byte_size()},
                {"resource", {
                    {"logical_name", resource.logical_name()},
                    {"kind", proto::ResourceKind_Name(resource.kind())},
                    {"width", resource.width()},
                    {"height", resource.height()},
                    {"depth", resource.depth()},
                    {"mip_level", resource.mip_level()},
                    {"layer", resource.layer()},
                    {"internal_format", resource.internal_format()},
                    {"channel_count", resource.channel_count()},
                    {"scalar_type", proto::ScalarType_Name(resource.scalar_type())},
                    {"byte_size", resource.byte_size()},
                    {"frame_id", resource.frame_id()},
                    {"semantic_label", resource.semantic_label()}}}});
        }
        return output;
    }
    if (message.has_job_failed()) {
        const auto& error = message.job_failed().error();
        auto output = common_json(message, "JobFailed");
        output["code"] = proto::ErrorCode_Name(error.code());
        output["message"] = error.message();
        output["retryable"] = error.retryable();
        output["log_path"] = error.log_path();
        output["artifacts"] = Json::array();
        for (const auto& artifact : message.job_failed().artifacts()) {
            output["artifacts"].push_back({{"path", artifact.path()},
                {"file_name", artifact.file_name()},
                {"kind", proto::ArtifactKind_Name(artifact.kind())},
                {"format", proto::ArtifactFormat_Name(artifact.format())},
                {"media_type", artifact.media_type()},
                {"byte_size", artifact.byte_size()}});
        }
        return output;
    }
    if (message.has_resume_state()) {
        auto output = common_json(message, "ResumeState");
        output["jobs"] = Json::array();
        for (const auto& job : message.resume_state().jobs()) {
            output["jobs"].push_back({{"request_id", job.request_id()},
                {"state", proto::JobState_Name(job.state())}, {"stage", proto::JobStage_Name(job.stage())}});
        }
        return output;
    }
    if (message.has_pong()) return common_json(message, "Pong");
    if (message.has_server_shutting_down()) return common_json(message, "ServerShuttingDown");
    return common_json(message, "Unknown");
}

proto::ClientMessage hello(const Options& options) {
    Json value = {{"message_id", "hello-" + options.instance_id}};
    auto message = envelope(value, options);
    auto* payload = message.mutable_client_hello();
    payload->mutable_protocol_version()->CopyFrom(message.protocol_version());
    payload->set_mcp_version("vibris-control-client");
    payload->set_workspace_id(options.workspace_id);
    payload->set_process_instance_uuid(options.instance_id);
    payload->add_capabilities(proto::CAPABILITY_CONTROL_STREAM);
    payload->add_capabilities(proto::CAPABILITY_RESUME);
    payload->add_capabilities(proto::CAPABILITY_PREPARED_SOURCES);
    return message;
}

}

int main(const int argc, char** argv) {
    const auto options = parse_options(argc, argv);
    if (!options) {
        std::cerr << "usage: vibris-control-client --host HOST --port PORT --workspace-id ID "
                     "--instance-id UUID [--timeout-seconds SECONDS]\n";
        return 2;
    }
    auto channel = grpc::CreateChannel(options->host + ":" + std::to_string(options->port),
        grpc::InsecureChannelCredentials());
    auto stub = proto::VibrisControl::NewStub(channel);
    grpc::ClientContext context;
    context.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(options->timeout_seconds));
    auto stream = stub->Control(&context);
    if (!stream->Write(hello(*options))) {
        std::cerr << "failed to send ClientHello\n";
        return 1;
    }
    std::jthread reader([&stream] {
        proto::ServerMessage message;
        while (stream->Read(&message)) {
            std::cout << server_json(message).dump() << '\n' << std::flush;
        }
    });
    int exit_code = 0;
    try {
        std::string line;
        while (std::getline(std::cin, line)) {
            if (line.empty()) continue;
            const auto command = Json::parse(line);
            if (command.at("op") == "close") break;
            if (!stream->Write(make_command(command, *options))) {
                std::cerr << "control stream closed while writing\n";
                exit_code = 1;
                break;
            }
        }
    } catch (const std::exception& error) {
        std::cerr << "invalid command: " << error.what() << '\n';
        context.TryCancel();
        exit_code = 2;
    }
    stream->WritesDone();
    reader.join();
    const grpc::Status status = stream->Finish();
    if (!status.ok() && exit_code == 0) {
        std::cerr << "control stream failed: " << status.error_message() << '\n';
        exit_code = 1;
    }
    return exit_code;
}