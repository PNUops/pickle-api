package kr.ac.pusan.pickle.llm.openrouter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.ac.pusan.pickle.llm.dto.ModelPricing;
import kr.ac.pusan.pickle.llm.dto.ModelPricing.ModelPriceAxis;
import kr.ac.pusan.pickle.llm.dto.ModelPricing.ModelPriceTier;
import kr.ac.pusan.pickle.llm.dto.ModelPricing.ModelPriceUnit;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a stored vendor pricing object into the shape the console renders.
 *
 * <p>The reading happens here, at request time, rather than when the catalogue
 * is fetched, so the stored column stays the vendor's own words. A unit table
 * that turns out wrong is then a code fix, not a data repair.
 *
 * <p>Units are this table's claim, not the vendor's: the listing states a bare
 * number per axis. Measured against the live listing on 2026-10-10, every axis
 * but {@code web_search} sits in the per-token range (input images included,
 * at 5e-8 to 2e-6), and {@code web_search} sits between $0.0025 and $0.018, the
 * range of a per-call fee. The vendor's documentation calls {@code image} a cost
 * "per image input", which the listed values contradict; if a model ever lists a
 * per-image value here it will read as an implausibly large per-token price, not
 * a small one. An axis missing from both sets is sent as UNKNOWN
 * with the vendor's raw number.
 */
public final class ModelPricings {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final BigDecimal PER_MILLION = new BigDecimal("1000000");

    /** Per-token axes, in the order the console lists them. */
    private static final List<String> TOKEN_AXES = List.of(
            "prompt", "completion", "input_cache_read", "input_cache_write",
            "input_cache_write_1h", "internal_reasoning", "image", "image_output",
            "audio", "input_audio_cache", "audio_output");

    /** Per-call axes: a search for {@code web_search}, an API request for {@code request}. */
    private static final List<String> CALL_AXES = List.of("web_search", "request");

    /** Keys inside a tier that state when it applies rather than what it costs. */
    private static final Set<String> TIER_CONDITIONS = Set.of(
            "min_prompt_tokens", "utc_days", "utc_start", "utc_end");

    private static final Map<String, Integer> ORDER = order();

    private ModelPricings() {
    }

    /**
     * Null when nothing was stored, which is a row not refreshed since the
     * column appeared, not a model with no prices. Malformed JSON reads the
     * same way: the column's CHECK already guarantees an object, so this only
     * guards against a hand edit.
     */
    public static @Nullable ModelPricing read(@Nullable String stored) {
        if (stored == null) {
            return null;
        }
        JsonNode root;
        try {
            root = JSON.readTree(stored);
        } catch (JacksonException e) {
            return null;
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        List<ModelPriceTier> tiers = new ArrayList<>();
        JsonNode overrides = root.path("overrides");
        if (overrides.isArray()) {
            for (JsonNode band : overrides) {
                if (band.isObject()) {
                    tiers.add(tier(band));
                }
            }
        }
        return new ModelPricing(axes(root, Set.of("overrides")), List.copyOf(tiers));
    }

    private static ModelPriceTier tier(JsonNode band) {
        List<String> other = new ArrayList<>();
        Integer minPrompt = null;
        JsonNode min = band.path("min_prompt_tokens");
        if (!min.isMissingNode()) {
            if (min.isIntegralNumber() && min.asLong() >= 0 && min.canConvertToInt()) {
                minPrompt = min.asInt();
            } else {
                other.add("min_prompt_tokens");
            }
        }
        List<String> days = new ArrayList<>();
        JsonNode dayNode = band.path("utc_days");
        if (!dayNode.isMissingNode()) {
            if (dayNode.isArray()) {
                for (JsonNode day : dayNode) {
                    if (day.isString()) {
                        days.add(day.asString());
                    } else if (!other.contains("utc_days")) {
                        // Dropping it would widen the band to days it does not cover.
                        other.add("utc_days");
                    }
                }
            } else {
                other.add("utc_days");
            }
        }
        Integer start = clockMinute(band.path("utc_start"), false);
        if (start == null && !band.path("utc_start").isMissingNode()) {
            other.add("utc_start");
        }
        Integer end = clockMinute(band.path("utc_end"), true);
        if (end == null && !band.path("utc_end").isMissingNode()) {
            other.add("utc_end");
        }
        // Anything else that is not a price is a condition this code cannot
        // state. Its name goes on the wire so the screen can say the band has
        // a condition it is not showing, rather than show a narrower band as
        // if it applied everywhere.
        band.propertyNames().forEach(key -> {
            if (!TIER_CONDITIONS.contains(key)
                    && (!numeric(band.path(key)) || looksLikeCondition(key))) {
                other.add(key);
            }
        });
        return new ModelPriceTier(minPrompt, List.copyOf(days), start, end,
                List.copyOf(other), axes(band, TIER_CONDITIONS));
    }

    /**
     * The vendor writes clock times as HHMM integers ({@code 1600} is 16:00).
     * An end of zero is midnight at the end of the day, so it becomes 1440;
     * reading it as minute zero would make every band that runs to midnight
     * look empty.
     */
    private static @Nullable Integer clockMinute(JsonNode node, boolean end) {
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            return null;
        }
        int hhmm = node.asInt();
        int hours = hhmm / 100;
        int minutes = hhmm % 100;
        if (hhmm < 0 || hours > 24 || minutes > 59 || (hours == 24 && minutes != 0)) {
            return null;
        }
        int total = hours * 60 + minutes;
        return end && total == 0 ? 1440 : total;
    }

