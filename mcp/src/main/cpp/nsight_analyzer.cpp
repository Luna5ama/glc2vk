#include "nsight_analyzer.hpp"

#include "state_error.hpp"

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <Windows.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdlib>
#include <cwctype>
#include <fstream>
#include <iterator>
#include <limits>
#include <map>
#include <numeric>
#include <optional>
#include <regex>
#include <ranges>
#include <set>
#include <string>
#include <string_view>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

namespace vibris::mcp {
namespace {

namespace fs = std::filesystem;

constexpr std::string_view kInvalidBundle = "INVALID_NSIGHT_BUNDLE";
constexpr std::string_view kInvalidQuery = "INVALID_NSIGHT_QUERY";
constexpr std::size_t kMaxDescriptorBytes = 1024 * 1024;
constexpr std::size_t kMaxLineBytes = 16 * 1024 * 1024;

struct Statistics final {
    double minimum = 0.0;
    double average = 0.0;
    double p50 = 0.0;
    double maximum = 0.0;
    std::size_t count = 0;
};

struct Metric final {
    std::string name;
    std::vector<double> values;
};

struct Marker final {
    std::string name;
    std::string path;
    std::string parent_path;
    std::size_t depth = 0;
    std::vector<double> durations_ms;
    double total_duration_ms = 0.0;
    bool has_child = false;
    bool suspect = false;
    std::size_t order = 0;
};

struct Bundle final {
    fs::path descriptor_path;
    Json descriptor;
    std::map<std::string, fs::path> files;
    std::map<std::string, std::string> repro;
    std::vector<double> frame_ms;
    std::vector<Metric> metrics;
    std::vector<Marker> markers;
};

[[noreturn]] void invalid_bundle(std::string message) {
    throw StateError(kInvalidBundle, std::move(message));
}

[[noreturn]] void invalid_query(std::string message) {
    throw StateError(kInvalidQuery, std::move(message));
}

bool equal_component(const fs::path& left, const fs::path& right) {
    const auto lhs = left.native();
    const auto rhs = right.native();
    return lhs.size() == rhs.size() && std::equal(lhs.begin(), lhs.end(), rhs.begin(),
        [](const wchar_t a, const wchar_t b) { return std::towlower(a) == std::towlower(b); });
}

bool path_begins_with(const fs::path& path, const fs::path& prefix) {
    const auto normalized_path = path.lexically_normal();
    const auto normalized_prefix = prefix.lexically_normal();
    auto value = normalized_path.begin();
    const auto value_end = normalized_path.end();
    for (auto expected = normalized_prefix.begin(); expected != normalized_prefix.end();
         ++expected, ++value) {
        if (value == value_end || !equal_component(*value, *expected)) return false;
    }
    return true;
}

bool ordinary_file(const fs::path& path) {
    const auto attributes = GetFileAttributesW(path.c_str());
    return attributes != INVALID_FILE_ATTRIBUTES &&
        (attributes & FILE_ATTRIBUTE_DIRECTORY) == 0 &&
        (attributes & FILE_ATTRIBUTE_REPARSE_POINT) == 0;
}

std::string read_bounded(const fs::path& path, const std::size_t limit) {
    std::error_code error;
    const auto bytes = fs::file_size(path, error);
    if (error || bytes == 0 || bytes > limit) invalid_bundle("The Nsight bundle descriptor has an invalid size.");
    std::ifstream input(path, std::ios::binary);
    if (!input) invalid_bundle("The Nsight bundle descriptor could not be opened.");
    std::string value(static_cast<std::size_t>(bytes), '\0');
    input.read(value.data(), static_cast<std::streamsize>(value.size()));
    if (!input || input.gcount() != static_cast<std::streamsize>(value.size())) {
        invalid_bundle("The Nsight bundle descriptor could not be read.");
    }
    return value;
}

std::vector<std::string_view> fields(const std::string& line) {
    std::vector<std::string_view> result;
    std::size_t start = 0;
    while (true) {
        const auto separator = line.find('\t', start);
        if (separator == std::string::npos) {
            result.emplace_back(line.data() + start, line.size() - start);
            return result;
        }
        result.emplace_back(line.data() + start, separator - start);
        start = separator + 1;
    }
}

void trim_cr(std::string& line) {
    if (!line.empty() && line.back() == '\r') line.pop_back();
    if (line.size() > kMaxLineBytes) invalid_bundle("An Nsight TSV row is unreasonably large.");
}

void trim_utf8_bom(std::string& line) {
    if (line.size() >= 3 && static_cast<unsigned char>(line[0]) == 0xEF &&
        static_cast<unsigned char>(line[1]) == 0xBB && static_cast<unsigned char>(line[2]) == 0xBF) {
        line.erase(0, 3);
    }
}

std::optional<double> number(const std::string_view value) {
    if (value.empty()) return std::nullopt;
    std::string text(value);
    char* end = nullptr;
    const auto parsed = std::strtod(text.c_str(), &end);
    if (end != text.c_str() + text.size() || !std::isfinite(parsed)) return std::nullopt;
    return parsed;
}

Statistics statistics(const std::vector<double>& values) {
    Statistics result;
    if (values.empty()) return result;
    result.count = values.size();
    result.minimum = *std::min_element(values.begin(), values.end());
    result.maximum = *std::max_element(values.begin(), values.end());
    result.average = std::accumulate(values.begin(), values.end(), 0.0) / static_cast<double>(values.size());
    auto ordered = values;
    const auto middle = ordered.begin() + static_cast<std::ptrdiff_t>(ordered.size() / 2);
    std::nth_element(ordered.begin(), middle, ordered.end());
    result.p50 = *middle;
    return result;
}

Json stats_json(const std::vector<double>& values) {
    const auto value = statistics(values);
    if (value.count == 0) return nullptr;
    return Json{{"sample_count", value.count}, {"min", value.minimum}, {"avg", value.average},
                {"p50", value.p50}, {"max", value.maximum}};
}

fs::path required_file(const Bundle& bundle, const std::string_view name) {
    const auto found = bundle.files.find(std::string(name));
    if (found == bundle.files.end()) invalid_bundle("The Nsight bundle descriptor omitted " + std::string(name) + ".");
    return found->second;
}

std::map<std::string, std::string> parse_repro(const fs::path& path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) invalid_bundle("REPRO_INFO.xls could not be opened.");
    std::map<std::string, std::string> result;
    std::string line;
    bool first = true;
    while (std::getline(input, line)) {
        trim_cr(line);
        if (first) {
            trim_utf8_bom(line);
            first = false;
        }
        const auto row = fields(line);
        if (row.size() >= 2) result.emplace(std::string(row[0]), std::string(row[1]));
    }
    return result;
}

std::vector<double> parse_frame(const fs::path& path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) invalid_bundle("FRAME.xls could not be opened.");
    std::string line;
    if (!std::getline(input, line)) invalid_bundle("FRAME.xls is empty.");
    trim_cr(line);
    trim_utf8_bom(line);
    const auto row = fields(line);
    std::vector<double> result;
    for (std::size_t index = 1; index < row.size(); ++index) {
        if (const auto parsed = number(row[index])) result.push_back(*parsed);
    }
    if (result.empty()) invalid_bundle("FRAME.xls contains no frame durations.");
    return result;
}

