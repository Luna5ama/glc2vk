#include "workspace_source_fixture.hpp"

#include <array>
#include <exception>
#include <iostream>
#include <string_view>
#include <utility>

namespace {

namespace fs = std::filesystem;
using vibris::mcp::SourcePreparer;
using vibris::mcp::WorkspaceCopier;
using vibris::mcp::copy_workspace_tree;
using vibris::mcp::copy_workspace_tree_after_check;
using vibris::mcp::test::WorkspaceFixture;
using vibris::mcp::test::capture_state_error;
using vibris::mcp::test::file_totals;
using vibris::mcp::test::generous_limits;
using vibris::mcp::test::mutating_copier;
using vibris::mcp::test::pending_has_no_sources;
using vibris::mcp::test::read_file;
using vibris::mcp::test::replace_with_file_symlink;
using vibris::mcp::test::require;

void workspace_snapshot_tracked_untracked_ignored_and_retry() {
    // Given: a Git worktree with tracked, untracked, and ignored shader files that mutates after the first copy.
    WorkspaceFixture fixture;
    std::size_t copy_calls = 0;
    fs::path owned_directory;
    {
        SourcePreparer preparer(
            fixture.worktree(),
            fixture.pending(),
            generous_limits(),
            mutating_copier(fixture.live_file(), 1, copy_calls));

        // When: the workspace source is prepared through the public one-retry boundary.
        auto prepared = preparer.prepare_workspace();
        owned_directory = prepared.directory();
        const auto& reference = prepared.reference();
        const auto [expected_files, expected_bytes] = file_totals(fixture.shaders());

        // Then: the retry accepts the second enumeration and flattens the shaders root into the UUID directory.
        require(copy_calls == 2, "A single mutation must cause exactly one automatic retry.");
        require(reference.file_count() == expected_files, "PreparedSourceRef did not use the second file count.");
        require(reference.total_bytes() == expected_bytes, "PreparedSourceRef did not use the second byte count.");
        require(reference.origin().has_workspace(), "Prepared source did not record workspace origin.");
        require(reference.requested_revision() == "workspace" && reference.resolved_revision().size() == 40,
            "PreparedSourceRef omitted the requested workspace revision or resolved full commit.");
        require(prepared.resolved_revision().size() == 40,
            "Prepared workspace source did not retain its full HEAD revision.");
        require(owned_directory == fixture.pending() / reference.uuid(), "Prepared source used the wrong final path.");
        require(read_file(owned_directory / "composite.fsh") == "tracked-composite", "Tracked file was omitted.");
        require(read_file(owned_directory / "untracked.glsl") == "untracked-source", "Untracked file was omitted.");
        require(
            read_file(owned_directory / "ignored.properties") == "ignored-source", "Git-ignored file was omitted.");
        require(read_file(owned_directory / "lib" / "live.glsl") == read_file(fixture.live_file()),
            "Retry kept the first, mutated snapshot.");
        require(fs::is_empty(owned_directory / "empty"), "Prepared source omitted an empty directory.");
        require(!fs::exists(owned_directory / "shaders"), "Prepared source retained the outer shaders prefix.");
    }
    require(!fs::exists(owned_directory), "Owned PreparedSource did not clean up on destruction.");
}

void staging_promotion() {
    // Given: a stable workspace and an empty server-declared pending root.
    WorkspaceFixture fixture;
    fs::path released_directory;
    {
        WorkspaceCopier stable_copy = copy_workspace_tree;
        SourcePreparer preparer(fixture.worktree(), fixture.pending(), generous_limits(), std::move(stable_copy));

        // When: preparation completes and ownership is explicitly released.
        auto prepared = preparer.prepare_workspace();
        released_directory = prepared.directory();
        const auto uuid = prepared.reference().uuid();
        prepared.release();

        // Then: staging was atomically promoted to the direct UUID child, and release prevents RAII deletion.
        require(released_directory == fixture.pending() / uuid, "Promotion did not produce pending/<uuid>.");
        require(fs::is_directory(released_directory), "Promoted source directory is missing.");
        require(!fs::exists(fixture.pending() / ".staging" / uuid), "UUID remained under .staging after promotion.");
        require(read_file(released_directory / "composite.fsh") == "tracked-composite", "Promotion lost content.");
    }
    require(fs::is_directory(released_directory), "Released PreparedSource was deleted by its destructor.");
}

void mutation_twice_fails() {
    // Given: a workspace copier that mutates the source after both allowed copy attempts.
    WorkspaceFixture fixture;
    std::size_t copy_calls = 0;
    SourcePreparer preparer(
        fixture.worktree(), fixture.pending(), generous_limits(), mutating_copier(fixture.live_file(), 2, copy_calls));

    // When: both the initial snapshot and its one retry observe different second metadata enumerations.
    const auto error = capture_state_error([&preparer] {
        static_cast<void>(preparer.prepare_workspace());
    });

    // Then: the structured mutation error is returned after exactly two copies and every partial source is removed.
    require(error.code == "SOURCE_CHANGED_DURING_SNAPSHOT", "Two mutations returned the wrong structured error.");
    require(copy_calls == 2, "Mutation failure did not stop after exactly one retry.");
    require(pending_has_no_sources(fixture.pending()), "Mutation failure left a staging or final source directory.");
}

void missing_pending_root_is_rejected() {
    // Given: a valid workspace but a server-advertised pending root that does not exist.
    WorkspaceFixture fixture;
    const auto missing = fixture.pending() / "missing";

    // When: source preparation is constructed against that inaccessible shared root.
    const auto error = capture_state_error([&fixture, &missing] {
        SourcePreparer preparer(fixture.worktree(), missing, generous_limits());
        static_cast<void>(preparer.prepare_workspace());
    });

    // Then: the MCP reports server readiness and never creates the missing root.
    require(error.code == "SERVER_NOT_READY", "A missing pending root returned the wrong structured error.");
    require(!fs::exists(missing), "Source preparation created a missing server-advertised root.");
}

void checked_file_swap_does_not_read_reparse_target() {
    // Given: an ordinary source file and a target outside the copied workspace.
    vibris::mcp::test::TempDirectory fixture("workspace-reparse-swap");
    const auto source = fixture.path() / "source";
    const auto destination = fixture.path() / "destination";
    const auto checked_file = source / "checked.glsl";
    const auto reparse_target = fixture.path() / "outside.glsl";
    vibris::mcp::test::write_file(checked_file, "ordinary-source");
    vibris::mcp::test::write_file(reparse_target, "must-not-be-read");
    bool swapped = false;

    // When: the checked file is replaced by a reparse point immediately before it is opened.
    const auto error = capture_state_error([&] {
        copy_workspace_tree_after_check(source, destination, [&](const fs::path& path) {
            require(path == checked_file, "The deterministic swap hook observed the wrong file.");
            replace_with_file_symlink(path, reparse_target);
            swapped = true;
        });
    });

    // Then: the reparse target is rejected before any destination file is created.
    require(swapped, "The checked workspace file was not swapped.");
    require(error.code == "SOURCE_CONTAINS_REPARSE_POINT", "A checked-file swap returned the wrong error.");
    require(!fs::exists(destination / "checked.glsl"), "The copier read a target through the swapped path.");
}

void source_soak() {
    WorkspaceFixture fixture;
    constexpr std::size_t iterations = 1'000;
    for (std::size_t index = 0; index < iterations; ++index) {
        {
            SourcePreparer preparer(fixture.worktree(), fixture.pending(), generous_limits());
            const auto prepared = preparer.prepare_workspace();
            require(fs::is_directory(prepared.directory()), "Soak iteration did not prepare a source.");
        }
        require(pending_has_no_sources(fixture.pending()), "Soak iteration retained an owned source.");
    }
}

void queued_snapshot_materializes_with_stable_provenance() {
    WorkspaceFixture fixture;
    vibris::mcp::test::TempDirectory server_pending("queued-snapshot-server");
    SourcePreparer freezer(fixture.worktree(), fixture.pending(), generous_limits());
    auto frozen = freezer.prepare_workspace();
    vibris::mcp::test::write_file(fixture.live_file(), "changed-after-queue");
    SourcePreparer materializer(fixture.worktree(), server_pending.path(), generous_limits());

    auto materialized = materializer.prepare_snapshot(frozen.directory(), frozen.reference());

    require(materialized.reference().uuid() != frozen.reference().uuid(),
        "Queued snapshot materialization reused the checkpoint source UUID.");
    require(materialized.reference().requested_revision() == frozen.reference().requested_revision() &&
            materialized.reference().resolved_revision() == frozen.reference().resolved_revision(),
        "Queued snapshot materialization changed revision provenance.");
    require(read_file(materialized.directory() / "lib" / "live.glsl") != read_file(fixture.live_file()),
        "Queued snapshot materialization reread the mutable workspace.");
}

using TestCase = std::pair<std::string_view, void (*)()>;
constexpr std::array<TestCase, 7> test_cases {{
    {"WorkspaceSnapshotTrackedUntrackedIgnoredAndRetry", workspace_snapshot_tracked_untracked_ignored_and_retry},
    {"StagingPromotion", staging_promotion},
    {"MutationTwiceFails", mutation_twice_fails},
    {"MissingPendingRootRejected", missing_pending_root_is_rejected},
    {"CheckedFileSwapDoesNotReadReparseTarget", checked_file_swap_does_not_read_reparse_target},
    {"SourceSoak", source_soak},
    {"QueuedSnapshotMaterializesWithStableProvenance", queued_snapshot_materializes_with_stable_provenance},
}};

} // namespace

int main(int argc, char** argv) {
    if (argc != 2) {
        std::cerr << "usage: vibris-workspace-source-tests <scenario>\n";
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
    std::cerr << "Unknown workspace source test scenario: " << argv[1] << '\n';
    return 2;
}