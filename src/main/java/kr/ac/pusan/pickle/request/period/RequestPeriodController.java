package kr.ac.pusan.pickle.request.period;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.inventory.CatalogStatus;
import kr.ac.pusan.pickle.request.period.dto.RequestPeriodResponse;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract tag {@code reference}: GET /request-periods — the usage periods the
 * request form offers.
 *
 * <p>The tag name is pinned because the generated spec would otherwise derive
 * it from this class name, which would move it in step with an internal
 * rename.</p>
 */
@RestController
@RequestMapping("/api/v1/request-periods")
@Tag(name = "request-period-controller")
public class RequestPeriodController {

    private final RequestPeriodPresetRepository repository;
    private final Clock clock;

    public RequestPeriodController(RequestPeriodPresetRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Periods a request submitted today could still sit inside.
     *
     * <p>A row whose end date has passed is filtered out rather than shown
     * disabled: the dates are absolute, so last term's row is not a choice
     * anybody could make, and an operator who forgets to add this term's row
     * should see an empty list rather than a list of expired ones.</p>
     */
    @GetMapping
    @Operation(summary = "신청 가능한 사용 기간 목록",
            description = "관리자가 등록한 기간 중 오늘 기준으로 아직 끝나지 않은 것만 돌려줍니다."
                    + " 모든 항목이 종료일을 가집니다. 끝나지 않는 기간은 신청 본문의"
                    + " reqIndefinite로 요청합니다.")
    @Transactional(readOnly = true)
    public List<RequestPeriodResponse> listRequestPeriods() {
        // KST 달력 날짜 — 종료일은 KST 자정까지 유효하다는 계약과 같은 기준이다.
        LocalDate today = ClockConfig.todayKst(clock);
        return repository.findByStatusInDisplayOrder(CatalogStatus.ACTIVE).stream()
                .filter(preset -> preset.isOfferableOn(today))
                .map(RequestPeriodResponse::from)
                .toList();
    }
}
