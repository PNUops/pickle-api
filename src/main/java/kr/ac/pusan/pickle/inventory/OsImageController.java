package kr.ac.pusan.pickle.inventory;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import kr.ac.pusan.pickle.inventory.dto.VmTemplateResponse;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code reference}: GET /templates — active presets for the request wizard.
 *
 * <p>The tag name is pinned because the generated spec would otherwise derive it
 * from this class name, which would move it in step with an internal rename.</p>
 */
@RestController
@RequestMapping("/api/v1/templates")
@Tag(name = "vm-template-controller")
public class OsImageController {

    private final OsImageRepository osImageRepository;

    public OsImageController(OsImageRepository osImageRepository) {
        this.osImageRepository = osImageRepository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<VmTemplateResponse> listTemplates() {
        return osImageRepository.findByStatusOrderByIdAsc(TemplateStatus.ACTIVE).stream()
                .map(VmTemplateResponse::from)
                .toList();
    }
}
