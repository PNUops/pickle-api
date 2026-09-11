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
        @Schema(description = "이 이름이 가져야 할 레코드 세트 전체. 빈 목록은 전부 삭제입니다.")
        @NotNull
        @Valid
        List<DesiredRecordSet> records) {

    /** Contract schema {@code DesiredRecordSet}. */
    public record DesiredRecordSet(
            @Schema(description = "도메인 이름 기준 상대 이름. 빈 문자열은 도메인 이름 자신입니다.",
                    example = "www")
            @NotNull
            String name,

            @Schema(description = "레코드 종류.")
            @NotNull
            DnsRecordType type,

            @Schema(description = "이 세트의 값 전체. 순서가 의미를 갖는 종류가 있어 그대로 보존됩니다.")
            @NotNull
            List<String> values,

            @Schema(description = "TTL(초). 60에서 86400 사이입니다.", example = "300")
            int ttl) {
    }
}
