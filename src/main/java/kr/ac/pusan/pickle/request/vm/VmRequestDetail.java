package kr.ac.pusan.pickle.request.vm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * What a VM request asks for, and what the reviewer granted.
 *
 * <p>Keyed by the request it belongs to rather than by an id of its own: there
 * is exactly one of these per VM request, and the pairing is the point.
 * The granted period is deliberately not here — every resource type has a
 * period, so it stays on the review row with the rest of the decision.
 */
@Entity
@Table(name = "vm_request_details")
public class VmRequestDetail {

    @Id
    @Column(name = "request_id")
    private Long requestId;

    @Column(name = "image_id", nullable = false)
    private Long imageId;

    /** Chosen spec preset (V58 axis split) — provenance + specReason baseline. */
    @Column(name = "flavor_id")
    private Long flavorId;

    @Column(name = "req_vcpu", nullable = false)
    private int reqVcpu;

    @Column(name = "req_memory_mb", nullable = false)
    private int reqMemoryMb;

    @Column(name = "req_disk_gb", nullable = false)
    private int reqDiskGb;

    @Column(name = "spec_reason")
    private String specReason;

    /** Requester-desired hostname/slug (v0.12.0); null = auto-generate at approval. */
    @Column(name = "desired_slug")
    private String desiredSlug;

    /**
     * Historical only: the request form stopped carrying a domain axis in
     * contract v0.29.0 (domains are attached to the VM afterwards), so new
     * rows store null. The columns stay because past requests recorded a
     * wish here and the detail view still shows it when present.
     */
    @Column(name = "desired_subdomain")
    private String desiredSubdomain;

    /** Historical only — see {@link #desiredSubdomain}. */
    @Column(name = "root_domain")
    private String rootDomain;

    @Column(name = "granted_vcpu")
    private Integer grantedVcpu;

    @Column(name = "granted_memory_mb")
    private Integer grantedMemoryMb;

    @Column(name = "granted_disk_gb")
    private Integer grantedDiskGb;

    @Column(name = "granted_image_id")
    private Long grantedImageId;

    /** Reviewer-forced node; null leaves placement to the pipeline. */
    @Column(name = "node_id")
    private Long nodeId;

    protected VmRequestDetail() {
    }

    public VmRequestDetail(Long requestId, Long imageId, Long flavorId, int reqVcpu, int reqMemoryMb,
            int reqDiskGb, String specReason, String desiredSlug) {
        this.requestId = requestId;
        this.imageId = imageId;
        this.flavorId = flavorId;
        this.reqVcpu = reqVcpu;
        this.reqMemoryMb = reqMemoryMb;
        this.reqDiskGb = reqDiskGb;
        this.specReason = specReason;
        this.desiredSlug = desiredSlug;
    }

    /** Records what the reviewer granted, at approval time. */
    public void grant(Integer vcpu, Integer memoryMb, Integer diskGb, Long imageId, Long nodeId) {
        this.grantedVcpu = vcpu;
        this.grantedMemoryMb = memoryMb;
        this.grantedDiskGb = diskGb;
        this.grantedImageId = imageId;
        this.nodeId = nodeId;
    }

    public Long getRequestId() {
        return requestId;
    }

    public Long getImageId() {
        return imageId;
    }

    public Long getFlavorId() {
        return flavorId;
    }

    public int getReqVcpu() {
        return reqVcpu;
    }

    public int getReqMemoryMb() {
        return reqMemoryMb;
    }

    public int getReqDiskGb() {
        return reqDiskGb;
    }

    public String getSpecReason() {
        return specReason;
    }

    public String getDesiredSlug() {
        return desiredSlug;
    }

    public String getDesiredSubdomain() {
        return desiredSubdomain;
    }

    public String getRootDomain() {
        return rootDomain;
    }

    public Integer getGrantedVcpu() {
        return grantedVcpu;
    }

    public Integer getGrantedMemoryMb() {
        return grantedMemoryMb;
    }

    public Integer getGrantedDiskGb() {
        return grantedDiskGb;
    }

    public Long getGrantedImageId() {
        return grantedImageId;
    }

    public Long getNodeId() {
        return nodeId;
    }
}
