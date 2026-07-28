#include "phase_two_source_handler.hpp"

#include "state_error.hpp"

#include <cstdint>
#include <filesystem>
#include <list>
#include <string>
#include <utility>

namespace vibris::mcp {
namespace {

namespace control = ::vibris::control::v1;

[[nodiscard]] Json source_json(const PreparedSource& source) {
    const auto& reference = source.reference();
    Json result{{"uuid", reference.uuid()},
                {"kind", reference.origin().has_workspace() ? "workspace" : "commit"},
                {"file_count", reference.file_count()},
                {"total_bytes", reference.total_bytes()},
                {"attempts", source.attempts()}};
    if (reference.origin().has_commit()) {
        result["requested_revision"] = std::string(source.requested_revision());
        result["resolved_revision"] = reference.origin().commit().revision();
    } else {
        result["head_revision"] = std::string(source.resolved_revision());
    }
    return result;
}

void prepare_one(SourcePreparer& preparer, const Json* source, std::list<PreparedSource>& prepared) {
    if (source == nullptr || source->value("kind", std::string("workspace")) == "workspace") {
        prepared.emplace_back(preparer.prepare_workspace());
        return;
    }
    prepared.emplace_back(preparer.prepare_commit(source->at("revision").get<std::string>()));
}

[[nodiscard]] SourceLimits server_limits(const control::ServerHello& server) {
    if (!server.ready()) {
        throw StateError("SERVER_NOT_READY", "The local Vibris server is not ready.", true);
    }
    if (server.pending_shaders_root().empty() || !std::filesystem::path(server.pending_shaders_root()).is_absolute() ||
        server.limits().max_source_bytes() == 0 || server.limits().max_source_files() == 0) {
        throw StateError("SERVER_NOT_READY", "The local Vibris server did not advertise a usable source root.", true);
    }
    return {.max_total_bytes = server.limits().max_source_bytes(),
            .max_files = server.limits().max_source_files()};
}

} // namespace

PhaseTwoSourceHandler::PhaseTwoSourceHandler(std::filesystem::path workspace_root)
    : workspace_root_(std::move(workspace_root)) {}

Json PhaseTwoSourceHandler::prepare(
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

    Json summaries = Json::array();
    for (const auto& source : prepared) summaries.push_back(source_json(source));
    Json result{{"phase", 2},
                {"execution_available", false},
                {"source_prepared", true},
                {"prepared_sources", std::move(summaries)}};
    owned_sources_.splice(owned_sources_.end(), prepared);
    return result;
}

void PhaseTwoSourceHandler::clear() noexcept {
    owned_sources_.clear();
}

} // namespace vibris::mcp