package kr.ac.pusan.pickle.vm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Read-side mapping used by the VM's atomic power-admission predicates. */
@Entity
@Immutable
@Table(name = "vm_power_dispatches")
public class VmPowerDispatch {
    @Id
    private UUID id;
    @Column(name = "vm_id", nullable = false)
    private Long vmId;
    @Column(nullable = false)
    private boolean terminal;
    protected VmPowerDispatch() {}
}