std::vector<Metric> parse_frame_metrics(const fs::path& path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) invalid_bundle("GPUTRACE_FRAME.xls could not be opened.");
    std::vector<Metric> result;
    std::string line;
    bool first = true;
    while (std::getline(input, line)) {
        trim_cr(line);
        if (first) {
            trim_utf8_bom(line);
            first = false;
        }
        const auto row = fields(line);
        if (row.empty() || row.front().empty()) continue;
        Metric metric{std::string(row.front()), {}};
        metric.values.reserve(row.size() - 1);
        for (std::size_t index = 1; index < row.size(); ++index) {
            if (const auto parsed = number(row[index])) metric.values.push_back(*parsed);
        }
        result.push_back(std::move(metric));
    }
    return result;
}

std::vector<Marker> parse_markers(const fs::path& path, const double trace_span_ms) {
    std::ifstream input(path, std::ios::binary);
    if (!input) invalid_bundle("D3DPERF_EVENTS.xls could not be opened.");
    std::string line;
    if (!std::getline(input, line)) invalid_bundle("D3DPERF_EVENTS.xls is empty.");
    trim_cr(line);
    trim_utf8_bom(line);
    std::vector<Marker> result;
    std::vector<std::size_t> stack;
    while (std::getline(input, line)) {
        trim_cr(line);
        const auto row = fields(line);
        if (row.empty()) continue;
        const auto leading = row.front().find_first_not_of(' ');
        const auto spaces = leading == std::string_view::npos ? row.front().size() : leading;
        auto name = std::string(row.front().substr(spaces));
        if (name.empty()) continue;
        const auto depth = spaces / 8;
        while (stack.size() > depth) stack.pop_back();
        std::string parent;
        if (!stack.empty()) {
            auto& parent_marker = result[stack.back()];
            parent_marker.has_child = true;
            parent = parent_marker.path;
        }
        Marker marker;
        marker.name = std::move(name);
        marker.parent_path = parent;
        marker.path = parent.empty() ? marker.name : parent + "/" + marker.name;
        marker.depth = depth;
        marker.order = result.size();
        for (std::size_t index = 1; index < row.size(); ++index) {
            if (const auto parsed = number(row[index])) marker.durations_ms.push_back(*parsed);
        }
        marker.total_duration_ms = std::accumulate(
            marker.durations_ms.begin(), marker.durations_ms.end(), 0.0);
        if (trace_span_ms > 0.0 && marker.total_duration_ms > trace_span_ms * 1.3) {
            std::vector<double> alternating;
            for (std::size_t index = 0; index < marker.durations_ms.size(); index += 2) {
                alternating.push_back(marker.durations_ms[index]);
            }
            const auto decoded = std::accumulate(alternating.begin(), alternating.end(), 0.0);
            if (!alternating.empty() && decoded <= trace_span_ms * 1.3) {
                marker.durations_ms = std::move(alternating);
                marker.total_duration_ms = decoded;
            } else {
                const auto& candidate = !alternating.empty() && decoded < marker.total_duration_ms ?
                    alternating : marker.durations_ms;
                std::vector<double> filtered;
                std::ranges::copy_if(candidate, std::back_inserter(filtered), [trace_span_ms](const double value) {
                    return value > 0.0 && value < trace_span_ms * 0.5;
                });
                marker.durations_ms = std::move(filtered);
                marker.total_duration_ms = std::min(trace_span_ms, std::accumulate(
                    marker.durations_ms.begin(), marker.durations_ms.end(), 0.0));
                marker.suspect = true;
            }
        }
        if (marker.durations_ms.empty()) continue;
        result.push_back(std::move(marker));
        if (stack.size() == depth) stack.push_back(result.size() - 1);
    }
    std::vector<Marker> aggregated;
    std::unordered_map<std::string, std::size_t> by_path;
    for (auto& marker : result) {
        const auto [found, inserted] = by_path.emplace(marker.path, aggregated.size());
        if (inserted) {
            aggregated.push_back(std::move(marker));
            continue;
        }
        auto& target = aggregated[found->second];
        target.durations_ms.insert(
            target.durations_ms.end(), marker.durations_ms.begin(), marker.durations_ms.end());
        target.total_duration_ms += marker.total_duration_ms;
        target.has_child = target.has_child || marker.has_child;
        target.suspect = target.suspect || marker.suspect;
    }
    return aggregated;
}

