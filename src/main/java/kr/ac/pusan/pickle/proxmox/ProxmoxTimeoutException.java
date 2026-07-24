package kr.ac.pusan.pickle.proxmox;

/**
 * Waiting for a Proxmox task (UPID) exceeded the polling deadline: the task
 * is still running as far as we know — callers decide whether to keep
 * waiting, compensate, or park the work as NEEDS_ADMIN.
 */
public class ProxmoxTimeoutException extends RuntimeException {

    public ProxmoxTimeoutException(String message) {
        super(message);
    }

    public ProxmoxTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
