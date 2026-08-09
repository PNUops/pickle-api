package kr.ac.pusan.pickle.access;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import kr.ac.pusan.pickle.access.dto.AddVmAccessGrantRequest;
import kr.ac.pusan.pickle.access.dto.UpdateVmAccessGrantRequest;
import kr.ac.pusan.pickle.access.dto.VmAccessGrantView;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.RequireReauth;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Contract tag {@code vm-access}: who may reach one VM, and at what rung. */
@Tag(name = "vm-access", description = "VM 접근 권한 — 이 VM에 누가 접근할 수 있는지를 정합니다.")
@RestController
@RequestMapping("/api/v1/vms/{vmId}/access")
public class VmAccessGrantController {

    private final VmAccessGrantService service;

    public VmAccessGrantController(VmAccessGrantService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "접근 권한 목록",
            description = "이 VM의 접근 권한 전체입니다. VM 소유자와 그룹 소유자만 볼 수 있습니다.")
    public List<VmAccessGrantView> listVmAccessGrants(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long vmId) {
        return service.list(principal, vmId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequireReauth
    @Operation(summary = "접근 권한 부여",
            description = "지정한 사용자 또는 소유 그룹 전체에 이 VM의 접근 권한을 부여합니다. "
                    + "사용자는 이 VM을 소유한 그룹의 구성원이어야 하고, 그룹 전체에는 "
                    + "참여자·열람자까지만 부여할 수 있습니다.")
    public VmAccessGrantView addVmAccessGrant(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long vmId,
            @Valid @RequestBody AddVmAccessGrantRequest request,
            HttpServletRequest httpRequest) {
        return service.add(principal, vmId, request, clientIp(httpRequest));
    }

    @PatchMapping("/{grantId}")
    @RequireReauth
    @Operation(summary = "접근 권한 등급 변경")
    public VmAccessGrantView updateVmAccessGrant(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long vmId,
            @PathVariable long grantId,
            @Valid @RequestBody UpdateVmAccessGrantRequest request,
            HttpServletRequest httpRequest) {
        return service.update(principal, vmId, grantId, request, clientIp(httpRequest));
    }

    @DeleteMapping("/{grantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequireReauth
    @Operation(summary = "접근 권한 회수",
            description = "회수해도 이미 열람한 초기 비밀번호와 이미 열려 있는 SSH 세션은 회수되지 "
                    + "않습니다. 필요하면 비밀번호를 재생성해 주세요.")
    public void removeVmAccessGrant(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long vmId,
            @PathVariable long grantId,
            HttpServletRequest httpRequest) {
        service.remove(principal, vmId, grantId, clientIp(httpRequest));
    }
}
