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
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
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
    private final AuditService auditService;

    public GroupService(GroupRepository groupRepository, GroupMemberRepository groupMemberRepository,
            UserRepository userRepository, AuditService auditService) {
        this.groupRepository = groupRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
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
        if (groupRepository.existsBySlug(request.slug())) {
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
        auditService.record(actor.id(), actor.role().name(), AuditService.GROUP_CREATE,
                "group", group.getId(),
                Map.of("kind", group.getKind().name(), "name", group.getName(), "slug", group.getSlug()), ip);
        return toDetail(group);
    }

    @Transactional(readOnly = true)
    public GroupDetailResponse get(AuthenticatedUser actor, long groupId) {
        Group group = findGroup(groupId);
        groupMemberRepository.findByGroupIdAndUserId(groupId, actor.id())
                .orElseThrow(() -> accessDenied("그룹 멤버만 조회할 수 있습니다."));
        return toDetail(group);
    }

    @Transactional
    public GroupDetailResponse update(AuthenticatedUser actor, long groupId, UpdateGroupRequest request) {
        Group group = findGroup(groupId);
        GroupMember membership = groupMemberRepository.findByGroupIdAndUserId(groupId, actor.id())
                .orElseThrow(() -> accessDenied("그룹 OWNER만 수정할 수 있습니다."));
        if (membership.getRole() != GroupMemberRole.OWNER) {
            throw accessDenied("그룹 OWNER만 수정할 수 있습니다.");
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
        return toDetail(group);
    }

    @Transactional
    public GroupMemberResponse addMember(AuthenticatedUser actor, long groupId,
            AddGroupMemberRequest request, String ip) {
        Group group = findGroup(groupId);
        requireOwnerForMemberManagement(group, actor, "그룹 OWNER만 멤버를 추가할 수 있습니다.",
                "PERSONAL 그룹에는 멤버를 추가할 수 없습니다.");
        if (request.role() == GroupMemberRole.OWNER) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("role",
                    "OWNER 역할은 멤버 추가로 부여할 수 없습니다. 역할 변경(소유권 이전)을 사용해 주세요.")));
        }

        User target = userRepository.findByEmail(Texts.normalizeEmail(request.email()))
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCodes.GROUP_MEMBER_USER_NOT_FOUND, "사용자를 찾을 수 없습니다",
                        "해당 이메일로 가입된 사용자가 없습니다. 가입 후 다시 시도해 주세요."));
        if (groupMemberRepository.findByGroupIdAndUserId(groupId, target.getId()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.GROUP_MEMBER_ALREADY_EXISTS,
                    "이미 그룹 멤버입니다", "해당 사용자는 이미 이 그룹의 멤버입니다.");
        }

        GroupMember member;
        try {
            member = groupMemberRepository.save(new GroupMember(group, target.getId(), request.role()));
        } catch (DataIntegrityViolationException raceWithConcurrentAdd) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.GROUP_MEMBER_ALREADY_EXISTS,
                    "이미 그룹 멤버입니다", "해당 사용자는 이미 이 그룹의 멤버입니다.");
        }
        auditService.record(actor.id(), actor.role().name(), AuditService.GROUP_MEMBER_ADD,
                "group", groupId,
                Map.of("userId", target.getId(), "email", target.getEmail(), "role", member.getRole().name()), ip);
        return GroupMemberResponse.from(member, target);
    }

    @Transactional
    public GroupMemberResponse updateMemberRole(AuthenticatedUser actor, long groupId, long targetUserId,
            UpdateGroupMemberRequest request, String ip) {
        Group group = findGroup(groupId);
        GroupMember actorMembership = requireOwnerForMemberManagement(group, actor,
                "그룹 OWNER만 역할을 변경할 수 있습니다.", "PERSONAL 그룹의 멤버 구성은 변경할 수 없습니다.");
        GroupMember target = groupMemberRepository.findByGroupIdAndUserId(groupId, targetUserId)
                .orElseThrow(GroupService::memberNotFound);

        GroupMemberRole previousRole = target.getRole();
        boolean ownershipTransfer = false;
        if (request.role() == GroupMemberRole.OWNER) {
            if (targetUserId != actor.id()) {
                // Ownership transfer: the previous OWNER steps down to MANAGER.
                target.setRole(GroupMemberRole.OWNER);
                actorMembership.setRole(GroupMemberRole.MANAGER);
                ownershipTransfer = true;
            }
        } else {
            if (targetUserId == actor.id()) {
                // The (sole) OWNER cannot demote themselves; transfer first.
                throw soleOwnerRemoval("유일한 소유자의 역할은 변경할 수 없습니다");
            }
            target.setRole(request.role());
        }

        // Load the response projection before the REQUIRES_NEW audit write so a
        // failure here cannot leave an audit row for a rolled-back change.
        User targetUser = userRepository.findById(targetUserId).orElseThrow(GroupService::memberNotFound);
        auditService.record(actor.id(), actor.role().name(), AuditService.GROUP_MEMBER_UPDATE,
                "group", groupId,
                Map.of("userId", targetUserId, "previousRole", previousRole.name(),
                        "role", target.getRole().name(), "ownershipTransfer", ownershipTransfer), ip);
        return GroupMemberResponse.from(target, targetUser);
    }

    @Transactional
    public void removeMember(AuthenticatedUser actor, long groupId, long targetUserId, String ip) {
        Group group = findGroup(groupId);
        if (group.getKind() == GroupKind.PERSONAL) {
            throw memberManageForbidden("멤버를 관리할 권한이 없습니다", "PERSONAL 그룹의 멤버 구성은 변경할 수 없습니다.");
        }
        GroupMember actorMembership = groupMemberRepository.findWithLockByGroupIdAndUserId(groupId, actor.id())
                .orElseThrow(() -> memberManageForbidden("멤버를 제거할 권한이 없습니다",
                        "그룹 OWNER만 다른 멤버를 제거할 수 있습니다."));
        boolean selfLeave = targetUserId == actor.id();
        if (!selfLeave && actorMembership.getRole() != GroupMemberRole.OWNER) {
            throw memberManageForbidden("멤버를 제거할 권한이 없습니다", "그룹 OWNER만 다른 멤버를 제거할 수 있습니다.");
        }

        GroupMember target = groupMemberRepository.findByGroupIdAndUserId(groupId, targetUserId)
                .orElseThrow(GroupService::memberNotFound);
        if (target.getRole() == GroupMemberRole.OWNER
                && groupMemberRepository.countByGroupIdAndRole(groupId, GroupMemberRole.OWNER) <= 1) {
            throw soleOwnerRemoval("유일한 소유자는 나갈 수 없습니다");
        }

        groupMemberRepository.delete(target);
        auditService.record(actor.id(), actor.role().name(), AuditService.GROUP_MEMBER_REMOVE,
                "group", groupId,
                Map.of("userId", targetUserId, "previousRole", target.getRole().name(),
                        "selfLeave", selfLeave), ip);
    }

    /**
     * Member-management gate (add/role-change): actor must be OWNER and the
     * group must not be PERSONAL — both violations render 403
     * {@code GROUP_MEMBER_MANAGE_FORBIDDEN} per contract.
     */
    private GroupMember requireOwnerForMemberManagement(Group group, AuthenticatedUser actor,
            String notOwnerDetail, String personalDetail) {
        if (group.getKind() == GroupKind.PERSONAL) {
            throw memberManageForbidden("멤버를 관리할 권한이 없습니다", personalDetail);
        }
        return groupMemberRepository.findWithLockByGroupIdAndUserId(group.getId(), actor.id())
                .filter(membership -> membership.getRole() == GroupMemberRole.OWNER)
                .orElseThrow(() -> memberManageForbidden("멤버를 관리할 권한이 없습니다", notOwnerDetail));
    }

    private GroupDetailResponse toDetail(Group group) {
        List<GroupMember> members = groupMemberRepository.findByGroupIdOrderByIdAsc(group.getId());
        Map<Long, User> users = userRepository
                .findAllById(members.stream().map(GroupMember::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return GroupDetailResponse.from(group, members.stream()
                .map(member -> GroupMemberResponse.from(member, users.get(member.getUserId())))
                .toList());
    }

    private Group findGroup(long groupId) {
        return groupRepository.findById(groupId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                        "리소스를 찾을 수 없습니다", "해당 그룹이 존재하지 않습니다."));
    }

    private static String normalize(String description) {
        return Texts.blankToNull(description);
    }

    private static ApiException memberNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 사용자는 이 그룹의 멤버가 아닙니다.");
    }

    private static ApiException accessDenied(String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED, "접근 권한이 없습니다", detail);
    }

    private static ApiException memberManageForbidden(String title, String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.GROUP_MEMBER_MANAGE_FORBIDDEN, title, detail);
    }

    private static ApiException soleOwnerRemoval(String title) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.GROUP_SOLE_OWNER_REMOVAL, title,
                "소유권을 다른 멤버에게 이전한 뒤 다시 시도해 주세요.");
    }

    private static ApiException slugDuplicate(String slug) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.GROUP_SLUG_DUPLICATE,
                "이미 사용 중인 slug입니다", "'" + slug + "'은(는) 이미 다른 그룹이 사용 중입니다.");
    }
}
