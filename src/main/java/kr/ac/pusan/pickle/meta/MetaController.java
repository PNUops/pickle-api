package kr.ac.pusan.pickle.meta;

import kr.ac.pusan.pickle.settings.SettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code reference}: GET /meta/request-options — operator-tunable
 * choices and validation lists for the VM request form, read from settings.
 */
@RestController
@RequestMapping("/api/v1/meta")
public class MetaController {

    private final SettingsService settingsService;

    public MetaController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping("/request-options")
    public RequestOptionsResponse requestOptions() {
        return new RequestOptionsResponse(
                settingsService.stringList(SettingsService.ALLOWED_ROOT_DOMAINS),
                settingsService.stringList(SettingsService.RESERVED_SUBDOMAINS));
    }
}
