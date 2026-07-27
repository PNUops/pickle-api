package kr.ac.pusan.pickle.vm;

import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Org-scope resolution shared by the admin surfaces that act on a single VM
 * (period update, gateway block, detail/events, power intervention): the org
 * tier sees only its own org's VMs, and cross-org targets answer the same 404
 * as unknown ids so the existence of other orgs' VMs stays private.
 */
@Component
public class AdminVmAccess {

    private final VmRepository vmRepository;

    public AdminVmAccess(VmRepository vmRepository) {
        this.vmRepository = vmRepository;
    }

    public Vm requireOrgScopedVm(AuthenticatedUser actor, long vmId) {
        Vm vm = vmRepository.findById(vmId).orElseThrow(AdminVmAccess::vmNotFound);
        if (actor.role().isOrgTier() && !vm.getOrgId().equals(actor.orgId())) {
            throw vmNotFound();
        }
        return vm;
    }

    private static ApiException vmNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 VM이 존재하지 않습니다.");
    }
}
