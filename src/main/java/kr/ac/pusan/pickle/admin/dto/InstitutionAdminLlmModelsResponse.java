package kr.ac.pusan.pickle.admin.dto;

import java.util.List;
import kr.ac.pusan.pickle.llm.dto.LlmKeyModelsResponse;
import kr.ac.pusan.pickle.llm.dto.LlmKeyModelsResponse.PaidModels;
import org.jspecify.annotations.Nullable;

/** Candidate models with monetary access evidence independently projected. */
public record InstitutionAdminLlmModelsResponse(List<LlmKeyModelsResponse.SelfServedModel> selfServed,
        @Nullable PaidModels paid, boolean financeVisible) {
    public static InstitutionAdminLlmModelsResponse of(LlmKeyModelsResponse value, boolean finance) {
        return new InstitutionAdminLlmModelsResponse(value.selfServed(), finance ? value.paid() : null, finance);
    }
}
