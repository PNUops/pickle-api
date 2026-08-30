package kr.ac.pusan.pickle.admin;

import java.util.List;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.LlmKeyContext;
import kr.ac.pusan.pickle.admin.dto.ApprovalContextResponse.VmContext;
import kr.ac.pusan.pickle.request.Request;
import org.jspecify.annotations.Nullable;

/** Resource-specific decision support attached to an approval context. */
public interface ApprovalContextContributor {

    ResourceType type();

    Contribution contribute(Request request, List<Long> applicantWorkspaceIds);

    record Contribution(@Nullable VmContext vm, @Nullable LlmKeyContext llmKey) {
        public static Contribution vm(VmContext context) {
            return new Contribution(context, null);
        }

        public static Contribution llmKey(LlmKeyContext context) {
            return new Contribution(null, context);
        }
    }
}
