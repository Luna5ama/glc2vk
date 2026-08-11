#include "source_preparer.hpp"

#include "config_document.hpp"
#include "git_repository.hpp"
#include "source_path_policy.hpp"
#include "state_error.hpp"

#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <Windows.h>
#include <bcrypt.h>

#include <array>
#include <fstream>
#include <limits>
#include <span>
#include <system_error>
#include <utility>
#include <vector>

namespace vibris::mcp {
namespace {

namespace fs = std::filesystem;

constexpr std::string_view kChangedCode = "SOURCE_CHANGED_DURING_SNAPSHOT";
constexpr std::string_view kInternalErrorCode = "INTERNAL_ERROR";
constexpr std::uint64_t kArchiveBaseOverhead = 1024 * 1024;
constexpr std::uint64_t kArchivePerFileOverhead = 4096;

[[noreturn]] void throw_internal(std::string message);

class Sha256 final {
public:
    Sha256() {
        if (BCryptOpenAlgorithmProvider(&algorithm_, BCRYPT_SHA256_ALGORITHM, nullptr, 0) < 0) {
            throw_internal("Could not initialize SHA-256 provenance hashing.");
        }
        DWORD object_bytes = 0;
        DWORD copied = 0;
        if (BCryptGetProperty(algorithm_, BCRYPT_OBJECT_LENGTH,
                reinterpret_cast<PUCHAR>(&object_bytes), sizeof(object_bytes), &copied, 0) < 0 ||
            copied != sizeof(object_bytes)) {
            BCryptCloseAlgorithmProvider(algorithm_, 0);
            throw_internal("Could not size SHA-256 provenance hashing.");
        }
        object_.resize(object_bytes);
        if (BCryptCreateHash(algorithm_, &hash_, object_.data(), object_bytes, nullptr, 0, 0) < 0) {
            BCryptCloseAlgorithmProvider(algorithm_, 0);
            throw_internal("Could not create SHA-256 provenance hashing.");
        }
    }

    Sha256(const Sha256&) = delete;
    Sha256& operator=(const Sha256&) = delete;

    ~Sha256() {
        if (hash_ != nullptr) BCryptDestroyHash(hash_);
        if (algorithm_ != nullptr) BCryptCloseAlgorithmProvider(algorithm_, 0);
    }

    void update(const std::span<const std::byte> bytes) {
        if (!bytes.empty() && BCryptHashData(hash_, const_cast<PUCHAR>(
                reinterpret_cast<const UCHAR*>(bytes.data())), static_cast<ULONG>(bytes.size()), 0) < 0) {
            throw_internal("Could not update SHA-256 provenance hashing.");
        }
    }

    void update(const std::string_view text) {
        update(std::as_bytes(std::span(text.data(), text.size())));
    }

    void byte(const std::uint8_t value) {
        update(std::as_bytes(std::span(&value, 1)));
    }

