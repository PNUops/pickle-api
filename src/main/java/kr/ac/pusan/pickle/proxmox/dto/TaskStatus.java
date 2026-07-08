package kr.ac.pusan.pickle.proxmox.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code GET /nodes/{n}/tasks/{upid}/status} response (fields we use).
 * {@code exitstatus} is only present once {@code status} is {@code stopped};
 * {@code "OK"} means success, anything else is the failure reason (e.g.
 * "VM quit/powerdown failed - got timeout").
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskStatus(String status, String exitstatus, String upid) {

    public static final String STATUS_STOPPED = "stopped";
    public static final String EXITSTATUS_OK = "OK";

    public boolean isStopped() {
        return STATUS_STOPPED.equals(status);
    }

    public boolean isOk() {
        return isStopped() && EXITSTATUS_OK.equals(exitstatus);
    }
}
