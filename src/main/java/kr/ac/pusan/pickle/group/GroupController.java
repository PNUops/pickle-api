package kr.ac.pusan.pickle.group;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import kr.ac.pusan.pickle.group.dto.AddGroupMemberRequest;
import kr.ac.pusan.pickle.group.dto.CreateGroupRequest;
import kr.ac.pusan.pickle.group.dto.GroupDetailResponse;
import kr.ac.pusan.pickle.group.dto.GroupMemberResponse;
import kr.ac.pusan.pickle.group.dto.GroupSummaryResponse;
import kr.ac.pusan.pickle.group.dto.UpdateGroupMemberRequest;
import kr.ac.pusan.pickle.group.dto.UpdateGroupRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Contract tag {@code groups} (openapi.yaml v0.2.1, server /api/v1). */
@RestController
@RequestMapping("/api/v1/groups")
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    @GetMapping
    public List<GroupSummaryResponse> listGroups(@AuthenticationPrincipal AuthenticatedUser principal) {
        return groupService.listMyGroups(principal);
    }

    @PostMapping
    public ResponseEntity<GroupDetailResponse> createGroup(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateGroupRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(groupService.create(principal, request, clientIp(httpRequest)));
    }

    @GetMapping("/{groupId}")
    public GroupDetailResponse getGroup(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long groupId) {
        return groupService.get(principal, groupId);
    }

    @PatchMapping("/{groupId}")
    public GroupDetailResponse updateGroup(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long groupId,
            @Valid @RequestBody UpdateGroupRequest request) {
        return groupService.update(principal, groupId, request);
    }

    @PostMapping("/{groupId}/members")
    public ResponseEntity<GroupMemberResponse> addGroupMember(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long groupId,
            @Valid @RequestBody AddGroupMemberRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(groupService.addMember(principal, groupId, request, clientIp(httpRequest)));
    }

    @PatchMapping("/{groupId}/members/{userId}")
    public GroupMemberResponse updateGroupMember(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long groupId,
            @PathVariable long userId,
            @Valid @RequestBody UpdateGroupMemberRequest request,
            HttpServletRequest httpRequest) {
        return groupService.updateMemberRole(principal, groupId, userId, request, clientIp(httpRequest));
    }

    @DeleteMapping("/{groupId}/members/{userId}")
    public ResponseEntity<Void> removeGroupMember(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long groupId,
            @PathVariable long userId,
            HttpServletRequest httpRequest) {
        groupService.removeMember(principal, groupId, userId, clientIp(httpRequest));
        return ResponseEntity.noContent().build();
    }
}
