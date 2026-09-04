package kr.ac.pusan.pickle.admin;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.AdminRequestPeriodResponse;
import kr.ac.pusan.pickle.admin.dto.CreateRequestPeriodRequest;
import kr.ac.pusan.pickle.admin.dto.UpdateRequestPeriodRequest;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.inventory.CatalogStatus;
import kr.ac.pusan.pickle.request.period.RequestPeriodPreset;
import kr.ac.pusan.pickle.request.period.RequestPeriodPresetRepository;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator management of the usage periods the request form offers.
 *
 * <p>The dates are absolute, so this catalogue goes stale on a calendar rather
 * than on a decision: last term's row stops being a choice the day it ends. The
 * listing therefore reports every row including the expired ones, which is what
 * tells an operator that this term's row is missing.</p>
 */
@Service
public class AdminRequestPeriodService {

    private final RequestPeriodPresetRepository repository;
    private final AuditService auditService;
    private final Clock clock;

    public AdminRequestPeriodService(RequestPeriodPresetRepository repository,
            AuditService auditService, Clock clock) {
        this.repository = repository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AdminRequestPeriodResponse> list() {
        LocalDate today = ClockConfig.todayKst(clock);
        return repository.findAllInDisplayOrder().stream()
                .map(preset -> AdminRequestPeriodResponse.from(preset, today))
                .toList();
    }

    @Transactional
    public AdminRequestPeriodResponse create(AuthenticatedUser actor,
            CreateRequestPeriodRequest request, String ip) {
        if (repository.existsByName(request.name())) {
            throw nameTaken();
        }
        // saveAndFlush + catch: existsByName is a pre-check only. Under a
        // concurrent create of the same name the unique index is the arbiter,
        // and the loser must get the same 422 rather than a 500 at commit.
        RequestPeriodPreset preset;
        try {
            preset = repository.saveAndFlush(new RequestPeriodPreset(request.name(),
                    request.displayName(), request.endDate(), CatalogStatus.ACTIVE,
                    request.displayOrder() == null ? 0 : request.displayOrder()));
        } catch (DataIntegrityViolationException raced) {
            throw nameTaken();
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.REQUEST_PERIOD_CREATE, "request_period", preset.getPublicId(),
                Map.of("name", preset.getName(), "endDate", String.valueOf(preset.getEndDate())), ip);
        return AdminRequestPeriodResponse.from(preset, ClockConfig.todayKst(clock));
    }

    @Transactional
    public AdminRequestPeriodResponse update(AuthenticatedUser actor, UUID periodId,
            UpdateRequestPeriodRequest request, String ip) {
        RequestPeriodPreset preset = repository.findByPublicId(periodId)
                .orElseThrow(() -> notFound("해당 사용 기간이 존재하지 않습니다."));
        if (request.displayName() == null && request.endDate() == null
                && request.status() == null && request.displayOrder() == null) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("displayName",
                    "변경할 필드를 최소 1개 지정해야 합니다.")));
        }

        Map<String, Object> changes = new LinkedHashMap<>();
        if (request.displayName() != null && !request.displayName().equals(preset.getDisplayName())) {
            changes.put("displayName", preset.getDisplayName() + " -> " + request.displayName());
            preset.setDisplayName(request.displayName());
        }
        if (request.endDate() != null && !request.endDate().equals(preset.getEndDate())) {
            changes.put("endDate", preset.getEndDate() + " -> " + request.endDate());
            preset.setEndDate(request.endDate());
        }
        if (request.status() != null && request.status() != preset.getStatus()) {
            changes.put("status", preset.getStatus() + " -> " + request.status());
            preset.setStatus(request.status());
        }
        if (request.displayOrder() != null && !Objects.equals(request.displayOrder(),
                preset.getDisplayOrder())) {
            changes.put("displayOrder", preset.getDisplayOrder() + " -> " + request.displayOrder());
            preset.setDisplayOrder(request.displayOrder());
        }
        if (!changes.isEmpty()) {
            auditService.recordAfterCommit(actor.id(), actor.role().name(),
                    AuditService.REQUEST_PERIOD_UPDATE, "request_period", preset.getPublicId(),
                    Map.of("name", preset.getName(), "changes", changes), ip);
        }
        return AdminRequestPeriodResponse.from(preset, ClockConfig.todayKst(clock));
    }

    private static ApiException nameTaken() {
        return ApiException.validationFailed(List.of(
                new FieldValidationError("name", "이미 사용 중인 기간 이름입니다.")));
    }

    private static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", detail);
    }
}