    [[nodiscard]] std::string finish() {
        std::array<std::uint8_t, 32> digest{};
        if (BCryptFinishHash(hash_, digest.data(), static_cast<ULONG>(digest.size()), 0) < 0) {
            throw_internal("Could not finish SHA-256 provenance hashing.");
        }
        static constexpr char digits[] = "0123456789abcdef";
        std::string encoded(digest.size() * 2, '0');
        for (std::size_t index = 0; index < digest.size(); ++index) {
            encoded[index * 2] = digits[digest[index] >> 4];
            encoded[index * 2 + 1] = digits[digest[index] & 0xf];
        }
        return encoded;
    }

private:
    BCRYPT_ALG_HANDLE algorithm_ = nullptr;
    BCRYPT_HASH_HANDLE hash_ = nullptr;
    std::vector<UCHAR> object_;
};

template <typename Integer>
void big_endian(Sha256& digest, Integer value) {
    std::array<std::byte, sizeof(Integer)> bytes{};
    for (std::size_t index = 0; index < bytes.size(); ++index) {
        bytes[bytes.size() - index - 1] = static_cast<std::byte>(value & 0xff);
        value >>= 8;
    }
    digest.update(bytes);
}

std::string source_tree_sha256(const fs::path& root, const SourceLimits& limits) {
    const auto before = enumerate_workspace_tree(root, limits);
    Sha256 digest;
    digest.update("vibris-source-tree-v1");
    digest.byte(0);
    std::vector<char> buffer(1024 * 1024);
    for (const auto& entry : before.entries) {
        if (entry.type != WorkspaceEntryType::regular_file) continue;
        const auto relative = entry.relative_path.generic_u8string();
        digest.byte('F');
        big_endian(digest, static_cast<std::uint32_t>(relative.size()));
        digest.update(std::as_bytes(std::span(relative.data(), relative.size())));
        big_endian(digest, entry.size);
        std::ifstream input(root / entry.relative_path, std::ios::binary);
        if (!input.good()) throw StateError(kChangedCode, "The shader source changed while it was fingerprinted.", true);
        while (input.good()) {
            input.read(buffer.data(), static_cast<std::streamsize>(buffer.size()));
            const auto count = input.gcount();
            if (count > 0) digest.update(std::as_bytes(std::span(buffer.data(), static_cast<std::size_t>(count))));
        }
        if (!input.eof()) throw StateError(kChangedCode, "The shader source changed while it was fingerprinted.", true);
    }
    const auto after = enumerate_workspace_tree(root, limits);
    if (before != after) {
        throw StateError(kChangedCode, "The shader source changed while it was fingerprinted.", true);
    }
    return digest.finish();
}

std::string provenance_delta_sha256(std::string_view domain, std::string_view first, std::string_view second) {
    Sha256 digest;
    digest.update(domain);
    digest.byte(0);
    digest.update(first);
    digest.byte(0);
    digest.update(second);
    return digest.finish();
}

struct GitSnapshot final {
    std::string branch;
    std::string head;
    std::string shader_tree_id;
    bool dirty;

