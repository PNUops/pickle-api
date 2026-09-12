package kr.ac.pusan.pickle.gpu;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GpuReadinessConfiguration {
    @Bean @ConditionalOnMissingBean(GpuGuestReadiness.class)
    GpuGuestReadiness gpuGuestReadiness() {
        return vm -> "가상머신의 GPU 드라이버 준비 상태를 아직 확인할 수 없습니다. 관리자에게 문의해 주세요.";
    }
    @Bean @ConditionalOnMissingBean(GpuMigrationReadiness.class)
    GpuMigrationReadiness gpuMigrationReadiness() {
        return (vm, destination) -> vm.getNodeId() == destination ? null
                : "다른 노드의 가상머신을 이동하는 기능이 준비되지 않았습니다. 현재 가상머신은 변경되지 않습니다.";
    }
}
