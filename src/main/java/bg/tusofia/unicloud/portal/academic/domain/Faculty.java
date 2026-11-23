package bg.tusofia.unicloud.portal.academic.domain;

/**
 * A faculty of the university. {@code code} (for example {@code FCST}) is the identifier other
 * UniCloud products use; it appears in the {@code faculty} claim (docs/sso.md, "User claims").
 */
public record Faculty(String code, String name) {
}