Bundle load_bundle(const fs::path& workspace_root, const fs::path& requested) {
    if (!requested.is_absolute()) invalid_bundle("artifact_path must be absolute.");
    const auto descriptor_path = requested.lexically_normal();
    const auto artifact_link = (workspace_root / ".vibris" / "artifact").lexically_normal();
    if (!path_begins_with(descriptor_path, artifact_link) || descriptor_path == artifact_link) {
        invalid_bundle("artifact_path must be inside this worktree's .vibris/artifact link.");
    }
    const auto link_attributes = GetFileAttributesW(artifact_link.c_str());
    if (link_attributes == INVALID_FILE_ATTRIBUTES ||
        (link_attributes & FILE_ATTRIBUTE_DIRECTORY) == 0 ||
        (link_attributes & FILE_ATTRIBUTE_REPARSE_POINT) == 0) {
        invalid_bundle("The worktree .vibris/artifact managed link is unavailable.");
    }
    if (!ordinary_file(descriptor_path)) invalid_bundle("The Nsight bundle descriptor is unavailable.");
    Json descriptor;
    try {
        descriptor = Json::parse(read_bounded(descriptor_path, kMaxDescriptorBytes));
    } catch (const Json::exception&) {
        invalid_bundle("The Nsight bundle descriptor is invalid JSON.");
    }
    if (!descriptor.is_object() || descriptor.value("schema_version", 0) != 1 ||
        descriptor.value("kind", std::string{}) != "vibris_nsight_gpu_trace_bundle" ||
        !descriptor.value("bundle_complete", false) || !descriptor.contains("files") ||
        !descriptor.at("files").is_object()) {
        invalid_bundle("The artifact is not a complete Vibris Nsight bundle descriptor.");
    }
    Bundle bundle{descriptor_path, descriptor};
    for (const auto& [logical, value] : descriptor.at("files").items()) {
        if (!value.is_string()) invalid_bundle("The Nsight bundle file map is invalid.");
        const fs::path name(value.get<std::string>());
        if (name.empty() || name.is_absolute() || name.has_parent_path() || name.filename() != name) {
            invalid_bundle("The Nsight bundle contains an unsafe sibling file name.");
        }
        const auto path = descriptor_path.parent_path() / name;
        if (!ordinary_file(path)) invalid_bundle("The Nsight bundle is missing " + logical + ".");
        bundle.files.emplace(logical, path);
    }
    bundle.repro = parse_repro(required_file(bundle, "REPRO_INFO.xls"));
    bundle.frame_ms = parse_frame(required_file(bundle, "FRAME.xls"));
    bundle.metrics = parse_frame_metrics(required_file(bundle, "GPUTRACE_FRAME.xls"));
    const auto trace_span = std::accumulate(bundle.frame_ms.begin(), bundle.frame_ms.end(), 0.0);
    bundle.markers = parse_markers(required_file(bundle, "D3DPERF_EVENTS.xls"), trace_span);
    static_cast<void>(required_file(bundle, "GPUTRACE_REGIMES.xls"));
    return bundle;
}

std::regex compile_regex(const std::string& pattern, const std::string_view label) {
    try {
        return std::regex(pattern, std::regex::ECMAScript | std::regex::icase);
    } catch (const std::regex_error&) {
        invalid_query(std::string(label) + " is not a valid regular expression.");
    }
}

std::vector<std::string> matching_paths(const Bundle& bundle, const std::string& pattern) {
    const auto expression = compile_regex(pattern, "in_marker");
    std::vector<std::string> result;
    for (const auto& marker : bundle.markers) {
        if (std::regex_search(marker.name, expression)) result.push_back(marker.path);
    }
    if (result.empty()) invalid_query("No marker matched in_marker.");
    return result;
}

bool path_in_scope(const std::string_view path, const std::vector<std::string>& roots) {
    return std::ranges::any_of(roots, [path](const std::string& root) {
        return path == root || (path.size() > root.size() && path.starts_with(root) && path[root.size()] == '/');
    });
}

std::vector<std::string> evidence_roots(const Bundle& bundle, const Json& query) {
    return matching_paths(bundle, query.value("in_marker", std::string("^Replay$")));
}

const Metric* find_metric(const Bundle& bundle, const std::string_view name) {
    const auto found = std::ranges::find_if(bundle.metrics, [name](const Metric& value) { return value.name == name; });
    return found == bundle.metrics.end() ? nullptr : &*found;
}

std::vector<std::string> headline_metrics(const Bundle& bundle) {
    constexpr std::array patterns{
        "sm__throughput", "sm__inst_executed_realtime", "warps_inactive_sm_active",
        "l1tex__t_sector_hit_rate", "l1tex__throughput",
    };
    std::vector<std::string> result;
    for (const auto pattern : patterns) {
        const auto found = std::ranges::find_if(bundle.metrics, [pattern](const Metric& metric) {
            return metric.name.find(pattern) != std::string::npos;
        });
        if (found != bundle.metrics.end()) result.push_back(found->name);
    }
    return result;
}

using PathMetrics = std::unordered_map<std::string, std::unordered_map<std::string, std::vector<double>>>;

PathMetrics stream_regimes(
    const Bundle& bundle,
    const std::vector<std::string>& metric_names,
    const std::unordered_set<std::string>& wanted_paths) {
    PathMetrics output;
    if (metric_names.empty() || wanted_paths.empty()) return output;
    std::ifstream input(required_file(bundle, "GPUTRACE_REGIMES.xls"), std::ios::binary);
    if (!input) invalid_bundle("GPUTRACE_REGIMES.xls could not be opened.");
    std::string line;
    if (!std::getline(input, line)) invalid_bundle("GPUTRACE_REGIMES.xls is empty.");
    trim_cr(line);
    trim_utf8_bom(line);
    const auto header = fields(line);
    std::unordered_map<std::string, std::vector<std::size_t>> columns;
    for (const auto& name : metric_names) columns.emplace(name, std::vector<std::size_t>{});
    for (std::size_t index = 1; index < header.size(); ++index) {
        const auto found = columns.find(std::string(header[index]));
        if (found != columns.end()) found->second.push_back(index);
    }
    while (std::getline(input, line)) {
        trim_cr(line);
        const auto row = fields(line);
        if (row.empty()) continue;
        const auto path = std::string(row.front());
        if (!wanted_paths.contains(path)) continue;
        auto& target = output[path];
        for (const auto& [name, indices] : columns) {
            auto& values = target[name];
            for (const auto index : indices) {
                if (index < row.size()) {
                    if (const auto parsed = number(row[index])) values.push_back(*parsed);
                }
            }
        }
    }
    return output;
}

Json measurement_contract() {
    return Json{
        {"evidence_marker", "^Replay$"},
        {"pass_durations_only", true},
        {"excluded", Json::array({"whole_capture", "relative_to_capture", "frame_budget", "fraction_of_gpu",
                                   "cpu_submission", "Copy", "sleep_or_yield", "unmarked_tail"})},
    };
}

Json hardware(const Bundle& bundle) {
    const auto get = [&](const std::string& key) -> Json {
        const auto value = bundle.repro.find(key);
        return value == bundle.repro.end() ? Json(nullptr) : Json(value->second);
    };
    return Json{{"gpu", get("Device Name")}, {"chip", get("Chip Name")}, {"driver", get("Driver Version")},
                {"api", get("API")}, {"nsight_version", get("Product Version")},
                {"process", get("Process File Name")}};
}

