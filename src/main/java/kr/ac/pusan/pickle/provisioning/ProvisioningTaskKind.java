package kr.ac.pusan.pickle.provisioning;

/** What a {@code provisioning_tasks} row does to its VM (docs/plan/02). */
public enum ProvisioningTaskKind {
    PROVISION,
    DELETE,
    REINSTALL
}
