package kr.ac.pusan.pickle.observation;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Dedicated peer and reporter authentication is provided by its own filter chain. */
@Hidden
@RestController
public class PlatformHardwareController {
    private final PlatformHardwareObservationService service;
    public PlatformHardwareController(PlatformHardwareObservationService service) { this.service = service; }
    @PostMapping(value = "/internal/platform-hardware-observations", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PlatformHardwareObservationService.PlatformHardwareAck ingest(@RequestBody byte[] body) {
        return service.ingest(body);
    }
}
