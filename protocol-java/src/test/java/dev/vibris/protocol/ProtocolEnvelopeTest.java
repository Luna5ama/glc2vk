package dev.vibris.protocol;

import com.google.protobuf.Descriptors;
import dev.vibris.protocol.v1.Capability;
import dev.vibris.protocol.v1.ClientHello;
import dev.vibris.protocol.v1.ClientMessage;
import dev.vibris.protocol.v1.Ping;
import dev.vibris.protocol.v1.ProtocolVersion;
import dev.vibris.protocol.v1.ServerHello;
import dev.vibris.protocol.v1.ServerMessage;
import dev.vibris.protocol.v1.VibrisControlProto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolEnvelopeTest {
    @Test
    void validV1EnvelopeRoundTrips() throws Exception {
        ProtocolVersion version = ProtocolVersion.newBuilder().setMajor(1).setMinor(0).build();
        ClientMessage hello = ClientMessage.newBuilder()
            .setProtocolVersion(version)
            .setMessageId("message-hello")
            .setRequestId("request-1")
            .setWorkspaceId("workspace-1")
            .setClientHello(ClientHello.newBuilder()
                .setProtocolVersion(version)
                .setMcpVersion("1.0.0")
                .setWorkspaceId("workspace-1")
                .setProcessInstanceUuid("58f2e5d8-6587-4d0d-a334-4e259466fb27")
                .addCapabilities(Capability.CAPABILITY_CONTROL_STREAM))
            .build();
        ClientMessage parsedHello = ClientMessage.parseFrom(hello.toByteArray());

        assertEquals(1, parsedHello.getProtocolVersion().getMajor());
        assertEquals(0, parsedHello.getProtocolVersion().getMinor());
        assertEquals("message-hello", parsedHello.getMessageId());
        assertEquals("request-1", parsedHello.getRequestId());
        assertEquals("workspace-1", parsedHello.getWorkspaceId());
        assertTrue(parsedHello.getClientHello().getCapabilitiesList()
            .contains(Capability.CAPABILITY_CONTROL_STREAM));

        ClientMessage ping = ClientMessage.newBuilder()
            .setProtocolVersion(version)
            .setMessageId("message-ping")
            .setRequestId("request-1")
            .setWorkspaceId("workspace-1")
            .setPing(Ping.newBuilder().setSequence(7).setClientTimeUnixMs(1234))
            .build();
        ClientMessage parsedPing = ClientMessage.parseFrom(ping.toByteArray());

        assertEquals("message-ping", parsedPing.getMessageId());
        assertEquals("request-1", parsedPing.getRequestId());
        assertEquals("workspace-1", parsedPing.getWorkspaceId());
        assertEquals(7, parsedPing.getPing().getSequence());
        assertEquals(1234, parsedPing.getPing().getClientTimeUnixMs());

        ServerMessage serverHello = ServerMessage.newBuilder()
            .setProtocolVersion(version)
            .setMessageId("message-server-hello")
            .setRequestId("request-1")
            .setWorkspaceId("workspace-1")
            .setServerHello(ServerHello.newBuilder()
                .setProtocolVersion(version)
                .setServerVersion("1.0.0")
                .setPendingShadersRoot("R:\\vibris\\pending-shaders")
                .setArtifactRoot("R:\\vibris\\artifacts"))
            .build();
        ServerMessage parsedServerHello = ServerMessage.parseFrom(serverHello.toByteArray());

        assertEquals("message-server-hello", parsedServerHello.getMessageId());
        assertEquals("request-1", parsedServerHello.getRequestId());
        assertEquals("workspace-1", parsedServerHello.getWorkspaceId());
        assertEquals("R:\\vibris\\pending-shaders", parsedServerHello.getServerHello().getPendingShadersRoot());
        assertEquals("R:\\vibris\\artifacts", parsedServerHello.getServerHello().getArtifactRoot());

        List<Descriptors.FieldDescriptor> fields = VibrisControlProto.getDescriptor().getMessageTypes().stream()
            .flatMap(message -> Stream.concat(Stream.of(message), message.getNestedTypes().stream()))
            .flatMap(message -> message.getFields().stream())
            .toList();
        assertTrue(fields.stream().noneMatch(field -> field.getName().equals("absolute_path")));
        assertTrue(fields.stream().noneMatch(field -> field.getType() == Descriptors.FieldDescriptor.Type.BYTES));
    }
}