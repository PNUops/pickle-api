package kr.ac.pusan.pickle.meta;

import java.util.List;
import kr.ac.pusan.pickle.consent.TermsDocType;
import kr.ac.pusan.pickle.consent.TermsService;
import kr.ac.pusan.pickle.consent.dto.TermsDocumentView;
import kr.ac.pusan.pickle.consent.dto.TermsVersionView;
import kr.ac.pusan.pickle.settings.SettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code reference}: GET /meta/request-options (authed) and the
 * public terms endpoints (GET /meta/terms, /meta/terms/{docType}).
 */
@RestController
@RequestMapping("/api/v1/meta")
public class MetaController {

    private final SettingsService settingsService;
    private final TermsService termsService;

    public MetaController(SettingsService settingsService, TermsService termsService) {
        this.settingsService = settingsService;
        this.termsService = termsService;
    }

    @GetMapping("/request-options")
    public RequestOptionsResponse requestOptions() {
        return new RequestOptionsResponse(
                settingsService.stringList(SettingsService.ALLOWED_ROOT_DOMAINS),
                settingsService.stringList(SettingsService.RESERVED_SUBDOMAINS));
    }

    /** Public: current version metadata of every consent document. */
    @GetMapping("/terms")
    public List<TermsVersionView> listTerms() {
        return termsService.currentVersions();
    }

    /** Public: full current body (markdown) of one document. */
    @GetMapping("/terms/{docType}")
    public TermsDocumentView getTerms(@PathVariable TermsDocType docType) {
        return termsService.currentDocument(docType);
    }
}
