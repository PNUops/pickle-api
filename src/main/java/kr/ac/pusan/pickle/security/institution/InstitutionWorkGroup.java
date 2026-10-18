package kr.ac.pusan.pickle.security.institution;

import java.util.Arrays;
import java.util.Optional;

/** The five fixed groups that may carry a shared workspace scope. */
public enum InstitutionWorkGroup {
    VM, LLM, DOMAIN, GPU, WORKSPACE;

    public static Optional<InstitutionWorkGroup> fromId(String id) {
        return Arrays.stream(values()).filter(group -> group.name().equals(id)).findFirst();
    }
}
