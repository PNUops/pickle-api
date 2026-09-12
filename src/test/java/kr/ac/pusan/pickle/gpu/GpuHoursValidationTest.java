package kr.ac.pusan.pickle.gpu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import kr.ac.pusan.pickle.gpu.dto.ApproveGpuRequestSpec;
import kr.ac.pusan.pickle.gpu.dto.CreateGpuRequestSpec;
import kr.ac.pusan.pickle.gpu.dto.ExtendGpuLeaseRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

class GpuHoursValidationTest {
    private final JsonMapper json = JsonMapper.builder().build();
    @Test
    void fractionalHoursAreRejectedInsteadOfRoundedDown() {
        for (String value : List.of("1.5", "0.9", "2147483648")) {
            assertThatThrownBy(() -> json.readValue("{\"leaseHours\":"+value+"}", CreateGpuRequestSpec.class)).isInstanceOf(JacksonException.class);
            assertThatThrownBy(() -> json.readValue("{\"leaseHours\":"+value+"}", ApproveGpuRequestSpec.class)).isInstanceOf(JacksonException.class);
            assertThatThrownBy(() -> json.readValue("{\"hours\":"+value+"}", ExtendGpuLeaseRequest.class)).isInstanceOf(JacksonException.class);
        }
    }
    @Test
    void stringsCannotSupplyAnImplicitDuration() {
        for (String value : List.of("\"1\"", "\"\"", "true", "[]")) {
            assertThatThrownBy(() -> json.readValue("{\"leaseHours\":"+value+"}", CreateGpuRequestSpec.class)).isInstanceOf(JacksonException.class);
        }
    }
    @Test
    void integerValuedNumbersPreserveTheirExactValue() {
        assertThat(json.readValue("{\"leaseHours\":36}", CreateGpuRequestSpec.class).leaseHours()).isEqualTo(36);
        assertThat(json.readValue("{\"leaseHours\":36.0}", ApproveGpuRequestSpec.class).leaseHours()).isEqualTo(36);
        assertThat(json.readValue("{\"hours\":2e1}", ExtendGpuLeaseRequest.class).hours()).isEqualTo(20);
    }
}
