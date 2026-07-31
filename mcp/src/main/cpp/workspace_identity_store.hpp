#pragma once

#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <optional>
#include <string>

namespace vibris::mcp {

namespace detail {

inline constexpr std::size_t kMaxWorkspaceIdentityJsonNestingDepth = 16;
inline constexpr std::size_t kMaxWorkspaceIdentityTempCreateAttempts = 16;
inline constexpr std::size_t kMaxWorkspaceIdentityWinnerReadRetries = 16;

enum class WorkspaceIdentityIoOperation {
    create,
    write,
    flush,
    move,
    cleanup,
    winner_retry,
};

class WorkspaceIdentityIoHooks {
public:
    virtual ~WorkspaceIdentityIoHooks() = default;

    virtual void before(WorkspaceIdentityIoOperation operation, const std::filesystem::path& temporary_path,
        const std::filesystem::path& identity_path) noexcept;
    [[nodiscard]] virtual std::optional<std::uint32_t> injected_error(
        WorkspaceIdentityIoOperation operation, const std::filesystem::path& temporary_path,
        const std::filesystem::path& identity_path) noexcept;
    virtual void after(WorkspaceIdentityIoOperation operation, const std::filesystem::path& temporary_path,
        const std::filesystem::path& identity_path, bool success, std::uint32_t error) noexcept;
};

}

class WorkspaceIdentityStore final {
public:
    WorkspaceIdentityStore(std::filesystem::path identity_path, std::filesystem::path legacy_config_path);
    WorkspaceIdentityStore(std::filesystem::path identity_path, std::filesystem::path legacy_config_path,
        detail::WorkspaceIdentityIoHooks& hooks);

    [[nodiscard]] const std::filesystem::path& path() const noexcept;
    [[nodiscard]] std::string load_or_create() const;

private:
    [[nodiscard]] std::optional<std::string> load_existing() const;
    [[nodiscard]] std::optional<std::string> load_existing_after_publish_loss() const;
    [[nodiscard]] std::optional<std::string> load_legacy() const;
    [[nodiscard]] std::string publish(std::string workspace_id) const;

    std::filesystem::path identity_path_;
    std::filesystem::path legacy_config_path_;
    detail::WorkspaceIdentityIoHooks* hooks_ = nullptr;
};

}
