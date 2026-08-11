#pragma once

#include "job_context.hpp"

#include <string>
#include <string_view>

namespace vibris::mcp::detail {

enum class ConfigDocumentKind {
    persisted,
};

void validate_config(const JobContext& config, bool workspace_required = true);
[[nodiscard]] bool is_uuid(std::string_view value);
[[nodiscard]] JobContext parse_config(std::string_view text, ConfigDocumentKind kind);
[[nodiscard]] std::string serialize_config(const JobContext& config);
[[nodiscard]] std::string generate_uuid();

} // namespace vibris::mcp::detail
