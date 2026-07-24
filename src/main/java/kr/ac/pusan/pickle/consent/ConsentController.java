package kr.ac.pusan.pickle.consent;

import jakarta.validation.Valid;
import java.util.List;
import kr.ac.pusan.pickle.consent.dto.ConsentUpdateRequest;
import kr.ac.pusan.pickle.consent.dto.ConsentView;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Contract tag {@code me}: consent history and re-consent. */
@RestController
@RequestMapping("/api/v1/me/consents")
public class ConsentController {

    private final TermsService termsService;

    public ConsentController(TermsService termsService) {
        this.termsService = termsService;
    }

    @GetMapping
    public List<ConsentView> listMyConsents(@AuthenticationPrincipal AuthenticatedUser principal) {
        return termsService.listConsents(principal.id());
    }

    @PostMapping
    public List<ConsentView> acceptConsents(@AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody ConsentUpdateRequest request) {
        return termsService.acceptConsents(principal.id(), request.consents());
    }
}
