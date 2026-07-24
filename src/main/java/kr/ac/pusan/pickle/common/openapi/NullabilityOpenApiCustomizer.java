package kr.ac.pusan.pickle.common.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.TypeFilter;

/**
 * Derives the response nullability contract from the Java records themselves:
 * every record component is {@code required} unless it is annotated
 * {@code @org.jspecify.annotations.Nullable} (TYPE_USE — swagger-core cannot
 * read it, so this customizer applies it via reflection after model
 * resolution).
 *
 * <p>Scope is limited to components reachable from operation responses, so
 * request bodies keep their bean-validation-driven ({@code @NotNull})
 * required sets and optional PATCH fields stay optional. Nullable components
 * additionally gain the JSON-Schema {@code "null"} type (scalar props) or an
 * {@code anyOf [ref, null]} wrapper (ref props), matching how Jackson always
 * serializes the field with an explicit {@code null}.</p>
 *
 * <p>Fail-fast guarantees: a response-reachable object schema whose name
 * cannot be mapped to exactly one record class aborts spec generation —
 * silent coverage gaps or simple-name collisions would corrupt the published
 * contract (schema names are the Java simple names by design).</p>
 */
final class NullabilityOpenApiCustomizer {

    private static final String BASE_PACKAGE = "kr.ac.pusan.pickle";

    /** Schemas defined by hand in {@link OpenApiConfig}, not backed by a record. */
    private static final Set<String> HAND_DEFINED = Set.of("Problem");

    /** Generic page envelope: schema name pattern {@code PageResponse<Element>}. */
    private static final String PAGE_PREFIX = "PageResponse";

    private NullabilityOpenApiCustomizer() {
    }

    static void apply(OpenAPI openApi) {
        Map<String, Schema> schemas = openApi.getComponents().getSchemas();
        if (schemas == null) {
            return;
        }
        Map<String, List<Class<?>>> index = recordIndex();
        Set<String> responseReachable = reachableFromResponses(openApi, schemas);
        for (String name : responseReachable) {
            if (HAND_DEFINED.contains(name)) {
                continue;
            }
            Schema<?> schema = schemas.get(name);
            if (schema == null || schema.getEnum() != null || schema.getProperties() == null) {
                continue; // enums and property-less schemas carry no required[]
            }
            Class<?> record = resolveRecord(name, index);
            applyToSchema(name, schema, record);
        }

        // Request-side nullability: an optional (not bean-validation-required)
        // request field accepts an explicit null — Jackson binds it as-is and
        // several PATCH bodies rely on null-means-clear semantics. Emit the
        // null union so typed clients can send it. required[] stays as
        // computed from @NotNull/@NotBlank; response-shared schemas keep the
        // response rule.
        for (String name : reachableFromRequests(openApi, schemas)) {
            if (responseReachable.contains(name) || HAND_DEFINED.contains(name)) {
                continue;
            }
            Schema<?> schema = schemas.get(name);
            if (schema == null || schema.getEnum() != null || schema.getProperties() == null) {
                continue;
            }
            List<Class<?>> candidates = index.getOrDefault(
                    name.startsWith(PAGE_PREFIX) ? PAGE_PREFIX : name, List.of());
            if (candidates.size() == 1 && candidates.get(0).isRecord()) {
                // Records: nullability is an explicit @Nullable opt-in, exactly
                // like responses — an optional-but-non-null field (e.g. a port
                // whose omission means "default") must NOT accept null.
                // required[] stays as computed from bean validation.
                for (RecordComponent component : candidates.get(0).getRecordComponents()) {
                    Schema<?> prop = (Schema<?>) schema.getProperties().get(component.getName());
                    if (prop == null) {
                        continue;
                    }
                    freeFormIfJsonNode(component, prop);
                    if (component.getAnnotatedType().isAnnotationPresent(Nullable.class)) {
                        markNullable(prop, schema, component.getName());
                    }
                }
            } else {
                // Presence-tracking request CLASSES (UpdateOrgRequest etc.)
                // distinguish absent vs explicit null by design — every
                // optional field genuinely accepts null.
                Set<String> required = schema.getRequired() != null
                        ? new LinkedHashSet<>(schema.getRequired())
                        : Set.of();
                for (Map.Entry<String, Schema> prop
                        : ((Map<String, Schema>) schema.getProperties()).entrySet()) {
                    if (!required.contains(prop.getKey())) {
                        markNullable(prop.getValue(), schema, prop.getKey());
                    }
                }
            }
        }
    }

    /** Every schema name reachable from any operation request body via $refs. */
    private static Set<String> reachableFromRequests(OpenAPI openApi, Map<String, Schema> schemas) {
        Set<String> reachable = new LinkedHashSet<>();
        if (openApi.getPaths() == null) {
            return reachable;
        }
        openApi.getPaths().values().forEach(pathItem ->
                pathItem.readOperations().forEach(op -> {
                    if (op.getRequestBody() == null || op.getRequestBody().getContent() == null) {
                        return;
                    }
                    op.getRequestBody().getContent().values()
                            .forEach(mt -> walk(mt.getSchema(), schemas, reachable));
                }));
        return reachable;
    }

    private static void applyToSchema(String name, Schema<?> schema, Class<?> record) {
        Set<String> props = schema.getProperties().keySet();
        List<String> required = new ArrayList<>();
        for (RecordComponent component : record.getRecordComponents()) {
            if (!props.contains(component.getName())) {
                continue; // e.g. @JsonIgnore or naming strategy mismatch
            }
            freeFormIfJsonNode(component, (Schema<?>) schema.getProperties().get(component.getName()));
            boolean nullable = component.getAnnotatedType().isAnnotationPresent(Nullable.class);
            if (nullable) {
                markNullable((Schema<?>) schema.getProperties().get(component.getName()),
                        schema, component.getName());
            } else {
                required.add(component.getName());
            }
        }
        schema.setRequired(required);
    }

