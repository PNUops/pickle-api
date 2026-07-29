package kr.ac.pusan.pickle.relay;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import kr.ac.pusan.pickle.relay.dto.RelaySyncRequest;
import kr.ac.pusan.pickle.relay.dto.RelaySyncResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Relay agent sync endpoint (outside {@code /api/v1}, hidden from the public
 * contract). Auth lives entirely in {@link RelayAuthFilter} on the dedicated
 * {@code /internal/relays/**} chain: by the time this handler runs, the caller
 * IS the relay whose id is in the path (source pin + per-relay token binding),
 * so no principal is consulted here.
 */
@Hidden
@RestController
@RequestMapping("/internal/relays")
public class RelaySyncController {

    private final RelaySyncService relaySyncService;

    public RelaySyncController(RelaySyncService relaySyncService) {
        this.relaySyncService = relaySyncService;
    }

    @PostMapping("/{relayId}/sync")
    public RelaySyncResponse sync(@PathVariable long relayId,
            @Valid @RequestBody RelaySyncRequest request) {
        return relaySyncService.sync(relayId, request);
    }
}
