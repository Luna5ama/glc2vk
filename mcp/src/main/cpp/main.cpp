#include <filesystem>
#include <iostream>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>

#include "mcp_stdio_server.hpp"
#include "phase_one_backend.hpp"
#include "state_error.hpp"
#include "tool_registry.hpp"

namespace {

constexpr std::size_t pending_limit = 256;

struct LaunchOptions final {
    std::optional<std::filesystem::path> workspace_root;
    std::string server_address = "127.0.0.1:50051";
    bool server_address_set = false;
};

LaunchOptions parse_options(int argc, char** argv) {
    LaunchOptions options;
    for (int index = 1; index < argc; ++index) {
        const std::string_view argument(argv[index]);
        if (argument == "--workspace-root" && !options.workspace_root && index + 1 < argc) {
            options.workspace_root = std::filesystem::path(argv[++index]);
        } else if (argument == "--server-address" && !options.server_address_set && index + 1 < argc) {
            options.server_address = argv[++index];
            options.server_address_set = true;
        } else {
            throw std::invalid_argument("usage: vibris-mcp [--workspace-root PATH] [--server-address LOOPBACK:PORT]");
        }
    }
    if (options.server_address.empty()) throw std::invalid_argument("server address must not be empty");
    return options;
}

std::string bounded(std::string value) {
    constexpr std::size_t limit = 512;
    if (value.size() > limit) value.resize(limit);
    return value;
}

} // namespace

int main(int argc, char** argv) {
    try {
        auto options = parse_options(argc, argv);
        vibris::mcp::PhaseOneBackend backend(std::move(options.workspace_root), std::move(options.server_address));
        const vibris::mcp::ToolRegistry tools(
            [&backend](std::string_view name, const vibris::mcp::Json& arguments) {
                return backend.dispatch(name, arguments);
            });
        const auto exit_code = vibris::mcp::McpStdioServer(std::cin, std::cout, tools).run();
        if (const auto stats = backend.shutdown()) {
            std::cerr << "pending_peak=" << stats->peak_pending_requests << " pending_limit=" << pending_limit
                      << " completion_queues=" << stats->completion_queue_count
                      << " worker_threads_joined=" << stats->worker_threads_joined << '\n';
        }
        return exit_code;
    } catch (const vibris::mcp::StateError& error) {
        std::cerr << error.code() << ": " << bounded(error.what()) << '\n';
    } catch (const std::exception& error) {
        std::cerr << "INTERNAL_ERROR: " << bounded(error.what()) << '\n';
    }
    return 1;
}