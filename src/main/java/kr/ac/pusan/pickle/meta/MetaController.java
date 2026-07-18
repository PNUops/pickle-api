package kr.ac.pusan.pickle.meta;

import kr.ac.pusan.pickle.settings.SettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code reference}: GET /meta/request-options — operator-tunable
 * choices and validation lists for the VM request form — and GET /meta/status
 * (M6), the public system status (maintenance mode, banner, contact email).
 */
@RestController
@RequestMapping("/api/v1/meta")
public class MetaController {

    private final SettingsService settingsService;
    private final SystemStatusService systemStatusService;

    public MetaController(SettingsService settingsService,
            SystemStatusService systemStatusService) {
        this.settingsService = settingsService;
        this.systemStatusService = systemStatusService;
    }

    @GetMapping("/request-options")
    public RequestOptionsResponse requestOptions() {
        return new RequestOptionsResponse(
                settingsService.stringList(SettingsService.ALLOWED_ROOT_DOMAINS),
                settingsService.stringList(SettingsService.RESERVED_SUBDOMAINS));
    }

    /** Public (contract {@code security: []}): polled by the login page and shell. */
    @GetMapping("/status")
    public SystemStatusResponse systemStatus() {
        return systemStatusService.current();
    }
}
