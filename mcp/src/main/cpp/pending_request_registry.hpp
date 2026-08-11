#pragma once

#include "vibris_control.grpc.pb.h"

#include <grpcpp/support/status.h>

#include <cstddef>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <string_view>
#include <unordered_map>
#include <utility>
#include <vector>

namespace vibris::mcp {

using GrpcCompletion = std::function<void(
    const grpc::Status&,
    const ::vibris::control::v2::ServerMessage&)>;

class PendingRequestRegistry final {
public:
    explicit PendingRequestRegistry(std::size_t capacity);

    PendingRequestRegistry(const PendingRequestRegistry&) = delete;
    PendingRequestRegistry& operator=(const PendingRequestRegistry&) = delete;

    bool add(::vibris::control::v2::ClientMessage request, GrpcCompletion completion);
    bool add_resume(std::string request_id, std::string workspace_id, GrpcCompletion completion);
    bool resolve(const ::vibris::control::v2::ServerMessage& response);
    bool cancel(std::string_view request_id, const grpc::Status& status);
    void fail_all(const grpc::Status& status);

    [[nodiscard]] std::vector<::vibris::control::v2::ClientMessage> requests() const;
    [[nodiscard]] std::size_t size() const;
    [[nodiscard]] std::size_t capacity() const noexcept;
    [[nodiscard]] std::size_t peak_size() const;

private:
    struct CallbackSlot {
        explicit CallbackSlot(GrpcCompletion value) : completion(std::move(value)) {}

        std::mutex mutex;
        GrpcCompletion completion;
        bool terminal = false;
    };

    struct Entry {
        ::vibris::control::v2::ClientMessage request;
        std::shared_ptr<CallbackSlot> callback;
        bool accepted = false;
    };

    static std::string_view request_key(const ::vibris::control::v2::ClientMessage& request);
    static std::string_view response_key(const ::vibris::control::v2::ServerMessage& response);
    static bool complete_claimed(CallbackSlot& callback, const grpc::Status& status,
        const ::vibris::control::v2::ServerMessage& response, bool terminal) noexcept;
    static bool complete(const std::shared_ptr<CallbackSlot>& callback, const grpc::Status& status,
        const ::vibris::control::v2::ServerMessage& response, bool terminal) noexcept;

    const std::size_t capacity_;
    mutable std::mutex mutex_;
    std::unordered_map<std::string, Entry> entries_;
    std::size_t peak_size_ = 0;
};

}