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
class CreditModelPatternsTest {

    private List<String> normalize(List<String> input, List<FieldValidationError> errors) {
        return CreditModelPatterns.normalize(input, "creditAllowedModels", errors);
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
     * The comparison is lower-cased on both halves of the guard. A vendor name
     * arriving capitalized must survive, and a self-serving name arriving
     * capitalized under a tilde must still be caught — the two cases meet only
     * here, and the gateway's own guard pins the same pair.
     */
    @Test
    void lowercasesBeforeJudgingEitherHalf() {
        List<FieldValidationError> errors = new ArrayList<>();
        List<String> kept = normalize(List.of("~Anthropic/Claude-Sonnet-Latest"), errors);
        assertThat(errors).isEmpty();
        assertThat(kept).containsExactly("~anthropic/claude-sonnet-latest");

        List<FieldValidationError> reservedErrors = new ArrayList<>();
        normalize(List.of("~PICKLE-general"), reservedErrors);
        assertThat(reservedErrors).isNotEmpty();
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
        assertThat(CreditModelPatterns.isReserved("~pickle-general")).isTrue();
        assertThat(CreditModelPatterns.isReserved("~anthropic/claude-sonnet-latest")).isFalse();
    }

    /**
     * The cases are the gateway's own, transcribed. Two matchers that must
     * agree need one table of truths, and the screen that lists what a key may
     * call is only right while they do.
     */
    @Test
    void matchesTheSameNamesTheGatewayWould() {
        record Case(String pattern, String name, boolean want) { }
        List<Case> cases = List.of(
                new Case("openai/gpt-4o-mini", "openai/gpt-4o-mini", true),
                new Case("openai/gpt-4o-mini", "openai/gpt-4o", false),
                new Case("openai/*", "openai/gpt-4o", true),
                new Case("openai/*", "openai/a/b", true),
                new Case("openai/*", "anthropic/claude", false),
                // A vendor boundary, not a string prefix.
                new Case("openai/*", "openai-mirror/gpt-4o", false),
                // The half after the slash may not be empty.
                new Case("openai/*", "openai/", false),
                // "Everything" is spelled by an empty list, never by a star.
                new Case("*", "openai/gpt-4o", false),
                new Case("*", "*", false),
                new Case("", "openai/gpt-4o", false),
                new Case("some-model", "some-model", true),
                // A leading tilde is an ordinary character, so the two vendor
                // spellings stay separate prefixes.
                new Case("~anthropic/claude-sonnet-latest", "~anthropic/claude-sonnet-latest", true),
                new Case("~anthropic/*", "~anthropic/claude-sonnet-latest", true),
                new Case("anthropic/*", "~anthropic/claude-sonnet-latest", false),
                new Case("~anthropic/*", "anthropic/claude-sonnet-4", false),
                new Case("~", "~anthropic/claude", false),
                new Case("~/*", "~anthropic/claude", false));
        for (Case c : cases) {
            assertThat(CreditModelAllowlist.matches(c.pattern(), c.name()))
                    .describedAs("matches(%s, %s)", c.pattern(), c.name())
                    .isEqualTo(c.want());
        }
    }

    /**
     * The model name is lower-cased before comparing, so a vendor that
     * capitalises its own listing still matches a stored pattern.
     *
     * <p>Only the name side is asserted. An upper-case <em>pattern</em> also
     * matches in this method, but that is not the rule being transcribed and
     * must not be frozen as if it were: the gateway rejects a pattern of that
     * shape at load time and drops the whole key rather than matching with it.
     * What keeps the two sides from ever disagreeing on it is the next test.
     */
    @Test
    void comparesTheNameWithoutRegardToCase() {
        assertThat(CreditModelAllowlist.matches("openai/*", "OpenAI/GPT-4o")).isTrue();
        assertThat(CreditModelAllowlist.matches("openai/gpt-4o", "OpenAI/GPT-4o")).isTrue();
    }

    /**
     * Why the leniency above is harmless: a pattern this method would treat
     * differently from the gateway cannot be in storage to begin with. The two
     * mechanisms are different and only one of them is a refusal.
     *
     * <p>An upper-case pattern is not rejected, it is <em>folded</em> — the
     * write path lower-cases before it validates, so what lands in the column
     * is already lower-case and the gateway never sees the shape it would drop
     * a key over. A second slash is a genuine refusal by the format rule.
     * Stating this as "the constraint refuses upper case" would name the wrong
     * mechanism and would go stale the moment the folding moved.
     */
    @Test
    void aPatternTheGatewayWouldDropCannotReachStorage() {
        List<FieldValidationError> folded = new ArrayList<>();
        assertThat(normalize(List.of("OpenAI/GPT-4o"), folded))
                .containsExactly("openai/gpt-4o");
        assertThat(folded).isEmpty();

        List<FieldValidationError> refused = new ArrayList<>();
        normalize(List.of("a/b/*"), refused);
        assertThat(refused).isNotEmpty();
    }
}
