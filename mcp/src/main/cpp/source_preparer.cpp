#include "source_preparer.hpp"

#include "config_document.hpp"
#include "git_repository.hpp"
#include "source_path_policy.hpp"
#include "state_error.hpp"

#include <system_error>
#include <limits>
#include <utility>

namespace vibris::mcp {
namespace {

namespace fs = std::filesystem;

constexpr std::string_view kChangedCode = "SOURCE_CHANGED_DURING_SNAPSHOT";
constexpr std::string_view kInternalErrorCode = "INTERNAL_ERROR";
constexpr std::uint64_t kArchiveBaseOverhead = 1024 * 1024;
constexpr std::uint64_t kArchivePerFileOverhead = 4096;

[[noreturn]] void throw_internal(std::string message) {
    throw StateError(kInternalErrorCode, std::move(message));
}

std::size_t archive_capture_limit(const SourceLimits& limits) {
    const auto file_overhead = static_cast<std::uint64_t>(limits.max_files) * kArchivePerFileOverhead;
    if (limits.max_total_bytes > std::numeric_limits<std::size_t>::max() - kArchiveBaseOverhead ||
        file_overhead > std::numeric_limits<std::size_t>::max() - kArchiveBaseOverhead - limits.max_total_bytes) {
        throw_internal("Server source limits exceed the supported archive capture size.");
    }
    return static_cast<std::size_t>(limits.max_total_bytes + file_overhead + kArchiveBaseOverhead);
}

class StagingDirectory final {
public:
    explicit StagingDirectory(fs::path path) : path_(std::move(path)) {
        std::error_code error;
        fs::create_directories(path_.parent_path(), error);
        if (error) {
            throw_internal("Could not create the prepared source staging directory.");
        }
        const auto created = fs::create_directory(path_, error);
        if (error || !created) {
            throw_internal("Could not reserve a unique prepared source staging directory.");
        }
        owned_ = true;
    }

    StagingDirectory(const StagingDirectory&) = delete;
    StagingDirectory& operator=(const StagingDirectory&) = delete;

    ~StagingDirectory() {
        if (owned_) {
            std::error_code ignored;
            fs::remove_all(path_, ignored);
        }
    }

    [[nodiscard]] const fs::path& path() const noexcept {
        return path_;
    }

    void adopt(fs::path& path) noexcept {
        path_.swap(path);
    }

