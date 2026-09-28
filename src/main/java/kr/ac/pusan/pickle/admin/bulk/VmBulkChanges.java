package kr.ac.pusan.pickle.admin.bulk;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.VmPeriodService;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeFieldDiff;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeKind;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeReason;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeRequest;
import kr.ac.pusan.pickle.admin.dto.AdminBulkChangeSpec;
import kr.ac.pusan.pickle.admin.dto.AdminBulkVmDeletionAction;
import kr.ac.pusan.pickle.admin.dto.AdminBulkVmDeletionChange;
import kr.ac.pusan.pickle.admin.dto.AdminBulkVmPeriodChange;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmDeleteKind;
import kr.ac.pusan.pickle.vm.VmDeletionService;
import kr.ac.pusan.pickle.vm.VmLifecycleService;
import kr.ac.pusan.pickle.vm.VmPowerAction;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The three VM kinds of a bulk change: period, power and deletion.
 *
 * <p>Reach mirrors the admin VM surfaces: period and power to whoever
 * operates the VM's institution (elsewhere NOT_FOUND), deletion to a system
 * administrator or an organisation administrator of that institution, with
 * a role that may see the VM but not schedule its deletion told FORBIDDEN.
 * No row lock is taken: every VM write is a compare-and-set, and a claim that
 * loses is reported as the state it lost to.
 */
@Component
class VmBulkChanges extends BulkChangeHandler<Vm> {

    private final VmRepository vmRepository;
    private final VmPeriodService periodService;
    private final VmLifecycleService lifecycleService;
    private final VmDeletionService deletionService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    VmBulkChanges(VmRepository vmRepository, VmPeriodService periodService,
            VmLifecycleService lifecycleService, VmDeletionService deletionService,
            ObjectMapper objectMapper, Clock clock) {
        this.vmRepository = vmRepository;
        this.periodService = periodService;
        this.lifecycleService = lifecycleService;
        this.deletionService = deletionService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    Set<AdminBulkChangeKind> kinds() {
        return Set.of(AdminBulkChangeKind.VM_PERIOD, AdminBulkChangeKind.VM_POWER,
                AdminBulkChangeKind.VM_DELETION);
    }

    @Override
    void validate(AdminBulkChangeRequest request, List<FieldValidationError> errors) {
        AdminBulkChangeSpec change = request.change();
        switch (change.kind()) {
            case VM_PERIOD -> {
                AdminBulkVmPeriodChange period = change.vmPeriod();
                boolean clearing = Boolean.TRUE.equals(period.clearEndDate());
                if (clearing && period.endDate() != null) {
                    errors.add(new FieldValidationError("change.vmPeriod.endDate",
                            "종료일을 지우면서 동시에 지정할 수는 없습니다."));
                } else if (!clearing && period.endDate() == null) {
                    errors.add(new FieldValidationError("change.vmPeriod.endDate",
                            "종료일을 정하거나 무기한으로 바꿔 주세요."));
                } else if (!clearing) {
                    FieldValidationError tooEarly = VmPeriodService.beforeToday(period.endDate(),
                            clock);
                    if (tooEarly != null) {
                        errors.add(new FieldValidationError("change.vmPeriod.endDate",
                                tooEarly.message()));
                    }
                }
            }
            case VM_DELETION -> {
                AdminBulkVmDeletionChange deletion = change.vmDeletion();
                if (deletion.action() != AdminBulkVmDeletionAction.SCHEDULE) {
                    return;
                }
                if (deletion.scheduledFor() == null) {
                    errors.add(new FieldValidationError("change.vmDeletion.scheduledFor",
                            "삭제 예정 시각을 지정해 주세요."));
                } else if (!deletion.scheduledFor().isAfter(clock.instant())) {
                    errors.add(new FieldValidationError("change.vmDeletion.scheduledFor",
                            "삭제 예정일은 미래 시각이어야 합니다."));
                }
                if (deletion.reason() == null || deletion.reason().isBlank()) {
                    errors.add(new FieldValidationError("change.vmDeletion.reason",
                            "삭제 사유를 입력해 주세요."));
                }
            }
            case VM_POWER -> {
                // The action is the whole request, and the annotation already
                // made it mandatory.
            }
            default -> throw new IllegalStateException("not a VM kind: " + change.kind());
        }
    }

    @Override
    Map<UUID, Vm> load(AdminBulkChangeRequest request) {
        Map<UUID, Vm> vms = new LinkedHashMap<>();
        for (UUID id : request.targetIds()) {
            vmRepository.findByPublicId(id).ifPresent(vm -> vms.put(id, vm));
        }
        return vms;
    }

    @Override
    long orderKey(Vm vm) {
        return vm.getId();
    }

    @Override
    String name(Vm vm) {
        return vm.getName();
    }

    @Override
    @Nullable AdminBulkChangeReason accessRefusal(AuthenticatedUser actor, Vm vm,
            AdminBulkChangeSpec change) {
        Long orgId = vm.getOrgId();
        boolean deletion = change.kind() == AdminBulkChangeKind.VM_DELETION;
        if (actor.role().isOrgTier()) {
            if (!actor.reads(orgId)) {
                return AdminBulkChangeReason.NOT_FOUND;
            }
            if (deletion) {
                return actor.administers(orgId) ? null : AdminBulkChangeReason.FORBIDDEN;
            }
            return actor.operates(orgId) ? null : AdminBulkChangeReason.NOT_FOUND;
        }
        if (deletion && actor.role() != UserRole.SYS_ADMIN) {
            return AdminBulkChangeReason.FORBIDDEN;
        }
        return null;
    }

    @Override
    Map<String, Object> fingerprintValues(Vm vm, AdminBulkChangeSpec change) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", vm.getStatus().name());
        switch (change.kind()) {
            case VM_PERIOD -> {
                values.put("startDate", BulkFingerprints.plain(vm.getStartDate()));
                values.put("endDate", BulkFingerprints.plain(vm.getEndDate()));
                values.put("deleteKind", BulkFingerprints.plain(vm.getDeleteKind()));
                values.put("deleteScheduledFor", BulkFingerprints.plain(vm.getDeleteScheduledFor()));
                values.put("deleteRequestedAt", BulkFingerprints.plain(vm.getDeleteRequestedAt()));
            }
            case VM_POWER -> {
                values.put("pendingPowerAction", vm.getPendingPowerAction());
                values.put("endDate", BulkFingerprints.plain(vm.getEndDate()));
            }
            case VM_DELETION -> {
                values.put("deleteKind", BulkFingerprints.plain(vm.getDeleteKind()));
                values.put("deleteScheduledFor", BulkFingerprints.plain(vm.getDeleteScheduledFor()));
            }
            default -> throw new IllegalStateException("not a VM kind: " + change.kind());
        }
        return values;
    }

