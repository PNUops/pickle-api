package kr.ac.pusan.pickle.vmsettings;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VmSettingRepository extends JpaRepository<VmSetting, Long> {

    List<VmSetting> findByVmId(Long vmId);

    Optional<VmSetting> findByVmIdAndKey(Long vmId, String key);

    /** Batch load one key across many VMs (list views — avoids N+1 on display_name). */
    List<VmSetting> findByKeyAndVmIdIn(String key, Collection<Long> vmIds);
}
