package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.llm.CreditLimitReset;
import kr.ac.pusan.pickle.llm.LlmApiKeyStatus;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import kr.ac.pusan.pickle.request.ReviewDecision;
import kr.ac.pusan.pickle.request.RequestStatus;
import kr.ac.pusan.pickle.user.UserStatus;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code ApprovalContext}: common applicant, workspace-member
 * and request-history panels plus exactly one resource-specific context,
 * {@link #vm()} or {@link #llmKey()}. The top-level VM fields and the VM fields in
 * {@link WorkspacePanel} remain only for clients deployed before the typed
 * contexts; new clients read VM resources from {@link VmContext}.
 */
public record ApprovalContextResponse(
        ResourceType type,
        Applicant applicant,
        @Schema(deprecated = true)
        @Deprecated
        Resources applicantResources,
        WorkspacePanel workspace,
        List<HistoryEntry> history,
        @Schema(deprecated = true)
        @Deprecated
        @Nullable OrgHeadroom orgHeadroom,
        @Schema(deprecated = true)
        @Deprecated
        @Nullable String guidance,
        @Nullable VmContext vm,
        @Nullable LlmKeyContext llmKey,
        @Nullable GpuContext gpu,
        @Nullable DomainContext domain,
        UUID orgId,
        String orgName,
        Instant observedAt,
        @Schema(description = "이 신청의 실제 request_id로 연결된 자원의 기록과 상태. "
                + "자원 행 등록은 VM 프로비저닝 완료가 아닙니다. 신청 FK가 없는 과거 도메인 이름에서 현재 자원 ID를 추론하지 않습니다.")
        List<AdminRequestGeneratedResourceResponse> generatedResources,
        @Schema(description = "저장된 실제 대상 계정별 관리자 사용자 상세 조회 허용 여부. 현재 디렉터리 권한과 교집합이며, "
                + "워크스페이스를 떠난 계정도 포함합니다. 가입 전 식별자로 계정을 추정하지 않습니다.")
        Map<UUID, Boolean> recipientUserReadFlags) {

    public record GpuContext(@Nullable Long availableCards, @Nullable Long queuedAllocations, int requestedLeaseHours, @Nullable String vmName) {}

    /**
     * What a reviewer needs before approving a name.
     *
     * <p>Both fields are the two ways approving can fail at the last moment.
     * Nothing holds a name between submission and approval, so it can be taken
     * meanwhile; and the workspace's cap is counted when the name is created,
     * not when it was asked for. Showing them here is what stops the reviewer
     * meeting a conflict they did nothing to cause.</p>
     *
     * @param available  whether the name is still free at the moment of reading
     * @param held       external names this workspace holds, reserved ones included
     * @param cap        how many it may hold
     */
    public record DomainContext(String fqdn, boolean available, long held, int cap) {}

    public record Applicant(
            UUID id,
            String name,
            String email,
            @Nullable Instant signupAt,
            @Schema(description = "현재 신청 열람 권한 범위에서 확인되는 승인 수") long approvedCount,
            @Schema(description = "현재 신청 열람 권한 범위에서 확인되는 반려 수") long rejectedCount,
            boolean canReadDetail) {
    }

    public record Resources(@Nullable List<VmBriefResponse> activeVms, @Nullable ResourceTotalsResponse totals) {
    }

    public record WorkspacePanel(
            UUID id,
            String name,
            WorkspaceKind kind,
            List<MemberBrief> members,
            @Schema(deprecated = true,
                    description = "호환용 VM 목록. 새 클라이언트는 vm.workspaceResources.activeVms를 사용합니다.")
            @Deprecated
            @Nullable List<VmBriefResponse> activeVms,
            @Schema(deprecated = true,
                    description = "호환용 VM 합계. 새 클라이언트는 vm.workspaceResources.totals를 사용합니다.")
            @Deprecated
            @Nullable ResourceTotalsResponse totals,
            boolean canReadDetail) {
    }

    public record MemberBrief(UUID userId, String name, WorkspaceMemberRole role,
            UserStatus userStatus, boolean canReadDetail) {
    }

    public record HistoryEntry(
            UUID requestId,
            ResourceType type,
            String resourceName,
            Instant submittedAt,
            RequestStatus status,
            @Nullable ReviewDecision decision,
            @Nullable String comment,
            @Nullable String reviewerName,
            UUID orgId,
            boolean canReadDetail) {
    }

    public record OrgHeadroom(
            ResourceTotalsResponse allocated,
            Capacity capacity,
            double vcpuOvercommitRatio,
            double memoryUsageRatio,
            @Schema(description = "할당 디스크 / thin pool 용량 — 용량 미등록 노드가 있으면 null. "
                    + "오버프로비저닝 전제라 1을 넘을 수 있고 경고 임계값은 없습니다.")
            @Nullable Double diskUsageRatio,
            List<String> warnings) {
    }

    public record Capacity(
            long cpuThreads,
            long memoryMb,
            @Schema(description = "ACTIVE 노드의 thin pool 용량 합(GB) — 용량 미등록 노드가 있으면 null")
            @Nullable Long diskGb) {
    }

    public record VmContext(
            Resources applicantResources,
            Resources workspaceResources,
            @Nullable OrgHeadroom orgHeadroom,
            @Nullable String guidance,
            @Nullable AdminApprovalImageReference requestedImage) {
    }

    public record LlmKeyContext(
            List<LlmKeyBrief> applicantKeys,
            List<LlmKeyBrief> workspaceKeys, @Nullable List<InstitutionLlmAccountReference> accountReferences) {
        public LlmKeyContext(List<LlmKeyBrief> applicantKeys, List<LlmKeyBrief> workspaceKeys) { this(applicantKeys, workspaceKeys, null); }
    }

    public record LlmKeyBrief(
            UUID id,
            String name,
            @Nullable UUID workspaceId,
            String workspaceName,
            LlmApiKeyStatus status,
            @Nullable Instant expiresAt,
            @Nullable Integer rpm,
            @Nullable Integer tpm,
            @Nullable Integer concurrency,
            @Nullable Long dailyTokens,
            @Nullable BigDecimal creditLimit,
            @Nullable CreditLimitReset creditLimitReset,
            @Nullable Boolean creditAxisConnected,
            UUID orgId,
            boolean canReadDetail, boolean financeVisible) {
        public LlmKeyBrief(UUID id, String name, UUID workspaceId, String workspaceName, LlmApiKeyStatus status,
                Instant expiresAt, Integer rpm, Integer tpm, Integer concurrency, Long dailyTokens,
                BigDecimal creditLimit, CreditLimitReset creditLimitReset, boolean creditAxisConnected, UUID orgId, boolean canReadDetail) {
            this(id, name, workspaceId, workspaceName, status, expiresAt, rpm, tpm, concurrency, dailyTokens,
                    creditLimit, creditLimitReset, creditAxisConnected, orgId, canReadDetail, true);
        }
    }
}
