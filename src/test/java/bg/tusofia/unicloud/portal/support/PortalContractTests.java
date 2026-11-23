package bg.tusofia.unicloud.portal.support;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The contract check itself refuses what the pinned contract does not allow. */
class PortalContractTests {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void missingFieldsWrongTypesAndWrongStatusesFail() {
        assertThatThrownBy(() -> PortalContract.check("GET", "/api/v1/portal/me", 200, json.readTree("{}")))
                .isInstanceOf(AssertionError.class).hasMessageContaining("missing user");
        assertThatThrownBy(() -> PortalContract.check("GET", "/api/v1/portal/courses", 200,
                json.readTree("[{\"id\": \"7\", \"code\": \"X\", \"title\": \"X\", \"ects\": 5, \"faculty\": \"F\","
                        + " \"teacher\": \"T\", \"enrolled\": 0, \"capacity\": 1, \"my_status\": null}]")))
                .isInstanceOf(AssertionError.class).hasMessageContaining("not an integer");
        assertThatThrownBy(() -> PortalContract.check("POST", "/api/v1/portal/courses/{id}/enroll", 200,
                json.readTree("{\"outcome\": \"MAYBE\", \"message\": \"?\"}")))
                .isInstanceOf(AssertionError.class).hasMessageContaining("is not one of");
        assertThatThrownBy(() -> PortalContract.check("GET", "/api/v1/portal/me", 418, json.readTree("{}")))
                .isInstanceOf(AssertionError.class).hasMessageContaining("may answer 418");
    }

    @Test
    void extraFieldsAreAllowed() {
        assertThatCode(() -> PortalContract.check("POST", "/api/v1/portal/courses/{id}/enroll", 200,
                json.readTree("{\"outcome\": \"ENROLLED\", \"message\": \"ok\", \"_links\": {}}")))
                .doesNotThrowAnyException();
    }
}
