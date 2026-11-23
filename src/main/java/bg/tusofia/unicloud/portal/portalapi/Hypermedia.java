package bg.tusofia.unicloud.portal.portalapi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HAL-FORMS controls on the answers of the app's JSON API (react-hypermedia-ui spec, "Hypermedia
 * in every answer"): {@code _links} for where the user can go, {@code _templates} for what the
 * user can do now. The answers keep the contract's fields; these are added next to them, and only
 * when the user may use them. Spring HATEOAS is not used: its models would change the contract's
 * shapes (lists as {@code _embedded}).
 */
final class Hypermedia {

    static final String API = "/api/v1/portal";

    private final Map<String, Object> links = new LinkedHashMap<>();
    private final Map<String, Object> templates = new LinkedHashMap<>();

    static Hypermedia controls() {
        return new Hypermedia();
    }

    Hypermedia link(String relation, String href) {
        links.put(relation, Map.of("href", href));
        return this;
    }

    Hypermedia templatedLink(String relation, String href) {
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("href", href);
        link.put("templated", true);
        links.put(relation, link);
        return this;
    }

    Hypermedia template(String name, Template template) {
        templates.put(name, template.toMap());
        return this;
    }

    /** Adds the controls to {@code resource}, and returns it; nothing when there are none. */
    Map<String, Object> on(Map<String, Object> resource) {
        if (!links.isEmpty()) {
            resource.put("_links", links);
        }
        if (!templates.isEmpty()) {
            resource.put("_templates", templates);
        }
        return resource;
    }

    static Template form(String title, String method, String target) {
        return new Template(title, method, target, "application/json");
    }

    /** One HAL-FORMS template: method, target, content type and the fields with their rules. */
    static final class Template {
        private final Map<String, Object> template = new LinkedHashMap<>();
        private final List<Map<String, Object>> properties = new ArrayList<>();

        private Template(String title, String method, String target, String contentType) {
            template.put("title", title);
            template.put("method", method);
            template.put("target", target);
            template.put("contentType", contentType);
        }

        Template contentType(String contentType) {
            template.put("contentType", contentType);
            return this;
        }

        Template property(Property property) {
            properties.add(property.values);
            return this;
        }

        Map<String, Object> toMap() {
            Map<String, Object> copy = new LinkedHashMap<>(template);
            copy.put("properties", properties);
            return copy;
        }
    }

    static Property field(String name, String prompt) {
        return new Property(name, prompt);
    }

    /** One field of a template, with the rules the server checks again. */
    static final class Property {
        private final Map<String, Object> values = new LinkedHashMap<>();

        private Property(String name, String prompt) {
            values.put("name", name);
            values.put("prompt", prompt);
        }

        Property required() {
            values.put("required", true);
            return this;
        }

        Property type(String type) {
            values.put("type", type);
            return this;
        }

        Property length(int min, int max) {
            if (min > 0) {
                values.put("minLength", min);
            }
            values.put("maxLength", max);
            return this;
        }

        Property range(int min, int max) {
            values.put("type", "number");
            values.put("min", min);
            values.put("max", max);
            return this;
        }

        Property regex(String regex) {
            values.put("regex", regex);
            return this;
        }

        Property value(Object value) {
            values.put("value", value);
            return this;
        }

        /** A choice of one, as HAL-FORMS writes it: {@code options.inline} of {prompt, value}. */
        Property options(List<Map<String, Object>> choices) {
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("inline", choices);
            options.put("maxItems", 1);
            values.put("options", options);
            return this;
        }
    }

    static Map<String, Object> choice(String prompt, Object value) {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("prompt", prompt);
        choice.put("value", value);
        return choice;
    }
}