    [[nodiscard]] bool operator==(const GitSnapshot&) const = default;
};

GitSnapshot git_snapshot(const fs::path& workspace_root) {
    GitRepository repository(workspace_root);
    const auto head = repository.resolve_commit("HEAD");
    return {repository.current_branch(), head, repository.shader_tree_id(head),
        repository.shader_worktree_dirty()};
}

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

control::v2::PreparedSourceRef workspace_reference(
    std::string_view uuid, const fs::path& worktree, const WorkspaceProvenance& provenance,
    const WorkspaceMetadata& metadata) {
    control::v2::PreparedSourceRef reference;
    reference.set_source_uuid(uuid);
    reference.set_file_count(metadata.file_count);
    reference.set_total_bytes(metadata.total_bytes);
    auto* origin = reference.mutable_origin()->mutable_workspace();
    origin->set_worktree_root(worktree.string());
    origin->set_display_name(worktree.filename().string());
    reference.set_requested_revision("workspace");
    reference.set_resolved_revision(provenance.head);
    reference.set_snapshot_sha256(provenance.source_snapshot_sha256);
    reference.set_branch(provenance.branch);
    reference.set_start_head(provenance.head);
    reference.set_shader_tree_id(provenance.shader_tree_id);
    reference.set_dirty_shader_delta_sha256(provenance.dirty_shader_delta_sha256);
    return reference;
}

control::v2::PreparedSourceRef commit_reference(
    std::string_view uuid,
    const fs::path& worktree,
    std::string_view resolved_revision,
    const WorkspaceMetadata& metadata, std::string_view snapshot_sha256,
    const GitSnapshot& workspace) {
    control::v2::PreparedSourceRef reference;
    reference.set_source_uuid(uuid);
    reference.set_file_count(metadata.file_count);
    reference.set_total_bytes(metadata.total_bytes);
    auto* origin = reference.mutable_origin()->mutable_commit();
    origin->set_repository_id(worktree.filename().string());
    origin->set_revision(resolved_revision);
    origin->set_worktree_root(worktree.string());
    reference.set_requested_revision(resolved_revision);
    reference.set_resolved_revision(resolved_revision);
    reference.set_snapshot_sha256(snapshot_sha256);
    reference.set_branch(workspace.branch);
    reference.set_start_head(workspace.head);
    reference.set_shader_tree_id(GitRepository(worktree).shader_tree_id(resolved_revision));
    return reference;
}

} // namespace

WorkspaceProvenance capture_workspace_provenance(
    const fs::path& workspace_root, const SourceLimits limits) {
    for (std::size_t attempt = 1; attempt <= 2; ++attempt) {
        const auto before = git_snapshot(workspace_root);
        const auto snapshot = source_tree_sha256(workspace_root / "shaders", limits);
        const auto after = git_snapshot(workspace_root);
        if (before == after) {
            return {before.branch, before.head, before.shader_tree_id, snapshot,
                before.dirty ? provenance_delta_sha256(
                    "vibris-dirty-shader-delta-v1", before.shader_tree_id, snapshot) : std::string{}};
        }
        if (attempt == 2) {
            throw StateError(kChangedCode, "Git shader provenance changed during both fingerprint attempts.", true);
        }
    }
    throw_internal("Workspace provenance exhausted its retry boundary.");
}

std::string shader_content_delta_sha256(
    const std::string_view measured_snapshot_sha256, const std::string_view completion_snapshot_sha256) {
    if (measured_snapshot_sha256 == completion_snapshot_sha256) return {};
    return provenance_delta_sha256(
        "vibris-shader-content-delta-v1", measured_snapshot_sha256, completion_snapshot_sha256);
}

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
            const auto git_before = git_snapshot(workspace_root_);
            const auto before = enumerate_workspace_tree(shaders, limits_);
            workspace_copier_(shaders, destination.staging.path());
            const auto after = enumerate_workspace_tree(shaders, limits_);
            const auto git_after = git_snapshot(workspace_root_);
            if (before != after || git_before != git_after) {
                if (attempt == 1) {
                    continue;
                }
                throw StateError(kChangedCode, "The workspace source changed during both snapshot attempts.", true);
            }
            const auto snapshot = source_tree_sha256(destination.staging.path(), limits_);
            const WorkspaceProvenance provenance{
                git_after.branch,
                git_after.head,
                git_after.shader_tree_id,
                snapshot,
                git_after.dirty ? provenance_delta_sha256(
                    "vibris-dirty-shader-delta-v1", git_after.shader_tree_id, snapshot) : std::string{},
            };
            promote(destination);
            auto reference = workspace_reference(destination.uuid, workspace_root_, provenance, after);
            PreparedSource prepared(
                std::move(reference), destination.staging.path(), {}, attempt, {}, git_after.head);
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
    const auto snapshot = source_tree_sha256(destination.staging.path(), limits_);
    const auto workspace = git_snapshot(workspace_root_);
    promote(destination);
    auto reference = commit_reference(
        destination.uuid, workspace_root_, resolved_revision, metadata, snapshot, workspace);
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
    const fs::path& snapshot_root, const control::v2::PreparedSourceRef& provenance) const {
    SourcePathPolicy{}.require_no_reparse_ancestry(snapshot_root, snapshot_root);
    const auto before = enumerate_workspace_tree(snapshot_root, limits_);
    if (before.file_count != provenance.file_count() || before.total_bytes != provenance.total_bytes()) {
        throw StateError(kChangedCode, "The queued source snapshot no longer matches its checkpoint.", false);
    }
    if (provenance.snapshot_sha256().empty() ||
        source_tree_sha256(snapshot_root, limits_) != provenance.snapshot_sha256()) {
        throw StateError(kChangedCode, "The queued source snapshot fingerprint no longer matches its checkpoint.", false);
    }
    auto destination = create_destination(pending_root_);
    workspace_copier_(snapshot_root, destination.staging.path());
    const auto after = enumerate_workspace_tree(snapshot_root, limits_);
    if (before != after) {
        throw StateError(kChangedCode, "The queued source snapshot changed while it was materialized.", false);
    }
    if (source_tree_sha256(destination.staging.path(), limits_) != provenance.snapshot_sha256()) {
        throw StateError(kChangedCode, "The materialized source snapshot fingerprint changed.", false);
    }
    promote(destination);
    auto reference = provenance;
    reference.set_source_uuid(destination.uuid);
    PreparedSource prepared(
        std::move(reference), destination.staging.path(), {}, 1,
        provenance.requested_revision(), provenance.resolved_revision());
    destination.staging.release();
    return prepared;
}

} // namespace vibris::mcp
