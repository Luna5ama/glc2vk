#pragma once

#include <cstddef>
#include <filesystem>
#include <memory>
#include <span>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace vibris::mcp {

class GitArchivePipe final {
public:
    GitArchivePipe(const GitArchivePipe&) = delete;
    GitArchivePipe& operator=(const GitArchivePipe&) = delete;
    GitArchivePipe(GitArchivePipe&& other) noexcept
        : process_(std::exchange(other.process_, nullptr)),
          stdout_read_(std::exchange(other.stdout_read_, nullptr)),
          stderr_drain_(std::move(other.stderr_drain_)),
          waited_(std::exchange(other.waited_, true)) {
    }
    GitArchivePipe& operator=(GitArchivePipe&& other) noexcept {
        if (this != &other) {
            close();
            process_ = std::exchange(other.process_, nullptr);
            stdout_read_ = std::exchange(other.stdout_read_, nullptr);
            stderr_drain_ = std::move(other.stderr_drain_);
            waited_ = std::exchange(other.waited_, true);
        }
        return *this;
    }
    ~GitArchivePipe() {
        close();
    }

    [[nodiscard]] std::size_t read(std::span<std::byte> buffer);
    [[nodiscard]] int wait();

private:
    struct StderrDrain;

    GitArchivePipe(void* process, void* stdout_read, std::shared_ptr<StderrDrain> stderr_drain) noexcept;
    void close() noexcept;

    void* process_ = nullptr;
    void* stdout_read_ = nullptr;
    std::shared_ptr<StderrDrain> stderr_drain_;
    bool waited_ = false;

    friend class GitRepository;
};

class GitRepository final {
public:
    explicit GitRepository(std::filesystem::path repository);

    [[nodiscard]] std::string resolve_commit(std::string_view revision) const;
    [[nodiscard]] GitArchivePipe open_shader_archive(std::string_view full_sha) const;

private:
    static GitArchivePipe launch_git(const std::vector<std::wstring>& arguments);

    std::filesystem::path repository_;
};

} // namespace vibris::mcp