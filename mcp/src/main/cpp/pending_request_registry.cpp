#include "pending_request_registry.hpp"

#include <algorithm>
#include <stdexcept>
#include <utility>

namespace vibris::mcp {
namespace proto = ::vibris::control::v1;

PendingRequestRegistry::PendingRequestRegistry(const std::size_t capacity) : capacity_(capacity) {
    if (capacity == 0) {
        throw std::invalid_argument("pending request capacity must be positive");
    }
}

bool PendingRequestRegistry::add(proto::ClientMessage request, GrpcCompletion completion) {
    const std::string_view key = request_key(request);
    if (key.empty() || !completion) {
        return false;
    }

    std::scoped_lock lock(mutex_);
    if (entries_.size() >= capacity_ || entries_.contains(std::string(key))) {
        return false;
    }
    entries_.emplace(std::string(key), Entry{std::move(request), std::move(completion)});
    peak_size_ = std::max(peak_size_, entries_.size());
    return true;
}

bool PendingRequestRegistry::resolve(const proto::ServerMessage& response) {
    if (response.has_resume_state()) {
        struct Event {
            GrpcCompletion completion;
            grpc::Status status;
            proto::ServerMessage response;
        };
        std::vector<Event> events;
        {
            std::scoped_lock lock(mutex_);
            for (auto entry = entries_.begin(); entry != entries_.end();) {
                if (!entry->second.accepted) {
                    ++entry;
                    continue;
                }
                const auto job = std::find_if(
                    response.resume_state().jobs().begin(), response.resume_state().jobs().end(),
                    [&entry](const proto::JobSummary& summary) { return summary.request_id() == entry->first; });
                auto event_response = response;
                event_response.set_request_id(entry->first);
                if (job == response.resume_state().jobs().end() || job->state() == proto::JOB_STATE_UNSPECIFIED) {
                    events.push_back({std::move(entry->second.completion),
                        {grpc::StatusCode::NOT_FOUND, "accepted request was not found after reconnect"},
                        std::move(event_response)});
                    entry = entries_.erase(entry);
                } else {
                    events.push_back({entry->second.completion, grpc::Status::OK, std::move(event_response)});
                    ++entry;
                }
            }
        }
        for (auto& event : events) complete(event.completion, event.status, event.response);
        return !events.empty();
    }

    const std::string_view key = response_key(response);
    if (key.empty()) {
        return false;
    }

    GrpcCompletion completion;
    {
        std::scoped_lock lock(mutex_);
        const auto entry = entries_.find(std::string(key));
        if (entry == entries_.end()) {
            return false;
        }
        if (response.has_job_accepted()) {
            if (entry->second.accepted) return true;
            entry->second.accepted = true;
            completion = entry->second.completion;
        } else {
            completion = std::move(entry->second.completion);
            entries_.erase(entry);
        }
    }
    complete(completion, grpc::Status::OK, response);
    return true;
}

void PendingRequestRegistry::fail_all(const grpc::Status& status) {
    std::vector<GrpcCompletion> completions;
    {
        std::scoped_lock lock(mutex_);
        completions.reserve(entries_.size());
        for (auto& [id, entry] : entries_) {
            completions.push_back(std::move(entry.completion));
        }
        entries_.clear();
    }

    const proto::ServerMessage response;
    for (auto& completion : completions) {
        complete(completion, status, response);
    }
}

std::vector<proto::ClientMessage> PendingRequestRegistry::requests() const {
    std::scoped_lock lock(mutex_);
    std::vector<proto::ClientMessage> requests;
    requests.reserve(entries_.size());
    proto::ClientMessage resume;
    for (const auto& [id, entry] : entries_) {
        if (!entry.accepted) {
            requests.push_back(entry.request);
            continue;
        }
        if (!resume.has_resume_request()) {
            resume.mutable_protocol_version()->CopyFrom(entry.request.protocol_version());
            resume.set_message_id("resume-" + id);
            resume.set_workspace_id(entry.request.workspace_id());
        }
        resume.mutable_resume_request()->add_request_ids(id);
    }
    if (resume.has_resume_request()) requests.push_back(std::move(resume));
    return requests;
}

std::size_t PendingRequestRegistry::size() const {
    std::scoped_lock lock(mutex_);
    return entries_.size();
}

std::size_t PendingRequestRegistry::capacity() const noexcept {
    return capacity_;
}

std::size_t PendingRequestRegistry::peak_size() const {
    std::scoped_lock lock(mutex_);
    return peak_size_;
}

std::string_view PendingRequestRegistry::request_key(const proto::ClientMessage& request) {
    return request.request_id().empty() ? request.message_id() : request.request_id();
}

std::string_view PendingRequestRegistry::response_key(const proto::ServerMessage& response) {
    if (!response.request_id().empty()) {
        return response.request_id();
    }
    if (response.has_job_accepted() && !response.job_accepted().request_id().empty()) {
        return response.job_accepted().request_id();
    }
    if (response.has_job_completed() && !response.job_completed().request_id().empty()) {
        return response.job_completed().request_id();
    }
    if (response.has_job_failed() && !response.job_failed().request_id().empty()) {
        return response.job_failed().request_id();
    }
    return response.message_id();
}

void PendingRequestRegistry::complete(GrpcCompletion& completion, const grpc::Status& status,
    const proto::ServerMessage& response) noexcept {
    try {
        completion(status, response);
    } catch (...) {
        // User callbacks must not terminate the completion-queue worker.
    }
}

}