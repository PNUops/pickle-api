package kr.ac.pusan.pickle.provisioning;

/**
 * The eleven ordered steps of the provision pipeline.
 * {@code index} is what {@code provisioning_tasks.current_step} stores;
 * {@code label} is the Korean step name the contract exposes as
 * {@code ProvisioningTaskView.stepLabel}.
 */
public enum ProvisioningStep {

    GUARD(0, "준비 확인 중"),
    PLACE(1, "노드 배치 중"),
    ALLOC_IP(2, "IP 할당 중"),
    VMID(3, "VMID 발급 중"),
    CLONE(4, "OS 이미지 복제 중"),
    CONFIG(5, "VM 설정 중"),
    RESIZE(6, "디스크 크기 조정 중"),
    START(7, "VM 시작 중"),
    VERIFY(8, "네트워크 확인 중"),
    HOSTKEY(9, "호스트 키 수집 중"),
    FINALIZE(10, "마무리 중");

    public static final int TOTAL_STEPS = values().length;

    private final int index;
    private final String label;

    ProvisioningStep(int index, String label) {
        this.index = index;
        this.label = label;
    }

    public int index() {
        return index;
    }

    public String label() {
        return label;
    }

    /** The step for a {@code current_step} value; clamped for robustness. */
    public static ProvisioningStep of(int index) {
        ProvisioningStep[] steps = values();
        if (index < 0 || index >= steps.length) {
            return index < 0 ? GUARD : FINALIZE;
        }
        return steps[index];
    }
}
