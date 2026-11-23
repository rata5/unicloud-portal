package bg.tusofia.unicloud.portal.portalapi;

import bg.tusofia.unicloud.portal.accounts.application.PortalUser;
import bg.tusofia.unicloud.portal.user.domain.Permission;

/**
 * An error of the app's JSON API, as the contract has it: {@code {error, message}} with a
 * Bulgarian message (portal-api.openapi.json, PortalError). Ported from the fork's
 * {@code portal_api/mod.rs} ApiError.
 */
public class PortalApiException extends RuntimeException {

    private final int status;
    private final String error;

    public PortalApiException(int status, String error, String message) {
        super(message);
        this.status = status;
        this.error = error;
    }

    public static PortalApiException unauthenticated() {
        return new PortalApiException(401, "unauthenticated", "Влезте отново.");
    }

    public static PortalApiException forbidden() {
        return new PortalApiException(403, "forbidden", "Нямате права за това действие.");
    }

    public static PortalApiException notFound() {
        return new PortalApiException(404, "not_found", "Не е намерено.");
    }

    public static PortalApiException invalid(String message) {
        return new PortalApiException(400, "invalid", message);
    }

    public static PortalApiException conflict(String message) {
        return new PortalApiException(409, "conflict", message);
    }

    /** 403 unless the user holds the permission: every handler checks one, not a role. */
    public static void require(PortalUser user, Permission permission) {
        if (!user.has(permission)) {
            throw forbidden();
        }
    }

    public int status() {
        return status;
    }

    public String error() {
        return error;
    }
}
