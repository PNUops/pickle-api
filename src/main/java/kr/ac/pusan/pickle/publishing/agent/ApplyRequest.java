package kr.ac.pusan.pickle.publishing.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Full desired state for one FQDN (the proxy-agent control contract, {@code POST
 * /apply}). For {@code ABSENT} the target/cert fields are omitted (null →
 * dropped from the JSON body).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplyRequest(
        String fqdn,
        DesiredState desiredState,
        long generation,
        String targetIp,
        Integer targetPort,
        String certRef) {

    public static ApplyRequest present(String fqdn, long generation, String targetIp,
            int targetPort, String certRef) {
        return new ApplyRequest(fqdn, DesiredState.PRESENT, generation, targetIp, targetPort, certRef);
    }

    public static ApplyRequest absent(String fqdn, long generation) {
        return new ApplyRequest(fqdn, DesiredState.ABSENT, generation, null, null, null);
    }
}
