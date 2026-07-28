#include "result_mapper.hpp"

#include <google/protobuf/message.h>
#include <google/protobuf/util/json_util.h>
#include <nlohmann/json.hpp>

#include <stdexcept>
#include <string>

namespace vibris::mcp {
namespace proto = ::vibris::control::v1;
namespace {

nlohmann::json map_message(const google::protobuf::Message& message) {
    google::protobuf::util::JsonPrintOptions options;
    options.preserve_proto_field_names = true;
    options.always_print_fields_with_no_presence = true;

    std::string encoded;
    const auto status = google::protobuf::util::MessageToJsonString(message, &encoded, options);
    if (!status.ok()) {
        throw std::runtime_error("protobuf JSON mapping failed: " + status.ToString());
    }
    return nlohmann::json::parse(encoded);
}

}

nlohmann::json ResultMapper::list_presets(const proto::ListPresetsResponse& response) {
    return map_message(response);
}

nlohmann::json ResultMapper::validation(const proto::ValidateContextResponse& response) {
    return map_message(response);
}

nlohmann::json ResultMapper::status(const proto::GetStatusResponse& response) {
    return map_message(response);
}

nlohmann::json ResultMapper::server_message(const proto::ServerMessage& response) {
    return map_message(response);
}

}