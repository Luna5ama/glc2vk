#include "vibris_control.grpc.pb.h"
#include "protocol_version.hpp"

#include <grpcpp/grpcpp.h>

#include <charconv>
#include <chrono>
#include <cstdint>
#include <iostream>
#include <limits>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace {

using vibris::control::v2::CAPABILITY_CONTROL_STREAM;
using vibris::control::v2::Capability;
using vibris::control::v2::ClientMessage;
using vibris::control::v2::ServerMessage;
using vibris::control::v2::VibrisControl;

struct Options {
    std::string host;
    std::uint16_t port = 0;
    std::uint32_t protocol_major = 0;
    std::uint32_t protocol_minor = 0;
    std::vector<Capability> capabilities;
    std::string message_id;
    std::string scenario;
};

template <typename T>
std::optional<T> parse_number(std::string_view value) {
    T parsed{};
    const auto [end, error] = std::from_chars(value.data(), value.data() + value.size(), parsed);
    if (error != std::errc{} || end != value.data() + value.size()) {
        return std::nullopt;
    }
    return parsed;
}

std::optional<Capability> parse_capability(std::string_view value) {
    Capability capability;
    if (vibris::control::v2::Capability_Parse(std::string(value), &capability)) {
        return capability;
    }
    const auto number = parse_number<std::uint32_t>(value);
    if (!number || *number > static_cast<std::uint32_t>(std::numeric_limits<int>::max())) {
        return std::nullopt;
    }
    return static_cast<Capability>(*number);
}

std::optional<Options> parse_options(int argc, char** argv) {
    Options options;
    for (int index = 1; index < argc; index += 2) {
        if (index + 1 >= argc) {
            return std::nullopt;
        }
        const std::string_view name(argv[index]);
        const std::string_view value(argv[index + 1]);
        if (name == "--host") {
            options.host = value;
        } else if (name == "--port") {
            const auto port = parse_number<std::uint32_t>(value);
            if (!port || *port == 0 || *port > std::numeric_limits<std::uint16_t>::max()) {
                return std::nullopt;
            }
            options.port = static_cast<std::uint16_t>(*port);
        } else if (name == "--protocol-major") {
            const auto major = parse_number<std::uint32_t>(value);
            if (!major || *major == 0) {
                return std::nullopt;
            }
            options.protocol_major = *major;
        } else if (name == "--protocol-minor") {
            const auto minor = parse_number<std::uint32_t>(value);
            if (!minor) {
                return std::nullopt;
            }
            options.protocol_minor = *minor;
        } else if (name == "--capability") {
            const auto capability = parse_capability(value);
            if (!capability) {
                return std::nullopt;
            }
            options.capabilities.push_back(*capability);
        } else if (name == "--message-id") {
            options.message_id = value;
        } else if (name == "--scenario") {
            options.scenario = value;
        } else {
            return std::nullopt;
        }
    }
    if (options.host.empty() || options.port == 0 || options.message_id.empty()) {
        return std::nullopt;
    }
    if (options.scenario != "hello" && options.scenario != "hello-ping-pong") {
        return std::nullopt;
    }
    if (options.capabilities.empty()) {
        options.capabilities.push_back(CAPABILITY_CONTROL_STREAM);
    }
    return options;
}

std::string json_string(std::string_view value) {
    constexpr char hex[] = "0123456789abcdef";
    std::string escaped;
    escaped.reserve(value.size() + 2);
    escaped.push_back('"');
    for (const unsigned char character : value) {
        switch (character) {
            case '"': escaped += "\\\""; break;
            case '\\': escaped += "\\\\"; break;
            case '\b': escaped += "\\b"; break;
            case '\f': escaped += "\\f"; break;
            case '\n': escaped += "\\n"; break;
            case '\r': escaped += "\\r"; break;
            case '\t': escaped += "\\t"; break;
            default:
                if (character < 0x20) {
                    escaped += "\\u00";
                    escaped.push_back(hex[character >> 4]);
                    escaped.push_back(hex[character & 0x0f]);
                } else {
                    escaped.push_back(static_cast<char>(character));
                }
        }
    }
    escaped.push_back('"');
    return escaped;
}

