#pragma once

#include <cstdint>

namespace vibris::mcp {

struct SourceLimits final {
    std::uint64_t max_total_bytes;
    std::uint32_t max_files;
};

} // namespace vibris::mcp