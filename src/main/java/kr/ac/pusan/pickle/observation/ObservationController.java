package kr.ac.pusan.pickle.observation;

import io.swagger.v3.oas.annotations.Hidden;
import kr.ac.pusan.pickle.observation.dto.InferenceObservationAck;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Authentication is provided only by the dedicated observation chain. */
@Hidden
@RestController
public class ObservationController {
    private final ObservationIngestService service;
    public ObservationController(ObservationIngestService service) { this.service = service; }
    @PostMapping(value = "/internal/inference-observations", consumes = MediaType.APPLICATION_JSON_VALUE)
    public InferenceObservationAck ingest(@RequestBody byte[] body) { return service.ingest(body); }
}
