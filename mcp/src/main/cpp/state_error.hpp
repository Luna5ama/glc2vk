#pragma once

#include <cstddef>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>

namespace vibris::mcp {

inline constexpr std::string_view kInvalidWorktreeCode = "INVALID_WORKTREE";
inline constexpr std::string_view kInvalidConfigCode = "INVALID_CONFIG";
inline constexpr std::string_view kConfigIoErrorCode = "CONFIG_IO_ERROR";
inline constexpr std::string_view kRequestTooLargeCode = "REQUEST_TOO_LARGE";
inline constexpr std::string_view kStateIoErrorCode = "STATE_IO_ERROR";

class StateError final : public std::runtime_error {
public:
    StateError(std::string_view code, std::string message, bool retryable = false)
        : std::runtime_error(bound_message(std::move(message))), code_(code), retryable_(retryable) {
    }

    [[nodiscard]] std::string_view code() const noexcept {
        return code_;
    }

    [[nodiscard]] bool retryable() const noexcept {
        return retryable_;
    }

private:
    static std::string bound_message(std::string message) {
        constexpr std::size_t max_message_bytes = 512;
        if (message.size() > max_message_bytes) {
            message.resize(max_message_bytes);
        }
        return message;
    }

    std::string code_;
    bool retryable_;
};

} // namespace vibris::mcp
