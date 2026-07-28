#pragma once

#include <filesystem>

namespace vibris::mcp {

class WorktreeLock final {
public:
    WorktreeLock(const WorktreeLock&) = delete;
    WorktreeLock& operator=(const WorktreeLock&) = delete;

    WorktreeLock(WorktreeLock&& other) noexcept;
    WorktreeLock& operator=(WorktreeLock&& other) noexcept;
    ~WorktreeLock();

    [[nodiscard]] static WorktreeLock acquire(const std::filesystem::path& canonical_root);

private:
    explicit WorktreeLock(void* handle) noexcept;

    void* handle_ = nullptr;
};

} // namespace vibris::mcp