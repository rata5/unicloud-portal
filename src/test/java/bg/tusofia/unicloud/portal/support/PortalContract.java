package bg.tusofia.unicloud.portal.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks answers of the app's JSON API against the pinned contract,
 * openspec/changes/react-hypermedia-ui/portal-api.openapi.json: the operation must answer that
 * status, and the body must have every required field of the schema, with its type, all the
 * way down. Extra fields (such as _links and _templates) are allowed: the contract only grows.
 */
public final class PortalContract {

    private static final JsonNode CONTRACT = new ObjectMapper().readTree(
            new File("openspec/changes/react-hypermedia-ui/portal-api.openapi.json"));

    private PortalContract() {
    }

    /** {@code path} as the contract writes it, for example {@code /api/v1/portal/courses/{id}/enroll}. */
    public static void check(String method, String path, int status, JsonNode body) {
        JsonNode operation = CONTRACT.path("paths").path(path).path(method.toLowerCase());
        assertThat(operation.isMissingNode()).as("%s %s is in the contract", method, path).isFalse();
        JsonNode response = operation.path("responses").path(String.valueOf(status));
        assertThat(response.isMissingNode()).as("%s %s may answer %d", method, path, status).isFalse();
        JsonNode schema = response.path("content").path("application/json").path("schema");
        if (schema.isMissingNode()) {
            return;
        }
        List<String> problems = new ArrayList<>();
        matches(schema, body, method + " " + path, problems);
        assertThat(problems).as("answer of %s %s against the contract", method, path).isEmpty();
    }

    /**
     * Whether {@code method} on a concrete path (ids filled in) is an operation of the contract,
     * for example POST /api/v1/portal/courses/7/enroll.
     */
    public static boolean isOperation(String method, String concretePath) {
        for (var path : CONTRACT.path("paths").properties()) {
            String pattern = path.getKey().replaceAll("\\{[^/]+}", "[^/]+");
            if (concretePath.matches(pattern) && path.getValue().has(method.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static void matches(JsonNode schema, JsonNode value, String where, List<String> problems) {
        schema = resolve(schema);
        if (value == null || value.isNull()) {
            if (!allowsNull(schema)) {
                problems.add(where + ": null where the contract wants " + schema.path("type"));
            }
            return;
        }
        for (String combinator : List.of("oneOf", "anyOf")) {
            if (schema.has(combinator)) {
                for (JsonNode option : schema.path(combinator)) {
                    List<String> attempt = new ArrayList<>();
                    matches(option, value, where, attempt);
                    if (attempt.isEmpty()) {
                        return;
                    }
                }
                problems.add(where + ": matches none of " + combinator);
                return;
            }
        }
        String type = mainType(schema);
        switch (type) {
            case "object" -> {
                if (!value.isObject()) {
                    problems.add(where + ": not an object");
                    return;
                }
                for (JsonNode required : schema.path("required")) {
                    if (!value.has(required.asString())) {
                        problems.add(where + ": missing " + required.asString());
                    }
                }
                for (var property : schema.path("properties").properties()) {
                    if (value.has(property.getKey())) {
                        matches(property.getValue(), value.get(property.getKey()), where + "." + property.getKey(), problems);
                    }
                }
            }
            case "array" -> {
                if (!value.isArray()) {
                    problems.add(where + ": not an array");
                    return;
                }
                int i = 0;
                for (JsonNode item : value) {
                    matches(schema.path("items"), item, where + "[" + i++ + "]", problems);
                }
            }
            case "string" -> {
                if (!value.isString()) {
                    problems.add(where + ": not a string: " + value);
                } else if (schema.has("enum") && !contains(schema.path("enum"), value.asString())) {
                    problems.add(where + ": " + value.asString() + " is not one of " + schema.path("enum"));
                }
            }
            case "integer" -> {
                if (!value.isIntegralNumber()) {
                    problems.add(where + ": not an integer: " + value);
                }
            }
            case "boolean" -> {
                if (!value.isBoolean()) {
                    problems.add(where + ": not a boolean: " + value);
                }
            }
            default -> {
                // no type: anything goes
            }
        }
    }

    private static JsonNode resolve(JsonNode schema) {
        while (schema.has("$ref")) {
            String name = schema.path("$ref").asString().replace("#/components/schemas/", "");
            schema = CONTRACT.path("components").path("schemas").path(name);
        }
        return schema;
    }

    private static String mainType(JsonNode schema) {
        JsonNode type = schema.path("type");
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (!"null".equals(t.asString())) {
                    return t.asString();
                }
            }
            return "";
        }
        if (type.isMissingNode() && schema.has("properties")) {
            return "object";
        }
        return type.asString("");
    }

    private static boolean allowsNull(JsonNode schema) {
        JsonNode type = schema.path("type");
        if (type.isArray() && contains(type, "null")) {
            return true;
        }
        for (String combinator : List.of("oneOf", "anyOf")) {
            for (JsonNode option : schema.path(combinator)) {
                if ("null".equals(resolve(option).path("type").asString(""))) {
                    return true;
                }
            }
        }
        return schema.path("nullable").asBoolean(false);
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode item : array) {
            if (value.equals(item.asString())) {
                return true;
            }
        }
        return false;
    }
}
