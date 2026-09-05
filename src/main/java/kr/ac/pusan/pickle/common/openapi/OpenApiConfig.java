package kr.ac.pusan.pickle.common.openapi;

import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import kr.ac.pusan.pickle.config.PublicEndpoints;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.AntPathMatcher;

/**
 * Shapes the springdoc-generated contract so it is the single, self-sufficient
 * source for the published spec ({@code contract/openapi.yaml}) and the
 * console's type generation.
 *
 * <p>The generated spec is the machine truth: schema names are the Java class
 * names, domain enums are promoted to named components ({@code enumsAsRef}),
 * and free-form JSON fields ({@code JsonNode}) map to plain object schemas
 * instead of bean-introspected noise. The error envelope emitted by
 * {@code GlobalExceptionHandler}/{@code ProblemJsonWriter} is registered here
 * as the {@code Problem} component and attached to every operation as the
 * {@code default} response, which typed clients (openapi-fetch) pick up as the
 * error channel.</p>
 */
@Configuration
public class OpenApiConfig {

    /** Contract version served in {@code info.version}; bump on any contract change. */
    public static final String CONTRACT_VERSION = "0.66.0";

    /** Name of the bearer-JWT security scheme in the published spec. */
    private static final String BEARER_SCHEME = "bearerAuth";

    static {
        // Domain enums become named components (shared TS union types on the
        // console side) instead of inline copies at every usage site.
        ModelResolver.enumsAsRef = true;
        // Jackson 3 JsonNode is not special-cased by springdoc; without this it
        // is bean-introspected into ~24 bogus properties. An EMPTY schema (not
        // type:object) is the honest contract: these values are arbitrary JSON,
        // scalars included (typed clients render it as `unknown`).
        SpringDocUtils.getConfig().replaceWithSchema(tools.jackson.databind.JsonNode.class,
                new Schema<>());
    }

