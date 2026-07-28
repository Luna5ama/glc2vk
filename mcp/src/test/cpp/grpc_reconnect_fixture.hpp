#pragma once

#include "vibris_control.grpc.pb.h"

#include <grpcpp/grpcpp.h>

#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>

namespace vibris::mcp::test {

class ReconnectService final : public control::v1::VibrisControl::Service {
public:
    explicit ReconnectService(std::size_t drop_after) : drop_after_(drop_after) {
    }

    void release_requests() {
        {
            const std::lock_guard lock(gate_mutex_);
            released_ = true;
        }
        gate_.notify_all();
    }

    [[nodiscard]] std::size_t connections() const noexcept {
        return connections_.load();
    }

private:
    grpc::Status Control(
        grpc::ServerContext*,
        grpc::ServerReaderWriter<control::v1::ServerMessage, control::v1::ClientMessage>* stream) override {
        const std::size_t connection = connections_.fetch_add(1) + 1;
        bool greeted = false;
        control::v1::ClientMessage request;
        while (stream->Read(&request)) {
            control::v1::ServerMessage response;
            response.mutable_protocol_version()->set_major(1);
            response.mutable_protocol_version()->set_minor(0);
            response.set_message_id(request.message_id());
            response.set_request_id(request.request_id());
            response.set_workspace_id(request.workspace_id());
            if (!greeted) {
                if (!request.has_client_hello()) {
                    return {grpc::StatusCode::INVALID_ARGUMENT, "CLIENT_HELLO_REQUIRED"};
                }
                greeted = true;
                auto* hello = response.mutable_server_hello();
                hello->mutable_protocol_version()->set_major(1);
                hello->mutable_protocol_version()->set_minor(0);
                hello->set_server_version("grpc-reconnect-fixture");
                hello->add_capabilities(control::v1::CAPABILITY_CONTROL_STREAM);
                hello->add_capabilities(control::v1::CAPABILITY_RESUME);
                hello->set_ready(true);
                if (!stream->Write(response)) {
                    return {grpc::StatusCode::UNAVAILABLE, "hello write failed"};
                }
                continue;
            }
            if (!request.has_ping()) {
                return {grpc::StatusCode::INVALID_ARGUMENT, "PING_REQUIRED"};
            }
            {
                std::unique_lock lock(gate_mutex_);
                gate_.wait(lock, [this] { return released_; });
            }
            if (connection == 1 && drop_after_ != 0 && first_connection_responses_ >= drop_after_) {
                return {grpc::StatusCode::UNAVAILABLE, "fixture disconnect"};
            }
            auto* pong = response.mutable_pong();
            pong->set_sequence(request.ping().sequence());
            pong->set_client_time_unix_ms(request.ping().client_time_unix_ms());
            pong->set_server_time_unix_ms(request.ping().client_time_unix_ms());
            if (!stream->Write(response)) {
                return {grpc::StatusCode::UNAVAILABLE, "pong write failed"};
            }
            if (connection == 1) {
                ++first_connection_responses_;
            }
        }
        return grpc::Status::OK;
    }

    const std::size_t drop_after_;
    std::atomic<std::size_t> connections_ = 0;
    std::size_t first_connection_responses_ = 0;
    std::mutex gate_mutex_;
    std::condition_variable gate_;
    bool released_ = false;
};

class ReconnectServer final {
public:
    ReconnectServer(std::uint16_t port, std::size_t drop_after) : service_(drop_after) {
        grpc::ServerBuilder builder;
        int bound_port = 0;
        builder.AddListeningPort(
            "127.0.0.1:" + std::to_string(port), grpc::InsecureServerCredentials(), &bound_port);
        builder.RegisterService(&service_);
        server_ = builder.BuildAndStart();
        if (!server_ || bound_port != port) {
            throw std::runtime_error("failed to bind reconnect fixture server");
        }
    }

    ReconnectServer(const ReconnectServer&) = delete;
    ReconnectServer& operator=(const ReconnectServer&) = delete;

    ~ReconnectServer() {
        shutdown();
    }

    void release_requests() {
        service_.release_requests();
    }

    [[nodiscard]] std::size_t connections() const noexcept {
        return service_.connections();
    }

    void shutdown() {
        if (server_) {
            service_.release_requests();
            server_->Shutdown();
            server_->Wait();
            server_.reset();
        }
    }

private:
    ReconnectService service_;
    std::unique_ptr<grpc::Server> server_;
};

}