Json metric_values_for_paths(
    const Bundle& bundle,
    const std::vector<std::string>& names,
    const std::vector<std::string>& paths) {
    const std::unordered_set<std::string> wanted(paths.begin(), paths.end());
    const auto projected = stream_regimes(bundle, names, wanted);
    Json result = Json::object();
    for (const auto& name : names) {
        std::vector<double> values;
        for (const auto& path : paths) {
            const auto path_values = projected.find(path);
            if (path_values == projected.end()) continue;
            const auto metric = path_values->second.find(name);
            if (metric != path_values->second.end()) values.insert(values.end(), metric->second.begin(), metric->second.end());
        }
        result[name] = stats_json(values);
    }
    return result;
}

Json summary(const Bundle& bundle) {
    const Json query{{"in_marker", "^Replay$"}};
    const auto roots = evidence_roots(bundle, query);
    std::vector<const Marker*> passes;
    for (const auto& marker : bundle.markers) {
        if (!marker.has_child && marker.name != "Copy" && path_in_scope(marker.path, roots) &&
            std::ranges::find(roots, marker.path) == roots.end()) {
            passes.push_back(&marker);
        }
    }
    std::ranges::sort(passes, [](const Marker* left, const Marker* right) {
        return left->total_duration_ms > right->total_duration_ms;
    });
    if (passes.size() > 20) passes.resize(20);
    Json pass_rows = Json::array();
    for (const auto* pass : passes) {
        pass_rows.push_back({{"name", pass->name}, {"path", pass->path},
            {"instance_count", pass->durations_ms.size()},
            {"total_duration_ns", static_cast<std::uint64_t>(pass->total_duration_ms * 1'000'000.0)},
            {"duration", stats_json(pass->durations_ms)}});
    }
    std::vector<double> replay_durations;
    for (const auto& marker : bundle.markers) {
        if (std::ranges::find(roots, marker.path) != roots.end()) {
            replay_durations.insert(replay_durations.end(), marker.durations_ms.begin(), marker.durations_ms.end());
        }
    }
    const auto headlines = headline_metrics(bundle);
    return Json{
        {"schema_version", 1},
        {"operation", "summary"},
        {"bundle", bundle.descriptor_path.string()},
        {"capture", {
            {"mode", bundle.descriptor.value("capture_mode", std::string{})},
            {"pass_id", bundle.descriptor.value("pass_id", std::string{})},
            {"capture_type", bundle.descriptor.value("capture_type", std::string{})},
            {"replay_backend", bundle.descriptor.value("replay_backend", std::string{})},
            {"architecture", bundle.descriptor.value("architecture", std::string{})},
            {"metric_set_name", bundle.descriptor.value("metric_set_name", std::string{})},
        }},
        {"hardware_context", hardware(bundle)},
        {"replay", {{"marker_instances", replay_durations.size()}, {"duration_ms", stats_json(replay_durations)}}},
        {"top_passes", pass_rows},
        {"headline_metrics_in_replay", metric_values_for_paths(bundle, headlines, roots)},
        {"context_only", {{"captured_frame_count", bundle.frame_ms.size()},
                           {"captured_trace_span_ms", std::accumulate(bundle.frame_ms.begin(), bundle.frame_ms.end(), 0.0)}}},
        {"measurement_contract", measurement_contract()},
    };
}

Json stages(const Bundle& bundle, const Json& query) {
    auto roots = evidence_roots(bundle, query);
    if (query.contains("parent")) roots = matching_paths(bundle, query.at("parent").get<std::string>());
    const auto explicit_depth = query.contains("depth");
    const auto depth = explicit_depth ? query.at("depth").get<std::size_t>() : std::numeric_limits<std::size_t>::max();
    std::map<std::string, std::vector<const Marker*>> grouped;
    for (const auto& marker : bundle.markers) {
        if (marker.name == "Copy" || !path_in_scope(marker.path, roots) ||
            std::ranges::find(roots, marker.path) != roots.end()) continue;
        const bool direct_child = std::ranges::any_of(roots, [&](const std::string& root) {
            const auto found = std::ranges::find_if(bundle.markers, [&](const Marker& value) { return value.path == root; });
            return found != bundle.markers.end() && marker.depth == found->depth + 1;
        });
        if ((explicit_depth && marker.depth != depth) || (!explicit_depth && !direct_child)) continue;
        grouped[marker.name].push_back(&marker);
    }
    std::vector<Json> rows;
    for (const auto& [name, markers] : grouped) {
        double total = 0.0;
        std::size_t instances = 0;
        bool suspect = false;
        for (const auto* marker : markers) {
            total += marker->total_duration_ms;
            instances += marker->durations_ms.size();
            suspect = suspect || marker->suspect;
        }
        rows.push_back({{"name", name}, {"instance_count", instances},
            {"total_duration_ns", static_cast<std::uint64_t>(total * 1'000'000.0)}, {"suspect", suspect}});
    }
    std::ranges::sort(rows, [](const Json& left, const Json& right) {
        return left.at("total_duration_ns").get<std::uint64_t>() > right.at("total_duration_ns").get<std::uint64_t>();
    });
    const auto top = query.value("top", std::size_t{50});
    if (rows.size() > top) rows.resize(top);
    return Json{{"schema_version", 1}, {"operation", "stages"}, {"stages", Json(std::move(rows))},
                {"measurement_contract", measurement_contract()}};
}