void print_message(std::string_view type, const ServerMessage& message) {
    std::cout << "{\"type\":" << json_string(type)
              << ",\"protocol_major\":" << message.protocol_version().major()
              << ",\"protocol_minor\":" << message.protocol_version().minor()
              << ",\"message_id\":" << json_string(message.message_id()) << "}\n";
}

bool is_unsupported_version(const grpc::Status& status) {
    return status.error_message().find(vibris::mcp::unsupported_version_code) != std::string::npos;
}

int finish_failed_stream(std::unique_ptr<grpc::ClientReaderWriter<ClientMessage, ServerMessage>>& stream) {
    stream->WritesDone();
    const grpc::Status status = stream->Finish();
    if (is_unsupported_version(status)) {
        std::cout << "{\"type\":\"ProtocolRejected\",\"code\":\"UNSUPPORTED_VERSION\"}\n";
        return 0;
    }
    std::cerr << "control stream failed: " << status.error_message() << '\n';
    return 1;
}

ClientMessage make_hello(const Options& options) {
    ClientMessage message;
    message.mutable_protocol_version()->set_major(options.protocol_major);
    message.mutable_protocol_version()->set_minor(options.protocol_minor);
    message.set_message_id(options.message_id);
    message.set_workspace_id("protocol-smoke");
    auto* hello = message.mutable_client_hello();
    hello->set_client_version("protocol-smoke");
    hello->set_process_instance_id("protocol-smoke");
    for (const Capability capability : options.capabilities) {
        hello->add_capabilities(capability);
    }
    return message;
}

ClientMessage make_ping(const Options& options) {
    ClientMessage message;
    message.mutable_protocol_version()->set_major(options.protocol_major);
    message.mutable_protocol_version()->set_minor(options.protocol_minor);
    message.set_message_id(options.message_id);
    message.set_workspace_id("protocol-smoke");
    message.mutable_ping()->set_sequence(1);
    return message;
}

}

int main(int argc, char** argv) {
    const auto options = parse_options(argc, argv);
    if (!options) {
        std::cerr << "usage: vibris-protocol-smoke --host HOST --port PORT --protocol-major MAJOR "
                     "[--protocol-minor MINOR] [--capability CAPABILITY] --message-id ID "
                     "--scenario hello|hello-ping-pong\n";
        return 2;
    }
    vibris::control::v2::ProtocolVersion requested_version;
    requested_version.set_major(options->protocol_major);
    requested_version.set_minor(options->protocol_minor);
    if (!vibris::mcp::protocol_version_supported(options->protocol_major != 0, requested_version)) {
        std::cerr << "{\"type\":\"ProtocolRejected\",\"code\":\"UNSUPPORTED_VERSION\"}\n";
        return 0;
    }

    auto channel = grpc::CreateChannel(
        options->host + ":" + std::to_string(options->port),
        grpc::InsecureChannelCredentials());
    auto stub = VibrisControl::NewStub(channel);
    grpc::ClientContext context;
    context.set_deadline(std::chrono::system_clock::now() + std::chrono::seconds(10));
    auto stream = stub->Control(&context);

    if (!stream->Write(make_hello(*options))) {
        return finish_failed_stream(stream);
    }

    ServerMessage response;
    if (!stream->Read(&response)) {
        return finish_failed_stream(stream);
    }
    if (!response.has_server_hello()) {
        std::cerr << "expected ServerHello\n";
        context.TryCancel();
        stream->WritesDone();
        stream->Finish();
        return 1;
    }
    print_message("ServerHello", response);

    if (options->scenario == "hello-ping-pong") {
        if (!stream->Write(make_ping(*options))) {
            context.TryCancel();
            stream->WritesDone();
            const grpc::Status status = stream->Finish();
            std::cerr << "failed to send Ping: " << status.error_message() << '\n';
            return 1;
        }
    }
    stream->WritesDone();

    bool received_pong = options->scenario == "hello";
    while (stream->Read(&response)) {
        if (response.has_pong() && !received_pong) {
            print_message("Pong", response);
            received_pong = true;
        }
    }
    const grpc::Status status = stream->Finish();
    if (!status.ok()) {
        std::cerr << "control stream failed: " << status.error_message() << '\n';
        return 1;
    }
    if (!received_pong) {
        std::cerr << "control stream ended without Pong\n";
        return 1;
    }
    return 0;
}