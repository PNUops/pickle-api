package kr.ac.pusan.pickle.gpu;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** A successful config write is not evidence that the device is attached. */
public record GpuDeviceState(String status, Map<String, Object> config, List<Map<String, Object>> pending) {
    public GpuDeviceState {
        if (status == null || !List.of("running", "stopped").contains(status) || config == null || pending == null) {
            throw new IllegalStateException("가상머신의 실제 상태를 확인할 수 없습니다.");
        }
    }
    public boolean stopped() { return "stopped".equals(status); }
    public boolean hasPendingDeviceChange() {
        return pending.stream().anyMatch(row -> String.valueOf(row.get("key")).startsWith("hostpci")
                && (row.containsKey("pending") || "1".equals(String.valueOf(row.get("delete")))));
    }
    public boolean absent(Gpu gpu) { return !config.containsKey(gpu.hostpciSlot()); }
    public boolean attached(Gpu gpu) {
        Object value = config.get(gpu.hostpciSlot());
        return value != null && Arrays.stream(value.toString().split(","))
                .anyMatch(part -> part.equals("mapping=" + gpu.mappingName()));
    }
    public void requireKnown(Gpu gpu) {
        boolean movedMapping = config.entrySet().stream().anyMatch(entry -> entry.getKey().startsWith("hostpci")
                && !entry.getKey().equals(gpu.hostpciSlot()) && entry.getValue() != null
                && Arrays.stream(entry.getValue().toString().split(",")).anyMatch(part -> part.equals("mapping=" + gpu.mappingName())));
        if (movedMapping) { throw new IllegalStateException("GPU가 예상하지 않은 PCI 슬롯에 연결되어 있습니다."); }
        if (config.get("lock") != null || hasPendingDeviceChange() || (!absent(gpu) && !attached(gpu))) {
            throw new IllegalStateException("GPU 실제 연결과 대기 설정이 일치하지 않습니다.");
        }
    }
}
