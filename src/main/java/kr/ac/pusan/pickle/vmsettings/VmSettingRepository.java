package kr.ac.pusan.pickle.vmsettings;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VmSettingRepository extends JpaRepository<VmSetting, Long> {

    List<VmSetting> findByVmId(Long vmId);

    Optional<VmSetting> findByVmIdAndKey(Long vmId, String key);
}
