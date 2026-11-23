package bg.tusofia.unicloud.portal.accounts.application;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sign-in throttling (accounts-and-sign-in spec): after {@value #MAX_FAILURES} failed sign-ins
 * for one email within 15 minutes, that email is locked for 15 minutes, whatever the password.
 * Counted for every email asked about, existing or not, so the lock reveals nothing. Kept per
 * replica, in memory, as the spec allows; at most {@value #MAX_TRACKED} emails are tracked.
 */
@Component
public class SignInThrottle {

    static final int MAX_FAILURES = 10;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final int MAX_TRACKED = 100_000;

    private final ConcurrentHashMap<String, Failures> failures = new ConcurrentHashMap<>();
    private final Clock clock;

    public SignInThrottle() {
        this(Clock.systemUTC());
    }

    SignInThrottle(Clock clock) {
        this.clock = clock;
    }

    public boolean isLocked(String email) {
        Failures entry = failures.get(key(email));
        return entry != null && entry.lockedUntil != null && clock.instant().isBefore(entry.lockedUntil);
    }

    public void recordFailure(String email) {
        if (failures.size() >= MAX_TRACKED) {
            failures.clear();
        }
        Instant now = clock.instant();
        failures.compute(key(email), (k, entry) -> {
            Failures next = entry == null || now.isAfter(entry.windowStart.plus(WINDOW))
                    ? new Failures(now, 0, null)
                    : entry;
            int count = next.count + 1;
            return new Failures(next.windowStart, count, count >= MAX_FAILURES ? now.plus(WINDOW) : next.lockedUntil);
        });
    }

    public void clear(String email) {
        failures.remove(key(email));
    }

    private static String key(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    private record Failures(Instant windowStart, int count, Instant lockedUntil) {
    }
}
