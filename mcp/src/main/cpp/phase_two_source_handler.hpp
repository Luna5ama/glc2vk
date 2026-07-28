#pragma once

#include "source_preparer.hpp"
#include "tool_registry.hpp"
#include "vibris_control.pb.h"

#include <filesystem>
#include <list>
#include <string_view>

namespace vibris::mcp {

class PhaseTwoSourceHandler final {
public:
    explicit PhaseTwoSourceHandler(std::filesystem::path workspace_root);

    [[nodiscard]] Json prepare(
        std::string_view tool_name,
        const Json& arguments,
        const ::vibris::control::v1::ServerHello& server);
    void clear() noexcept;

private:
    std::filesystem::path workspace_root_;
    std::list<PreparedSource> owned_sources_;
};

} // namespace vibris::mcp