    @Override
    Judgement judge(AuthenticatedUser actor, Vm vm, AdminBulkChangeSpec change, Instant now) {
        return switch (change.kind()) {
            case VM_PERIOD -> judgePeriod(vm, change.vmPeriod());
            case VM_POWER -> judgePower(vm, change.vmPower().action());
            case VM_DELETION -> judgeDeletion(vm, change.vmDeletion(), now);
            default -> throw new IllegalStateException("not a VM kind: " + change.kind());
        };
    }

    private Judgement judgePeriod(Vm vm, AdminBulkVmPeriodChange period) {
        if (VmPeriodService.isDeletionBound(vm)) {
            return Judgement.refused(AdminBulkChangeReason.INVALID_STATE);
        }
        LocalDate newEnd = Boolean.TRUE.equals(period.clearEndDate()) ? null : period.endDate();
        if (newEnd != null && VmPeriodService.endBeforeStart(newEnd, vm.getStartDate()) != null) {
            return Judgement.refused(AdminBulkChangeReason.VALIDATION);
        }
        if (Objects.equals(vm.getEndDate(), newEnd)) {
            return Judgement.unchanged();
        }
        return Judgement.change(List.of(diff(objectMapper, "endDate", vm.getEndDate(), newEnd)),
                null);
    }

