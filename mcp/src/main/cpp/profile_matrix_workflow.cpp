#include "profile_matrix_workflow.hpp"

#include "config_document.hpp"
#include "source_preparer.hpp"
#include "state_error.hpp"

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <Windows.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <fstream>
#include <limits>
#include <system_error>
#include <utility>
#include <vector>

namespace vibris::mcp {
namespace {

namespace fs = std::filesystem;

constexpr std::uintmax_t maximum_checkpoint_bytes = 64ULL * 1024ULL * 1024ULL;

[[noreturn]] void checkpoint_error(std::string message, bool retryable = false) {
    throw StateError("PROFILE_CHECKPOINT_ERROR", std::move(message), retryable);
}

[[noreturn]] void invalid_job() {
    throw StateError("INVALID_PROFILE_JOB", "The profile matrix job checkpoint is invalid.");
}

bool reparse_point(const fs::path& path) {
    const auto attributes = GetFileAttributesW(path.c_str());
    return attributes != INVALID_FILE_ATTRIBUTES && (attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0;
}

void ensure_directory(const fs::path& path) {
    std::error_code error;
    fs::create_directories(path, error);
    if (error || !fs::is_directory(path, error) || error || reparse_point(path)) {
        checkpoint_error("The profile checkpoint directory is unavailable or unsafe.", true);
    }
    const auto parent = path.parent_path();
    if (!parent.empty() && reparse_point(parent)) {
        checkpoint_error("The workspace .vibris directory must not be a reparse point.");
    }
}

void write_all(HANDLE output, std::string_view value) {
    std::size_t offset = 0;
    while (offset < value.size()) {
        const auto remaining = value.size() - offset;
        const auto requested = static_cast<DWORD>(
            std::min<std::size_t>(remaining, std::numeric_limits<DWORD>::max()));
        DWORD written = 0;
        if (!WriteFile(output, value.data() + offset, requested, &written, nullptr) || written == 0) {
            checkpoint_error("Unable to write the profile checkpoint.", true);
        }
        offset += written;
    }
}

void atomic_write(const fs::path& path, std::string value) {
    ensure_directory(path.parent_path());
    const auto temporary = path.parent_path() /
        (path.filename().string() + ".tmp-" + detail::generate_uuid());
    HANDLE output = CreateFileW(temporary.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_NEW,
        FILE_ATTRIBUTE_NORMAL | FILE_FLAG_WRITE_THROUGH, nullptr);
    if (output == INVALID_HANDLE_VALUE) checkpoint_error("Unable to create a temporary profile checkpoint.", true);
    bool published = false;
    try {
        write_all(output, value);
        if (!FlushFileBuffers(output)) checkpoint_error("Unable to flush the profile checkpoint.", true);
        if (!CloseHandle(output)) checkpoint_error("Unable to close the profile checkpoint.", true);
        output = INVALID_HANDLE_VALUE;
        if (!MoveFileExW(temporary.c_str(), path.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
            checkpoint_error("Unable to publish the profile checkpoint.", true);
        }
        published = true;
    } catch (...) {
        if (output != INVALID_HANDLE_VALUE) CloseHandle(output);
        DeleteFileW(temporary.c_str());
        throw;
    }
    if (!published) DeleteFileW(temporary.c_str());
}

std::string read_file(const fs::path& path) {
    std::error_code error;
    const auto status = fs::symlink_status(path, error);
    if (error || !fs::is_regular_file(status) || fs::is_symlink(status) || reparse_point(path)) {
        checkpoint_error("The requested profile checkpoint does not exist or is unsafe.");
    }
    const auto size = fs::file_size(path, error);
    if (error || size == 0 || size > maximum_checkpoint_bytes) invalid_job();
    std::ifstream input(path, std::ios::binary);
    if (!input) checkpoint_error("Unable to read the profile checkpoint.", true);
    std::string value(static_cast<std::size_t>(size), '\0');
    input.read(value.data(), static_cast<std::streamsize>(value.size()));
    if (!input || input.gcount() != static_cast<std::streamsize>(value.size())) {
        checkpoint_error("Unable to read the complete profile checkpoint.", true);
    }
    return value;
}

const Json& named_value(const Json& values, std::string_view id, std::string_view kind) {
    const auto found = std::ranges::find_if(values, [id](const Json& value) {
        return value.at("id").get_ref<const std::string&>() == id;
    });
    if (found == values.end()) throw std::invalid_argument(std::string(kind) + " ID is not declared");
    return *found;
}

Json checkpoint_config(const JobContext& config) {
    return Json::parse(detail::serialize_config(config));
}

Json freeze_sources(const fs::path& workspace_root, const fs::path& state_directory,
    const std::string& job_id, const Json& sources) {
    const auto snapshot_root = state_directory / job_id / "sources";
    ensure_directory(snapshot_root);
    SourcePreparer preparer(
        workspace_root, snapshot_root, {.max_total_bytes = 512ULL * 1024ULL * 1024ULL, .max_files = 100'000});
    std::vector<PreparedSource> snapshots;
    Json frozen = Json::array();
    snapshots.reserve(sources.size());
    for (const auto& declared : sources) {
        const auto kind = declared.at("kind").get<std::string>();
        snapshots.emplace_back(kind == "commit"
            ? preparer.prepare_commit(declared.at("revision").get<std::string>())
            : preparer.prepare_workspace());
        const auto& prepared = snapshots.back();
        const auto& reference = prepared.reference();
        const bool commit = reference.origin().has_commit();
        frozen.push_back({{"id", declared.at("id")},
                          {"kind", "snapshot"},
                          {"job_id", job_id},
                          {"snapshot_uuid", reference.uuid()},
                          {"origin_kind", commit ? "commit" : "workspace"},
                          {"origin_name", commit ? reference.origin().commit().repository_id()
                                                  : reference.origin().workspace().display_name()},
                          {"requested_revision", reference.requested_revision()},
                          {"resolved_revision", reference.resolved_revision()},
                          {"file_count", reference.file_count()},
                          {"total_bytes", reference.total_bytes()}});
    }
    for (auto& snapshot : snapshots) snapshot.release();
    return frozen;
}

JobContext parse_checkpoint_config(const Json& value, std::string_view workspace_id) {
    auto config = detail::parse_config(value.dump(), detail::ConfigDocumentKind::persisted);
    if (config.workspace_id != workspace_id) invalid_job();
    return config;
}

Json case_specs(const Json& arguments) {
    Json result = Json::array();
    for (const auto& source_axis : arguments.at("matrix").at("sources")) {
        const auto source_id = source_axis.get<std::string>();
        auto source = named_value(arguments.at("sources"), source_id, "source");
        source.erase("id");
        for (const auto& config_axis : arguments.at("matrix").at("configs")) {
            const auto config_id = config_axis.get<std::string>();
            const auto& named_config = named_value(arguments.at("configs"), config_id, "config");
            result.push_back({
                {"case_id", source_id + "--" + config_id},
                {"source_id", source_id},
                {"config_id", config_id},
                {"source", source},
                {"config", named_config.contains("values") ? named_config.at("values") : Json(nullptr)},
                {"result", nullptr},
                {"pending_attempts", Json::array()},
                {"pending_error", nullptr},
            });
        }
    }
    return result;
}

bool retry_interruption(const Json& error) {
    if (!error.is_object()) return false;
    constexpr std::array codes{
        "SERVER_OFFLINE", "SERVER_RESTARTED", "SERVER_NOT_READY", "QUEUE_FULL",
        "QUEUE_TIMEOUT", "EXECUTION_TIMEOUT",
    };
    const auto code = error.value("error_code", std::string{});
    return std::ranges::find(codes, code) != codes.end();
}

Json failure_json(const ToolFailure& failure) {
    return {{"success", false}, {"error_code", failure.code}, {"message", failure.message},
            {"retryable", failure.retryable}, {"details", failure.details}};
}

Json case_arguments(const Json& document, const Json& spec) {
    const auto& original = document.at("arguments");
    Json result{{"recipe", "profile"}, {"frames", original.at("frames")}};
    constexpr std::array copied{
        "warmup_frames", "result_detail", "metric_filter", "statistics", "converted_units",
        "max_retries", "result_csv",
    };
    for (const auto* field : copied) {
        if (original.contains(field)) result[field] = original.at(field);
    }
    if (original.contains("__vibris_scene_context")) {
        result["__vibris_scene_context"] = original.at("__vibris_scene_context");
    }
    if (original.contains("__vibris_preset")) result["__vibris_preset"] = original.at("__vibris_preset");
    result["source"] = spec.at("source");
    if (!spec.at("config").is_null()) result["config"] = spec.at("config");
    result["__vibris_case_id"] = spec.at("case_id");
    result["__vibris_source_id"] = spec.at("source_id");
    result["__vibris_config_id"] = spec.at("config_id");
    result["__vibris_workflow_id"] = document.at("job_id");
    result["__vibris_result_kind"] = "profile_matrix";
    if (!spec.at("pending_attempts").empty()) {
        result["__vibris_previous_attempts"] = spec.at("pending_attempts");
    }
    return result;
}

void append_values(Json& target, const Json& source, std::string_view field) {
    const auto values = source.find(std::string(field));
    if (values == source.end() || !values->is_array()) return;
    for (const auto& value : *values) target.push_back(value);
}

std::size_t attempt_count(const Json& value) {
    if (!value.is_object()) return 0;
    return value.value("attempt_count", value.value("attempts", Json::array()).size());
}

bool has_metrics(const Json& value) {
    if (!value.is_object()) return false;
    if (value.value("status", std::string{}) == "passed") return true;
    const auto metrics = value.find("metrics");
    if (metrics == value.end() || !metrics->is_object()) return false;
    const auto timings = metrics->find("gpuTimings");
    if (timings != metrics->end() && timings->is_object() && !timings->empty()) return true;
    const auto programs = metrics->find("gpuProgramTimings");
    return programs != metrics->end() && programs->is_array() &&
        std::any_of(programs->begin(), programs->end(), [](const Json& program) {
            if (!program.is_object()) return false;
            const auto statistics = program.find("statistics");
            return statistics != program.end() && statistics->is_object() && !statistics->empty();
        });
}

Json pending_case(const Json& spec, const Json& arguments, const std::uint32_t default_warmup) {
    Json result{{"case_id", spec.at("case_id")},
                {"source_id", spec.at("source_id")},
                {"config_id", spec.at("config_id")},
                {"status", "pending"},
                {"error", spec.at("pending_error")},
                {"frames", arguments.at("frames")},
                {"warmup_frames", arguments.value("warmup_frames", default_warmup)},
                {"metrics", nullptr},
                {"provenance", nullptr},
                {"attempt_count", spec.at("pending_attempts").size()},
                {"retry_exhausted", false},
                {"attempts", spec.at("pending_attempts")}};
    return result;
}

bool terminal_workflow(std::string_view state) {
    return state == "completed" || state == "cancelled";
}

}

ProfileMatrixWorkflow::ProfileMatrixWorkflow(
    fs::path workspace_root, std::string workspace_id, ProfileMatrixCaseExecutor executor)
    : workspace_root_(fs::absolute(workspace_root).lexically_normal()),
      state_directory_(workspace_root_ / ".vibris" / "profile-matrix"),
      workspace_id_(std::move(workspace_id)), executor_(std::move(executor)) {
    if (!detail::is_uuid(workspace_id_) || !executor_) {
        throw std::invalid_argument("invalid profile matrix workflow configuration");
    }
}

ProfileMatrixWorkflow::~ProfileMatrixWorkflow() {
    shutdown();
}

Json ProfileMatrixWorkflow::create_checkpoint(const Json& arguments, const JobContext& config) const {
    Json stored_arguments = arguments;
    stored_arguments.erase("execution");
    const auto job_id = detail::generate_uuid();
    stored_arguments["sources"] = freeze_sources(
        workspace_root_, state_directory_, job_id, stored_arguments.at("sources"));
    auto specs = case_specs(stored_arguments);
    return {{"schema_version", 1},
            {"workspace_id", workspace_id_},
            {"job_id", job_id},
            {"kind", "profile_matrix"},
            {"workflow_state", "queued"},
            {"config", checkpoint_config(config)},
            {"arguments", std::move(stored_arguments)},
            {"case_specs", std::move(specs)},
            {"artifacts", Json::array()},
            {"job_attempts", Json::array()},
            {"progress_events", Json::array()},
            {"current_request_id", nullptr},
            {"current_request_accepted", false},
            {"last_error", nullptr},
            {"progress", {{"requested_cases", arguments.at("matrix").at("sources").size() *
                                                   arguments.at("matrix").at("configs").size()},
                           {"completed_cases", 0},
                           {"current_case_number", nullptr},
                           {"current_case_id", nullptr},
                           {"stage", "queued"}}}};
}

Json ProfileMatrixWorkflow::load(std::string_view job_id) const {
    if (!detail::is_uuid(job_id)) invalid_job();
    std::scoped_lock lock(store_mutex_);
    try {
        auto document = Json::parse(read_file(state_directory_ / (std::string(job_id) + ".json")));
        if (!document.is_object() || document.value("schema_version", 0) != 1 ||
            document.value("workspace_id", std::string{}) != workspace_id_ ||
            document.value("job_id", std::string{}) != job_id ||
            document.value("kind", std::string{}) != "profile_matrix" ||
            !document.contains("arguments") || !document.at("arguments").is_object() ||
            !document.contains("config") || !document.at("config").is_object() ||
            !document.contains("case_specs") || !document.at("case_specs").is_array() ||
            document.at("case_specs").empty() || document.at("case_specs").size() > 1024) {
            invalid_job();
        }
        static_cast<void>(parse_checkpoint_config(document.at("config"), workspace_id_));
        return document;
    } catch (const StateError&) {
        throw;
    } catch (const Json::exception&) {
        invalid_job();
    }
}

void ProfileMatrixWorkflow::save(const Json& document) const {
    const auto job_id = document.value("job_id", std::string{});
    if (!detail::is_uuid(job_id) || document.value("workspace_id", std::string{}) != workspace_id_) invalid_job();
    auto text = document.dump(2);
    if (text.size() > maximum_checkpoint_bytes) {
        checkpoint_error("The profile checkpoint exceeded its 64 MiB limit.");
    }
    text.push_back('\n');
    std::scoped_lock lock(store_mutex_);
    atomic_write(state_directory_ / (job_id + ".json"), std::move(text));
}

Json ProfileMatrixWorkflow::result(const Json& document) const {
    const auto& specs = document.at("case_specs");
    const auto& arguments = document.at("arguments");
    std::size_t receipts = 0;
    std::size_t passed = 0;
    std::size_t failed = 0;
    std::size_t incomplete = 0;
    std::size_t with_metrics = 0;
    std::size_t retried = 0;
    std::size_t total_attempts = 0;
    Json cases = Json::array();
    const auto default_warmup = document.at("config").value("default_warmup_frames", std::uint32_t{});
    for (const auto& spec : specs) {
        Json value = spec.at("result").is_object() ? spec.at("result") :
            pending_case(spec, arguments, default_warmup);
        if (spec.at("result").is_object()) {
            ++receipts;
            const auto status = value.value("status", std::string{});
            if (status == "passed") ++passed;
            else if (status == "failed") ++failed;
            else ++incomplete;
        }
        if (has_metrics(value)) ++with_metrics;
        const auto attempts = attempt_count(value);
        total_attempts += attempts;
        if (attempts > 1) ++retried;
        cases.push_back(std::move(value));
    }
    const auto state = document.at("workflow_state").get<std::string>();
    const bool completed = state == "completed";
    std::string status = state;
    if (completed) {
        status = incomplete != 0 ? "incomplete" : (failed == 0 ? "completed" : "completed_with_failures");
    }
    Json stages = Json::array();
    for (const auto& event : document.at("progress_events")) {
        const auto& stage = event.at("stage");
        if (std::ranges::find(stages, stage) == stages.end()) stages.push_back(stage);
    }
    const auto detail = arguments.value("result_detail", std::string("metrics"));
    Json output{{"success", completed && passed == specs.size()},
                {"kind", "profile_matrix"},
                {"job_id", document.at("job_id")},
                {"workflow_state", state},
                {"status", std::move(status)},
                {"resumable", !completed && state != "running" && state != "cancellation_requested"},
                {"result_detail", detail},
                {"gpu_timing_unit", "ns"},
                {"requested_cases", specs.size()},
                {"completed_cases", passed + failed},
                {"cases_with_metrics", with_metrics},
                {"missing_cases", specs.size() - with_metrics},
                {"failed_cases", failed},
                {"retried_cases", retried},
                {"total_attempts", total_attempts},
                {"max_retries", arguments.value("max_retries", 2)},
                {"passed", passed},
                {"failed", failed},
                {"incomplete", incomplete},
                {"receipt_count", receipts},
                {"progress", document.at("progress")},
                {"progress_stages", std::move(stages)},
                {"last_error", document.at("last_error")},
                {"cases", std::move(cases)},
                {"artifacts", document.at("artifacts")},
                {"job_attempts", document.at("job_attempts")}};
    if (detail == "full") output["progress_events"] = document.at("progress_events");
    return output;
}

ToolOutcome ProfileMatrixWorkflow::start(const Json& arguments, const JobContext& config) {
    reap_finished();
    {
        std::scoped_lock lock(worker_mutex_);
        if (worker_running_) {
            return ToolFailure{"PROFILE_MATRIX_BUSY", "Another profile matrix workflow is active.", true,
                               {{"job_id", active_job_id_}}};
        }
    }
    auto document = create_checkpoint(arguments, config);
    const auto job_id = document.at("job_id").get<std::string>();
    save(document);
    return begin(job_id, arguments.value("execution", std::string("sync")) == "async");
}

ToolOutcome ProfileMatrixWorkflow::control(const Json& arguments) {
    reap_finished();
    const auto operation = arguments.at("operation").get<std::string>();
    const auto job_id = arguments.at("job_id").get<std::string>();
    if (operation == "status") return result(load(job_id));
    if (operation == "cancel") {
        std::jthread cancelling;
        bool synchronous = false;
        {
            std::scoped_lock lock(worker_mutex_);
            if (worker_running_ && active_job_id_ == job_id) {
                if (worker_.joinable()) {
                    worker_.request_stop();
                    cancelling = std::move(worker_);
                } else {
                    synchronous = true;
                }
            }
        }
        if (synchronous) {
            return ToolFailure{"PROFILE_MATRIX_BUSY",
                "A synchronous profile matrix cannot be cancelled from another request.", true,
                {{"job_id", job_id}}};
        }
        if (cancelling.joinable()) cancelling.join();
        auto document = load(job_id);
        if (!terminal_workflow(document.at("workflow_state").get<std::string>())) {
            document["workflow_state"] = "cancelled";
            document["progress"]["stage"] = "cancelled";
            save(document);
        }
        return result(document);
    }
    if (operation != "resume") invalid_job();
    auto document = load(job_id);
    const auto state = document.at("workflow_state").get<std::string>();
    if (state == "completed") return result(document);
    {
        std::scoped_lock lock(worker_mutex_);
        if (worker_running_) {
            if (active_job_id_ == job_id) return result(document);
            return ToolFailure{"PROFILE_MATRIX_BUSY", "Another profile matrix workflow is active.", true,
                               {{"job_id", active_job_id_}}};
        }
    }
    document["workflow_state"] = "queued";
    document["progress"]["stage"] = "queued";
    document["last_error"] = nullptr;
    save(document);
    return begin(job_id, arguments.value("execution", std::string("async")) == "async");
}

Json ProfileMatrixWorkflow::active_status() const {
    std::string job_id;
    {
        std::scoped_lock lock(worker_mutex_);
        if (!worker_running_) return {{"active", false}};
        job_id = active_job_id_;
    }
    const auto snapshot = result(load(job_id));
    return {{"active", true},
            {"job_id", snapshot.at("job_id")},
            {"workflow_state", snapshot.at("workflow_state")},
            {"status", snapshot.at("status")},
            {"resumable", snapshot.at("resumable")},
            {"requested_cases", snapshot.at("requested_cases")},
            {"receipt_count", snapshot.at("receipt_count")},
            {"cases_with_metrics", snapshot.at("cases_with_metrics")},
            {"progress", snapshot.at("progress")},
            {"last_error", snapshot.at("last_error")}};
}

bool ProfileMatrixWorkflow::running() const {
    std::scoped_lock lock(worker_mutex_);
    return worker_running_;
}

ToolOutcome ProfileMatrixWorkflow::begin(std::string job_id, const bool asynchronous) {
    {
        std::scoped_lock lock(worker_mutex_);
        if (worker_running_) {
            return ToolFailure{"PROFILE_MATRIX_BUSY", "Another profile matrix workflow is active.", true,
                               {{"job_id", active_job_id_}}};
        }
        worker_running_ = true;
        active_job_id_ = job_id;
    }
    if (asynchronous) {
        worker_ = std::jthread([this, job_id](const std::stop_token stop) { execute(job_id, stop); });
        return result(load(job_id));
    }
    execute(job_id, {});
    return result(load(job_id));
}

void ProfileMatrixWorkflow::execute(std::string job_id, const std::stop_token stop) noexcept {
    try {
        auto document = load(job_id);
        document["workflow_state"] = "running";
        document["progress"]["stage"] = "loading";
        save(document);
        auto config = parse_checkpoint_config(document.at("config"), workspace_id_);
        auto& specs = document.at("case_specs");
        for (std::size_t index = 0; index < specs.size(); ++index) {
            auto& spec = specs[index];
            if (spec.at("result").is_object()) continue;
            if (stop.stop_requested()) {
                document["workflow_state"] = "cancelled";
                document["progress"]["stage"] = "cancelled";
                if (!document.value("current_request_accepted", false)) {
                    document["current_request_id"] = nullptr;
                }
                save(document);
                finish_active(job_id);
                return;
            }
            document["progress"]["current_case_number"] = index + 1;
            document["progress"]["current_case_id"] = spec.at("case_id");
            document["progress"]["stage"] = "loading";
            document["progress"]["completed_cases"] = index;
            save(document);

            const auto existing_request = document.at("current_request_id").is_string()
                ? std::optional<std::string>(document.at("current_request_id").get<std::string>())
                : std::nullopt;
            ProfileMatrixCaseExecution execution{
                .arguments = case_arguments(document, spec),
                .config = config,
                .resume_request_id = existing_request,
                .stop = stop,
            };
            execution.progress = [this, &document, &spec](std::string_view request_id,
                std::string_view stage, const bool accepted) {
                if (!request_id.empty()) {
                    document["current_request_id"] = request_id;
                    document["current_request_accepted"] = accepted;
                }
                document["progress"]["stage"] = stage;
                auto event = Json{{"case_id", spec.at("case_id")}, {"stage", stage}, {"accepted", accepted}};
                if (!request_id.empty()) event["request_id"] = request_id;
                auto& events = document.at("progress_events");
                if (events.size() < 4096 && (events.empty() || events.back() != event)) {
                    events.push_back(std::move(event));
                }
                save(document);
            };

            auto outcome = executor_(std::move(execution));
            if (stop.stop_requested()) {
                document["workflow_state"] = "cancelled";
                document["progress"]["stage"] = "cancelled";
                if (!document.value("current_request_accepted", false)) {
                    document["current_request_id"] = nullptr;
                }
                save(document);
                finish_active(job_id);
                return;
            }
            if (const auto* failure = std::get_if<ToolFailure>(&outcome)) {
                const auto error = failure_json(*failure);
                if (failure->code == "CANCELLED") {
                    document["workflow_state"] = "cancelled";
                    document["progress"]["stage"] = "cancelled";
                    document["current_request_id"] = nullptr;
                } else {
                    document["workflow_state"] = "paused";
                    document["progress"]["stage"] = "paused";
                }
                document["last_error"] = error;
                spec["pending_error"] = error;
                save(document);
                finish_active(job_id);
                return;
            }

            const auto& response = std::get<Json>(outcome);
            append_values(document.at("artifacts"), response, "artifacts");
            append_values(document.at("job_attempts"), response, "job_attempts");
            if (!response.contains("cases") || !response.at("cases").is_array() ||
                response.at("cases").size() != 1) {
                checkpoint_error("A profile case returned an invalid normalized result.");
            }
            auto profile_case = response.at("cases").front();
            if (profile_case.value("case_id", std::string{}) != spec.at("case_id").get<std::string>()) {
                checkpoint_error("A profile case returned a receipt for a different case identity.");
            }
            const auto& error = profile_case.at("error");
            if (retry_interruption(error)) {
                auto attempts = profile_case.value("attempts", Json::array());
                const bool outstanding = document.value("current_request_accepted", false) &&
                    document.at("current_request_id").is_string();
                if (outstanding && !attempts.empty()) attempts.erase(attempts.end() - 1);
                spec["pending_attempts"] = std::move(attempts);
                spec["pending_error"] = error;
                document["workflow_state"] = "paused";
                document["progress"]["stage"] = "paused";
                document["last_error"] = error;
                if (!outstanding) document["current_request_id"] = nullptr;
                save(document);
                finish_active(job_id);
                return;
            }

            spec["result"] = std::move(profile_case);
            spec["pending_attempts"] = Json::array();
            spec["pending_error"] = nullptr;
            document["current_request_id"] = nullptr;
            document["current_request_accepted"] = false;
            document["last_error"] = nullptr;
            document["progress"]["completed_cases"] = index + 1;
            document["progress"]["stage"] = "checkpointing";
            save(document);
        }
        document["workflow_state"] = "completed";
        document["progress"]["completed_cases"] = specs.size();
        document["progress"]["current_case_number"] = nullptr;
        document["progress"]["current_case_id"] = nullptr;
        document["progress"]["stage"] = "completed";
        document["current_request_id"] = nullptr;
        document["current_request_accepted"] = false;
        auto& events = document.at("progress_events");
        const auto completed = Json{{"case_id", nullptr}, {"stage", "completed"}, {"accepted", true}};
        if (events.size() < 4096) events.push_back(completed);
        else events.back() = completed;
        save(document);
    } catch (const StateError& error) {
        try {
            auto document = load(job_id);
            document["workflow_state"] = "paused";
            document["progress"]["stage"] = "paused";
            document["last_error"] = failure_json(
                ToolFailure{std::string(error.code()), error.what(), error.retryable()});
            save(document);
        } catch (...) {
        }
    } catch (const std::exception& error) {
        try {
            auto document = load(job_id);
            document["workflow_state"] = "paused";
            document["progress"]["stage"] = "paused";
            document["last_error"] = failure_json(
                ToolFailure{"INTERNAL_ERROR", error.what(), false});
            save(document);
        } catch (...) {
        }
    }
    finish_active(job_id);
}

void ProfileMatrixWorkflow::finish_active(std::string_view job_id) noexcept {
    std::scoped_lock lock(worker_mutex_);
    if (active_job_id_ == job_id) {
        worker_running_ = false;
        active_job_id_.clear();
    }
}

void ProfileMatrixWorkflow::reap_finished() {
    std::jthread completed;
    {
        std::scoped_lock lock(worker_mutex_);
        if (!worker_running_ && worker_.joinable()) completed = std::move(worker_);
    }
    if (completed.joinable()) completed.join();
}

void ProfileMatrixWorkflow::shutdown() {
    std::jthread active;
    {
        std::scoped_lock lock(worker_mutex_);
        if (worker_.joinable()) {
            worker_.request_stop();
            active = std::move(worker_);
        }
    }
    if (active.joinable()) active.join();
}

}