Json actions(const Bundle& bundle, const Json& query) {
    const auto roots = evidence_roots(bundle, query);
    const auto filter_pattern = query.value("filter", std::string{});
    const auto filter = filter_pattern.empty() ? std::optional<std::regex>{} :
        std::optional<std::regex>{compile_regex(filter_pattern, "filter")};
    std::vector<const Marker*> selected;
    for (const auto& marker : bundle.markers) {
        if (marker.has_child || marker.name == "Copy" || !path_in_scope(marker.path, roots) ||
            std::ranges::find(roots, marker.path) != roots.end()) continue;
        if (filter && !std::regex_search(marker.path, *filter) && !std::regex_search(marker.name, *filter)) continue;
        selected.push_back(&marker);
    }
    const auto sort = query.value("sort", std::string("duration"));
    std::ranges::sort(selected, [sort](const Marker* left, const Marker* right) {
        return sort == "start" ? left->order < right->order : left->total_duration_ms > right->total_duration_ms;
    });
    const auto top = query.value("top", std::size_t{50});
    if (selected.size() > top) selected.resize(top);
    const auto headline = headline_metrics(bundle);
    std::unordered_set<std::string> wanted;
    for (const auto* marker : selected) wanted.insert(marker->path);
    const auto projected = query.value("with_metrics", false) ? stream_regimes(bundle, headline, wanted) : PathMetrics{};
    Json rows = Json::array();
    for (const auto* marker : selected) {
        Json row{{"name", marker->name}, {"path", marker->path}, {"parent_path", marker->parent_path},
                 {"depth", marker->depth}, {"instance_count", marker->durations_ms.size()},
                 {"total_duration_ns", static_cast<std::uint64_t>(marker->total_duration_ms * 1'000'000.0)},
                 {"duration_ms", stats_json(marker->durations_ms)}, {"suspect", marker->suspect}};
        if (query.value("with_metrics", false)) {
            Json metrics = Json::object();
            const auto values = projected.find(marker->path);
            for (const auto& name : headline) {
                if (values == projected.end()) {
                    metrics[name] = nullptr;
                    continue;
                }
                const auto samples = values->second.find(name);
                metrics[name] = samples == values->second.end() ? Json(nullptr) : stats_json(samples->second);
            }
            row["headline_metrics"] = std::move(metrics);
        }
        rows.push_back(std::move(row));
    }
    return Json{{"schema_version", 1}, {"operation", "actions"},
                {"definition", "deepest D3DPERF_EVENTS marker; not a raw API call"},
                {"actions", std::move(rows)}, {"measurement_contract", measurement_contract()}};
}

Json metric_query(const Bundle& bundle, const Json& query) {
    const auto expression = compile_regex(query.at("name").get<std::string>(), "name");
    std::vector<const Metric*> matches;
    for (const auto& metric : bundle.metrics) {
        if (std::regex_search(metric.name, expression)) matches.push_back(&metric);
    }
    if (matches.empty()) invalid_query("No metric matched name.");
    if (matches.size() > 1 && !query.value("all_matches", false)) {
        invalid_query("Multiple metrics matched name; narrow it or set all_matches=true.");
    }
    const auto roots = evidence_roots(bundle, query);
    std::vector<std::string> names;
    for (const auto* metric : matches) names.push_back(metric->name);
    const auto scoped = metric_values_for_paths(bundle, names, roots);
    Json rows = Json::array();
    const auto top = query.value("top", std::size_t{50});
    for (std::size_t index = 0; index < matches.size() && index < top; ++index) {
        rows.push_back({{"name", matches[index]->name}, {"global_context_only", stats_json(matches[index]->values)},
                        {"in_marker", scoped.at(matches[index]->name)}});
    }
    return Json{{"schema_version", 1}, {"operation", "metric"}, {"metrics", std::move(rows)},
                {"measurement_contract", measurement_contract()}};
}

struct Concept final {
    const char* key;
    const char* pattern;
};

Json draws_diagnostic(const Bundle& bundle, const Json& query) {
    const auto roots = evidence_roots(bundle, query);
    std::vector<const Marker*> leaves;
    for (const auto& marker : bundle.markers) {
        if (!marker.has_child && marker.name != "Copy" && path_in_scope(marker.path, roots) &&
            std::ranges::find(roots, marker.path) == roots.end()) {
            leaves.push_back(&marker);
        }
    }
    const auto frame_count = std::max<std::size_t>(bundle.frame_ms.size(), 1);
    const auto frame_divisor = static_cast<double>(frame_count);
    std::vector<double> instance_durations;
    std::map<std::string, std::size_t> name_counts;
    std::size_t state_count = 0;
    double state_duration_ms = 0.0;
    double total_duration_ms = 0.0;
    const auto state_change = compile_regex(
        R"((^|[^a-z])(clear|resolve|barrier|transition|copy|present|fence|wait|flush|discard|map|unmap))",
        "state-change marker pattern");
    for (const auto* marker : leaves) {
        instance_durations.insert(
            instance_durations.end(), marker->durations_ms.begin(), marker->durations_ms.end());
        name_counts[marker->name] += marker->durations_ms.size();
        total_duration_ms += marker->total_duration_ms;
        if (std::regex_search(marker->name, state_change)) {
            state_count += marker->durations_ms.size();
            state_duration_ms += marker->total_duration_ms;
        }
    }
    const auto small_count = static_cast<std::size_t>(std::ranges::count_if(
        instance_durations, [](const double value) { return value < 0.005; }));
    std::vector<std::pair<std::string, std::size_t>> ranked(name_counts.begin(), name_counts.end());
    std::ranges::sort(ranked, [](const auto& left, const auto& right) { return left.second > right.second; });
    const auto top = std::min(query.value("top", std::size_t{10}), ranked.size());
    Json top_names = Json::array();
    for (std::size_t index = 0; index < top; ++index) {
        top_names.push_back({{"name", ranked[index].first},
                             {"instances", ranked[index].second},
                             {"instances_per_frame", ranked[index].second / frame_divisor}});
    }
    const auto small_fraction = instance_durations.empty() ? std::optional<double>{} :
        std::optional<double>{static_cast<double>(small_count) / instance_durations.size()};
    const auto frame_average_ms = std::accumulate(bundle.frame_ms.begin(), bundle.frame_ms.end(), 0.0) / frame_divisor;
    const auto state_ms_per_frame = state_duration_ms / frame_divisor;
    const auto state_fraction_of_frame = frame_average_ms > 0.0 ?
        std::optional<double>{state_ms_per_frame / frame_average_ms} : std::optional<double>{};
    Json signals{
        {"leaf_path_count", leaves.size()},
        {"leaf_instances_per_frame", instance_durations.size() / frame_divisor},
        {"leaf_duration_ms", stats_json(instance_durations)},
        {"leaf_total_ms_per_frame", total_duration_ms / frame_divisor},
        {"small_leaf_instances_per_frame", small_count / frame_divisor},
        {"small_leaf_fraction", small_fraction ? Json(*small_fraction) : Json(nullptr)},
        {"state_change_instances_per_frame", state_count / frame_divisor},
        {"state_change_ms_per_frame", state_ms_per_frame},
        {"state_change_fraction_of_frame", state_fraction_of_frame ? Json(*state_fraction_of_frame) : Json(nullptr)},
        {"top_leaf_names", std::move(top_names)},
    };
    Json verdict = Json::array();
    if (instance_durations.empty()) {
        verdict.push_back({{"tag", "data_missing"}, {"severity", "info"}});
    } else {
        verdict.push_back({{"tag", "leaf_count"}, {"severity", "info"}});
        if (small_fraction && *small_fraction >= 0.5) {
            verdict.push_back({{"tag", "many_small_leaves"},
                               {"severity", *small_fraction >= 0.7 ? "high" : "medium"}});
        }
        if (state_fraction_of_frame && *state_fraction_of_frame >= 0.15) {
            verdict.push_back({{"tag", "state_change_heavy"},
                               {"severity", *state_fraction_of_frame >= 0.25 ? "high" : "medium"}});
        } else if (state_count == 0) {
            verdict.push_back({{"tag", "state_change_not_visible"}, {"severity", "info"}});
        }
    }
    return Json{{"schema_version", 1}, {"operation", "draws"},
                {"scope", {{"in_marker", query.value("in_marker", std::string("^Replay$"))},
                            {"matched_markers", roots.size()}}},
                {"signals", std::move(signals)}, {"verdict", std::move(verdict)},
                {"context_only", {{"captured_frame_count", bundle.frame_ms.size()},
                                  {"captured_frame_average_ms", frame_average_ms}}},
                {"measurement_contract", measurement_contract()}};
}

