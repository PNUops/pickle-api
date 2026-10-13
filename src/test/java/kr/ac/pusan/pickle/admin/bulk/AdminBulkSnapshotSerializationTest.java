package kr.ac.pusan.pickle.admin.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Durable snapshots preserve partial updates rather than inventing clears. */
class AdminBulkSnapshotSerializationTest {
    @Test
    void aSavedPartialLimitsRequestKeepsOmissionAndExplicitNullDistinct() {
        ObjectMapper mapper = new ObjectMapper();
        String json = """
                {"targetType":"LLM_KEY","targetIds":["00000000-0000-4000-8000-000000000001"],
                 "change":{"kind":"LLM_KEY_LIMITS","llmKeyLimits":{"rpm":91,"tpm":null}}}
                """;
        AdminBulkChangeRequest original = mapper.readValue(json, AdminBulkChangeRequest.class);
        AdminBulkChangeRequest restored = mapper.readValue(mapper.writeValueAsString(original), AdminBulkChangeRequest.class);
        var limits = restored.change().llmKeyLimits();
        assertThat(limits.isRpmSet()).isTrue();
        assertThat(limits.getRpm()).isEqualTo(91);
        assertThat(limits.isTpmSet()).isTrue();
        assertThat(limits.getTpm()).isNull();
        assertThat(limits.isCreditLimitSet()).isFalse();
        assertThat(limits.isCreditLimitResetSet()).isFalse();
        assertThat(limits.isConcurrencySet()).isFalse();
        assertThat(limits.isDailyTokensSet()).isFalse();
        var snapshot = mapper.readTree(mapper.writeValueAsString(restored)).get("change").get("llmKeyLimits");
        assertThat(snapshot.propertyNames()).containsExactlyInAnyOrder("rpm", "tpm");
        tools.jackson.databind.JsonNode restoredChange = mapper.valueToTree(restored.change());
        tools.jackson.databind.JsonNode originalChange = mapper.valueToTree(original.change());
        assertThat(restoredChange).isEqualTo(originalChange);
    }
}
