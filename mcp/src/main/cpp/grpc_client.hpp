#pragma once

#include "pending_request_registry.hpp"
#include "vibris_control.grpc.pb.h"

#include <chrono>
#include <cstddef>
#include <functional>
#include <memory>
#include <string>

namespace vibris::mcp {

struct GrpcClientOptions {
    std::string target;
    std::string workspace_id;
    std::string mcp_version;
    std::string process_instance_uuid;
    std::size_t pending_request_limit = 256;
    std::chrono::milliseconds reconnect_delay{100};
    std::chrono::milliseconds unary_deadline{5000};
};

struct GrpcClientStats {
    std::size_t completion_queue_count = 1;
    std::size_t peak_pending_requests = 0;
    std::size_t pending_requests = 0;
    std::size_t worker_threads_started = 0;
    std::size_t worker_threads_joined = 0;
    bool control_connected = false;
};

using ListPresetsCompletion = std::function<void(
    const grpc::Status&,
    const ::vibris::control::v1::ListPresetsResponse&)>;
using GetServerInfoCompletion = std::function<void(
    const grpc::Status&,
    const ::vibris::control::v1::GetServerInfoResponse&)>;
using ValidateContextCompletion = std::function<void(
    const grpc::Status&,
    const ::vibris::control::v1::ValidateContextResponse&)>;
using GetStatusCompletion = std::function<void(
    const grpc::Status&,
    const ::vibris::control::v1::GetStatusResponse&)>;

class GrpcClient final {
public:
    explicit GrpcClient(GrpcClientOptions options);
    ~GrpcClient();

    GrpcClient(const GrpcClient&) = delete;
    GrpcClient& operator=(const GrpcClient&) = delete;
    GrpcClient(GrpcClient&&) = delete;
    GrpcClient& operator=(GrpcClient&&) = delete;

    void start();
    bool get_server_info(GetServerInfoCompletion completion);
    bool list_presets(ListPresetsCompletion completion);
    bool validate_context(
        ::vibris::control::v1::ValidateContextRequest request,
        ValidateContextCompletion completion);
    bool get_status(GetStatusCompletion completion);
    bool submit(::vibris::control::v1::ClientMessage message, GrpcCompletion completion);
    bool resume(std::string request_id, GrpcCompletion completion);
    bool cancel(std::string_view request_id, std::string reason);
    void shutdown();

    [[nodiscard]] GrpcClientStats stats() const;

private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

}
