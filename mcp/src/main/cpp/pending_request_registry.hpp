#pragma once

#include "vibris_control.grpc.pb.h"

#include <grpcpp/support/status.h>

#include <cstddef>
#include <functional>
#include <mutex>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

namespace vibris::mcp {

using GrpcCompletion = std::function<void(
    const grpc::Status&,
    const ::vibris::control::v1::ServerMessage&)>;

class PendingRequestRegistry final {
public:
    explicit PendingRequestRegistry(std::size_t capacity);

    PendingRequestRegistry(const PendingRequestRegistry&) = delete;
    PendingRequestRegistry& operator=(const PendingRequestRegistry&) = delete;

    bool add(::vibris::control::v1::ClientMessage request, GrpcCompletion completion);
    bool resolve(const ::vibris::control::v1::ServerMessage& response);
    void fail_all(const grpc::Status& status);

    [[nodiscard]] std::vector<::vibris::control::v1::ClientMessage> requests() const;
    [[nodiscard]] std::size_t size() const;
    [[nodiscard]] std::size_t capacity() const noexcept;
    [[nodiscard]] std::size_t peak_size() const;

private:
    struct Entry {
        ::vibris::control::v1::ClientMessage request;
        GrpcCompletion completion;
        bool accepted = false;
    };

    static std::string_view request_key(const ::vibris::control::v1::ClientMessage& request);
    static std::string_view response_key(const ::vibris::control::v1::ServerMessage& response);
    static void complete(GrpcCompletion& completion, const grpc::Status& status,
        const ::vibris::control::v1::ServerMessage& response) noexcept;

    const std::size_t capacity_;
    mutable std::mutex mutex_;
    std::unordered_map<std::string, Entry> entries_;
    std::size_t peak_size_ = 0;
};

}