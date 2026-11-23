package bg.tusofia.unicloud.portal.accounts.infrastructure;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * The portal's password hashing (accounts-and-sign-in spec, "Password storage" and "Password
 * hashing load"):
 * <ul>
 *   <li>new hashes are Argon2id, stored as {@code {argon2}...};</li>
 *   <li>every hash made before (BCrypt, stored with no {@code {id}} prefix) still verifies, and
 *       {@link #upgradeEncoding} says it should be replaced at the next sign-in;</li>
 *   <li>at most {@value #PARALLEL_HASHES} hashes run at once per replica; more callers wait for a
 *       slot instead of failing, so a sign-in rush cannot use every CPU.</li>
 * </ul>
 * Module secrets go through the same encoder (Spring Authorization Server uses this bean).
 */
public final class PortalPasswordEncoder implements PasswordEncoder {

    public static final int PARALLEL_HASHES = 4;

    private final PasswordEncoder delegate;
    private final Semaphore slots;

    public PortalPasswordEncoder() {
        this(PARALLEL_HASHES);
    }

    PortalPasswordEncoder(int parallelHashes) {
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
        DelegatingPasswordEncoder delegating = new DelegatingPasswordEncoder("argon2", Map.of(
                "argon2", Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(),
                "bcrypt", bcrypt));
        // Hashes from before this change have no {id} prefix: they are all BCrypt
        delegating.setDefaultPasswordEncoderForMatches(bcrypt);
        this.delegate = delegating;
        this.slots = new Semaphore(parallelHashes, true);
    }

    @Override
    public String encode(CharSequence rawPassword) {
        slots.acquireUninterruptibly();
        try {
            return delegate.encode(rawPassword);
        } finally {
            slots.release();
        }
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        slots.acquireUninterruptibly();
        try {
            return delegate.matches(rawPassword, encodedPassword);
        } finally {
            slots.release();
        }
    }

    /** True for a hash that is not Argon2id yet: it is replaced at the user's next sign-in. */
    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);
    }

    /** How many hashes are running now; for the load test of the limit. */
    int running() {
        return PARALLEL_HASHES - slots.availablePermits();
    }
}
