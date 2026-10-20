package kr.ac.pusan.pickle.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * The passthrough vocabulary, and the one place its rule differs from the model
 * lists it sits beside.
 */
class PassthroughEndpointsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void normalizesCaseAndWhitespaceAndKeepsTypedOrderWithoutDuplicates() {
        List<FieldValidationError> errors = new ArrayList<>();
        List<String> normalized = PassthroughEndpoints.normalize(
                List.of(" Images ", "EMBEDDINGS", "images"), "endpoints", errors);
        assertThat(errors).isEmpty();
        assertThat(normalized).containsExactly("images", "embeddings");
    }

    /**
     * An unknown token is an error rather than a silent drop, and the direction
     * is the reason. Dropping it would save a form that grants less than the
     * approver read on the screen.
     */
    @Test
    void refusesATokenOutsideTheVocabulary() {
        List<FieldValidationError> errors = new ArrayList<>();
        List<String> normalized = PassthroughEndpoints.normalize(
                List.of("image"), "endpoints", errors);
        assertThat(normalized).isEmpty();
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst().field()).isEqualTo("endpoints[0]");
        // The message names what is available, because "unknown" alone does not
        // tell somebody who mistyped one character what to type instead.
        assertThat(errors.getFirst().message()).startsWith("알 수 없는 기능입니다.")
                .contains("embeddings").contains("images");
    }

    @Test
    void nullAndEmptyAndBlankEntriesAllSpellTheEmptyList() {
        List<FieldValidationError> errors = new ArrayList<>();
        assertThat(PassthroughEndpoints.normalize(null, "endpoints", errors)).isEmpty();
        assertThat(PassthroughEndpoints.normalize(List.of(), "endpoints", errors)).isEmpty();
        assertThat(PassthroughEndpoints.normalize(List.of("  "), "endpoints", errors)).isEmpty();
        assertThat(errors).isEmpty();
        assertThat(PassthroughEndpoints.toJson(mapper, List.of()))
                .isEqualTo(PassthroughEndpoints.EMPTY_JSON);
    }

    /**
     * Reading back is lenient the same way {@link CreditModelPatterns} is, and
     * that leniency costs nothing here. An unreadable list closes every
     * passthrough path for the key, which grants nothing and is visible to its
     * owner immediately. The same leniency on a deny list reopens exactly the
     * models somebody refused.
     */
    @Test
    void anUnreadableStoredValueReadsAsNoneRatherThanAsEverything() {
        assertThat(PassthroughEndpoints.fromJson(mapper, "{\"images\":true}", "k")).isEmpty();
        assertThat(PassthroughEndpoints.fromJson(mapper, "not json", "k")).isEmpty();
        assertThat(PassthroughEndpoints.fromJson(mapper, null, "k")).isEmpty();
    }

    /**
     * A token this build does not know is a permission it cannot enforce, so it
     * is dropped on the way out rather than carried into the gateway document
     * where it would describe a fence neither side applies.
     */
    @Test
    void dropsAnUnknownStoredTokenAndKeepsTheRest() {
        assertThat(PassthroughEndpoints.fromJson(mapper, "[\"images\",\"audio\"]", "k"))
                .containsExactly("images");
    }

    /**
     * A name in the vocabulary that the gateway does not serve yet is refused
     * with its own wording, so somebody who typed it correctly is not told it
     * does not exist.
     */
    @Test
    void refusesAKnownNameThatCannotBeGrantedYet() {
        List<FieldValidationError> errors = new ArrayList<>();
        assertThat(PassthroughEndpoints.normalize(List.of("chat", "videos"), "endpoints", errors))
                .containsExactly("chat");
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst().field()).isEqualTo("endpoints[1]");
        assertThat(errors.getFirst().message()).startsWith("아직 부여할 수 없는 기능입니다.")
                .contains("chat, web_search, pdf_ocr, images, embeddings, rerank");
    }

    /** A feature of chat means nothing without chat. */
    @Test
    void aWholeGrantRefusesAChatFeatureWithoutChat() {
        List<FieldValidationError> errors = new ArrayList<>();
        assertThat(PassthroughEndpoints.normalizeGrant(List.of("images", "web_search"),
                "endpoints", errors)).isEmpty();
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst().field()).isEqualTo("endpoints");

        errors.clear();
        assertThat(PassthroughEndpoints.normalizeGrant(List.of("pdf_ocr", "chat"),
                "endpoints", errors)).containsExactly("pdf_ocr", "chat");
        assertThat(errors).isEmpty();
    }

    /**
     * Entries alone carry no hierarchy, because a bulk ADD of web_search is
     * judged before the key it lands on is known.
     */
    @Test
    void entriesAloneDoNotCheckTheHierarchy() {
        List<FieldValidationError> errors = new ArrayList<>();
        assertThat(PassthroughEndpoints.normalize(List.of("web_search"), "endpoints", errors))
                .containsExactly("web_search");
        assertThat(errors).isEmpty();
    }

    /** Chat alone is not a money change; anything else beside it is. */
    @Test
    void withoutChatComparesTheRestAsASet() {
        assertThat(PassthroughEndpoints.withoutChat(List.of("chat", "images")))
                .isEqualTo(PassthroughEndpoints.withoutChat(List.of("images")));
        assertThat(PassthroughEndpoints.withoutChat(List.of("embeddings", "images")))
                .isEqualTo(PassthroughEndpoints.withoutChat(List.of("images", "embeddings")));
        assertThat(PassthroughEndpoints.withoutChat(List.of("chat")))
                .isNotEqualTo(PassthroughEndpoints.withoutChat(List.of("images")));
    }

    /**
     * A stored name is read back if it is in the vocabulary, granted yet or
     * not, so widening what may be granted never needs a rewrite of rows.
     */
    @Test
    void readsBackEveryNameInTheVocabulary() {
        assertThat(PassthroughEndpoints.fromJson(mapper, "[\"chat\",\"videos\"]", "k"))
                .containsExactly("chat", "videos");
    }

    @Test
    void roundTripsThroughTheStoredForm() {
        List<String> value = List.of("images", "embeddings");
        assertThat(PassthroughEndpoints.fromJson(mapper,
                PassthroughEndpoints.toJson(mapper, value), "k")).isEqualTo(value);
    }
}