    /**
     * A {@code JsonNode}-typed component (or a map of them) holds ARBITRARY
     * JSON, scalars included — springdoc still emits {@code type: object} for
     * it, which typed clients render as an empty object. Strip the type so the
     * property is a true free-form schema ({@code unknown} on the client).
     */
    private static void freeFormIfJsonNode(RecordComponent component, Schema<?> prop) {
        if (prop == null) {
            return;
        }
        if (component.getType() == tools.jackson.databind.JsonNode.class) {
            clearType(prop);
        } else if (Map.class.isAssignableFrom(component.getType())
                && prop.getAdditionalProperties() instanceof Schema<?> ap
                && component.getGenericType() instanceof java.lang.reflect.ParameterizedType pt
                && pt.getActualTypeArguments().length == 2
                && pt.getActualTypeArguments()[1] == tools.jackson.databind.JsonNode.class) {
            clearType(ap);
        }
    }

    private static void clearType(Schema<?> schema) {
        schema.setType(null);
        schema.setTypes(null);
        schema.setProperties(null);
    }

    /** Adds the JSON-Schema {@code "null"} type; refs get an anyOf wrapper. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void markNullable(Schema<?> prop, Schema<?> owner, String propName) {
        if (prop.get$ref() != null) {
            Schema refPart = new Schema<>();
            refPart.set$ref(prop.get$ref());
            Schema nullPart = new Schema<>();
            nullPart.addType("null");
            Schema wrapper = new Schema<>();
            wrapper.setAnyOf(List.of(refPart, nullPart));
            wrapper.setDescription(prop.getDescription());
            ((Map<String, Schema>) owner.getProperties()).put(propName, wrapper);
        } else if (prop.getType() != null || prop.getTypes() != null) {
            prop.addType("null");
        }
        // A free-form schema (no type at all) already admits null — leave it.
    }

    /** Every schema name reachable from any operation response via $refs. */
    private static Set<String> reachableFromResponses(OpenAPI openApi, Map<String, Schema> schemas) {
        Set<String> reachable = new LinkedHashSet<>();
        if (openApi.getPaths() == null) {
            return reachable;
        }
        openApi.getPaths().values().forEach(pathItem ->
                pathItem.readOperations().forEach(op -> {
                    if (op.getResponses() == null) {
                        return;
                    }
                    for (ApiResponse response : op.getResponses().values()) {
                        if (response.getContent() == null) {
                            continue;
                        }
                        response.getContent().values()
                                .forEach(mt -> walk(mt.getSchema(), schemas, reachable));
                    }
                }));
        return reachable;
    }

    private static void walk(Schema<?> schema, Map<String, Schema> schemas, Set<String> reachable) {
        if (schema == null) {
            return;
        }
        if (schema.get$ref() != null) {
            String name = schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1);
            if (reachable.add(name)) {
                walk(schemas.get(name), schemas, reachable);
            }
            return;
        }
        nvl(schema.getAllOf()).forEach(sub -> walk(sub, schemas, reachable));
        nvl(schema.getAnyOf()).forEach(sub -> walk(sub, schemas, reachable));
        nvl(schema.getOneOf()).forEach(sub -> walk(sub, schemas, reachable));
        if (schema.getItems() != null) {
            walk(schema.getItems(), schemas, reachable);
        }
        if (schema.getProperties() != null) {
            schema.getProperties().values().forEach(p -> walk((Schema<?>) p, schemas, reachable));
        }
        if (schema.getAdditionalProperties() instanceof Schema<?> ap) {
            walk(ap, schemas, reachable);
        }
    }

    private static List<Schema> nvl(List<Schema> list) {
        return list != null ? list : List.of();
    }

    /** simpleName → record classes (nested records are scanned as own resources). */
    private static Map<String, List<Class<?>>> recordIndex() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                        return true; // include nested (non-independent) records
                    }
                };
        TypeFilter recordFilter = (metadataReader, metadataReaderFactory) ->
                "java.lang.Record".equals(metadataReader.getClassMetadata().getSuperClassName());
        scanner.addIncludeFilter(recordFilter);
        Map<String, List<Class<?>>> index = new HashMap<>();
        for (BeanDefinition bd : scanner.findCandidateComponents(BASE_PACKAGE)) {
            try {
                Class<?> clazz = Class.forName(bd.getBeanClassName(), false,
                        NullabilityOpenApiCustomizer.class.getClassLoader());
                index.computeIfAbsent(clazz.getSimpleName(), k -> new ArrayList<>()).add(clazz);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("record class vanished: " + bd.getBeanClassName(), e);
            }
        }
        return index;
    }

    private static Class<?> resolveRecord(String schemaName, Map<String, List<Class<?>>> index) {
        String lookup = schemaName.startsWith(PAGE_PREFIX) && !schemaName.equals(PAGE_PREFIX)
                ? PAGE_PREFIX
                : schemaName;
        List<Class<?>> candidates = index.getOrDefault(lookup, List.of());
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException("response schema '" + schemaName
                    + "' has no matching record class — nullability contract would be silently lost");
        }
        throw new IllegalStateException("response schema '" + schemaName
                + "' maps to multiple records " + candidates
                + " — schema names must be unambiguous Java simple names");
    }
}
