package dev.vibris.mod;

import dev.vibris.api.CompileCatalog;
import net.irisshaders.iris.gl.shader.ShaderCompileException;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IrisVibrisCompileCatalog {
	private static final byte[] PATCHED_SOURCE_HASH_DOMAIN = "vibris-patched-program-v1".getBytes(StandardCharsets.UTF_8);
	private static final String EMPTY_SOURCE_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
	private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();
	private static final ThreadLocal<byte[]> PATCHED_SOURCE_HASH_BUFFER = ThreadLocal.withInitial(() -> new byte[8192]);
	private static final Pattern GLSL_LOCATION = Pattern.compile("^(?:ERROR|WARNING)?\\s*:?\\s*(?:[^:]+:)?(\\d+)(?::|\\()(\\d+)?\\)?\\s*:?\\s*(.*)$", Pattern.CASE_INSENSITIVE);
	private static final AtomicLong NEXT_GENERATION = new AtomicLong();
	private static final ThreadLocal<Session> ACTIVE = new ThreadLocal<>();
	private static volatile CompileCatalog published = CompileCatalog.empty(0);

	private IrisVibrisCompileCatalog() {
	}

	public static Session begin(ProgramSet programSet) {
		Objects.requireNonNull(programSet, "programSet");
		Session session = beginEmpty(NEXT_GENERATION.incrementAndGet());
		registerProgramArray(session, programSet, ProgramArrayId.Begin);
		registerProgramArray(session, programSet, ProgramArrayId.Prepare);
		registerProgramArray(session, programSet, ProgramArrayId.Deferred);
		registerProgramArray(session, programSet, ProgramArrayId.Composite);
		registerProgramArray(session, programSet, ProgramArrayId.ShadowComposite);
		programSet.get(ProgramId.Final).ifPresent(source -> session.registerCompositeIntent(source.getName(), "final", source));
		registerComputes(session, "setup", programSet.getSetup());
		registerComputes(session, "shadow", programSet.getShadowCompute());
		registerComputes(session, "final", programSet.getFinalCompute());
		return session;
	}

	static Session beginEmpty(long generation) {
		if (ACTIVE.get() != null) {
			throw new IllegalStateException("A compile catalog session is already active on this thread");
		}
		Session session = new Session(generation);
		ACTIVE.set(session);
		return session;
	}

	public static CompileCatalog finish(Session session) {
		if (ACTIVE.get() != session) {
			throw new IllegalStateException("The compile catalog session is not active on this thread");
		}
		ACTIVE.remove();
		return session.snapshot();
	}

	public static void succeedRemaining(Session session) {
		if (ACTIVE.get() != session) throw new IllegalStateException("The compile catalog session is not active on this thread");
		session.completeRemainingSuccess();
	}

	public static void failRemaining(Session session, Exception exception) {
		if (ACTIVE.get() != session) throw new IllegalStateException("The compile catalog session is not active on this thread");
		session.failRemaining(exception);
	}

	public static void publish(CompileCatalog catalog) {
		published = Objects.requireNonNull(catalog, "catalog");
	}

	public static CompileCatalog current() {
		return published;
	}

	public static void registerGraphicsIntent(String programId, String passId, ProgramSource source) {
		Session session = ACTIVE.get();
		if (session != null) {
			session.registerGraphicsIntent(programId, passId, source);
		}
	}

	public static <T> T compileGraphics(String programId, String passId, Map<PatchShaderType, String> sources, Supplier<T> operation) {
		return compile(programId, passId, graphicsStages(sources), patchedSourceHash(sources), operation);
	}

	public static <T> T compileCompute(String programId, String passId, String source, Supplier<T> operation) {
		EnumMap<PatchShaderType, String> sources = new EnumMap<>(PatchShaderType.class);
		sources.put(PatchShaderType.COMPUTE, source);
		return compile(programId, passId, List.of(CompileCatalog.ShaderStage.COMPUTE), patchedSourceHash(sources), operation);
	}

	public static void recordCompileLog(String fileName, String log, boolean successful) {
		Attempt attempt = currentAttempt();
		if (attempt != null) {
			attempt.recordCompile(fileName, log, successful);
		}
	}

	public static void recordLinkLog(String fileName, String log, boolean successful) {
		Attempt attempt = currentAttempt();
		if (attempt != null) {
			attempt.recordLink(fileName, log, successful);
		}
	}

	private static <T> T compile(String programId, String passId, List<CompileCatalog.ShaderStage> stages, String patchedSourceSha256, Supplier<T> operation) {
		Session session = ACTIVE.get();
		if (session == null) {
			return operation.get();
		}

		Attempt attempt = session.beginAttempt(programId, passId, stages, patchedSourceSha256);
		try {
			T result = operation.get();
			attempt.complete();
			return result;
		} catch (RuntimeException exception) {
			attempt.failIfUnreported(exception);
			attempt.complete();
			throw exception;
		} finally {
			session.endAttempt(attempt);
		}
	}

	private static Attempt currentAttempt() {
		Session session = ACTIVE.get();
		return session == null ? null : session.attempt;
	}

	private static void registerProgramArray(Session session, ProgramSet programSet, ProgramArrayId arrayId) {
		String passId = arrayId.getGroup().getBaseName();
		for (ProgramSource source : programSet.getComposite(arrayId)) {
			if (source != null) {
				session.registerCompositeIntent(source.getName(), passId, source);
			}
		}
		for (ComputeSource[] group : programSet.getCompute(arrayId)) {
			registerComputes(session, passId, group);
		}
	}

	private static void registerComputes(Session session, String passId, ComputeSource[] sources) {
		if (sources == null) {
			return;
		}
		for (ComputeSource source : sources) {
			if (source != null) {
				session.registerIntent(source.getName() + ".csh", passId, List.of(CompileCatalog.ShaderStage.COMPUTE));
			}
		}
	}

	private static List<CompileCatalog.ShaderStage> graphicsStages(ProgramSource source) {
		List<CompileCatalog.ShaderStage> stages = new ArrayList<>();
		source.getVertexSource().ifPresent(ignored -> stages.add(CompileCatalog.ShaderStage.VERTEX));
		source.getTessControlSource().ifPresent(ignored -> stages.add(CompileCatalog.ShaderStage.TESS_CONTROL));
		source.getTessEvalSource().ifPresent(ignored -> stages.add(CompileCatalog.ShaderStage.TESS_EVALUATION));
		source.getGeometrySource().ifPresent(ignored -> stages.add(CompileCatalog.ShaderStage.GEOMETRY));
		source.getFragmentSource().ifPresent(ignored -> stages.add(CompileCatalog.ShaderStage.FRAGMENT));
		return stages;
	}

	private static List<CompileCatalog.ShaderStage> graphicsStages(Map<PatchShaderType, String> sources) {
		List<CompileCatalog.ShaderStage> stages = new ArrayList<>();
		addStage(sources, PatchShaderType.VERTEX, CompileCatalog.ShaderStage.VERTEX, stages);
		addStage(sources, PatchShaderType.TESS_CONTROL, CompileCatalog.ShaderStage.TESS_CONTROL, stages);
		addStage(sources, PatchShaderType.TESS_EVAL, CompileCatalog.ShaderStage.TESS_EVALUATION, stages);
		addStage(sources, PatchShaderType.GEOMETRY, CompileCatalog.ShaderStage.GEOMETRY, stages);
		addStage(sources, PatchShaderType.FRAGMENT, CompileCatalog.ShaderStage.FRAGMENT, stages);
		return stages;
	}

	private static void addStage(Map<PatchShaderType, String> sources, PatchShaderType patchType, CompileCatalog.ShaderStage stage, List<CompileCatalog.ShaderStage> stages) {
		if (sources.get(patchType) != null) {
			stages.add(stage);
		}
	}

	private static String patchedSourceHash(Map<PatchShaderType, String> sources) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(PATCHED_SOURCE_HASH_DOMAIN);
			digest.update((byte) 0);
			for (PatchShaderType type : PatchShaderType.values()) {
				String source = sources.get(type);
				if (source == null) {
					continue;
				}
				updateField(digest, type.name());
				updateField(digest, source);
			}
			return toHex(digest.digest());
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static void updateField(MessageDigest digest, String value) {
		int byteLength = utf8Length(value);
		digest.update((byte) (byteLength >>> 24));
		digest.update((byte) (byteLength >>> 16));
		digest.update((byte) (byteLength >>> 8));
		digest.update((byte) byteLength);

		byte[] buffer = PATCHED_SOURCE_HASH_BUFFER.get();
		int position = 0;
		for (int index = 0; index < value.length(); index++) {
			if (position > buffer.length - 4) {
				digest.update(buffer, 0, position);
				position = 0;
			}
			char character = value.charAt(index);
			if (character < 0x80) {
				buffer[position++] = (byte) character;
			} else if (character < 0x800) {
				buffer[position++] = (byte) (0xC0 | character >>> 6);
				buffer[position++] = (byte) (0x80 | character & 0x3F);
			} else if (Character.isHighSurrogate(character) && index + 1 < value.length()
				&& Character.isLowSurrogate(value.charAt(index + 1))) {
				int codePoint = Character.toCodePoint(character, value.charAt(++index));
				buffer[position++] = (byte) (0xF0 | codePoint >>> 18);
				buffer[position++] = (byte) (0x80 | codePoint >>> 12 & 0x3F);
				buffer[position++] = (byte) (0x80 | codePoint >>> 6 & 0x3F);
				buffer[position++] = (byte) (0x80 | codePoint & 0x3F);
			} else if (Character.isSurrogate(character)) {
				buffer[position++] = '?';
			} else {
				buffer[position++] = (byte) (0xE0 | character >>> 12);
				buffer[position++] = (byte) (0x80 | character >>> 6 & 0x3F);
				buffer[position++] = (byte) (0x80 | character & 0x3F);
			}
		}
		if (position > 0) {
			digest.update(buffer, 0, position);
		}
	}

	private static int utf8Length(String value) {
		int length = 0;
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (character < 0x80 || Character.isSurrogate(character)
				&& (!Character.isHighSurrogate(character) || index + 1 >= value.length()
				|| !Character.isLowSurrogate(value.charAt(index + 1)))) {
				length++;
			} else if (character < 0x800) {
				length += 2;
			} else if (Character.isHighSurrogate(character)) {
				length += 4;
				index++;
			} else {
				length += 3;
			}
		}
		return length;
	}

	private static String toHex(byte[] bytes) {
		char[] result = new char[bytes.length * 2];
		for (int index = 0; index < bytes.length; index++) {
			int value = bytes[index] & 0xff;
			result[index * 2] = HEX_DIGITS[value >>> 4];
			result[index * 2 + 1] = HEX_DIGITS[value & 0xf];
		}
		return new String(result);
	}

	public static final class Session {
		private final long generation;
		private final Map<Key, Entry> entries = new LinkedHashMap<>();
		private Attempt attempt;

		private Session(long generation) {
			if (generation < 0) {
				throw new IllegalArgumentException("generation must not be negative");
			}
			this.generation = generation;
		}

		void registerGraphicsIntent(String programId, String passId, ProgramSource source) {
			registerIntent(programId, passId, graphicsStages(source));
		}

		private void registerCompositeIntent(String programId, String passId, ProgramSource source) {
			List<CompileCatalog.ShaderStage> stages = new ArrayList<>();
			source.getVertexSource().ifPresent(ignored -> stages.add(CompileCatalog.ShaderStage.VERTEX));
			source.getGeometrySource().ifPresent(ignored -> stages.add(CompileCatalog.ShaderStage.GEOMETRY));
			source.getFragmentSource().ifPresent(ignored -> stages.add(CompileCatalog.ShaderStage.FRAGMENT));
			registerIntent(programId, passId, stages);
		}

		void registerIntent(String programId, String passId, Collection<CompileCatalog.ShaderStage> stages) {
			if (stages.isEmpty()) {
				return;
			}
			Key key = new Key(programId, passId);
			entries.compute(key, (ignored, existing) -> {
				if (existing == null) {
					return new Entry(programId, passId, stages);
				}
				existing.verifyStages(stages);
				return existing;
			});
		}

		private Attempt beginAttempt(String programId, String passId, List<CompileCatalog.ShaderStage> stages, String patchedSourceSha256) {
			if (attempt != null) {
				throw new IllegalStateException("Nested shader compilation attempts are not supported");
			}
			registerIntent(programId, passId, stages);
			Entry entry = entries.get(new Key(programId, passId));
			attempt = new Attempt(entry, patchedSourceSha256);
			return attempt;
		}

		private void endAttempt(Attempt expected) {
			if (attempt != expected) {
				throw new IllegalStateException("Compile catalog attempt mismatch");
			}
			attempt = null;
		}

		private void completeRemainingSuccess() {
			entries.values().stream()
				.filter(entry -> entry.compileState == CompileCatalog.CompileState.NOT_PRESENT)
				.forEach(entry -> entry.complete(
					CompileCatalog.CompileState.SUCCEEDED, CompileCatalog.CompileState.SUCCEEDED, EMPTY_SOURCE_SHA256, List.of()));
		}

		private void failRemaining(Exception exception) {
			String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
			entries.values().stream()
				.filter(entry -> entry.compileState == CompileCatalog.CompileState.NOT_PRESENT)
				.forEach(entry -> entry.complete(
					CompileCatalog.CompileState.FAILED, CompileCatalog.CompileState.NOT_APPLICABLE, EMPTY_SOURCE_SHA256,
					List.of(CompileCatalog.Diagnostic.of(
						CompileCatalog.DiagnosticSeverity.ERROR, entry.programId, 0, 0, message))));
		}

		CompileCatalog snapshot() {
			if (attempt != null) {
				throw new IllegalStateException("Cannot snapshot an active compile attempt");
			}
			List<CompileCatalog.ProgramEntry> programs = entries.values().stream().map(Entry::snapshot).toList();
			return CompileCatalog.of(programs, generation);
		}
	}

	private static final class Attempt {
		private final Entry entry;
		private final String patchedSourceSha256;
		private final Map<String, CompileCatalog.Diagnostic> diagnostics = new LinkedHashMap<>();
		private boolean compileReported;
		private boolean compileSuccessful = true;
		private boolean linkReported;
		private boolean linkSuccessful = true;

		private Attempt(Entry entry, String patchedSourceSha256) {
			this.entry = entry;
			this.patchedSourceSha256 = patchedSourceSha256;
		}

		private void recordCompile(String fileName, String log, boolean successful) {
			compileReported = true;
			compileSuccessful &= successful;
			addDiagnostics(fileName, log, successful, "Shader compilation failed without a driver diagnostic.");
		}

		private void recordLink(String fileName, String log, boolean successful) {
			linkReported = true;
			linkSuccessful &= successful;
			addDiagnostics(fileName, log, successful, "Shader linking failed without a driver diagnostic.");
		}

		private void failIfUnreported(RuntimeException exception) {
			if (!compileReported) {
				String fileName = exception instanceof ShaderCompileException shaderException
					? shaderException.getFilename()
					: entry.programId;
				String message = exception instanceof ShaderCompileException shaderException
					? shaderException.getError()
					: exception.getMessage();
				recordCompile(fileName, message == null ? exception.getClass().getSimpleName() : message, false);
			} else if (compileSuccessful && !linkReported) {
				String message = exception.getMessage();
				recordLink(entry.programId, message == null ? exception.getClass().getSimpleName() : message, false);
			}
		}

		private void complete() {
			if (!compileReported) {
				compileReported = true;
			}
			if (compileSuccessful && !linkReported) {
				linkReported = true;
			}
			entry.complete(
				compileSuccessful ? CompileCatalog.CompileState.SUCCEEDED : CompileCatalog.CompileState.FAILED,
				compileSuccessful
					? (linkSuccessful ? CompileCatalog.CompileState.SUCCEEDED : CompileCatalog.CompileState.FAILED)
					: CompileCatalog.CompileState.NOT_APPLICABLE,
				patchedSourceSha256,
				diagnostics.values());
		}

		private void addDiagnostics(String fileName, String log, boolean successful, String missingLogMessage) {
			if (log == null || log.isBlank()) {
				if (!successful) {
					CompileCatalog.Diagnostic diagnostic = CompileCatalog.Diagnostic.of(
						CompileCatalog.DiagnosticSeverity.ERROR, fileName, 0, 0, missingLogMessage);
					diagnostics.put(diagnostic.fingerprintSha256(), diagnostic);
				}
				return;
			}
			for (String line : log.lines().map(String::strip).filter(value -> !value.isEmpty()).toList()) {
				CompileCatalog.Diagnostic diagnostic = parseDiagnostic(fileName, line, successful);
				diagnostics.put(diagnostic.fingerprintSha256(), diagnostic);
			}
		}
	}

	private static CompileCatalog.Diagnostic parseDiagnostic(String fileName, String text, boolean successful) {
		CompileCatalog.DiagnosticSeverity severity = !successful || text.regionMatches(true, 0, "error", 0, 5)
			? CompileCatalog.DiagnosticSeverity.ERROR
			: CompileCatalog.DiagnosticSeverity.WARNING;
		int line = 0;
		int column = 0;
		String message = text;
		Matcher matcher = GLSL_LOCATION.matcher(text);
		if (matcher.matches()) {
			line = Integer.parseInt(matcher.group(1));
			if (matcher.group(2) != null && !matcher.group(2).isEmpty()) {
				column = Integer.parseInt(matcher.group(2));
			}
			if (matcher.group(3) != null && !matcher.group(3).isBlank()) {
				message = matcher.group(3).strip();
			}
		}
		return CompileCatalog.Diagnostic.of(severity, fileName, line, column, message);
	}

	private static final class Entry {
		private final String programId;
		private final String passId;
		private final List<CompileCatalog.ShaderStage> stages;
		private CompileCatalog.CompileState compileState = CompileCatalog.CompileState.NOT_PRESENT;
		private CompileCatalog.CompileState linkState = CompileCatalog.CompileState.NOT_PRESENT;
		private String patchedSourceSha256 = "";
		private Collection<CompileCatalog.Diagnostic> diagnostics = List.of();

		private Entry(String programId, String passId, Collection<CompileCatalog.ShaderStage> stages) {
			this.programId = programId;
			this.passId = passId;
			this.stages = stages.stream().distinct().sorted().toList();
		}

		private void verifyStages(Collection<CompileCatalog.ShaderStage> candidate) {
			List<CompileCatalog.ShaderStage> canonical = candidate.stream().distinct().sorted().toList();
			if (!stages.equals(canonical)) {
				throw new IllegalStateException("Conflicting shader stages for " + programId + "/" + passId);
			}
		}

		private void complete(CompileCatalog.CompileState compileState, CompileCatalog.CompileState linkState, String patchedSourceSha256, Collection<CompileCatalog.Diagnostic> diagnostics) {
			this.compileState = compileState;
			this.linkState = linkState;
			this.patchedSourceSha256 = patchedSourceSha256;
			this.diagnostics = List.copyOf(diagnostics);
		}

		private CompileCatalog.ProgramEntry snapshot() {
			return CompileCatalog.ProgramEntry.of(programId, passId, stages, compileState, linkState, patchedSourceSha256, diagnostics);
		}
	}

	private record Key(String programId, String passId) {
	}
}
