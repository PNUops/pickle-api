package kr.ac.pusan.pickle.proxmox;

/**
 * A Proxmox task (UPID) reached {@code stopped} with a non-OK
 * {@code exitstatus} (e.g. "VM quit/powerdown failed - got timeout",
 * "VM 102 already running"). The HTTP call itself succeeded — this is the
 * asynchronous task reporting failure.
 */
public class ProxmoxTaskFailedException extends RuntimeException {

    private final String upid;
    private final String exitstatus;

    public ProxmoxTaskFailedException(String upid, String exitstatus) {
        super("Proxmox task failed (exitstatus: " + exitstatus + "): " + upid);
        this.upid = upid;
        this.exitstatus = exitstatus;
    }

    public String upid() {
        return upid;
    }

    public String exitstatus() {
        return exitstatus;
    }
}
