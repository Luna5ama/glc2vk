#pragma once

#include "grpc_client.hpp"

#include <grpcpp/alarm.h>
#include <grpcpp/grpcpp.h>

#include <atomic>
#include <deque>
#include <mutex>
#include <stop_token>
#include <thread>
#include <utility>

namespace vibris::mcp {

namespace proto = ::vibris::control::v1;

class GrpcClient::Impl final {
public:
    explicit Impl(GrpcClientOptions options);
    ~Impl();

    void start();
    bool get_server_info(GetServerInfoCompletion completion);
    bool list_presets(ListPresetsCompletion completion);
    bool validate_context(proto::ValidateContextRequest request, ValidateContextCompletion completion);
    bool get_status(GetStatusCompletion completion);
    bool debug_control(proto::DebugControlRequest request, DebugControlCompletion completion,
        std::chrono::milliseconds deadline);
    bool submit(proto::ClientMessage message, GrpcCompletion completion);
    bool cancel(std::string_view request_id, std::string reason);
    void shutdown();
    [[nodiscard]] GrpcClientStats stats() const;

private:
    struct Tag {
        virtual ~Tag() = default;
        virtual void complete(bool ok) noexcept = 0;
    };

    enum class ControlKind { start, read, write, finish };
    enum class AlarmKind { wake, reconnect };

    struct ControlTag final : Tag {
        ControlTag(Impl& owner, const ControlKind kind) : owner(owner), kind(kind) {}
        void complete(const bool ok) noexcept override { owner.handle_control(kind, ok); }
        Impl& owner;
        ControlKind kind;
    };

    struct AlarmTag final : Tag {
        AlarmTag(Impl& owner, const AlarmKind kind) : owner(owner), kind(kind) {}
        void complete(const bool ok) noexcept override { owner.handle_alarm(kind, ok); }
        Impl& owner;
        AlarmKind kind;
        grpc::Alarm alarm;
    };

    template <typename Request, typename Response, typename Completion>
    struct UnaryTag final : Tag {
        UnaryTag(Impl& owner, Request request, Completion completion)
            : owner(owner), request(std::move(request)), completion(std::move(completion)) {}
        void complete(bool ok) noexcept override;
        Impl& owner;
        Request request;
        Response response;
        Completion completion;
        grpc::ClientContext context;
        grpc::Status status;
        std::unique_ptr<grpc::ClientAsyncResponseReader<Response>> reader;
    };

    template <typename Request, typename Response, typename Completion, typename StartCall>
    bool start_unary(Request request, Completion completion, StartCall start_call,
        std::chrono::milliseconds deadline = std::chrono::milliseconds::zero());

    template <typename Call>
    void finish_unary(Call& call, bool ok) noexcept;

    void run(std::stop_token stop) noexcept;
    void ensure_stub_locked();
    void schedule_alarm_locked(AlarmKind kind, std::chrono::milliseconds delay);
    [[nodiscard]] proto::ClientMessage hello() const;
    void start_control();
    void handle_alarm(AlarmKind kind, bool ok) noexcept;
    void handle_control(ControlKind kind, bool ok) noexcept;
    void begin_read();
    void begin_write();
    void fail_stream();
    void maybe_finish();
    void finish_stream();
    [[nodiscard]] bool is_stopping() const;

    const GrpcClientOptions options_;
    PendingRequestRegistry pending_;
    mutable std::mutex mutex_;
    grpc::CompletionQueue queue_;
    std::shared_ptr<grpc::Channel> channel_;
    std::unique_ptr<proto::VibrisControl::Stub> stub_;
    std::unique_ptr<grpc::ClientContext> control_context_;
    std::unique_ptr<grpc::ClientAsyncReaderWriter<proto::ClientMessage, proto::ServerMessage>> stream_;
    std::jthread worker_;
    std::deque<proto::ClientMessage> submitted_;
    std::deque<proto::ClientMessage> writes_;
    proto::ClientMessage write_message_;
    proto::ServerMessage read_message_;
    grpc::Status finish_status_;
    std::size_t unary_in_flight_ = 0;
    std::size_t peak_pending_ = 0;
    bool started_ = false;
    bool stopping_ = false;
    bool stream_started_ = false;
    bool stream_failed_ = false;
    bool read_in_flight_ = false;
    bool write_in_flight_ = false;
    bool finish_in_flight_ = false;
    std::atomic_size_t workers_started_{0};
    std::atomic_size_t workers_joined_{0};
    std::atomic_bool connected_{false};
};

}
