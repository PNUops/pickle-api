package kr.ac.pusan.pickle.group;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.common.text.Texts;
import kr.ac.pusan.pickle.group.dto.AddGroupMemberRequest;
import kr.ac.pusan.pickle.group.dto.CreateGroupRequest;
import kr.ac.pusan.pickle.group.dto.GroupDetailResponse;
import kr.ac.pusan.pickle.group.dto.GroupMemberResponse;
import kr.ac.pusan.pickle.group.dto.GroupSummaryResponse;
import kr.ac.pusan.pickle.group.dto.UpdateGroupMemberRequest;
import kr.ac.pusan.pickle.group.dto.UpdateGroupRequest;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import kr.ac.pusan.pickle.vmrequest.VmRequest;
import kr.ac.pusan.pickle.vmrequest.VmRequestRepository;
import kr.ac.pusan.pickle.vmrequest.VmRequestStatus;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TEAM/PROJECT group management (contract tag {@code groups}). Authorization
 * is resolved in this layer from a single membership row per request
 * (docs/plan/07): OWNER edits group info, manages members and transfers
 * ownership; PERSONAL groups have immutable membership.
 */
@Service
public class GroupService {

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final VmRepository vmRepository;
    private final VmRequestRepository vmRequestRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public GroupService(GroupRepository groupRepository, GroupMemberRepository groupMemberRepository,
            UserRepository userRepository, VmRepository vmRepository,
            VmRequestRepository vmRequestRepository, AuditService auditService,
            NotificationService notificationService) {
        this.groupRepository = groupRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.userRepository = userRepository;
        this.vmRepository = vmRepository;
        this.vmRequestRepository = vmRequestRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    @Transactional(readOnly = true)
    public List<GroupSummaryResponse> listMyGroups(AuthenticatedUser actor) {
        List<GroupMember> memberships = groupMemberRepository.findWithGroupByUserId(actor.id());
        if (memberships.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> counts = groupMemberRepository
                .countMembersByGroupIdIn(memberships.stream().map(m -> m.getGroup().getId()).toList())
                .stream()
                .collect(Collectors.toMap(GroupMemberRepository.GroupMemberCount::getGroupId,
                        GroupMemberRepository.GroupMemberCount::getMemberCount));
        return memberships.stream()
                .map(m -> GroupSummaryResponse.from(m, counts.getOrDefault(m.getGroup().getId(), 1L)))
                .toList();
    }

    @Transactional
    public GroupDetailResponse create(AuthenticatedUser actor, CreateGroupRequest request, String ip) {
        if (request.kind() == GroupKind.PERSONAL) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("kind",
                    "PERSONAL 그룹은 자동 생성됩니다. TEAM 또는 PROJECT만 생성할 수 있습니다.")));
        }
        if (groupRepository.existsBySlugAndDeletedAtIsNull(request.slug())) {
            throw slugDuplicate(request.slug());
        }
        Group group;
        try {
            group = groupRepository.save(new Group(request.kind(), request.name().strip(),
                    request.slug(), normalize(request.description())));
        } catch (DataIntegrityViolationException raceWithConcurrentCreate) {
            throw slugDuplicate(request.slug());
        }
        groupMemberRepository.save(new GroupMember(group, actor.id(), GroupMemberRole.OWNER));
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.GROUP_CREATE,
                "group", group.getId(),
                Map.of("kind", group.getKind().name(), "name", group.getName(), "slug", group.getSlug()), ip);
        return toDetail(group, GroupMemberRole.OWNER);
    }

    @Transactional(readOnly = true)
    public GroupDetailResponse get(AuthenticatedUser actor, long groupId) {
        Group group = findGroup(groupId);
        GroupMember membership = groupMemberRepository.findByGroupIdAndUserId(groupId, actor.id())
                .orElseThrow(() -> accessDenied("그룹 구성원만 조회할 수 있습니다."));
        return toDetail(group, membership.getRole());
    }

    @Transactional
    public GroupDetailResponse update(AuthenticatedUser actor, long groupId, UpdateGroupRequest request) {
        Group group = findGroup(groupId);
        GroupMember membership = groupMemberRepository.findByGroupIdAndUserId(groupId, actor.id())
                .orElseThrow(() -> accessDenied("그룹 소유자(OWNER)만 수정할 수 있습니다."));
        if (membership.getRole() != GroupMemberRole.OWNER) {
            throw accessDenied("그룹 소유자(OWNER)만 수정할 수 있습니다.");
        }
        if (request.isEmpty()) {
            throw ApiException.validationFailed(List.of(
                    new FieldValidationError("name", "수정할 값을 하나 이상 보내 주세요.")));
        }
        if (request.isNameSet()) {
            if (request.getName() == null || request.getName().isBlank()) {
                throw ApiException.validationFailed(List.of(
                        new FieldValidationError("name", "그룹 이름은 비울 수 없습니다.")));
            }
            group.setName(request.getName().strip());
        }
        if (request.isDescriptionSet()) {
            group.setDescription(normalize(request.getDescription()));
        }
        return toDetail(group, membership.getRole());
    }

    @Transactional
    public GroupMemberResponse addMember(AuthenticatedUser actor, long groupId,
            AddGroupMemberRequest request, String ip) {
        Group group = findGroup(groupId);
        requireOwnerForMemberManagement(group, actor, "그룹 소유자(OWNER)만 구성원을 추가할 수 있습니다.",
                "PERSONAL 그룹에는 구성원을 추가할 수 없습니다.");
        if (request.role() == GroupMemberRole.OWNER) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("role",
                    "OWNER 역할은 구성원 추가로 부여할 수 없습니다. 역할 변경(소유권 이전)을 사용해 주세요.")));
        }

        User target = userRepository.findByEmail(Texts.normalizeEmail(request.email()))
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCodes.GROUP_MEMBER_USER_NOT_FOUND, "사용자를 찾을 수 없습니다",
                        "해당 이메일로 가입된 사용자가 없습니다. 가입 후 다시 시도해 주세요."));
        if (groupMemberRepository.findByGroupIdAndUserId(groupId, target.getId()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.GROUP_MEMBER_ALREADY_EXISTS,
                    "이미 그룹 구성원입니다", "해당 사용자는 이미 이 그룹의 구성원입니다.");
        }

        GroupMember member;
        try {
            member = groupMemberRepository.save(new GroupMember(group, target.getId(), request.role()));
        } catch (DataIntegrityViolationException raceWithConcurrentAdd) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.GROUP_MEMBER_ALREADY_EXISTS,
                    "이미 그룹 구성원입니다", "해당 사용자는 이미 이 그룹의 구성원입니다.");
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.GROUP_MEMBER_ADD,
                "group", groupId,
                Map.of("userId", target.getId(), "email", target.getEmail(), "role", member.getRole().name()), ip);
        return GroupMemberResponse.from(member, target);
    }

    @Transactional
    public GroupMemberResponse updateMemberRole(AuthenticatedUser actor, long groupId, long targetUserId,
            UpdateGroupMemberRequest request, String ip) {
        Group group = findGroup(groupId);
        GroupMember actorMembership = requireOwnerForMemberManagement(group, actor,
                "그룹 소유자(OWNER)만 역할을 변경할 수 있습니다.", "PERSONAL 그룹의 구성원은 변경할 수 없습니다.");
        GroupMember target = groupMemberRepository.findByGroupIdAndUserId(groupId, targetUserId)
                .orElseThrow(GroupService::memberNotFound);

        GroupMemberRole previousRole = target.getRole();
        boolean ownershipTransfer = false;
        if (request.role() == GroupMemberRole.OWNER) {
            if (targetUserId != actor.id()) {
                // Ownership transfer: the previous OWNER steps down to EDITOR.
                target.setRole(GroupMemberRole.OWNER);
                actorMembership.setRole(GroupMemberRole.EDITOR);
                ownershipTransfer = true;
            }
        } else {
            if (targetUserId == actor.id()) {
                // The (sole) OWNER cannot demote themselves; transfer first.
                throw soleOwnerRemoval("유일한 소유자의 역할은 변경할 수 없습니다");
            }
            target.setRole(request.role());
        }

        // The audit is deferred to after commit (recordAfterCommit), so a
        // failure anywhere in this method leaves no audit row for a change that
        // never committed.
        User targetUser = userRepository.findById(targetUserId).orElseThrow(GroupService::memberNotFound);
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.GROUP_MEMBER_UPDATE,
                "group", groupId,
                Map.of("userId", targetUserId, "previousRole", previousRole.name(),
                        "role", target.getRole().name(), "ownershipTransfer", ownershipTransfer), ip);
        return GroupMemberResponse.from(target, targetUser);
    }

    @Transactional
    public void removeMember(AuthenticatedUser actor, long groupId, long targetUserId, String ip) {
        Group group = findGroup(groupId);
        if (group.getKind() == GroupKind.PERSONAL) {
            throw memberManageForbidden("구성원을 관리할 권한이 없습니다", "PERSONAL 그룹의 구성원은 변경할 수 없습니다.");
        }
        GroupMember actorMembership = groupMemberRepository.findWithLockByGroupIdAndUserId(groupId, actor.id())
                .orElseThrow(() -> memberManageForbidden("구성원을 제거할 권한이 없습니다",
                        "그룹 소유자(OWNER)만 다른 구성원을 제거할 수 있습니다."));
        boolean selfLeave = targetUserId == actor.id();
        if (!selfLeave && actorMembership.getRole() != GroupMemberRole.OWNER) {
            throw memberManageForbidden("구성원을 제거할 권한이 없습니다", "그룹 소유자(OWNER)만 다른 구성원을 제거할 수 있습니다.");
        }

        GroupMember target = groupMemberRepository.findByGroupIdAndUserId(groupId, targetUserId)
                .orElseThrow(GroupService::memberNotFound);
        if (target.getRole() == GroupMemberRole.OWNER
                && groupMemberRepository.countByGroupIdAndRole(groupId, GroupMemberRole.OWNER) <= 1) {
            throw soleOwnerRemoval("유일한 소유자는 나갈 수 없습니다");
        }

        groupMemberRepository.delete(target);
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.GROUP_MEMBER_REMOVE,
                "group", groupId,
                Map.of("userId", targetUserId, "previousRole", target.getRole().name(),
                        "selfLeave", selfLeave), ip);
    }

    /**
     * Soft-deletes a group (contract {@code deleteGroup}, M6). OWNER only;
     * non-members are masked as 404 and members below OWNER get 403. PERSONAL
     * groups are never deletable (409), and a group with any non-destroyed VM
     * (DELETED excluded, DELETING counts as blocking — shared
     * {@link VmRepository#countActiveByGroupId}) is refused (409). The row is
     * kept (VM/audit history) with {@code deleted_at} stamped; ACTIVE members
     * are notified and the deletion is audited.
     */
    @Transactional
    public void delete(AuthenticatedUser actor, long groupId, String ip) {
        Group group = groupRepository.findByIdAndDeletedAtIsNull(groupId)
                .orElseThrow(GroupService::groupNotFound);
        GroupMember membership = groupMemberRepository.findByGroupIdAndUserId(groupId, actor.id())
                .orElseThrow(GroupService::groupNotFound); // non-member: mask existence
        if (membership.getRole() != GroupMemberRole.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.GROUP_ROLE_INSUFFICIENT,
                    "그룹을 삭제할 권한이 없습니다", "그룹의 OWNER만 그룹을 삭제할 수 있습니다.");
        }
        if (group.getKind() == GroupKind.PERSONAL) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.GROUP_PERSONAL_UNDELETABLE,
                    "그룹을 삭제할 수 없습니다",
                    "개인 그룹은 삭제할 수 없습니다. 계정 탈퇴 시에만 함께 정리됩니다.");
        }
        if (vmRepository.countActiveByGroupId(groupId, VmStatus.DELETED) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.GROUP_HAS_ACTIVE_VMS,
                    "그룹을 삭제할 수 없습니다",
                    "그룹에 삭제되지 않은 VM이 있습니다. VM 삭제(파기 완료) 후 다시 시도해 주세요.");
        }

        // Cancel the group's in-flight (SUBMITTED) VM requests in this tx so an
        // approval racing the delete can't provision into a dead group. Each
        // request is locked and re-checked (same guard the cancel/approve paths
        // use) — a request the approver already decided is left alone, and its
        // approval that lost the row lock hits the existing SUBMITTED check
        // (409 REQUEST_ALREADY_DECIDED) once this delete commits.
        for (VmRequest pending : vmRequestRepository
                .findByGroupIdAndStatus(groupId, VmRequestStatus.SUBMITTED)) {
            VmRequest locked = vmRequestRepository.findWithLockById(pending.getId()).orElse(null);
            if (locked == null || locked.getStatus() != VmRequestStatus.SUBMITTED) {
                continue;
            }
            locked.setStatus(VmRequestStatus.CANCELED);
            auditService.recordAfterCommit(actor.id(), actor.role().name(),
                    AuditService.REQUEST_CANCEL, "vm_request", locked.getId(),
                    Map.of("groupId", groupId, "reason", "group_deleted"), ip);
        }

        // Recipients are resolved before the soft-delete flips visibility; the
        // membership rows themselves are kept (only the group row is stamped).
        List<Long> recipients = notificationService.groupMemberIds(groupId);
        group.softDelete(actor.id(), Instant.now());
        auditService.recordAfterCommit(actor.id(), actor.role().name(), AuditService.GROUP_DELETE,
                "group", groupId, Map.of("kind", group.getKind().name(), "name", group.getName(),
                        "slug", group.getSlug()), ip);
        notificationService.publish(recipients, NotificationEvent.GROUP_DELETED,
                Map.of("groupId", groupId, "groupName", group.getName()), "group_deleted:" + groupId);
    }

    /**
     * Member-management gate (add/role-change): actor must be OWNER and the
     * group must not be PERSONAL — both violations render 403
     * {@code GROUP_MEMBER_MANAGE_FORBIDDEN} per contract.
     */
    private GroupMember requireOwnerForMemberManagement(Group group, AuthenticatedUser actor,
            String notOwnerDetail, String personalDetail) {
        if (group.getKind() == GroupKind.PERSONAL) {
            throw memberManageForbidden("구성원을 관리할 권한이 없습니다", personalDetail);
        }
        return groupMemberRepository.findWithLockByGroupIdAndUserId(group.getId(), actor.id())
                .filter(membership -> membership.getRole() == GroupMemberRole.OWNER)
                .orElseThrow(() -> memberManageForbidden("구성원을 관리할 권한이 없습니다", notOwnerDetail));
    }

    private GroupDetailResponse toDetail(Group group, GroupMemberRole myRole) {
        List<GroupMember> members = groupMemberRepository.findByGroupIdOrderByIdAsc(group.getId());
        Map<Long, User> users = userRepository
                .findAllById(members.stream().map(GroupMember::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return GroupDetailResponse.from(group, myRole, members.stream()
                .map(member -> GroupMemberResponse.from(member, users.get(member.getUserId())))
                .toList());
    }

    /** All read/manage paths exclude soft-deleted groups — a deleted group answers 404. */
    private Group findGroup(long groupId) {
        return groupRepository.findByIdAndDeletedAtIsNull(groupId)
                .orElseThrow(GroupService::groupNotFound);
    }

    private static ApiException groupNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 그룹이 존재하지 않습니다.");
    }

    private static String normalize(String description) {
        return Texts.blankToNull(description);
    }

    private static ApiException memberNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 사용자는 이 그룹의 구성원이 아닙니다.");
    }

    private static ApiException accessDenied(String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED, "접근 권한이 없습니다", detail);
    }

    private static ApiException memberManageForbidden(String title, String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.GROUP_MEMBER_MANAGE_FORBIDDEN, title, detail);
    }

    private static ApiException soleOwnerRemoval(String title) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.GROUP_SOLE_OWNER_REMOVAL, title,
                "소유권을 다른 구성원에게 이전한 뒤 다시 시도해 주세요.");
    }

    private static ApiException slugDuplicate(String slug) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.GROUP_SLUG_DUPLICATE,
                "이미 사용 중인 slug입니다", "'" + slug + "'은(는) 이미 다른 그룹이 사용 중입니다.");
    }
}
