package dev.vibris.core;

import dev.vibris.core.source.SourceRecord;
import dev.vibris.core.source.SourceState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceStateTest {
    @Test
    void acceptedHappyLifecycle() {
        SourceRecord source = new SourceRecord("c16314e4-a252-4a69-a64b-dff523668559", 1);

        source.queue();
        source.beginActivation();
        source.activated();
        source.release();

        assertEquals(SourceState.RELEASED_ACTIVE, source.state());
        assertFalse(source.deletionEligible());

        source.deactivate();
        assertEquals(SourceState.RECLAIMABLE, source.state());
        assertTrue(source.deletionEligible());

        source.beginDeleting();
        source.deleted();
        assertEquals(SourceState.DELETED, source.state());
    }

    @Test
    void queuedCancellationBecomesReclaimable() {
        SourceRecord source = new SourceRecord("a5dd7211-8fbb-4625-aeef-46fe597e537e", 1);

        source.queue();
        source.release();

        assertEquals(SourceState.RECLAIMABLE, source.state());
        assertTrue(source.deletionEligible());
    }

    @Test
    void invalidTransitionIsRejected() {
        SourceRecord source = new SourceRecord("0d2f4fd0-eebc-4a8a-94fa-61cae6ed95c8", 1);

        assertThrows(IllegalStateException.class, source::activated);
    }
}