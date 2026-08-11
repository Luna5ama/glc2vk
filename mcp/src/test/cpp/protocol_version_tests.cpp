#include "protocol_version.hpp"

#include <cassert>
#include <string_view>

int main() {
    vibris::control::v2::ProtocolVersion version;

    assert(!vibris::mcp::protocol_version_supported(false, version));

    version.set_major(1);
    version.set_minor(99);
    assert(!vibris::mcp::protocol_version_supported(true, version));

    version.set_major(vibris::mcp::protocol_major);
    version.set_minor(vibris::mcp::protocol_minor);
    assert(vibris::mcp::protocol_version_supported(true, version));

    version.set_minor(999);
    assert(vibris::mcp::protocol_version_supported(true, version));
    assert(std::string_view(vibris::mcp::unsupported_version_code) == "UNSUPPORTED_VERSION");
}