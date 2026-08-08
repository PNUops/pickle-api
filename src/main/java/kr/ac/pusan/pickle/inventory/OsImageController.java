package kr.ac.pusan.pickle.inventory;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import kr.ac.pusan.pickle.inventory.dto.OsImageResponse;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code reference}: GET /os-images — active presets for the request wizard.
 *
 * <p>The tag name is pinned because the generated spec would otherwise derive it
 * from this class name, which would move it in step with an internal rename.</p>
 */
@RestController
@RequestMapping("/api/v1/os-images")
@Tag(name = "os-image-controller")
public class OsImageController {

    private final OsImageRepository osImageRepository;

    public OsImageController(OsImageRepository osImageRepository) {
        this.osImageRepository = osImageRepository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<OsImageResponse> listOsImages() {
        return osImageRepository.findByStatusOrderByIdAsc(CatalogStatus.ACTIVE).stream()
                .map(OsImageResponse::from)
                .toList();
    }
}
