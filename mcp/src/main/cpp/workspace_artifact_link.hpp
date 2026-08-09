#pragma once

#include "tool_registry.hpp"

#include <filesystem>
#include <string>

namespace vibris::mcp {

class WorkspaceArtifactLink final {
public:
    WorkspaceArtifactLink(std::filesystem::path workspace_root, std::string workspace_id);

    void rewrite(ToolOutcome& outcome) const;

private:
    std::filesystem::path workspace_root_;
    std::filesystem::path link_path_;
    std::string workspace_id_;
};

} // namespace vibris::mcp
