package kr.ac.pusan.pickle.inventory;

import java.util.List;
import kr.ac.pusan.pickle.inventory.dto.VmFlavorResponse;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Contract tag {@code reference}: GET /vm-flavors — active spec presets for the request wizard. */
@RestController
@RequestMapping("/api/v1/vm-flavors")
public class VmFlavorController {

    private final VmFlavorRepository vmFlavorRepository;

    public VmFlavorController(VmFlavorRepository vmFlavorRepository) {
        this.vmFlavorRepository = vmFlavorRepository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<VmFlavorResponse> listVmFlavors() {
        return vmFlavorRepository.findByStatusInDisplayOrder(CatalogStatus.ACTIVE).stream()
                .map(VmFlavorResponse::from)
                .toList();
    }
}
