package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import kr.ac.pusan.pickle.vm.VmPowerAction;

/** Bulk change kind {@code VM_POWER}: one power intent for every target. */
public record AdminBulkVmPowerChange(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "관리자 전원 개입과 같은 네 가지입니다. 시작은 STOPPED에서만, 종료와 재부팅은 RUNNING에서만, "
                        + "강제 종료는 RUNNING 또는 REBOOTING에서만 받습니다.")
        @NotNull(message = "전원 작업 종류를 지정해 주세요.")
        VmPowerAction action) {
}