Json diagnostic(const Bundle& bundle, const Json& query) {
    const auto operation = query.at("operation").get<std::string>();
    if (operation == "draws") return draws_diagnostic(bundle, query);
    std::vector<Concept> concepts;
    if (operation == "bandwidth") concepts = {
        {"dram_pct", R"(dramc__throughput\.avg\.pct_of_peak_sustained_elapsed$)"},
        {"l2_pct", R"(lts__throughput\.avg\.pct_of_peak_sustained_elapsed$)"},
        {"l1tex_pct", R"(l1tex__throughput\.avg\.pct_of_peak_sustained_elapsed$)"},
        {"pcie_pct", R"(pcie__throughput\.avg\.pct_of_peak_sustained_elapsed$)"},
        {"sm_pct", R"(sm__throughput\.avg\.pct_of_peak_sustained_elapsed$)"},
    };
    else if (operation == "shader_bound") concepts = {
        {"sm_throughput", R"(sm__throughput\.avg\.pct_of_peak_sustained_elapsed$)"},
        {"gr_cycles_active", R"(gr__cycles_active\.avg\.pct_of_peak_sustained_elapsed$)"},
        {"compute_sync", R"(gr__compute_cycles_active_queue_sync\.avg\.pct_of_peak_sustained_elapsed$)"},
        {"compute_async", R"(gr__compute_cycles_active_queue_async\.avg\.pct_of_peak_sustained_elapsed$)"},
        {"ps_warps_active", R"(warps_active_shader_ps_realtime.*pct_of_peak_sustained_elapsed$)"},
        {"vtg_warps_active", R"(warps_active_shader_vtg_realtime.*pct_of_peak_sustained_elapsed$)"},
        {"cs_warps_active", R"(warps_active_shader_cs_realtime.*pct_of_peak_sustained_elapsed$)"},
        {"warps_inactive_sm_active", R"(warps_inactive_sm_active_realtime.*pct_of_peak_sustained_elapsed$)"},
    };
    else if (operation == "texture_cache") concepts = {
        {"l1tex_throughput", R"(l1tex__throughput\.avg\.pct_of_peak_sustained_elapsed$)"},
        {"l1_hit_rate", R"(l1tex__t_sector_hit_rate\.pct$)"},
        {"l2_hit_rate", R"(lts__average_t_sector_hit_rate_realtime\.pct$)"},
        {"dram_pct", R"(dramc__throughput\.avg\.pct_of_peak_sustained_elapsed$)"},
    };
    else if (operation == "overdraw") concepts = {
        {"pixels_input", R"(prop__input_pixels_type_3d_realtime\.sum$)"},
        {"pixels_to_crop", R"(prop__prop2crop_pixels_realtime\.sum$)"},
        {"pixels_passed_z", R"(prop__prop2zrop_pixels_op_passed_realtime\.sum$)"},
        {"zcull_input", R"(raster__zcull_input_samples_realtime\.sum$)"},
        {"zcull_accepted", R"(raster__zcull_input_samples_op_accepted_realtime\.sum$)"},
        {"crop_write_pct", R"(crop__write_throughput\.avg\.pct_of_peak_sustained_elapsed$)"},
        {"zrop_write_pct", R"(zrop__write_throughput\.avg\.pct_of_peak_sustained_elapsed$)"},
    };
    else if (operation == "geometry") concepts = {
        {"vaf_pct", R"(vaf__throughput\.avg\.pct_of_peak_sustained_elapsed$)"},
        {"pda_pct", R"(pda__throughput\.avg\.pct_of_peak_sustained_elapsed$)"},
        {"raster_pct", R"(raster__throughput\.avg\.pct_of_peak_sustained_elapsed$)"},
        {"prims_input", R"(pda__input_prims_realtime\.sum$)"},
        {"pixels_input", R"(prop__input_pixels_type_3d_realtime\.sum$)"},
        {"samples_to_zcull", R"(raster__zcull_input_samples_realtime\.sum$)"},
    };
    else if (operation == "stalls") concepts = {
        {"gr_cycles_active", R"(gr__cycles_active\.avg\.pct_of_peak_sustained_elapsed$)"},
        {"gpu_syncce_active", R"(gpu__engine_cycles_active_any_syncce\.avg\.pct_of_peak_sustained_elapsed$)"},
    };

    std::map<std::string, std::string> resolved;
    Json missing = Json::array();
    for (const auto& entry : concepts) {
        const auto expression = compile_regex(entry.pattern, entry.key);
        const auto found = std::ranges::find_if(bundle.metrics, [&](const Metric& metric) {
            return std::regex_search(metric.name, expression);
        });
        if (found == bundle.metrics.end()) missing.push_back(entry.key);
        else resolved.emplace(entry.key, found->name);
    }
    const auto roots = evidence_roots(bundle, query);
    std::vector<std::string> names;
    for (const auto& [key, name] : resolved) {
        static_cast<void>(key);
        names.push_back(name);
    }
    const auto raw_values = metric_values_for_paths(bundle, names, roots);
    Json values = Json::object();
    Json global_context = Json::object();
    std::map<std::string, std::optional<double>> averages;
    for (const auto& entry : concepts) {
        const auto found = resolved.find(entry.key);
        if (found == resolved.end() || raw_values.at(found->second).is_null()) {
            values[entry.key] = nullptr;
            averages[entry.key] = std::nullopt;
        } else {
            values[entry.key] = raw_values.at(found->second);
            averages[entry.key] = raw_values.at(found->second).at("avg").get<double>();
        }
        if (found == resolved.end()) {
            global_context[entry.key] = nullptr;
        } else {
            const auto metric = std::ranges::find_if(bundle.metrics, [&](const Metric& candidate) {
                return candidate.name == found->second;
            });
            global_context[entry.key] = metric == bundle.metrics.end() ? Json(nullptr) : stats_json(metric->values);
        }
    }
    const auto get = [&](const std::string& key) { return averages[key]; };
    const auto ratio = [&](const std::string& numerator, const std::string& denominator) -> Json {
        const auto n = get(numerator);
        const auto d = get(denominator);
        return n && d && *d != 0.0 ? Json(*n / *d) : Json(nullptr);
    };
    Json signals = Json::object();
    Json verdict = Json::array();
    if (operation == "bandwidth") {
        std::optional<std::pair<std::string, double>> dominant;
        std::vector<std::pair<std::string, double>> ranking;
        for (const auto* key : {"dram_pct", "l2_pct", "l1tex_pct", "pcie_pct"}) {
            if (const auto value = get(key)) {
                ranking.emplace_back(key, *value);
                if (!dominant || *value > dominant->second) dominant = std::pair{std::string(key), *value};
            }
        }
        std::ranges::sort(ranking, [](const auto& left, const auto& right) { return left.second > right.second; });
        Json ranking_json = Json::array();
        for (const auto& [tier, value] : ranking) ranking_json.push_back({{"tier", tier}, {"pct", value}});
        signals["dominant_tier"] = dominant ? Json(dominant->first) : Json(nullptr);
        signals["dominant_tier_pct"] = dominant ? Json(dominant->second) : Json(nullptr);
        signals["tier_ranking"] = std::move(ranking_json);
        signals["sm_pct"] = get("sm_pct") ? Json(*get("sm_pct")) : Json(nullptr);
        signals["memory_vs_compute"] = dominant && get("sm_pct") ? Json(dominant->second - *get("sm_pct")) : Json(nullptr);
        if (dominant && dominant->second >= 80.0) verdict.push_back({{"tag", "memory_saturated"}, {"severity", "high"}});
        else if (dominant && dominant->second >= 60.0) verdict.push_back({{"tag", "memory_pressure"}, {"severity", "medium"}});
        if (get("pcie_pct") && *get("pcie_pct") >= 30.0) verdict.push_back({{"tag", "pcie_pressure"}, {"severity", "high"}});
    } else if (operation == "shader_bound") {
        signals["sm_stall_ratio"] = ratio("warps_inactive_sm_active", "sm_throughput");
        const auto sync = get("compute_sync");
        const auto async = get("compute_async");
        signals["async_efficiency"] = sync && async && *sync + *async > 0.0 ? Json(*async / (*sync + *async)) : Json(nullptr);
        const auto compute_total = sync || async ? std::optional<double>{sync.value_or(0.0) + async.value_or(0.0)} :
            std::optional<double>{};
        signals["compute_dominance"] = compute_total && get("gr_cycles_active") && *get("gr_cycles_active") != 0.0 ?
            Json(*compute_total / *get("gr_cycles_active")) : Json(nullptr);
        std::optional<std::pair<std::string, double>> stage;
        std::vector<std::pair<std::string, double>> stage_ranking;
        for (const auto* key : {"ps_warps_active", "vtg_warps_active", "cs_warps_active"}) {
            if (const auto value = get(key)) {
                stage_ranking.emplace_back(key, *value);
                if (!stage || *value > stage->second) stage = std::pair{std::string(key), *value};
            }
        }
        std::ranges::sort(stage_ranking, [](const auto& left, const auto& right) { return left.second > right.second; });
        const auto stage_label = [](const std::string& key) {
            if (key.starts_with("ps_")) return std::string("ps");
            if (key.starts_with("vtg_")) return std::string("vtg");
            return std::string("cs");
        };
        Json stage_json = Json::array();
        for (const auto& [key, value] : stage_ranking) {
            stage_json.push_back({{"stage", stage_label(key)}, {"warps_active_pct", value}});
        }
        signals["dominant_shader_stage"] = stage ? Json(stage_label(stage->first)) : Json(nullptr);
        signals["dominant_stage_pct"] = stage ? Json(stage->second) : Json(nullptr);
        signals["stage_ranking"] = std::move(stage_json);
        if (get("sm_throughput") && *get("sm_throughput") >= 70.0) {
            verdict.push_back({{"tag", "sm_saturated"}, {"severity", "high"}});
        } else if (get("sm_throughput") && *get("sm_throughput") >= 40.0) {
            verdict.push_back({{"tag", "sm_pressure"}, {"severity", "medium"}});
        }
        if (!signals["sm_stall_ratio"].is_null() && signals["sm_stall_ratio"].get<double>() >= 0.25) {
            verdict.push_back({{"tag", "sm_active_but_stalled"}, {"severity", "high"}});
        }
    } else if (operation == "texture_cache") {
        signals["l1_hit_rate"] = get("l1_hit_rate") ? Json(*get("l1_hit_rate")) : Json(nullptr);
        signals["l2_hit_rate"] = get("l2_hit_rate") ? Json(*get("l2_hit_rate")) : Json(nullptr);
        signals["l1tex_throughput"] = get("l1tex_throughput") ? Json(*get("l1tex_throughput")) : Json(nullptr);
        signals["dram_throughput"] = get("dram_pct") ? Json(*get("dram_pct")) : Json(nullptr);
        signals["l1_miss_rate"] = get("l1_hit_rate") ? Json(100.0 - *get("l1_hit_rate")) : Json(nullptr);
        signals["l2_miss_rate"] = get("l2_hit_rate") ? Json(100.0 - *get("l2_hit_rate")) : Json(nullptr);
        signals["miss_to_dram"] = get("l1_hit_rate") && get("l2_hit_rate") ?
            Json(((100.0 - *get("l1_hit_rate")) / 100.0) * ((100.0 - *get("l2_hit_rate")) / 100.0)) : Json(nullptr);
        if (get("l1_hit_rate") && *get("l1_hit_rate") < 60.0) verdict.push_back({{"tag", "l1tex_low_hit_rate"}, {"severity", "medium"}});
        if (get("l2_hit_rate") && *get("l2_hit_rate") < 60.0) verdict.push_back({{"tag", "l2_low_hit_rate"}, {"severity", "medium"}});
    } else if (operation == "overdraw") {
        signals["overdraw_ratio"] = ratio("pixels_input", "pixels_to_crop");
        const auto zcull_acceptance = ratio("zcull_accepted", "zcull_input");
        signals["zcull_rejection_rate"] = zcull_acceptance.is_null() ? Json(nullptr) :
            Json(1.0 - zcull_acceptance.get<double>());
        const auto late_z_pass = ratio("pixels_passed_z", "pixels_input");
        signals["late_z_pass_rate"] = late_z_pass;
        signals["late_z_attrition_rate"] = late_z_pass.is_null() ? Json(nullptr) :
            Json(1.0 - late_z_pass.get<double>());
        signals["color_write_pct"] = get("crop_write_pct") ? Json(*get("crop_write_pct")) : Json(nullptr);
        signals["depth_write_pct"] = get("zrop_write_pct") ? Json(*get("zrop_write_pct")) : Json(nullptr);
        if (!signals["overdraw_ratio"].is_null() && signals["overdraw_ratio"].get<double>() >= 2.5) {
            verdict.push_back({{"tag", "overdraw_high"}, {"severity", "high"}});
        } else if (!signals["overdraw_ratio"].is_null() && signals["overdraw_ratio"].get<double>() >= 1.5) {
            verdict.push_back({{"tag", "overdraw_moderate"}, {"severity", "medium"}});
        }
    } else if (operation == "geometry") {
        signals["pixels_per_prim"] = ratio("pixels_input", "prims_input");
        signals["samples_per_pixel"] = ratio("samples_to_zcull", "pixels_input");
        std::optional<std::pair<std::string, double>> dominant;
        std::vector<std::pair<std::string, double>> ranking;
        for (const auto* key : {"vaf_pct", "pda_pct", "raster_pct"}) {
            if (const auto value = get(key)) {
                ranking.emplace_back(key, *value);
                if (!dominant || *value > dominant->second) dominant = std::pair{std::string(key), *value};
            }
        }
        std::ranges::sort(ranking, [](const auto& left, const auto& right) { return left.second > right.second; });
        Json ranking_json = Json::array();
        for (const auto& [key, value] : ranking) {
            ranking_json.push_back({{"stage", key.substr(0, key.size() - 4)}, {"pct", value}});
        }
        signals["dominant_frontend"] = dominant ?
            Json(dominant->first.substr(0, dominant->first.size() - 4)) : Json(nullptr);
        signals["dominant_frontend_pct"] = dominant ? Json(dominant->second) : Json(nullptr);
        signals["frontend_ranking"] = std::move(ranking_json);
        if (!signals["pixels_per_prim"].is_null() && signals["pixels_per_prim"].get<double>() < 4.0) {
            verdict.push_back({{"tag", "micro_triangles"}, {"severity", "high"}});
        } else if (!signals["pixels_per_prim"].is_null() && signals["pixels_per_prim"].get<double>() < 16.0) {
            verdict.push_back({{"tag", "small_triangles"}, {"severity", "medium"}});
        }
    } else if (operation == "stalls") {
        const auto frame_total_ms = std::accumulate(bundle.frame_ms.begin(), bundle.frame_ms.end(), 0.0);
        double depth_one_ms = 0.0;
        std::size_t depth_one_count = 0;
        for (const auto& marker : bundle.markers) {
            if (marker.depth == 1) {
                depth_one_ms += marker.total_duration_ms;
                depth_one_count += marker.durations_ms.size();
            }
        }
        const auto coverage = frame_total_ms > 0.0 ?
            std::optional<double>{depth_one_ms / frame_total_ms * 100.0} : std::optional<double>{};
        signals["gr_idle_pct"] = get("gr_cycles_active") ? Json(100.0 - *get("gr_cycles_active")) : Json(nullptr);
        signals["gpu_syncce_active"] = get("gpu_syncce_active") ? Json(*get("gpu_syncce_active")) : Json(nullptr);
        signals["marker_coverage_pct_context_only"] = coverage ? Json(*coverage) : Json(nullptr);
        signals["unaccounted_ms_context_only"] = frame_total_ms > 0.0 ?
            Json(std::max(frame_total_ms - depth_one_ms, 0.0)) : Json(nullptr);
        signals["depth1_marker_instances_context_only"] = depth_one_count;
        if (!signals["gr_idle_pct"].is_null() && signals["gr_idle_pct"].get<double>() >= 30.0) {
            verdict.push_back({{"tag", "gpu_idle"}, {"severity", "high"}});
        }
        if (coverage && *coverage < 80.0) {
            verdict.push_back({{"tag", "low_marker_coverage"}, {"severity", "medium"}});
        }
        if (get("gpu_syncce_active") && *get("gpu_syncce_active") >= 20.0) {
            verdict.push_back({{"tag", "dma_pressure"},
                               {"severity", *get("gpu_syncce_active") >= 40.0 ? "high" : "medium"}});
        }
    }
    if (resolved.empty()) verdict.push_back({{"tag", "data_missing"}, {"severity", "info"}});
    return Json{{"schema_version", 1}, {"operation", operation},
                {"scope", {{"in_marker", query.value("in_marker", std::string("^Replay$"))},
                            {"matched_markers", roots.size()}}},
                {"metrics_resolved", resolved}, {"metrics_missing", std::move(missing)},
                {"in_marker_values", std::move(values)}, {"global_context_only", std::move(global_context)},
                {"signals", std::move(signals)},
                {"verdict", std::move(verdict)}, {"measurement_contract", measurement_contract()}};
}

} // namespace

Json analyze_nsight_bundle(
    const fs::path& workspace_root,
    const fs::path& artifact_path,
    const Json& query) {
    const auto bundle = load_bundle(workspace_root, artifact_path);
    const auto operation = query.at("operation").get<std::string>();
    if (operation == "summary") return summary(bundle);
    if (operation == "stages") return stages(bundle, query);
    if (operation == "actions") return actions(bundle, query);
    if (operation == "metric") return metric_query(bundle, query);
    return diagnostic(bundle, query);
}

} // namespace vibris::mcp
