#include <nlohmann/json.hpp>

#include <algorithm>
#include <array>
#include <fstream>
#include <iostream>
#include <string>
#include <string_view>
#include <vector>

namespace {

bool require(bool condition, std::string_view message) {
    if (!condition) {
        std::cerr << message << '\n';
    }
    return condition;
}

}

int main(int argc, char** argv) {
    if (argc != 2) {
        std::cerr << "usage: vibris-stdio-fixture-test REQUESTS.jsonl\n";
        return 2;
    }

    std::ifstream input(argv[1]);
    if (!input) {
        std::cerr << "could not open request fixture: " << argv[1] << '\n';
        return 2;
    }

    std::vector<std::string> methods;
    std::vector<std::string> tools;
    std::string line;
    try {
        while (std::getline(input, line)) {
            const nlohmann::json request = nlohmann::json::parse(line);
            if (!require(request.at("jsonrpc") == "2.0", "fixture request must use JSON-RPC 2.0")) {
                return 1;
            }
            methods.push_back(request.at("method").get<std::string>());
            if (request.at("method") == "tools/call") {
                tools.push_back(request.at("params").at("name").get<std::string>());
            }
        }
    } catch (const nlohmann::json::exception& error) {
        std::cerr << "invalid request fixture: " << error.what() << '\n';
        return 1;
    }

    const std::array expected_methods{
        "initialize",
        "tools/list",
        "tools/call",
        "tools/call",
        "tools/call",
    };
    const std::array expected_tools{
        "vibris_get_status",
        "vibris_list_presets",
        "vibris_run_actions",
    };
    return require(
        std::ranges::equal(methods, expected_methods),
        "fixture must initialize, list tools, query status and presets, then run an action") && require(
        std::ranges::equal(tools, expected_tools),
        "fixture tool-call order changed") ? 0 : 1;
}
