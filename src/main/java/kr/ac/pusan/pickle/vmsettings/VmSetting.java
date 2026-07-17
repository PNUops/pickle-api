package kr.ac.pusan.pickle.vmsettings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A per-VM setting override row (V29). Present only when the setting differs
 * from its registry default; the stored {@code value} is JSON typed per the
 * registry. The registry ({@link VmSettingsService}) owns type/default/role.
 */
@Entity
@Table(name = "vm_settings")
public class VmSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vm_id", nullable = false)
    private Long vmId;

    @Column(name = "key", nullable = false)
    private String key;

    @Column(columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String value;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected VmSetting() {
    }

    public VmSetting(Long vmId, String key, String value, Long updatedBy, Instant updatedAt) {
        this.vmId = vmId;
        this.key = key;
        this.value = value;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    public Long getVmId() {
        return vmId;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void apply(String value, Long updatedBy, Instant updatedAt) {
        this.value = value;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }
}
