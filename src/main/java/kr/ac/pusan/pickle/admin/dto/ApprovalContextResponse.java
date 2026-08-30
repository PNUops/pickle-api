package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.llm.CreditLimitReset;
import kr.ac.pusan.pickle.llm.LlmApiKeyStatus;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceMemberRole;
import kr.ac.pusan.pickle.request.ReviewDecision;
import kr.ac.pusan.pickle.request.RequestStatus;
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
        OrgHeadroom orgHeadroom,
        @Schema(deprecated = true)
        @Deprecated
        String guidance,
        @Nullable VmContext vm,
        @Nullable LlmKeyContext llmKey) {

    public record Applicant(
            UUID id,
            String name,
            String email,
            Instant signupAt,
            long approvedCount,
            long rejectedCount) {
    }

    public record Resources(List<VmBriefResponse> activeVms, ResourceTotalsResponse totals) {
    }

    public record WorkspacePanel(
            UUID id,
            String name,
            WorkspaceKind kind,
            List<MemberBrief> members,
            @Schema(deprecated = true,
                    description = "호환용 VM 목록. 새 클라이언트는 vm.workspaceResources.activeVms를 사용합니다.")
            @Deprecated
            List<VmBriefResponse> activeVms,
            @Schema(deprecated = true,
                    description = "호환용 VM 합계. 새 클라이언트는 vm.workspaceResources.totals를 사용합니다.")
            @Deprecated
            ResourceTotalsResponse totals) {
    }

    public record MemberBrief(UUID userId, String name, WorkspaceMemberRole role) {
    }

    public record HistoryEntry(
            UUID requestId,
            ResourceType type,
            String resourceName,
            Instant submittedAt,
            RequestStatus status,
            @Nullable ReviewDecision decision,
            @Nullable String comment,
            @Nullable String reviewerName) {
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
            OrgHeadroom orgHeadroom,
            String guidance) {
    }

    public record LlmKeyContext(
            List<LlmKeyBrief> applicantKeys,
            List<LlmKeyBrief> workspaceKeys) {
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
            BigDecimal creditLimit,
            @Nullable CreditLimitReset creditLimitReset,
            boolean creditAxisConnected) {
    }
}
