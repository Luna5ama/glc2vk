#include "native_metrics.hpp"

#ifdef VIBRIS_SANITIZER_BUILD

#include <sanitizer/allocator_interface.h>
#include <windows.h>

#include <atomic>
#include <chrono>
#include <cstdlib>
#include <fstream>
#include <memory>

namespace vibris::mcp {

void record_native_metrics() noexcept {
    char* raw_path = nullptr;
    std::size_t path_size = 0;
    if (_dupenv_s(&raw_path, &path_size, "VIBRIS_SOAK_METRICS") != 0) return;
    const std::unique_ptr<char, decltype(&std::free)> path(raw_path, &std::free);
    if (!path || path_size <= 1) return;

    DWORD handles = 0;
    if (!GetProcessHandleCount(GetCurrentProcess(), &handles)) return;

    static std::atomic_uint64_t sequence{0};
    const auto now = std::chrono::system_clock::now().time_since_epoch();
    const auto unix_ms = std::chrono::duration_cast<std::chrono::milliseconds>(now).count();
    try {
        std::ofstream output(path.get(), std::ios::app);
        output << "{\"sequence\":" << sequence.fetch_add(1, std::memory_order_relaxed)
               << ",\"unix_ms\":" << unix_ms
               << ",\"handle_count\":" << handles
               << ",\"heap_allocated_bytes\":" << __sanitizer_get_current_allocated_bytes()
               << ",\"heap_size_bytes\":" << __sanitizer_get_heap_size() << "}\n";
    } catch (...) {
    }
}

}

#else

namespace vibris::mcp {

void record_native_metrics() noexcept {}

}

#endif