package kr.ac.pusan.pickle.publishing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import kr.ac.pusan.pickle.publishing.dns.DnsRecordType;

/**
 * Contract schema {@code ReplaceDnsRecordSetsRequest}: every set the name
 * should have.
 *
 * <p>A whole desired state rather than one change. The server computes the
 * difference, so sending the same body twice is one change and a retry is safe;
 * an add-one endpoint would make the client responsible for knowing what is
 * already there, and two clients editing at once would each remove the other's
 * work without either of them saying so.</p>
 */
public record ReplaceDnsRecordSetsRequest(
        @Schema(description = "이 이름에 적용할 레코드 세트 전체예요. 빈 목록은 모든 세트의 삭제를 뜻해요.")
        @NotNull
        @Valid
        List<DesiredRecordSet> records) {

    /** Contract schema {@code DesiredRecordSet}. */
    public record DesiredRecordSet(
            @Schema(description = "도메인 기준 상대 이름이며, 빈 문자열은 도메인 이름 자체예요.",
                    example = "www")
            @NotNull
            String name,

            @Schema(description = "레코드 종류.")
            @NotNull
            DnsRecordType type,

            @Schema(description = "이 세트의 값 전체예요. 순서에 의미가 있는 종류가 있어 입력 순서를 그대로 유지해요.")
            @NotNull
            List<String> values,

            @Schema(description = "TTL(초)이며, 60 이상 86400 이하로 입력해 주세요.", example = "300")
            int ttl) {
    }
}
