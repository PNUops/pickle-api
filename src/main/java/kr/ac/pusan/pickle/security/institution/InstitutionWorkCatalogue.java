package kr.ac.pusan.pickle.security.institution;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

/** FULL's global trust is a curated list; adding a global enum never grants it automatically. */
public final class InstitutionWorkCatalogue {
    public static final String REVISION = "2";

    private static final Set<InstitutionWork> FULL_GLOBAL_RESOURCE_READ = Set.of(InstitutionWork.LLM_KEY_COST_READ);

    private static final Set<InstitutionWork> FULL_GLOBAL_WORK = Set.of(
            InstitutionWork.VM_REQUEST_SUPPORT_READ, InstitutionWork.VM_METADATA_SUPPORT_READ,
            InstitutionWork.VM_ACL_SUPPORT_READ, InstitutionWork.VM_ACL_SUPPORT_MANAGE,
            InstitutionWork.LLM_REQUEST_SUPPORT_READ, InstitutionWork.LLM_METADATA_SUPPORT_READ,
            InstitutionWork.LLM_ACL_SUPPORT_READ, InstitutionWork.LLM_ACL_SUPPORT_MANAGE,
            InstitutionWork.DOMAIN_REQUEST_SUPPORT_READ, InstitutionWork.DOMAIN_METADATA_SUPPORT_READ,
            InstitutionWork.DOMAIN_ACL_SUPPORT_READ, InstitutionWork.DOMAIN_ACL_SUPPORT_MANAGE,
            InstitutionWork.GPU_REQUEST_SUPPORT_READ, InstitutionWork.GPU_METADATA_SUPPORT_READ,
            InstitutionWork.GPU_ACL_SUPPORT_READ, InstitutionWork.GPU_ACL_SUPPORT_MANAGE,
            InstitutionWork.WORKSPACE_SUPPORT_READ, InstitutionWork.WORKSPACE_INFO_UPDATE,
            InstitutionWork.WORKSPACE_MEMBERS_MANAGE, InstitutionWork.WORKSPACE_INVITATIONS_MANAGE,
            InstitutionWork.WORKSPACE_OWNERS_MANAGE, InstitutionWork.WORKSPACE_TERMINATE,
            InstitutionWork.WORKSPACE_RECIPIENT_CANCEL, InstitutionWork.WORKSPACE_ANNOUNCEMENT_SEND,
            InstitutionWork.STUDENT_LOOKUP, InstitutionWork.STUDENT_SUPPORT_READ,
            InstitutionWork.STUDENT_PROFILE_CORRECT, InstitutionWork.NOTICE_READ, InstitutionWork.NOTICE_MANAGE,
            InstitutionWork.SHARED_CERTIFICATE_READ, InstitutionWork.ROOT_DOMAIN_CATALOGUE_READ);

    public static boolean includedInFull(InstitutionWork work) {
        return switch (work.boundary()) {
            case RESOURCE, INSTITUTION -> true;
            case RESOURCE_SUPPORT, WORKSPACE_SUPPORT, QUALIFIED_WORKSPACE, GLOBAL -> FULL_GLOBAL_WORK.contains(work);
        };
    }

    /** Resource reads whose institution boundary is global only for actual FULL sources. */
    public static boolean isGlobalResourceReadInFull(InstitutionWork work) {
        return FULL_GLOBAL_RESOURCE_READ.contains(work);
    }

    public static Set<InstitutionWork> selectableWork() {
        return Arrays.stream(InstitutionWork.values()).filter(InstitutionWork::selectable).collect(Collectors.toUnmodifiableSet());
    }

    /** Every catalogue input that changes authority also changes confirmation context. */
    static String descriptor() {
        return REVISION + "\n" + Arrays.stream(InstitutionWork.values())
                .sorted(Comparator.comparing(InstitutionWork::id))
                .map(work -> work.id() + ":" + work.group() + ":" + work.boundary()
                        + ":" + work.write() + ":" + work.selectable() + ":" + includedInFull(work)
                        + ":" + isGlobalResourceReadInFull(work))
                .collect(Collectors.joining("\n"));
    }

    private InstitutionWorkCatalogue() { }
}
