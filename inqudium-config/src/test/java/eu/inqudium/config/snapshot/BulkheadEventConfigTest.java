package eu.inqudium.config.snapshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BulkheadEventConfig")
class BulkheadEventConfigTest {

    @Test
    void disabled_should_have_every_flag_off() {
        // Given / When
        BulkheadEventConfig c = BulkheadEventConfig.disabled();

        // Then
        assertThat(c.onAcquire()).isFalse();
        assertThat(c.onRelease()).isFalse();
        assertThat(c.onReject()).isFalse();
        assertThat(c.waitTrace()).isFalse();
        assertThat(c.rollbackTrace()).isFalse();
        assertThat(c.anyEnabled()).isFalse();
    }

    @Test
    void allEnabled_should_have_every_flag_on() {
        // Given / When
        BulkheadEventConfig c = BulkheadEventConfig.allEnabled();

        // Then
        assertThat(c.onAcquire()).isTrue();
        assertThat(c.onRelease()).isTrue();
        assertThat(c.onReject()).isTrue();
        assertThat(c.waitTrace()).isTrue();
        assertThat(c.rollbackTrace()).isTrue();
        assertThat(c.anyEnabled()).isTrue();
    }

    @Test
    void disabled_should_be_a_cached_singleton() {
        // What is to be tested: that BulkheadEventConfig.disabled() returns the same instance
        // every call. Why: the default constructor path on every BulkheadBuilderBase reaches
        // for it; allocating per-call would be wasteful for the no-events common case.
        // Why important: the bulkhead's hot path is allocation-sensitive; the gating record
        // sits in the snapshot every component reads.

        // Given / When / Then
        assertThat(BulkheadEventConfig.disabled()).isSameAs(BulkheadEventConfig.disabled());
        assertThat(BulkheadEventConfig.allEnabled()).isSameAs(BulkheadEventConfig.allEnabled());
    }

    @Test
    void anyEnabled_should_be_true_when_any_single_flag_is_set() {
        // Given / When / Then
        assertThat(new BulkheadEventConfig(true, false, false, false, false).anyEnabled()).isTrue();
        assertThat(new BulkheadEventConfig(false, true, false, false, false).anyEnabled()).isTrue();
        assertThat(new BulkheadEventConfig(false, false, true, false, false).anyEnabled()).isTrue();
        assertThat(new BulkheadEventConfig(false, false, false, true, false).anyEnabled()).isTrue();
        assertThat(new BulkheadEventConfig(false, false, false, false, true).anyEnabled()).isTrue();
    }

    @Test
    void custom_constructor_should_carry_each_flag_through() {
        // Given / When
        BulkheadEventConfig c = new BulkheadEventConfig(true, false, true, false, true);

        // Then
        assertThat(c.onAcquire()).isTrue();
        assertThat(c.onRelease()).isFalse();
        assertThat(c.onReject()).isTrue();
        assertThat(c.waitTrace()).isFalse();
        assertThat(c.rollbackTrace()).isTrue();
    }
}
