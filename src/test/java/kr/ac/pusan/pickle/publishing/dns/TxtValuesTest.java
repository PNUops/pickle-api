package kr.ac.pusan.pickle.publishing.dns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * TXT data in presentation form. What matters here is not the quoting itself
 * but that a value survives the round trip and that a value the zone already
 * holds compares equal to the same value on its way in — the comparison the
 * provider skips a write on.
 */
class TxtValuesTest {

    @Test
    void quotesAndUnquotesAValue() {
        assertThat(TxtValues.encode("pv-abc123")).isEqualTo("\"pv-abc123\"");
        assertThat(TxtValues.decode("\"pv-abc123\"")).isEqualTo("pv-abc123");
    }

    @Test
    void escapesTheQuoteAndBackslash() {
        assertThat(TxtValues.encode("say \"hi\"")).isEqualTo("\"say \\\"hi\\\"\"");
        assertThat(TxtValues.decode(TxtValues.encode("say \"hi\""))).isEqualTo("say \"hi\"");
        assertThat(TxtValues.decode(TxtValues.encode("back\\slash"))).isEqualTo("back\\slash");
    }

    @Test
    void decodesWhatAnotherToolSplitIntoSeveralStrings() {
        // A long value in a zone is several character-strings that readers
        // concatenate. Decoding has to join them, or the provider would read
        // such a record as different from the value it represents and rewrite
        // it on every reconcile.
        assertThat(TxtValues.decode("\"abc\" \"def\"")).isEqualTo("abcdef");
        assertThat(TxtValues.decode("\"abc\"\"def\"")).isEqualTo("abcdef");
    }

    @Test
    void leavesAnUnquotedValueAlone() {
        // Both sides of the comparison run through decode, and the desired side
        // may not be quoted yet. It has to be a no-op there.
        assertThat(TxtValues.decode("pv-abc123")).isEqualTo("pv-abc123");
        assertThat(TxtValues.decodeAll(List.of("pv-a", "\"pv-b\"")))
                .containsExactly("pv-a", "pv-b");
    }

    @Test
    void refusesAValueLongerThanOneCharacterString() {
        String tooLong = "x".repeat(TxtValues.MAX_OCTETS + 1);
        assertThatThrownBy(() -> TxtValues.encode(tooLong))
                .isInstanceOf(DnsProviderException.class)
                .hasMessageContaining("256");
        assertThat(TxtValues.encode("x".repeat(TxtValues.MAX_OCTETS)))
                .hasSize(TxtValues.MAX_OCTETS + 2);
    }

    @Test
    void countsOctetsNotCharacters() {
        // The limit is on the wire, so a Korean label costs three octets per
        // character. Counting characters would let a value through that the
        // zone then refuses.
        assertThat(TxtValues.octets("가")).isEqualTo(3);
        assertThatThrownBy(() -> TxtValues.encode("가".repeat(86)))
                .isInstanceOf(DnsProviderException.class);
        assertThat(TxtValues.decode(TxtValues.encode("가".repeat(85))))
                .isEqualTo("가".repeat(85));
    }
}
