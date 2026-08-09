package kr.ac.pusan.pickle.resource;

import java.util.List;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.springframework.stereotype.Component;

/** The VM's answers to {@link ResourceTypeAdapter}. */
@Component
public class VmResourceAdapter implements ResourceTypeAdapter {

    private final VmRepository vmRepository;

    public VmResourceAdapter(VmRepository vmRepository) {
        this.vmRepository = vmRepository;
    }

    @Override
    public ResourceType type() {
        return ResourceType.VM;
    }

    @Override
    public List<Long> idsOwnedByWorkspace(long workspaceId) {
        return vmRepository.findIdsByWorkspaceIdIn(List.of(workspaceId));
    }
}