    private static List<ModelPriceAxis> axes(JsonNode object, Set<String> skip) {
        List<ModelPriceAxis> axes = new ArrayList<>();
        object.propertyNames().forEach(key -> {
            if (skip.contains(key) || looksLikeCondition(key)) {
                return;
            }
            BigDecimal raw = price(object.path(key));
            if (raw == null) {
                return;
            }
            axes.add(axis(key, raw));
        });
        axes.sort(Comparator.comparingInt((ModelPriceAxis a) -> ORDER.getOrDefault(a.axis(),
                Integer.MAX_VALUE)).thenComparing(ModelPriceAxis::axis));
        return List.copyOf(axes);
    }

    private static ModelPriceAxis axis(String key, BigDecimal raw) {
        if (TOKEN_AXES.contains(key)) {
            return new ModelPriceAxis(key, ModelPriceUnit.PER_MILLION_TOKENS,
                    raw.multiply(PER_MILLION).setScale(6, RoundingMode.HALF_UP));
        }
        if (CALL_AXES.contains(key)) {
            return new ModelPriceAxis(key, ModelPriceUnit.PER_CALL, raw);
        }
        return new ModelPriceAxis(key, ModelPriceUnit.UNKNOWN, raw);
    }

    /**
     * Same three meanings as the catalogue fetch: a decimal string or number
     * is a price and zero is a real one, anything else is not a price, and a
     * negative value is the router sentinel and is not a price either.
     */
    private static @Nullable BigDecimal price(JsonNode value) {
        if (!value.isString() && !value.isNumber()) {
            return null;
        }
        String raw = value.asString();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            BigDecimal parsed = new BigDecimal(raw.trim());
            return parsed.signum() < 0 ? null : parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * A numeric value is a price unless its name says it is a bound or a
     * clock. Without this a new numeric condition such as a token ceiling
     * would show as a price axis and its band as unconditional.
     */
    private static boolean looksLikeCondition(String key) {
        return key.startsWith("min_") || key.startsWith("max_") || key.startsWith("utc_");
    }

    /** A number in either spelling, sentinel included. */
    private static boolean numeric(JsonNode value) {
        if (value.isNumber()) {
            return true;
        }
        if (!value.isString()) {
            return false;
        }
        try {
            new BigDecimal(value.asString().trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static Map<String, Integer> order() {
        List<String> all = new ArrayList<>(TOKEN_AXES);
        all.addAll(CALL_AXES);
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < all.size(); i++) {
            map.put(all.get(i), i);
        }
        return Map.copyOf(map);
    }
}