    @Bean
    public OpenAPI pickleOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Pickle API")
                        .description("부산대학교 클라우드 플랫폼 Pickle의 REST API. 인증은 JWT Bearer, "
                                + "오류 응답은 RFC 9457 problem+json(Problem 스키마)을 따릅니다.")
                        .version(CONTRACT_VERSION))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("액세스 토큰 (JWT HS256, 15분 만료). 클레임: sub(계정 공개 식별자 UUID), "
                                + "role, token_version. 비밀번호 변경·계정 비활성화 시 token_version이 올라가 "
                                + "기존 토큰이 즉시 무효화됩니다. 리프레시 토큰은 보안 스킴이 아니라 "
                                + "__Host-pickle_refresh httpOnly 쿠키로만 오갑니다.")))
                // Applies to every operation unless the operation overrides it
                // with an empty requirement (see publicOperationSecurityCustomizer).
                .security(List.of(new SecurityRequirement().addList(BEARER_SCHEME)));
    }

    /**
     * Publishes {@code security: []} on the operations {@link PublicEndpoints}
     * serves without authentication, so a spec reader can tell them apart from
     * the bearer-protected majority. The patterns come from the same list
     * {@code SecurityConfig} uses for its {@code permitAll} rules, so the
     * contract cannot drift from the runtime rule.
     */
    @Bean
    public OpenApiCustomizer publicOperationSecurityCustomizer() {
        AntPathMatcher matcher = new AntPathMatcher();
        return openApi -> openApi.getPaths().forEach((path, pathItem) -> {
            boolean authenticatedException = PublicEndpoints.AUTHENTICATED_EXCEPTIONS.stream()
                    .anyMatch(pattern -> matcher.match(pattern, path));
            if (authenticatedException) {
                return;
            }
            boolean publicForAnyMethod = PublicEndpoints.ANY_METHOD.stream()
                    .anyMatch(pattern -> matcher.match(pattern, path));
            boolean publicForGet = PublicEndpoints.GET_ONLY.stream()
                    .anyMatch(pattern -> matcher.match(pattern, path));
            pathItem.readOperationsMap().forEach((method, operation) -> {
                if (publicForAnyMethod
                        || (publicForGet && method == PathItem.HttpMethod.GET)) {
                    operation.setSecurity(List.of());
                }
            });
        });
    }

    /**
     * Advertises the sudo-mode contract on every {@code @RequireReauth}
     * operation: the optional {@code X-Reauth-Token} header parameter and a
     * 403 {@code REAUTH_REQUIRED} note. Without this customizer the generated
     * spec would silently omit the requirement — nothing else gates it.
     */
    @Bean
    public org.springdoc.core.customizers.OperationCustomizer reauthOperationCustomizer() {
        return (operation, handlerMethod) -> {
            boolean required = org.springframework.core.annotation.AnnotatedElementUtils
                    .hasAnnotation(handlerMethod.getMethod(), kr.ac.pusan.pickle.security.RequireReauth.class)
                    || org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(
                            handlerMethod.getBeanType(), kr.ac.pusan.pickle.security.RequireReauth.class);
            if (!required) {
                return operation;
            }
            operation.addParametersItem(new io.swagger.v3.oas.models.parameters.HeaderParameter()
                    .name("X-Reauth-Token")
                    .required(false)
                    .schema(new StringSchema())
                    .description("재인증(sudo-mode) 토큰 — POST /auth/reverify가 발급 (10분 유효, "
                            + "다회용). 없거나 만료·무효면 403 REAUTH_REQUIRED."));
            String reauthNote = "재인증 필요 — 유효한 X-Reauth-Token 없음 (`REAUTH_REQUIRED`)";
            ApiResponse existing403 = operation.getResponses() != null
                    ? operation.getResponses().get("403") : null;
            if (existing403 != null) {
                // Keep the op's own 403 semantics (role gates etc.) and append.
                existing403.setDescription(existing403.getDescription() == null
                        ? reauthNote : existing403.getDescription() + " / " + reauthNote);
            } else {
                operation.getResponses().addApiResponse("403", new ApiResponse()
                        .description(reauthNote)
                        .content(new Content().addMediaType("application/problem+json",
                                new MediaType().schema(
                                        new Schema<>().$ref("#/components/schemas/Problem")))));
            }
            return operation;
        };
    }

    /**
     * Registers the {@code Problem} error envelope (exact runtime shape of
     * {@code GlobalExceptionHandler}: RFC 9457 fields + {@code code} and
     * {@code errors[]} extensions) and attaches it to every operation as the
     * {@code default} response.
     */
    @Bean
    public OpenApiCustomizer problemContractCustomizer() {
        return openApi -> {
            Schema<?> fieldError = new ObjectSchema()
                    .description("검증 실패 필드 한 건 (422 응답의 errors[] 원소)")
                    .addProperty("field", new StringSchema().description("실패한 요청 필드 이름"))
                    .addProperty("message", new StringSchema().description("한국어 오류 메시지"))
                    .required(List.of("field", "message"));
            Schema<?> problem = new ObjectSchema()
                    .description("RFC 9457 오류 응답 + Pickle 확장(code, errors). "
                            + "모든 비정상 응답은 이 형태로 반환됩니다.")
                    .addProperty("type", new StringSchema().description("문제 유형 URI (기본 about:blank)"))
                    .addProperty("title", new StringSchema().description("사람이 읽는 짧은 제목"))
                    .addProperty("status", new IntegerSchema().description("HTTP 상태 코드"))
                    .addProperty("detail", new StringSchema().description("이 발생 건에 대한 설명"))
                    .addProperty("instance", new StringSchema().description("발생 경로"))
                    .addProperty("code", new StringSchema()
                            .description("기계 판독용 안정 오류 코드 (클라이언트 분기 기준)"))
                    .addProperty("errors", new ArraySchema()
                            .description("검증 실패 상세 (422에서만 존재)")
                            .items(new Schema<>().$ref("#/components/schemas/FieldValidationError")))
                    .required(List.of("title", "status", "code"));
            openApi.getComponents().addSchemas("FieldValidationError", fieldError);
            openApi.getComponents().addSchemas("Problem", problem);

            ApiResponse problemResponse = new ApiResponse()
                    .description("오류 — 상태 코드와 무관하게 Problem 형태")
                    .content(new Content().addMediaType("application/problem+json",
                            new MediaType().schema(new Schema<>().$ref("#/components/schemas/Problem"))));
            openApi.getPaths().values().forEach(pathItem ->
                    pathItem.readOperations().forEach(op ->
                            op.getResponses().addApiResponse("default", problemResponse)));

            // Runs after the error envelope is in place so response reachability
            // (incl. FieldValidationError) is complete.
            NullabilityOpenApiCustomizer.apply(openApi);
        };
    }
}
