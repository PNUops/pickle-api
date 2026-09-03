package kr.ac.pusan.pickle.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import org.junit.jupiter.api.Test;

/**
 * The money fence's format rule lives in four places — here, the CHECK the
 * migrations install, the gateway's loader, and the console's input helper —
 * so the rule is pinned at the one of them the api writes through.
 */
class CreditModelAllowlistTest {

    private List<String> normalize(List<String> input, List<FieldValidationError> errors) {
        return CreditModelAllowlist.normalize(input, "creditAllowedModels", errors);
    }

    /**
     * The vendor's floating aliases resolve to the newest model of a family and
     * route through passthrough already, so a fence that cannot spell them
     * leaves a restricted key narrower than an unrestricted one.
     */
    @Test
    void keepsFloatingVendorAliases() {
        List<FieldValidationError> errors = new ArrayList<>();
        List<String> kept = normalize(
                List.of("~anthropic/claude-sonnet-latest", "~openai/*", "openai/gpt-4o-mini"),
                errors);

        assertThat(errors).isEmpty();
        assertThat(kept).containsExactly(
                "~anthropic/claude-sonnet-latest", "~openai/*", "openai/gpt-4o-mini");
    }

    /**
     * The tilde is a prefix on a real name, not a name. Admitting the leading
     * position must not admit the shapes the rule already refused.
     */
    @Test
    void refusesTheTildeOnItsOwn() {
        for (String bad : List.of("~", "~*", "~/gpt-4o", "~/*", "*", "~~openai/gpt-4o")) {
            List<FieldValidationError> errors = new ArrayList<>();
            normalize(List.of(bad), errors);
            assertThat(errors)
                    .describedAs("entry %s must be refused", bad)
                    .isNotEmpty();
        }
    }

    /**
     * The reserved guard compares after stripping the tilde. Without that,
     * widening the pattern would let a self-serving name into a list that may
     * hold commercial names only, past the guard on one character.
     */
    @Test
    void refusesSelfServingNamesWearingATilde() {
        for (String reserved : List.of("~pickle-general", "~pnu-general",
                "pickle-general", "pnu-general")) {
            List<FieldValidationError> errors = new ArrayList<>();
            normalize(List.of(reserved), errors);
            assertThat(errors)
                    .describedAs("reserved name %s must be refused", reserved)
                    .isNotEmpty();
        }
        assertThat(CreditModelAllowlist.isReserved("~pickle-general")).isTrue();
        assertThat(CreditModelAllowlist.isReserved("~anthropic/claude-sonnet-latest")).isFalse();
    }
}
