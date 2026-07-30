#include "source_handler.hpp"

#include "state_error.hpp"

#include <algorithm>
#include <cstdint>
#include <filesystem>
#include <list>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace vibris::mcp {
namespace {

namespace control = ::vibris::control::v1;

void prepare_one(SourcePreparer& preparer, const Json* source, std::list<PreparedSource>& prepared) {
    if (source == nullptr || source->value("kind", std::string("workspace")) == "workspace") {
        prepared.emplace_back(preparer.prepare_workspace());
        return;
    }
    prepared.emplace_back(preparer.prepare_commit(source->at("revision").get<std::string>()));
}

[[nodiscard]] SourceLimits server_limits(const control::ServerHello& server) {
    if (server.pending_shaders_root().empty() || !std::filesystem::path(server.pending_shaders_root()).is_absolute() ||
        server.limits().max_source_bytes() == 0 || server.limits().max_source_files() == 0) {
        throw StateError("SERVER_NOT_READY", "The local Vibris server did not advertise a usable source root.", true);
    }
    return {.max_total_bytes = server.limits().max_source_bytes(),
            .max_files = server.limits().max_source_files()};
}

} // namespace

SourceHandler::SourceHandler(std::filesystem::path workspace_root)
    : workspace_root_(std::move(workspace_root)) {}

SourceHandler::~SourceHandler() {
    clear();
}

void SourceHandler::prepare(
    std::string_view tool_name, const Json& arguments, const control::ServerHello& server) {
    SourcePreparer preparer(
        workspace_root_, std::filesystem::path(server.pending_shaders_root()), server_limits(server));
    std::list<PreparedSource> prepared;
    if (tool_name == "vibris_run_recipe" && arguments.value("recipe", std::string{}) == "ab_compare") {
        prepare_one(preparer, &arguments.at("a").at("source"), prepared);
        prepare_one(preparer, &arguments.at("b").at("source"), prepared);
    } else {
        const auto source = arguments.find("source");
        prepare_one(preparer, source == arguments.end() ? nullptr : &*source, prepared);
    }

    source_batches_.emplace_back();
    source_batches_.back().sources.splice(source_batches_.back().sources.end(), prepared);
}

std::vector<control::PreparedSourceRef> SourceHandler::bind_latest(std::string request_id) {
    if (request_id.empty()) throw std::invalid_argument("source request ID must not be empty");
    if (source_batches_.empty() || !source_batches_.back().request_id.empty()) {
        throw std::logic_error("no unbound prepared source batch");
    }
    if (std::any_of(source_batches_.begin(), source_batches_.end(), [&](const SourceBatch& batch) {
            return batch.request_id == request_id;
        })) {
        throw std::invalid_argument("source request ID is already bound");
    }
    std::vector<control::PreparedSourceRef> references;
    try {
        references.reserve(source_batches_.back().sources.size());
        for (const auto& source : source_batches_.back().sources) references.push_back(source.reference());
    } catch (...) {
        source_batches_.pop_back();
        throw;
    }
    source_batches_.back().request_id = std::move(request_id);
    return references;
}

void SourceHandler::observe(const control::ServerMessage& message) noexcept {
    const auto transfer = [](SourceBatch& batch) {
        if (!batch.released) {
            for (auto& source : batch.sources) source.release();
            batch.released = true;
        }
        batch.server_owned = true;
    };
    if (message.has_job_accepted()) {
        const auto& id = message.request_id().empty() ? message.job_accepted().request_id() : message.request_id();
        const auto batch = std::find_if(source_batches_.begin(), source_batches_.end(), [&](const SourceBatch& item) {
            return item.request_id == id;
        });
        if (batch != source_batches_.end()) transfer(*batch);
        return;
    }
    if (!message.has_resume_state()) return;

    const auto update = [&](SourceBatch& batch) {
        const auto job = std::find_if(
            message.resume_state().jobs().begin(), message.resume_state().jobs().end(),
            [&](const control::JobSummary& summary) { return summary.request_id() == batch.request_id; });
        if (job == message.resume_state().jobs().end() || job->state() == control::JOB_STATE_UNSPECIFIED) {
            batch.server_owned = false;
        } else {
            transfer(batch);
        }
    };
    if (!message.request_id().empty()) {
        const auto batch = std::find_if(source_batches_.begin(), source_batches_.end(), [&](const SourceBatch& item) {
            return item.request_id == message.request_id();
        });
        if (batch != source_batches_.end()) update(*batch);
        return;
    }
    for (auto& batch : source_batches_) {
        if (!batch.request_id.empty()) update(batch);
    }
}

void SourceHandler::retire(std::string_view request_id) noexcept {
    const auto batch = std::find_if(source_batches_.begin(), source_batches_.end(), [&](const SourceBatch& item) {
        return item.request_id == request_id;
    });
    if (batch == source_batches_.end()) return;
    if (!batch->server_owned && batch->released) {
        for (const auto& source : batch->sources) {
            std::error_code ignored;
            std::filesystem::remove_all(source.directory(), ignored);
        }
    }
    source_batches_.erase(batch);
}

void SourceHandler::clear() noexcept {
    for (const auto& batch : source_batches_) {
        if (!batch.server_owned && batch.released) {
            for (const auto& source : batch.sources) {
                std::error_code ignored;
                std::filesystem::remove_all(source.directory(), ignored);
            }
        }
    }
    source_batches_.clear();
}

} // namespace vibris::mcp
