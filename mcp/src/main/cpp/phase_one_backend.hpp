#pragma once

#include <filesystem>
#include <memory>
#include <optional>
#include <string>
#include <string_view>

#include "grpc_client.hpp"
#include "tool_registry.hpp"

namespace vibris::mcp {

class PhaseOneBackend final {
public:
    PhaseOneBackend(std::optional<std::filesystem::path> workspace_root, std::string server_address);
    ~PhaseOneBackend();

    PhaseOneBackend(const PhaseOneBackend&) = delete;
    PhaseOneBackend& operator=(const PhaseOneBackend&) = delete;

    [[nodiscard]] ToolOutcome dispatch(std::string_view name, const Json& arguments);
    [[nodiscard]] std::optional<GrpcClientStats> shutdown();

private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace vibris::mcp