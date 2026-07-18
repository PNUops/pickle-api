package kr.ac.pusan.pickle.settings;

import static kr.ac.pusan.pickle.common.web.ClientIps.clientIp;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.settings.dto.SettingUpdateRequest;
import kr.ac.pusan.pickle.settings.dto.SettingView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code admin}, operational settings ({@code listSettings} /
 * {@code updateSetting}) — SYS_ADMIN only. Small reference list, returned as a
 * plain array (orgs/templates convention).
 */
@RestController
@RequestMapping("/api/v1/admin/settings")
@PreAuthorize("hasRole('SYS_ADMIN')")
public class AdminSettingsController {

    private final SettingsService settingsService;

    public AdminSettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SYS_ADMIN', 'SYS_MANAGER')")
    public List<SettingView> listSettings() {
        return settingsService.list();
    }

    @PutMapping("/{key}")
    public SettingView updateSetting(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String key,
            @RequestBody SettingUpdateRequest request,
            HttpServletRequest httpRequest) {
        return settingsService.update(principal, key,
                request != null ? request.value() : null, clientIp(httpRequest));
    }
}
