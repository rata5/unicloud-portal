package bg.tusofia.unicloud.portal.accounts.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Argon2id for new hashes, BCrypt still verifies, at most 4 hashes at once (accounts-and-sign-in spec). */
class PortalPasswordEncoderTests {

    private final PortalPasswordEncoder encoder = new PortalPasswordEncoder();

    @Test
    void newHashesAreArgon2id() {
        String hash = encoder.encode("a-long-password");

        assertThat(hash).startsWith("{argon2}$argon2id$");
        assertThat(encoder.matches("a-long-password", hash)).isTrue();
        assertThat(encoder.matches("another-password", hash)).isFalse();
        assertThat(encoder.upgradeEncoding(hash)).isFalse();
    }

    @Test
    void bcryptHashesFromBeforeStillVerifyAndAskForAnUpgrade() {
        String old = new BCryptPasswordEncoder().encode("a-long-password");

        assertThat(encoder.matches("a-long-password", old)).isTrue();
        assertThat(encoder.upgradeEncoding(old)).isTrue();
    }

    @Test
    void thePlaceholderHashesOfTheTestDataNeverMatch() {
        assertThat(encoder.matches("anything", "$2a$10$dummyhashfordevonly000000000000000000000000000000000")).isFalse();
    }

    @Test
    void atMostFourHashesRunAtOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(20);
        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger most = new AtomicInteger();
        Thread watcher = new Thread(() -> {
            while (!done.get()) {
                most.accumulateAndGet(encoder.running(), Math::max);
            }
        });
        watcher.start();
        try {
            List<Future<String>> hashes = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                hashes.add(pool.submit(() -> encoder.encode("a-long-password")));
            }
            for (Future<String> hash : hashes) {
                assertThat(hash.get()).as("every caller waits for a slot and succeeds").startsWith("{argon2}");
            }
        } finally {
            done.set(true);
            watcher.join();
            pool.shutdown();
        }
        assertThat(most.get()).isBetween(1, PortalPasswordEncoder.PARALLEL_HASHES);
    }
}
