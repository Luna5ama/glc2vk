package dev.vibris.core;

import dev.vibris.core.request.RequestRegistry;
import dev.vibris.core.request.RequestState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RequestRegistryTest {
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-07-28T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void singleAcceptedRequestCompletes() {
        RequestRegistry<String> registry = new RequestRegistry<>(2, 2, Duration.ofMinutes(5), NOW);

        assertEquals(RequestRegistry.AcceptKind.NEW, registry.accept("request-1").kind());
        registry.markRunning("request-1");
        registry.finish("request-1", RequestState.COMPLETED, "result-1");

        RequestRegistry.AcceptResult<String> duplicate = registry.accept("request-1");
        assertEquals(RequestRegistry.AcceptKind.CACHED_FINAL, duplicate.kind());
        assertEquals(RequestState.COMPLETED, duplicate.snapshot().state());
        assertEquals("result-1", duplicate.snapshot().result());
    }

    @Test
    void duplicateLiveRequestReturnsCurrentStateWithoutAnotherEntry() {
        RequestRegistry<String> registry = new RequestRegistry<>(2, 2, Duration.ofMinutes(5), NOW);

        registry.accept("request-1");
        registry.markRunning("request-1");

        RequestRegistry.AcceptResult<String> duplicate = registry.accept("request-1");
        assertEquals(RequestRegistry.AcceptKind.CURRENT, duplicate.kind());
        assertEquals(RequestState.RUNNING, duplicate.snapshot().state());
        assertEquals(1, registry.liveSize());
    }

    @Test
    void liveAndTerminalBoundsAreIndependent() {
        RequestRegistry<String> registry = new RequestRegistry<>(1, 1, Duration.ofMinutes(5), NOW);

        assertEquals(RequestRegistry.AcceptKind.NEW, registry.accept("active").kind());
        assertEquals(RequestRegistry.AcceptKind.FULL, registry.accept("overflow").kind());
        registry.finish("active", RequestState.COMPLETED, "first");
        assertEquals(RequestRegistry.AcceptKind.NEW, registry.accept("next").kind());
        registry.finish("next", RequestState.COMPLETED, "second");

        assertEquals(RequestRegistry.AcceptKind.NEW, registry.accept("active").kind());
        assertEquals(RequestRegistry.AcceptKind.CACHED_FINAL, registry.accept("next").kind());
        assertEquals(1, registry.terminalSize());
    }

    @Test
    void expiredTerminalRequestCanBeAcceptedAgain() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T00:00:00Z"));
        RequestRegistry<String> registry = new RequestRegistry<>(1, 1, Duration.ofMinutes(5), clock);

        registry.accept("request-1");
        registry.finish("request-1", RequestState.COMPLETED, "result-1");
        clock.advance(Duration.ofMinutes(5));

        assertEquals(RequestRegistry.AcceptKind.NEW, registry.accept("request-1").kind());
        assertEquals(1, registry.liveSize());
        assertEquals(0, registry.terminalSize());
    }

    @Test
    void foreignOwnerCannotObserveLiveOrTerminalRequest() {
        RequestRegistry<String> registry = new RequestRegistry<>(1, 1, Duration.ofMinutes(5), NOW);

        assertEquals(RequestRegistry.AcceptKind.NEW, registry.accept("request-1", "owner-a").kind());
        assertEquals(RequestRegistry.AcceptKind.OWNER_MISMATCH,
            registry.accept("request-1", "owner-b").kind());
        assertFalse(registry.resume("request-1", "owner-b").isPresent());
        registry.finish("request-1", RequestState.COMPLETED, "result-1");
        assertEquals(RequestRegistry.AcceptKind.OWNER_MISMATCH,
            registry.accept("request-1", "owner-b").kind());
        assertFalse(registry.resume("request-1", "owner-b").isPresent());
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("only UTC is supported");
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}