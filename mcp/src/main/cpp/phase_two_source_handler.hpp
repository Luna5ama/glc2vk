#pragma once

#include "source_preparer.hpp"
#include "tool_registry.hpp"
#include "vibris_control.pb.h"

#include <filesystem>
#include <list>
#include <string>
#include <string_view>
#include <vector>

namespace vibris::mcp {

class PhaseTwoSourceHandler final {
public:
    explicit PhaseTwoSourceHandler(std::filesystem::path workspace_root);
    ~PhaseTwoSourceHandler();

    [[nodiscard]] Json prepare(
        std::string_view tool_name,
        const Json& arguments,
        const ::vibris::control::v1::ServerHello& server);
    std::vector<::vibris::control::v1::PreparedSourceRef> bind_latest(std::string request_id);
    void observe(const ::vibris::control::v1::ServerMessage& message) noexcept;
    void retire(std::string_view request_id) noexcept;
    void clear() noexcept;

private:
    struct SourceBatch {
        std::string request_id;
        std::list<PreparedSource> sources;
        bool released = false;
        bool server_owned = false;
    };

    std::filesystem::path workspace_root_;
    std::list<SourceBatch> source_batches_;
};

} // namespace vibris::mcp