package dev.vibris.mod;

import net.minecraft.client.DeltaTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SystemTimeUniformsTest {
	private VibrisTime.Scope activeScope;

	@AfterEach
	void closeScope() {
		if (activeScope != null) activeScope.close();
	}

	@Test
	void deterministicFrameIsRelativeToOrigin() {
		activeScope = VibrisTime.begin(100L);
		assertEquals(0L, VibrisTime.deterministicFrame(100L));
		assertEquals(1L, VibrisTime.deterministicFrame(101L));
	}

	@Test
	void deterministicScopeFreezesMinecraftInterpolation() {
		assertEquals(0.25F, VibrisTime.tickDelta(0.25F));
		activeScope = VibrisTime.begin(0L);
		assertTrue(VibrisTime.active());
		assertEquals(1.0F, VibrisTime.tickDelta(0.25F));
		assertSame(DeltaTracker.ONE, VibrisTime.deltaTracker(null));
	}

	@Test
	void scopeIsNonNestableAndIdempotentlyCloseable() {
		activeScope = VibrisTime.begin(0L);
		assertThrows(IllegalStateException.class, () -> VibrisTime.begin(0L));
		activeScope.close();
		activeScope.close();
		activeScope = null;
		assertFalse(VibrisTime.active());
	}
}
