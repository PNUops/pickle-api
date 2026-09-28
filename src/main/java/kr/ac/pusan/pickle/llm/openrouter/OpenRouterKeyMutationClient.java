package kr.ac.pusan.pickle.llm.openrouter;

import java.math.BigDecimal;
import java.util.UUID;
import kr.ac.pusan.pickle.llm.CreditLimitReset;
import org.jspecify.annotations.Nullable;

/** Provider key mutations used by ordinary reconciliation, never direct polling. */
public interface OpenRouterKeyMutationClient {

    void setDisabled(String managementSecret, @Nullable UUID workspaceId,
            String hash, boolean disabled);

    void updateLimit(String managementSecret, @Nullable UUID workspaceId,
            String hash, BigDecimal limit, @Nullable CreditLimitReset reset);
}
