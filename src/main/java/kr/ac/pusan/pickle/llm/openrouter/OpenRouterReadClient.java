package kr.ac.pusan.pickle.llm.openrouter;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The provider operations permitted to an observation-only polling path. */
public interface OpenRouterReadClient {

    OpenRouterClient.Credits credits(String managementSecret);

    List<OpenRouterClient.ManagedKey> listKeys(String managementSecret,
            @Nullable UUID workspaceId);
}
