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
    entries_.emplace(std::string(key),
        Entry{std::move(request), std::make_shared<CallbackSlot>(std::move(completion))});
    peak_size_ = std::max(peak_size_, entries_.size());
    return true;
}

bool PendingRequestRegistry::resolve(const proto::ServerMessage& response) {
    if (response.has_resume_state()) {
        struct Event {
            std::shared_ptr<CallbackSlot> callback;
            std::unique_lock<std::mutex> claim;
            grpc::Status status;
            proto::ServerMessage response;
            bool terminal;
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
                    auto callback = entry->second.callback;
                    events.push_back({callback, std::unique_lock(callback->mutex),
                        {grpc::StatusCode::NOT_FOUND, "accepted request was not found after reconnect"},
                        std::move(event_response), true});
                    entry = entries_.erase(entry);
                } else {
                    auto callback = entry->second.callback;
                    events.push_back({callback, std::unique_lock(callback->mutex), grpc::Status::OK,
                        std::move(event_response), false});
                    ++entry;
                }
            }
        }
        for (auto& event : events) {
            complete_claimed(*event.callback, event.status, event.response, event.terminal);
            event.claim.unlock();
        }
        return !events.empty();
    }

    const std::string_view key = response_key(response);
    if (key.empty()) {
        return false;
    }

    std::shared_ptr<CallbackSlot> callback;
    std::unique_lock<std::mutex> claim;
    bool terminal = false;
    {
        std::scoped_lock lock(mutex_);
        const auto entry = entries_.find(std::string(key));
        if (entry == entries_.end()) {
            return false;
        }
        if (response.has_job_accepted()) {
            if (entry->second.accepted) return true;
            callback = entry->second.callback;
            claim = std::unique_lock(callback->mutex);
            entry->second.accepted = true;
        } else if (response.has_job_progress()) {
            callback = entry->second.callback;
            claim = std::unique_lock(callback->mutex);
        } else {
            callback = entry->second.callback;
            claim = std::unique_lock(callback->mutex);
            terminal = true;
            entries_.erase(entry);
        }
    }
    complete_claimed(*callback, grpc::Status::OK, response, terminal);
    return true;
}

bool PendingRequestRegistry::cancel(const std::string_view request_id, const grpc::Status& status) {
    std::shared_ptr<CallbackSlot> callback;
    {
        std::scoped_lock lock(mutex_);
        const auto entry = entries_.find(std::string(request_id));
        if (entry == entries_.end()) return false;
        callback = entry->second.callback;
        entries_.erase(entry);
    }
    const proto::ServerMessage response;
    return complete(callback, status, response, true);
}

void PendingRequestRegistry::fail_all(const grpc::Status& status) {
    std::vector<std::shared_ptr<CallbackSlot>> callbacks;
    {
        std::scoped_lock lock(mutex_);
        callbacks.reserve(entries_.size());
        for (auto& [id, entry] : entries_) {
            callbacks.push_back(entry.callback);
        }
        entries_.clear();
    }

    const proto::ServerMessage response;
    for (const auto& callback : callbacks) {
        complete(callback, status, response, true);
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

bool PendingRequestRegistry::complete_claimed(CallbackSlot& callback, const grpc::Status& status,
    const proto::ServerMessage& response, const bool terminal) noexcept {
    if (callback.terminal) return false;
    callback.terminal = terminal;
    try {
        callback.completion(status, response);
    } catch (...) {
        // User callbacks must not terminate the completion-queue worker.
    }
    return true;
}

bool PendingRequestRegistry::complete(const std::shared_ptr<CallbackSlot>& callback, const grpc::Status& status,
    const proto::ServerMessage& response, const bool terminal) noexcept {
    std::scoped_lock lock(callback->mutex);
    return complete_claimed(*callback, status, response, terminal);
}

}