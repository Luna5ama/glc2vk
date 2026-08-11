#include "vibris_control.pb.h"

#include <google/protobuf/descriptor.pb.h>

#include <filesystem>
#include <fstream>
#include <iostream>
#include <string_view>

int main(int argc, char** argv) {
    if (argc != 3 || std::string_view(argv[1]) != "--output") {
        std::cerr << "usage: vibris-descriptor-dump --output PATH\n";
        return 2;
    }

    google::protobuf::FileDescriptorProto descriptor;
    vibris::control::v2::ClientMessage::descriptor()->file()->CopyTo(&descriptor);

    std::ofstream output(std::filesystem::path(argv[2]), std::ios::binary | std::ios::trunc);
    if (!output || !descriptor.SerializeToOstream(&output)) {
        std::cerr << "failed to write descriptor\n";
        return 1;
    }
    output.close();
    if (!output) {
        std::cerr << "failed to finish descriptor write\n";
        return 1;
    }
    return 0;
}