cmake_minimum_required(VERSION 3.25)

project(vibris_mcp_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(Protobuf CONFIG REQUIRED)
find_package(gRPC CONFIG REQUIRED)

set(PROTO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../proto")
set(PROTO_FILE "${PROTO_ROOT}/vibris_control.proto")
set(GENERATED_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")
set(GENERATED_SOURCES
    "${GENERATED_DIR}/vibris_control.pb.cc"
    "${GENERATED_DIR}/vibris_control.grpc.pb.cc")
set(GENERATED_HEADERS
    "${GENERATED_DIR}/vibris_control.pb.h"
    "${GENERATED_DIR}/vibris_control.grpc.pb.h")

add_custom_command(
    OUTPUT ${GENERATED_SOURCES} ${GENERATED_HEADERS}
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${GENERATED_DIR}"
    COMMAND protobuf::protoc
        "--proto_path=${PROTO_ROOT}"
        "--cpp_out=${GENERATED_DIR}"
        "--grpc_out=${GENERATED_DIR}"
        "--plugin=protoc-gen-grpc=$<TARGET_FILE:gRPC::grpc_cpp_plugin>"
        "${PROTO_FILE}"
    DEPENDS "${PROTO_FILE}" protobuf::protoc gRPC::grpc_cpp_plugin
    VERBATIM)

add_library(vibris_control_protocol STATIC ${GENERATED_SOURCES} ${GENERATED_HEADERS})
target_include_directories(vibris_control_protocol SYSTEM PUBLIC "${GENERATED_DIR}")
target_link_libraries(vibris_control_protocol PUBLIC gRPC::grpc++ protobuf::libprotobuf)

add_executable(vibris-protocol-smoke src/main/cpp/protocol_smoke.cpp)
target_link_libraries(vibris-protocol-smoke PRIVATE vibris_control_protocol)

add_executable(vibris-descriptor-dump src/main/cpp/descriptor_dump.cpp)
target_link_libraries(vibris-descriptor-dump PRIVATE vibris_control_protocol)

if(MSVC)
    target_compile_options(vibris-protocol-smoke PRIVATE /W4 /permissive-)
    target_compile_options(vibris-descriptor-dump PRIVATE /W4 /permissive-)
endif()

include(CTest)
if(BUILD_TESTING)
    add_test(
        NAME descriptor-dump
        COMMAND vibris-descriptor-dump
            --output "$<TARGET_FILE_DIR:vibris-descriptor-dump>/vibris_control_descriptor.bin")
    add_test(NAME protocol-smoke-cli-validation COMMAND vibris-protocol-smoke)
    set_tests_properties(protocol-smoke-cli-validation PROPERTIES WILL_FAIL TRUE)
endif()