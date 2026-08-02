#pragma once

#include <chrono>
#include <cstddef>
#include <filesystem>
#include <span>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace vibris::mcp {

struct GitRepositorySecurityAccess;

class GitArchivePipe final {
public:
    GitArchivePipe(const GitArchivePipe&) = delete;
    GitArchivePipe& operator=(const GitArchivePipe&) = delete;
    GitArchivePipe(GitArchivePipe&& other) noexcept;
    GitArchivePipe& operator=(GitArchivePipe&& other) noexcept;
    ~GitArchivePipe();

    [[nodiscard]] std::size_t read(std::span<std::byte> buffer);
    [[nodiscard]] int wait();

private:
    GitArchivePipe(void* process, void* stdout_read, void* stderr_read,
        std::chrono::steady_clock::time_point deadline) noexcept;
    void close() noexcept;
    void timeout();

    void* process_ = nullptr;
    void* stdout_read_ = nullptr;
    void* stderr_read_ = nullptr;
    std::chrono::steady_clock::time_point deadline_{};
    std::string stderr_text_;
    bool waited_ = false;

    friend class GitRepository;
};

class GitRepository final {
public:
    explicit GitRepository(std::filesystem::path repository);

    [[nodiscard]] std::string resolve_commit(std::string_view revision) const;
    [[nodiscard]] GitArchivePipe open_shader_archive(std::string_view full_sha) const;

private:
    static GitArchivePipe launch_git(const std::vector<std::wstring>& arguments,
        const std::filesystem::path& executable = {});

    std::filesystem::path repository_;
    friend struct GitRepositorySecurityAccess;
};

}
