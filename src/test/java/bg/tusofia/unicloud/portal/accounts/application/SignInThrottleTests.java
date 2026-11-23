package bg.tusofia.unicloud.portal.accounts.application;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** 10 failures in 15 minutes lock an email for 15 minutes (accounts-and-sign-in spec). */
class SignInThrottleTests {

    private final MovableClock clock = new MovableClock();
    private final SignInThrottle throttle = new SignInThrottle(clock);

    @Test
    void theTenthFailureLocksForFifteenMinutes() {
        for (int i = 0; i < 9; i++) {
            throttle.recordFailure("Student@TU-Sofia.bg");
        }
        assertThat(throttle.isLocked("student@tu-sofia.bg")).isFalse();

        throttle.recordFailure("student@tu-sofia.bg");
        assertThat(throttle.isLocked("student@tu-sofia.bg")).as("letter case does not matter").isTrue();
        assertThat(throttle.isLocked("other@tu-sofia.bg")).isFalse();

        clock.advance(Duration.ofMinutes(14));
        assertThat(throttle.isLocked("student@tu-sofia.bg")).isTrue();
        clock.advance(Duration.ofMinutes(2));
        assertThat(throttle.isLocked("student@tu-sofia.bg")).isFalse();
    }

    @Test
    void failuresSpreadOverMoreThanTheWindowDoNotLock() {
        for (int i = 0; i < 9; i++) {
            throttle.recordFailure("slow@tu-sofia.bg");
        }
        clock.advance(Duration.ofMinutes(16));
        throttle.recordFailure("slow@tu-sofia.bg");
        assertThat(throttle.isLocked("slow@tu-sofia.bg")).isFalse();
    }

    @Test
    void aSuccessfulSignInForgetsTheFailures() {
        for (int i = 0; i < 9; i++) {
            throttle.recordFailure("forgot@tu-sofia.bg");
        }
        throttle.clear("forgot@tu-sofia.bg");
        throttle.recordFailure("forgot@tu-sofia.bg");
        assertThat(throttle.isLocked("forgot@tu-sofia.bg")).isFalse();
    }

    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-23T08:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
