package kr.ac.pusan.pickle.networkpolicy;

import kr.ac.pusan.pickle.networkpolicy.dto.SourcePolicyPresetView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only source-policy editor presets; values are saved as explicit snapshots. */
@RestController
@RequestMapping("/api/v1/source-policy-presets")
public class SourcePolicyPresetController {

    private final PublicSourcePolicyService sourcePolicies;

    public SourcePolicyPresetController(PublicSourcePolicyService sourcePolicies) {
        this.sourcePolicies = sourcePolicies;
    }

    @GetMapping("/campus")
    @io.swagger.v3.oas.annotations.Operation(operationId = "getCampusSourcePolicyPreset",
            summary = "교내 출발지 preset 조회")
    public SourcePolicyPresetView getCampusSourcePolicyPreset(
            @RequestParam SourcePolicyTarget target) {
        return sourcePolicies.campusPreset(target);
    }
}
