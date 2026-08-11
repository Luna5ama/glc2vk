#include "source_preparer.hpp"

#include <system_error>
#include <utility>

namespace vibris::mcp {

PreparedSource::PreparedSource(
    control::v2::PreparedSourceRef reference,
    std::filesystem::path directory,
    ArchiveExtractionStats archive_stats,
    std::size_t attempts,
    std::string requested_revision,
    std::string resolved_revision)
    : reference_(std::move(reference)),
      directory_(std::move(directory)),
      archive_stats_(archive_stats),
      attempts_(attempts),
      requested_revision_(std::move(requested_revision)),
      resolved_revision_(std::move(resolved_revision)),
      owns_directory_(true) {
}

PreparedSource::PreparedSource(PreparedSource&& other)
    : reference_(std::move(other.reference_)),
      directory_(std::move(other.directory_)),
      archive_stats_(other.archive_stats_),
      attempts_(other.attempts_),
      requested_revision_(std::move(other.requested_revision_)),
      resolved_revision_(std::move(other.resolved_revision_)),
      owns_directory_(std::exchange(other.owns_directory_, false)) {
}

PreparedSource& PreparedSource::operator=(PreparedSource&& other) {
    if (this != &other) {
        cleanup();
        reference_ = std::move(other.reference_);
        directory_ = std::move(other.directory_);
        archive_stats_ = other.archive_stats_;
        attempts_ = other.attempts_;
        requested_revision_ = std::move(other.requested_revision_);
        resolved_revision_ = std::move(other.resolved_revision_);
        owns_directory_ = std::exchange(other.owns_directory_, false);
    }
    return *this;
}

PreparedSource::~PreparedSource() {
    cleanup();
}

const control::v2::PreparedSourceRef& PreparedSource::reference() const noexcept {
    return reference_;
}

const std::filesystem::path& PreparedSource::directory() const noexcept {
    return directory_;
}

const ArchiveExtractionStats& PreparedSource::archive_stats() const noexcept {
    return archive_stats_;
}

std::size_t PreparedSource::attempts() const noexcept {
    return attempts_;
}

std::string_view PreparedSource::requested_revision() const noexcept {
    return requested_revision_;
}

std::string_view PreparedSource::resolved_revision() const noexcept {
    return resolved_revision_;
}

void PreparedSource::release() noexcept {
    owns_directory_ = false;
}

void PreparedSource::cleanup() noexcept {
    if (owns_directory_) {
        std::error_code ignored;
        std::filesystem::remove_all(directory_, ignored);
        owns_directory_ = false;
    }
}

} // namespace vibris::mcp