package kr.ac.pusan.pickle.common.openapi;

import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    public static final String CONTRACT_VERSION = "0.13.0";

    static {
        // Domain enums become named components (shared TS union types on the
        // console side) instead of inline copies at every usage site.
        ModelResolver.enumsAsRef = true;
        // Jackson 3 JsonNode is not special-cased by springdoc; without this it
        // is bean-introspected into ~24 bogus properties. Free-form object is
        // the honest contract for these values.
        SpringDocUtils.getConfig().replaceWithSchema(tools.jackson.databind.JsonNode.class,
                new ObjectSchema());
    }

    @Bean
    public OpenAPI pickleOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Pickle API")
                .description("부산대학교 클라우드 플랫폼 Pickle의 REST API. 인증은 JWT Bearer, "
                        + "오류 응답은 RFC 9457 problem+json(Problem 스키마)을 따릅니다.")
                .version(CONTRACT_VERSION));
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
        };
    }
}
