#include "pending_request_registry.hpp"

#include <grpcpp/grpcpp.h>

#include <array>
#include <exception>
#include <iostream>
#include <stdexcept>
#include <string_view>
#include <utility>
#include <vector>

namespace {

namespace proto = ::vibris::control::v1;
using vibris::mcp::PendingRequestRegistry;

void require(bool condition, std::string_view message) {
    if (!condition) throw std::runtime_error(std::string(message));
}

proto::ClientMessage submit(std::string_view id) {
    proto::ClientMessage message;
    message.mutable_protocol_version()->set_major(1);
    message.set_message_id("message-" + std::string(id));
    message.set_request_id(id);
    message.set_workspace_id("ownership-test");
    message.mutable_submit_job()->set_request_id(id);
    return message;
}

proto::ServerMessage accepted(std::string_view id) {
    proto::ServerMessage message;
    message.set_request_id(id);
    message.mutable_job_accepted()->set_request_id(id);
    return message;
}

proto::ServerMessage completed(std::string_view id) {
    proto::ServerMessage message;
    message.set_request_id(id);
    message.mutable_job_completed()->set_request_id(id);
    return message;
}

struct Event {
    grpc::StatusCode status;
    proto::ServerMessage::PayloadCase payload;
};

void accepted_request_reconnects_with_resume() {
    // Given: one pending SubmitJob with an event callback.
    PendingRequestRegistry registry(4);
    std::vector<Event> events;
    require(registry.add(submit("job-a"), [&](const grpc::Status& status, const proto::ServerMessage& response) {
        events.push_back({status.error_code(), response.payload_case()});
    }), "SubmitJob was not registered.");

    // When: the server accepts the job twice before the stream reconnects.
    require(registry.resolve(accepted("job-a")), "JobAccepted did not match its pending request.");
    require(registry.resolve(accepted("job-a")), "Duplicate JobAccepted was not recognized.");
    const auto reconnect = registry.requests();

    // Then: acceptance is surfaced once, the request remains pending, and reconnect sends only ResumeRequest.
    require(events.size() == 1 && events.front().payload == proto::ServerMessage::kJobAccepted,
        "JobAccepted was not surfaced exactly once.");
    require(registry.size() == 1, "JobAccepted incorrectly resolved the pending request.");
    require(reconnect.size() == 1 && reconnect.front().has_resume_request(),
        "Accepted request did not reconnect with ResumeRequest.");
    require(reconnect.front().resume_request().request_ids_size() == 1 &&
            reconnect.front().resume_request().request_ids(0) == "job-a",
        "ResumeRequest did not contain the accepted request ID exactly once.");
    require(!reconnect.front().has_submit_job(), "Reconnect duplicated an accepted SubmitJob.");

    require(registry.resolve(completed("job-a")), "JobCompleted did not resolve the resumed request.");
    require(events.size() == 2 && events.back().payload == proto::ServerMessage::kJobCompleted,
        "Terminal completion was not surfaced after acceptance.");
    require(registry.size() == 0, "Terminal completion did not drain the request registry.");
}

void resume_not_found_finishes_without_resubmit() {
    // Given: a request whose JobAccepted event transferred source ownership to the server.
    PendingRequestRegistry registry(4);
    std::vector<Event> events;
    require(registry.add(submit("job-missing"),
        [&](const grpc::Status& status, const proto::ServerMessage& response) {
            events.push_back({status.error_code(), response.payload_case()});
        }), "SubmitJob was not registered.");
    require(registry.resolve(accepted("job-missing")), "JobAccepted did not match its pending request.");

    // When: reconnect reports no matching JobSummary for the accepted request.
    proto::ServerMessage resume;
    resume.mutable_resume_state();
    require(registry.resolve(resume), "Empty ResumeState was not applied to accepted requests.");

    // Then: NOT_FOUND is surfaced once and no automatic duplicate SubmitJob remains queued.
    require(events.size() == 2 && events.back().status == grpc::StatusCode::NOT_FOUND &&
            events.back().payload == proto::ServerMessage::kResumeState,
        "ResumeState NOT_FOUND did not terminate the request with an ownership signal.");
    require(registry.size() == 0 && registry.requests().empty(),
        "ResumeState NOT_FOUND left a duplicate submission pending.");
}

using TestCase = std::pair<std::string_view, void (*)()>;
constexpr std::array<TestCase, 2> test_cases{{
    {"AcceptedRequestReconnectsWithResume", accepted_request_reconnects_with_resume},
    {"ResumeNotFoundFinishesWithoutResubmit", resume_not_found_finishes_without_resubmit},
}};

}

int main(int argc, char** argv) {
    if (argc != 2) {
        std::cerr << "usage: vibris-grpc-request-ownership-tests <scenario>\n";
        return 2;
    }
    for (const auto& [name, test] : test_cases) {
        if (name == argv[1]) {
            try {
                test();
                std::cout << "PASS " << name << '\n';
                return 0;
            } catch (const std::exception& error) {
                std::cerr << "FAIL " << name << ": " << error.what() << '\n';
                return 1;
            }
        }
    }
    std::cerr << "Unknown request ownership scenario: " << argv[1] << '\n';
    return 2;
}