    private Judgement judgePower(Vm vm, VmPowerAction action) {
        if (action == VmPowerAction.START && lifecycleService.isExpired(vm)) {
            return Judgement.refused(AdminBulkChangeReason.EXPIRED);
        }
        if (!VmLifecycleService.allowedSources(action).contains(vm.getStatus())) {
            return Judgement.refused(AdminBulkChangeReason.INVALID_STATE);
        }
        String pending = vm.getPendingPowerAction();
        boolean supersedesReboot = "REBOOT".equals(pending) && action == VmPowerAction.FORCE_STOP;
        if (pending != null && !supersedesReboot) {
            return Judgement.refused(AdminBulkChangeReason.INVALID_STATE);
        }
        List<AdminBulkChangeFieldDiff> fields = new ArrayList<>();
        if (action == VmPowerAction.REBOOT) {
            fields.add(diff(objectMapper, "status", vm.getStatus(), "REBOOTING"));
        }
        fields.add(diff(objectMapper, "pendingPowerAction", pending, action.name()));
        return Judgement.change(fields, null);
    }

    private Judgement judgeDeletion(Vm vm, AdminBulkVmDeletionChange deletion, Instant now) {
        List<AdminBulkChangeFieldDiff> fields = new ArrayList<>();
        if (deletion.action() == AdminBulkVmDeletionAction.SCHEDULE) {
            if (vm.getDeleteKind() != null
                    || VmDeletionService.SCHEDULE_FORBIDDEN_STATUSES.contains(vm.getStatus())) {
                return Judgement.refused(AdminBulkChangeReason.INVALID_STATE);
            }
            if (deletionService.isDeletionProtected(vm.getId())) {
                return Judgement.refused(AdminBulkChangeReason.PROTECTED);
            }
            fields.add(diff(objectMapper, "deleteKind", null, VmDeleteKind.ADMIN));
            fields.add(diff(objectMapper, "deleteScheduledFor", null, deletion.scheduledFor()));
            return Judgement.change(fields, null);
        }
        if (vm.getDeleteKind() == null || !VmDeletionService.isCancelable(vm, now)) {
            return Judgement.refused(AdminBulkChangeReason.INVALID_STATE);
        }
        fields.add(diff(objectMapper, "deleteKind", vm.getDeleteKind(), null));
        fields.add(diff(objectMapper, "deleteScheduledFor", vm.getDeleteScheduledFor(), null));
        return Judgement.change(fields, null);
    }

    @Override
    @Nullable AdminBulkChangeReason write(AuthenticatedUser actor, Vm vm, Judgement judgement,
            AdminBulkChangeSpec change, UUID batchId, String ip) {
        return switch (change.kind()) {
            case VM_PERIOD -> {
                AdminBulkVmPeriodChange period = change.vmPeriod();
                LocalDate newEnd = Boolean.TRUE.equals(period.clearEndDate()) ? null
                        : period.endDate();
                yield reasonOf(periodService.changePeriod(actor, vm, vm.getStartDate(), newEnd,
                        batchId, ip));
            }
            case VM_POWER -> reasonOf(lifecycleService.adminPower(actor, vm,
                    change.vmPower().action(), batchId, ip));
            case VM_DELETION -> {
                AdminBulkVmDeletionChange deletion = change.vmDeletion();
                if (deletion.action() == AdminBulkVmDeletionAction.SCHEDULE) {
                    yield reasonOf(deletionService.scheduleAdminDeletion(actor, vm,
                            deletion.scheduledFor(), deletion.reason(), batchId, ip).refusal());
                }
                yield reasonOf(deletionService.cancelDeletion(actor, vm, batchId, ip));
            }
            default -> throw new IllegalStateException("not a VM kind: " + change.kind());
        };
    }

    /** The single path's refusal, as the reason code the bulk answer carries. */
    private static @Nullable AdminBulkChangeReason reasonOf(@Nullable ApiException refusal) {
        if (refusal == null) {
            return null;
        }
        return switch (refusal.getCode()) {
            case ErrorCodes.VM_EXPIRED -> AdminBulkChangeReason.EXPIRED;
            case ErrorCodes.VM_DELETION_PROTECTED -> AdminBulkChangeReason.PROTECTED;
            case ErrorCodes.VALIDATION_FAILED -> AdminBulkChangeReason.VALIDATION;
            default -> AdminBulkChangeReason.INVALID_STATE;
        };
    }
}
