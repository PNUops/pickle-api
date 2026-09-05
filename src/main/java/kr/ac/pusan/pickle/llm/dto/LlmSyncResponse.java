package kr.ac.pusan.pickle.llm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * api → gateway sync answer, in exactly two shapes — the split is structural
 * on purpose:
 *
 * <ul>
 *   <li>{@link Unchanged} is the whole "you are current" answer:
 *       {@code {"generation": N}} and nothing else.</li>
 *   <li>{@link Document} always carries {@code models} AND {@code keys}
 *       together. A response with exactly one of them is a contract violation
 *       the gateway refuses: omission is how "unchanged" is expressed, so
 *       {@code models} alone would revoke every key and {@code keys} alone
 *       would remove every model. Two record shapes make the invalid state
 *       inexpressible — no {@code @JsonInclude(NON_NULL)} on the collections,
 *       no null to drop out by accident.</li>
 * </ul>
 *
 * <p>{@code generation} and {@code serviceEnabled} are primitives, never
 * boxed: a null {@code serviceEnabled} would vanish from the JSON, and the
 * gateway refuses a document without it rather than reading absence as
 * {@code false} — the api must not be able to express that document at
 * all.</p>
 *
 * <p>An <b>empty</b> {@code keys} array is a real state ("no keys at all")
 * that the gateway applies, distinct from the member being absent
 * ("unchanged"); the same holds for {@code models}.</p>
 */
public sealed interface LlmSyncResponse {

    /** The caller is current: generation only, no document members at all. */
    record Unchanged(long generation) implements LlmSyncResponse {
    }

    /**
     * The full authorization document, from one MVCC snapshot.
     *
     * <p>{@code passthroughRef} names the upstream serving public model names
     * the catalogue does not list (the commercial passthrough); null drops the
     * member and disables passthrough. It is the one document member allowed
     * to be conditionally absent, because absence is a real state ("no
     * passthrough"), not "unchanged" — unlike {@code models}/{@code keys}.</p>
     */
    record Document(
            int formatVersion,
            long generation,
            boolean serviceEnabled,
            @JsonInclude(JsonInclude.Include.NON_NULL) String passthroughRef,
            List<ModelEntry> models,
            List<KeyEntry> keys) implements LlmSyncResponse {
    }

    /**
     * One servable model. {@code budgetAxis} is TOKEN or CREDIT — which of a
     * key's two budgets this model's usage counts against; the gateway reads
     * an absent value as TOKEN, so it is always sent explicitly here to keep
     * the wire self-describing.
     */
    record ModelEntry(
            String publicName,
            String upstreamRef,
            String upstreamModel,
            String fallbackRef,
            String visibility,
            String budgetAxis,
            int maxInputTokens,
            int maxOutputTokens) {
    }

    /**
     * One issued key as the gateway needs it: the sha256 of the bearer (never
     * plaintext), a status the gateway's vocabulary accepts (exactly ACTIVE |
     * SUSPENDED | REVOKED — an entry outside it is dropped gateway-side and
     * its owner loses service, so mapping down happens before serving), and
     * the limits the gateway enforces. {@code expiresAt} and {@code limits}
     * are omitted when absent (gateway defaults apply).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record KeyEntry(
            String keyId,
            String tokenHash,
            String status,
            Instant expiresAt,
            List<String> allowedModels,
            // The money axis, separate from allowedModels above on purpose: this
            // one governs CREDIT-axis models only, so a key restricted here
            // keeps its self-serving access. Empty means unrestricted.
            List<String> creditAllowedModels,
            // The other half of the money fence, same syntax, opposite meaning:
            // empty blocks nothing, and where both lists name a model the deny
            // side wins. Sent as its own member rather than folded into the one
            // above because "not listed" and "listed as refused" are different
            // decisions and only the gateway can combine them.
            List<String> creditDeniedModels,
            // The passthrough surface this key may reach, as capability tokens
            // from a closed set rather than paths. A third member and not a
            // third meaning on either list above, because its empty value goes
            // the other way: empty grants nothing. A path nobody granted must
            // not open by the mere act of the gateway learning to serve it.
            //
            // It governs only the paths the passthrough surface adds. Chat
            // completions and the model catalogue are outside it, which is also
            // what makes the member safe to omit: an older control plane that
            // sends none is describing "no passthrough", and those are exactly
            // the paths an older gateway does not serve.
            List<String> passthroughEndpoints,
            KeyLimits limits,
            boolean quotaExhausted,
            boolean recordBodies,
            // Why the credential below is missing, for the one missing-reason
            // that resolves on its own: a positive money budget whose
            // OpenRouter key has not been created yet. The gateway needs it
            // because omission alone cannot tell "never granted" from
            // "granted, not delivered yet", and those two deserve different
            // sentences — one tells the caller to apply for a budget, the
            // other to wait. False for every other reason the member is
            // absent, a ciphertext that will not decrypt included: that one
            // does not heal on its own and must not read as "almost ready".
            boolean creditPending,
            // The one usable secret in the document: upstream ref (lowercase)
            // to the bearer this key presents there, decrypted at serve time.
            // Included only while the key is truly ACTIVE with a positive
            // credit limit and a provisioned OpenRouter key; omission is what
            // closes the commercial axis for the key.
            Map<String, String> upstreamCredentials) {
    }

    /** Short-window limits enforced in the gateway; null members omitted. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record KeyLimits(Integer rpm, Integer tpm, Integer concurrency) {
    }
}
