#pragma once

#include "commit_extractor.hpp"
#include "source_types.hpp"
#include "vibris_control.pb.h"
#include "workspace_copier.hpp"

#include <cstddef>
#include <filesystem>
#include <functional>
#include <string>
#include <string_view>

namespace vibris::mcp {

using WorkspaceCopier = std::function<void(std::filesystem::path, std::filesystem::path)>;

struct WorkspaceProvenance final {
    std::string branch;
    std::string head;
    std::string shader_tree_id;
    std::string source_snapshot_sha256;
    std::string dirty_shader_delta_sha256;

    [[nodiscard]] bool operator==(const WorkspaceProvenance&) const = default;
};

[[nodiscard]] WorkspaceProvenance capture_workspace_provenance(
    const std::filesystem::path& workspace_root, SourceLimits limits);
[[nodiscard]] std::string shader_content_delta_sha256(
    std::string_view measured_snapshot_sha256, std::string_view completion_snapshot_sha256);

class PreparedSource final {
public:
    PreparedSource(const PreparedSource&) = delete;
    PreparedSource& operator=(const PreparedSource&) = delete;
    PreparedSource(PreparedSource&& other);
    PreparedSource& operator=(PreparedSource&& other);
    ~PreparedSource();

    [[nodiscard]] const control::v2::PreparedSourceRef& reference() const noexcept;
    [[nodiscard]] const std::filesystem::path& directory() const noexcept;
    [[nodiscard]] const ArchiveExtractionStats& archive_stats() const noexcept;
    [[nodiscard]] std::size_t attempts() const noexcept;
    [[nodiscard]] std::string_view requested_revision() const noexcept;
    [[nodiscard]] std::string_view resolved_revision() const noexcept;
    void release() noexcept;

private:
    friend class SourcePreparer;

    PreparedSource(
        control::v2::PreparedSourceRef reference,
        std::filesystem::path directory,
        ArchiveExtractionStats archive_stats,
        std::size_t attempts,
        std::string requested_revision,
        std::string resolved_revision);

    void cleanup() noexcept;

    control::v2::PreparedSourceRef reference_;
    std::filesystem::path directory_;
    ArchiveExtractionStats archive_stats_{};
    std::size_t attempts_ = 0;
    std::string requested_revision_;
    std::string resolved_revision_;
    bool owns_directory_ = false;
};

class SourcePreparer final {
public:
    SourcePreparer(
        std::filesystem::path workspace_root,
        std::filesystem::path pending_root,
        SourceLimits limits,
        WorkspaceCopier workspace_copier = copy_workspace_tree);

    [[nodiscard]] PreparedSource prepare_workspace() const;
    [[nodiscard]] PreparedSource prepare_commit(std::string_view revision) const;
    [[nodiscard]] PreparedSource prepare_snapshot(
        const std::filesystem::path& snapshot_root,
        const control::v2::PreparedSourceRef& provenance) const;

private:
    std::filesystem::path workspace_root_;
    std::filesystem::path pending_root_;
    SourceLimits limits_;
    WorkspaceCopier workspace_copier_;
};

} // namespace vibris::mcp
