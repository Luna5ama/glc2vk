package dev.vibris.protocol;

import com.google.protobuf.Descriptors;
import dev.vibris.protocol.v2.Action;
import dev.vibris.protocol.v2.ClientHello;
import dev.vibris.protocol.v2.ClientMessage;
import dev.vibris.protocol.v2.GetServerInfoRequest;
import dev.vibris.protocol.v2.GetStatusRequest;
import dev.vibris.protocol.v2.ListPresetsRequest;
import dev.vibris.protocol.v2.ListResourcesRequest;
import dev.vibris.protocol.v2.ProtocolVersion;
import dev.vibris.protocol.v2.SubmitJob;
import dev.vibris.protocol.v2.ValidateContextRequest;
import dev.vibris.protocol.v2.VibrisControlProto;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolEnvelopeTest {
    @Test
    void validV2EnvelopeRoundTrips() throws Exception {
        ProtocolVersion version = ProtocolVersionGate.current();
        ClientMessage hello = ClientMessage.newBuilder()
            .setProtocolVersion(version)
            .setMessageId("message-hello")
            .setWorkspaceId("workspace-1")
            .setClientHello(ClientHello.newBuilder()
                .setClientVersion("2.0.0")
                .setProcessInstanceId("58f2e5d8-6587-4d0d-a334-4e259466fb27"))
            .build();

        ClientMessage parsed = ClientMessage.parseFrom(hello.toByteArray());
        ProtocolVersionGate.requireSupported(parsed);

        assertEquals(2, parsed.getProtocolVersion().getMajor());
        assertEquals(0, parsed.getProtocolVersion().getMinor());
        assertEquals("message-hello", parsed.getMessageId());
        assertEquals("workspace-1", parsed.getWorkspaceId());
        assertEquals("2.0.0", parsed.getClientHello().getClientVersion());
    }

    @Test
    void rejectsV1BeforeInspectingSubmitPayload() {
        ClientMessage message = ClientMessage.newBuilder()
            .setProtocolVersion(ProtocolVersion.newBuilder().setMajor(1).setMinor(99))
            .setMessageId("v1-submit")
            .setWorkspaceId("workspace-1")
            .setSubmitJob(SubmitJob.getDefaultInstance())
            .build();

        UnsupportedProtocolVersionException error = assertThrows(
            UnsupportedProtocolVersionException.class,
            () -> ProtocolVersionGate.requireSupported(message));

        assertEquals(1, error.getSuppliedMajor());
        assertTrue(error.getMessage().startsWith("UNSUPPORTED_VERSION:"));
        assertTrue(message.hasSubmitJob());
    }

    @Test
    void rejectsMissingVersionBeforeInspectingSubmitPayload() {
        ClientMessage message = ClientMessage.newBuilder()
            .setMessageId("missing-version-submit")
            .setWorkspaceId("workspace-1")
            .setSubmitJob(SubmitJob.getDefaultInstance())
            .build();

        UnsupportedProtocolVersionException error = assertThrows(
            UnsupportedProtocolVersionException.class,
            () -> ProtocolVersionGate.requireSupported(message));

        assertEquals(null, error.getSuppliedMajor());
        assertTrue(error.getMessage().endsWith("received missing"));
    }

    @Test
    void descriptorContainsOnlyStrictV2Surface() {
        Descriptors.FileDescriptor descriptor = VibrisControlProto.getDescriptor();
        assertEquals("vibris.control.v2", descriptor.getPackage());
        assertEquals("dev.vibris.protocol.v2", descriptor.getOptions().getJavaPackage());

        Set<String> messageNames = descriptor.getMessageTypes().stream()
            .map(Descriptors.Descriptor::getName)
            .collect(Collectors.toSet());
        assertTrue(messageNames.containsAll(Set.of(
            "ServerStatus",
            "RuntimeLease",
            "JobStateSnapshot",
            "ActionReceipt",
            "CompileCatalog",
            "ResultProvenance",
            "ArtifactManifest",
            "DumpTextureAfterPass",
            "DumpBufferAfterPass")));

        Set<String> fieldNames = descriptor.getMessageTypes().stream()
            .flatMap(message -> Stream.concat(Stream.of(message), message.getNestedTypes().stream()))
            .flatMap(message -> message.getFields().stream())
            .map(Descriptors.FieldDescriptor::getName)
            .collect(Collectors.toSet());
        assertFalse(fieldNames.contains("ready"));
        assertFalse(fieldNames.contains("dump_texture_v2"));
        assertFalse(fieldNames.contains("list_textures_v2"));
        assertFalse(fieldNames.contains("absolute_path"));
        assertTrue(descriptor.getMessageTypes().stream()
            .flatMap(message -> message.getFields().stream())
            .noneMatch(field -> field.getType() == Descriptors.FieldDescriptor.Type.BYTES));

        Descriptors.Descriptor action = Action.getDescriptor();
        assertTrue(action.findFieldByName("dump_texture_after_pass") != null);
        assertTrue(action.findFieldByName("dump_buffer_after_pass") != null);
    }

    @Test
    void everyUnaryRequestCarriesProtocolVersion() {
        Set<Descriptors.Descriptor> requests = Set.of(
            GetServerInfoRequest.getDescriptor(),
            ListPresetsRequest.getDescriptor(),
            ListResourcesRequest.getDescriptor(),
            ValidateContextRequest.getDescriptor(),
            GetStatusRequest.getDescriptor());

        assertTrue(requests.stream().allMatch(request -> {
            Descriptors.FieldDescriptor version = request.findFieldByName("protocol_version");
            return version != null && version.getMessageType().getName().equals("ProtocolVersion");
        }));
    }
}