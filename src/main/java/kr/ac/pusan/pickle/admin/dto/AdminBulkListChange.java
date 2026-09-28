package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * One list field of a bulk change: replace the key's list, add to it, or take
 * from it. Clearing a list is {@code REPLACE} with no values; there is no
 * separate spelling for it.
 */
public record AdminBulkListChange(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "REPLACE는 목록을 통째로 바꾸고, ADD는 없는 값을 더하며, REMOVE는 있는 값을 뺍니다. "
                        + "비우려면 REPLACE에 빈 배열을 보냅니다.")
        @NotNull(message = "목록을 어떻게 바꿀지 지정해 주세요.")
        AdminBulkListOp op,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "적용할 값. 각 키의 현재 목록과 합친 결과가 단일 변경과 같은 규칙으로 검사됩니다.")
        @NotNull(message = "값 목록을 보내 주세요.")
        @Size(max = 50, message = "값은 최대 50개까지 보낼 수 있습니다.")
        List<String> values) {

    /** The list after this change meets {@code current}, order preserved, no repeats. */
    public List<String> applyTo(List<String> current) {
        return switch (op) {
            case REPLACE -> List.copyOf(values);
            case ADD -> {
                List<String> merged = new java.util.ArrayList<>(current);
                for (String value : values) {
                    if (!merged.contains(value)) {
                        merged.add(value);
                    }
                }
                yield List.copyOf(merged);
            }
            case REMOVE -> current.stream().filter(value -> !values.contains(value)).toList();
        };
    }
}
