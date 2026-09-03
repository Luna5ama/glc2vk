package dev.vibris.mod;

import dev.vibris.api.CompileCatalog;
import net.irisshaders.iris.gl.shader.ShaderCompileException;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IrisVibrisCompileCatalogTest {
	@Test
	void snapshotsCompleteCanonicalMappingAndTerminalOutcomes() {
		CompileCatalog first = buildFixture(false);
		CompileCatalog second = buildFixture(true);

		assertEquals(first.mappingSha256(), second.mappingSha256());
		assertEquals(List.of("composite21", "prepare7", "shadow.csh"),
			first.programs().stream().map(CompileCatalog.ProgramEntry::programId).toList());

		CompileCatalog.ProgramEntry graphics = first.programs().getFirst();
		assertEquals(CompileCatalog.CompileState.SUCCEEDED, graphics.compileState());
		assertEquals(CompileCatalog.CompileState.SUCCEEDED, graphics.linkState());
		assertEquals(64, graphics.patchedSourceSha256().length());
		assertEquals(CompileCatalog.DiagnosticSeverity.WARNING, graphics.diagnostics().getFirst().severity());

		CompileCatalog.ProgramEntry missing = first.programs().get(1);
		assertEquals(CompileCatalog.CompileState.NOT_PRESENT, missing.compileState());
		assertEquals(CompileCatalog.CompileState.NOT_PRESENT, missing.linkState());
		assertEquals("", missing.patchedSourceSha256());

		CompileCatalog.ProgramEntry compute = first.programs().get(2);
		assertEquals(CompileCatalog.CompileState.FAILED, compute.compileState());
		assertEquals(CompileCatalog.CompileState.NOT_APPLICABLE, compute.linkState());
		assertEquals(17, compute.diagnostics().getFirst().line());
		assertEquals(CompileCatalog.DiagnosticSeverity.ERROR, compute.diagnostics().getFirst().severity());
	}

	private static CompileCatalog buildFixture(boolean reverseRegistration) {
		IrisVibrisCompileCatalog.Session session = IrisVibrisCompileCatalog.beginEmpty(9);
		if (reverseRegistration) {
			registerCompute(session);
			registerMissing(session);
			registerGraphics(session);
		} else {
			registerGraphics(session);
			registerMissing(session);
			registerCompute(session);
		}

		Map<PatchShaderType, String> graphicsSources = new LinkedHashMap<>();
		graphicsSources.put(PatchShaderType.FRAGMENT, "void main() { }");
		graphicsSources.put(PatchShaderType.VERTEX, "void main() { gl_Position = vec4(0.0); }");
		IrisVibrisCompileCatalog.compileGraphics("composite21", "composite", graphicsSources, () -> {
			IrisVibrisCompileCatalog.recordCompileLog("composite21.vsh", "WARNING: 0:4: unused value", true);
			IrisVibrisCompileCatalog.recordCompileLog("composite21.fsh", "", true);
			IrisVibrisCompileCatalog.recordLinkLog("composite21", "", true);
			return 1;
		});

		assertThrows(ShaderCompileException.class, () -> IrisVibrisCompileCatalog.compileCompute(
			"shadow.csh", "shadow", "invalid compute source", () -> {
				IrisVibrisCompileCatalog.recordCompileLog("shadow.csh", "ERROR: 0:17: unknown identifier", false);
				throw new ShaderCompileException("shadow.csh", "ERROR: 0:17: unknown identifier");
			}));

		return IrisVibrisCompileCatalog.finish(session);
	}

	private static void registerGraphics(IrisVibrisCompileCatalog.Session session) {
		session.registerIntent("composite21", "composite", List.of(
			CompileCatalog.ShaderStage.VERTEX,
			CompileCatalog.ShaderStage.FRAGMENT));
	}

	private static void registerCompute(IrisVibrisCompileCatalog.Session session) {
		session.registerIntent("shadow.csh", "shadow", List.of(CompileCatalog.ShaderStage.COMPUTE));
	}

	private static void registerMissing(IrisVibrisCompileCatalog.Session session) {
		session.registerIntent("prepare7", "prepare", List.of(
			CompileCatalog.ShaderStage.VERTEX,
			CompileCatalog.ShaderStage.FRAGMENT));
	}
}