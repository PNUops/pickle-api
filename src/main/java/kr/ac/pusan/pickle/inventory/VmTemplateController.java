package kr.ac.pusan.pickle.inventory;

import java.util.List;
import kr.ac.pusan.pickle.inventory.dto.VmTemplateResponse;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Contract tag {@code reference}: GET /templates — active presets for the request wizard. */
@RestController
@RequestMapping("/api/v1/templates")
public class VmTemplateController {

    private final VmTemplateRepository vmTemplateRepository;

    public VmTemplateController(VmTemplateRepository vmTemplateRepository) {
        this.vmTemplateRepository = vmTemplateRepository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<VmTemplateResponse> listTemplates() {
        return vmTemplateRepository.findByStatusOrderByIdAsc(TemplateStatus.ACTIVE).stream()
                .map(VmTemplateResponse::from)
                .toList();
    }
}
