#pragma once

#include <cstddef>
#include <istream>
#include <ostream>
#include <string_view>

#include <nlohmann/json_fwd.hpp>

#include "tool_registry.hpp"

namespace vibris::mcp {

class McpStdioServer final {
public:
    static constexpr std::size_t max_message_bytes = 1024U * 1024U;

    McpStdioServer(std::istream& input, std::ostream& output, const ToolRegistry& tools) noexcept;

    int run();

private:
    void handle_line(std::string_view line);
    void write(const Json& message);

    std::istream& input_;
    std::ostream& output_;
    const ToolRegistry& tools_;
};

} // namespace vibris::mcp