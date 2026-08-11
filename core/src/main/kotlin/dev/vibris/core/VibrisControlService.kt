package dev.vibris.core

import dev.vibris.api.VibrisRuntimeAdapter
import dev.vibris.protocol.v2.ClientMessage
import dev.vibris.protocol.v2.ErrorCode
import dev.vibris.protocol.v2.GetServerInfoRequest
import dev.vibris.protocol.v2.GetServerInfoResponse
import dev.vibris.protocol.v2.GetStatusRequest
import dev.vibris.protocol.v2.GetStatusResponse
import dev.vibris.protocol.v2.ListPresetsRequest
import dev.vibris.protocol.v2.ListPresetsResponse
import dev.vibris.protocol.v2.ListResourcesRequest
import dev.vibris.protocol.v2.ListResourcesResponse
import dev.vibris.protocol.v2.Pong
import dev.vibris.protocol.v2.ScenePreset
import dev.vibris.protocol.v2.ServerMessage
import dev.vibris.protocol.v2.ValidateContextRequest
import dev.vibris.protocol.v2.ValidateContextResponse
import dev.vibris.protocol.v2.VibrisControlGrpc
import io.grpc.Status
import io.grpc.stub.StreamObserver
import java.nio.file.Path

class VibrisControlService internal constructor(
    configuration: ServerConfiguration,
    private val runtime: VibrisRuntimeAdapter,
    shaderLink: ShaderLink,
) : VibrisControlGrpc.VibrisControlImplBase(), AutoCloseable {
    private val artifacts = ArtifactManager(configuration.paths.artifactRoot, configuration.artifactQuotaBytes)
    private val engine = VibrisCoreEngine(
        configuration.paths.pendingShadersRoot,
        runtime,
        shaderLink,
        artifacts,
        configuration.maxSourceBytes,
        configuration.maxSourceFiles,
        configuration.maxGlobalQueue,
        configuration.maxActionsPerJob,
    )
    private val descriptor = ServerDescriptor(
        configuration.paths.pendingShadersRoot,
        artifacts,
        runtime,
        configuration.maxSourceBytes,
        configuration.maxSourceFiles,
    )

    internal constructor(
        pendingRoot: Path,
        artifactRoot: Path,
        runtime: VibrisRuntimeAdapter,
        shaderLink: ShaderLink,
    ) : this(
        ServerConfiguration.defaults(pendingRoot, artifactRoot),
        runtime,
        shaderLink,
    )

    constructor(pendingRoot: Path, artifactRoot: Path, runtime: VibrisRuntimeAdapter) :
        this(pendingRoot, artifactRoot, runtime, ShaderLink.transientLink())

    fun probe(): CoreProbe = engine.probe()

    override fun getServerInfo(
        request: GetServerInfoRequest,
        observer: StreamObserver<GetServerInfoResponse>,
    ) {
        observer.onNext(
            GetServerInfoResponse.newBuilder()
                .setProtocolVersion(ProtocolMessages.V2)
                .setServer(descriptor.hello(engine))
                .build(),
        )
        observer.onCompleted()
    }

    override fun listPresets(
        request: ListPresetsRequest,
        observer: StreamObserver<ListPresetsResponse>,
    ) {
        runtime.listPresets().whenComplete { presets, failure ->
            if (failure != null) {
                observer.onError(Status.INTERNAL.withDescription("PRESET_LIST_FAILED").asRuntimeException())
                return@whenComplete
            }
            val response = ListPresetsResponse.newBuilder().setProtocolVersion(ProtocolMessages.V2)
            presets.forEach { preset ->
                val tags = preset.tags.sorted()
                response.addPresets(
                    ScenePreset.newBuilder()
                        .setPresetId(preset.presetId)
                        .setDisplayName(preset.displayName)
                        .setVersion(preset.version)
                        .setContext(RuntimeJobContext.toProtocol(preset.context))
                        .addAllTags(tags)
                        .setPresetSha256(BenchmarkProvenance.presetHash(
                            preset.presetId,
                            preset.version,
                            preset.displayName,
                            preset.context,
                        )),
                )
            }
            observer.onNext(response.build())
            observer.onCompleted()
        }
    }

    override fun listResources(
        request: ListResourcesRequest,
        observer: StreamObserver<ListResourcesResponse>,
    ) {
        val resources = descriptor.resources()
        val filtered = if (!request.hasFilter()) {
            resources
        } else {
            val filter = request.filter
            resources.toBuilder()
                .clearResources()
                .addAllResources(resources.resourcesList.filter { resource ->
                    (filter.kindsCount == 0 || filter.kindsList.contains(resource.kind)) &&
                        (!filter.hasLogicalName() || resource.logicalName == filter.logicalName)
                })
                .build()
        }
        observer.onNext(
            ListResourcesResponse.newBuilder()
                .setProtocolVersion(ProtocolMessages.V2)
                .setCatalog(filtered)
                .build(),
        )
        observer.onCompleted()
    }

    override fun validateContext(
        request: ValidateContextRequest,
        observer: StreamObserver<ValidateContextResponse>,
    ) {
        if (!request.hasContext()) {
            observer.onNext(
                ValidateContextResponse.newBuilder()
                    .setProtocolVersion(ProtocolMessages.V2)
                    .setValid(false)
                    .build(),
            )
            observer.onCompleted()
            return
        }
        val context = RuntimeJobContext.toApi(request.context)
        runtime.validateContext(context).whenComplete { validation, failure ->
            if (failure != null) {
                observer.onError(Status.INTERNAL.withDescription("PRESET_VALIDATION_FAILED").asRuntimeException())
                return@whenComplete
            }
            val response = ValidateContextResponse.newBuilder()
                .setProtocolVersion(ProtocolMessages.V2)
                .setValid(validation.valid)
            validation.errors.forEach { error ->
                response.addErrors(
                    dev.vibris.protocol.v2.ProtocolError.newBuilder()
                        .setCode(ErrorCode.ERROR_CODE_INVALID_PRESET)
                        .setMessage(error)
                        .setRetryable(false),
                )
            }
            observer.onNext(response.build())
            observer.onCompleted()
        }
    }

    override fun getStatus(
        request: GetStatusRequest,
        observer: StreamObserver<GetStatusResponse>,
    ) {
        val status = descriptor.status(engine)
        observer.onNext(
            GetStatusResponse.newBuilder()
                .setProtocolVersion(ProtocolMessages.V2)
                .setStatus(status)
                .build(),
        )
        observer.onCompleted()
    }

    override fun control(responses: StreamObserver<ServerMessage>): StreamObserver<ClientMessage> {
        val session = ControlSession(responses)
        return object : StreamObserver<ClientMessage> {
            private var greeted = false
            private var terminated = false

            override fun onNext(message: ClientMessage) {
                if (terminated) {
                    return
                }
                if (!greeted) {
                    greet(message)
                    return
                }
                if (!message.hasProtocolVersion() || message.protocolVersion.major != 2) {
                    fail(Status.FAILED_PRECONDITION.withDescription("UNSUPPORTED_VERSION"))
                    return
                }
                if (message.workspaceId != session.workspaceId()) {
                    fail(Status.PERMISSION_DENIED.withDescription("WORKSPACE_MISMATCH"))
                    return
                }
                if (message.hasPing()) {
                    val pong = Pong.newBuilder()
                        .setSequence(message.ping.sequence)
                        .setClientTimeUnixMs(message.ping.clientTimeUnixMs)
                        .setServerTimeUnixMs(message.ping.clientTimeUnixMs)
                        .build()
                    session.send(
                        ProtocolMessages.envelope(
                            message.messageId,
                            message.requestId,
                            session.workspaceId(),
                        ).setPong(pong).build(),
                    )
                } else if (message.hasSubmitJob()) {
                    engine.submit(session, message)
                } else if (message.hasCancelJob()) {
                    engine.cancel(session, message.cancelJob.jobId)
                } else if (message.hasResumeJob()) {
                    engine.resume(session, message)
                }
            }

            private fun greet(message: ClientMessage) {
                if (!message.hasClientHello()) {
                    fail(Status.INVALID_ARGUMENT.withDescription("CLIENT_HELLO_REQUIRED"))
                    return
                }
                if (!message.hasProtocolVersion() || message.protocolVersion.major != 2) {
                    fail(Status.FAILED_PRECONDITION.withDescription("UNSUPPORTED_VERSION"))
                    return
                }
                val workspace = message.workspaceId
                if (workspace.isBlank()) {
                    fail(Status.INVALID_ARGUMENT.withDescription("WORKSPACE_ID_REQUIRED"))
                    return
                }
                greeted = true
                session.identify(workspace, message.clientHello.processInstanceId)
                session.send(
                    ProtocolMessages.envelope(message.messageId, message.requestId, workspace)
                        .setServerHello(descriptor.hello(engine))
                        .build(),
                )
            }

            private fun fail(status: Status) {
                if (terminated) {
                    return
                }
                terminated = true
                engine.disconnected(session)
                responses.onError(status.asRuntimeException())
            }

            override fun onError(throwable: Throwable) {
                if (terminated) {
                    return
                }
                terminated = true
                engine.disconnected(session)
            }

            override fun onCompleted() {
                if (terminated) {
                    return
                }
                terminated = true
                session.complete()
                engine.disconnected(session)
            }
        }
    }

    override fun close() {
        engine.close()
    }

}
