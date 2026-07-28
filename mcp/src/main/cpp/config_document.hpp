#pragma once

#include "config_store.hpp"

#include <string>
#include <string_view>

namespace vibris::mcp::detail {

enum class ConfigDocumentKind {
    persisted,
    configure_request,
};

void validate_config(const SessionConfig& config, bool workspace_required = true);
[[nodiscard]] SessionConfig parse_config(std::string_view text, ConfigDocumentKind kind);
[[nodiscard]] std::string serialize_config(const SessionConfig& config);
[[nodiscard]] std::string generate_uuid();

} // namespace vibris::mcp::detail