    void release() noexcept {
        owned_ = false;
    }

private:
    fs::path path_;
    bool owned_ = false;
};

struct Destination final {
    std::string uuid;
    fs::path final_path;
    StagingDirectory staging;
};

Destination create_destination(const fs::path& pending_root) {
    auto uuid = detail::generate_uuid();
    const auto staging_path = pending_root / ".staging" / uuid;
    return {uuid, pending_root / uuid, StagingDirectory(staging_path)};
}

void promote(Destination& destination) {
    std::error_code error;
    fs::rename(destination.staging.path(), destination.final_path, error);
    if (error) {
        throw_internal("Could not atomically promote the prepared source.");
    }
    destination.staging.adopt(destination.final_path);
}

control::v1::PreparedSourceRef workspace_reference(
    std::string_view uuid, const fs::path& worktree, std::string_view resolved_revision,
    const WorkspaceMetadata& metadata) {
    control::v1::PreparedSourceRef reference;
    reference.set_uuid(uuid);
    reference.set_file_count(metadata.file_count);
    reference.set_total_bytes(metadata.total_bytes);
    reference.mutable_origin()->mutable_workspace()->set_display_name(worktree.filename().string());
    reference.set_requested_revision("workspace");
    reference.set_resolved_revision(resolved_revision);
    return reference;
}

control::v1::PreparedSourceRef commit_reference(
    std::string_view uuid,
    const fs::path& worktree,
    std::string_view resolved_revision,
    const WorkspaceMetadata& metadata) {
    control::v1::PreparedSourceRef reference;
    reference.set_uuid(uuid);
    reference.set_file_count(metadata.file_count);
    reference.set_total_bytes(metadata.total_bytes);
    auto* origin = reference.mutable_origin()->mutable_commit();
    origin->set_repository_id(worktree.filename().string());
    origin->set_revision(resolved_revision);
    reference.set_requested_revision(resolved_revision);
    reference.set_resolved_revision(resolved_revision);
    return reference;
}

} // namespace

SourcePreparer::SourcePreparer(
    fs::path workspace_root, fs::path pending_root, SourceLimits limits, WorkspaceCopier workspace_copier)
    : workspace_root_(std::move(workspace_root)),
      pending_root_(std::move(pending_root)),
      limits_(limits),
      workspace_copier_(std::move(workspace_copier)) {
    if (!workspace_copier_) {
        throw_internal("Workspace source copier is not configured.");
    }
    std::error_code error;
    const auto status = fs::status(pending_root_, error);
    if (error || !fs::is_directory(status)) {
        throw StateError("SERVER_NOT_READY", "The server-declared pending source root is not accessible.", true);
    }
    SourcePathPolicy{}.require_no_reparse_ancestry(pending_root_, pending_root_);
}

PreparedSource SourcePreparer::prepare_workspace() const {
    const auto shaders = workspace_root_ / "shaders";
    for (std::size_t attempt = 1; attempt <= 2; ++attempt) {
        auto destination = create_destination(pending_root_);
        try {
            const auto before = enumerate_workspace_tree(shaders, limits_);
            workspace_copier_(shaders, destination.staging.path());
            const auto after = enumerate_workspace_tree(shaders, limits_);
            if (before != after) {
                if (attempt == 1) {
                    continue;
                }
                throw StateError(kChangedCode, "The workspace source changed during both snapshot attempts.", true);
            }
            const auto resolved_revision = GitRepository(workspace_root_).resolve_commit("HEAD");
            promote(destination);
            auto reference = workspace_reference(destination.uuid, workspace_root_, resolved_revision, after);
            PreparedSource prepared(
                std::move(reference), destination.staging.path(), {}, attempt, {}, resolved_revision);
            destination.staging.release();
            return prepared;
        } catch (const StateError& error) {
            if (attempt == 1 && error.code() == kChangedCode) {
                continue;
            }
            throw;
        }
    }
    throw_internal("Workspace source preparation exhausted its retry boundary.");
}

PreparedSource SourcePreparer::prepare_commit(std::string_view revision) const {
    GitRepository repository(workspace_root_);
    const auto resolved_revision = repository.resolve_commit(revision);
    auto destination = create_destination(pending_root_);
    CommitExtractor extractor(SourcePathPolicy{}, limits_);
    const auto archive_stats = extractor.extract(
        repository.open_shader_archive(resolved_revision, archive_capture_limit(limits_)),
        destination.staging.path());
    const auto metadata = enumerate_workspace_tree(destination.staging.path(), limits_);
    if (metadata.file_count != archive_stats.extracted_file_count ||
        metadata.total_bytes != archive_stats.extracted_total_bytes) {
        throw_internal("Extracted shader metadata did not match the staged source tree.");
    }
    promote(destination);
    auto reference = commit_reference(destination.uuid, workspace_root_, resolved_revision, metadata);
    reference.set_requested_revision(revision);
    PreparedSource prepared(
        std::move(reference),
        destination.staging.path(),
        archive_stats,
        1,
        std::string(revision),
        resolved_revision);
    destination.staging.release();
    return prepared;
}

PreparedSource SourcePreparer::prepare_snapshot(
    const fs::path& snapshot_root, const control::v1::PreparedSourceRef& provenance) const {
    SourcePathPolicy{}.require_no_reparse_ancestry(snapshot_root, snapshot_root);
    const auto before = enumerate_workspace_tree(snapshot_root, limits_);
    if (before.file_count != provenance.file_count() || before.total_bytes != provenance.total_bytes()) {
        throw StateError(kChangedCode, "The queued source snapshot no longer matches its checkpoint.", false);
    }
    auto destination = create_destination(pending_root_);
    workspace_copier_(snapshot_root, destination.staging.path());
    const auto after = enumerate_workspace_tree(snapshot_root, limits_);
    if (before != after) {
        throw StateError(kChangedCode, "The queued source snapshot changed while it was materialized.", false);
    }
    promote(destination);
    auto reference = provenance;
    reference.set_uuid(destination.uuid);
    PreparedSource prepared(
        std::move(reference), destination.staging.path(), {}, 1,
        provenance.requested_revision(), provenance.resolved_revision());
    destination.staging.release();
    return prepared;
}

} // namespace vibris::mcp