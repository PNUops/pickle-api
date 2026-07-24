package kr.ac.pusan.pickle.provisioning;

import kr.ac.pusan.pickle.proxmox.dto.ClusterResource;
import kr.ac.pusan.pickle.vm.Vm;

/**
 * Destroy-target verification: a vmid alone is not proof
 * of identity — Proxmox recycles vmids, and destroying whatever currently
 * sits at the number could kill a foreign guest. A guest counts as ours when
 * its name equals the VM's hostname (the clone step names it that) or it
 * carries the {@code pickle} tag (the config step sets it). Shared by the
 * delete pipeline, provisioning compensation and the drift reconciler.
 */
final class ManagedGuestIdentity {

    /** Tag marking a Proxmox guest as pickle-managed (set by the config step). */
    static final String MANAGED_TAG = "pickle";

    private ManagedGuestIdentity() {
    }

    /** Is the guest at this resource plausibly the given VM? */
    static boolean matches(Vm vm, ClusterResource resource) {
        return (vm.getHostname() != null && vm.getHostname().equals(resource.name()))
                || hasManagedTag(resource.tags());
    }

    /** True when the semicolon/comma-separated PVE tag list contains {@code pickle}. */
    static boolean hasManagedTag(String tags) {
        if (tags == null || tags.isBlank()) {
            return false;
        }
        for (String tag : tags.split("[;,]")) {
            if (MANAGED_TAG.equalsIgnoreCase(tag.trim())) {
                return true;
            }
        }
        return false;
    }
}
