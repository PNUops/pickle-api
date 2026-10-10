package kr.ac.pusan.pickle.llm.openrouter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import kr.ac.pusan.pickle.llm.dto.ModelPricing;
import kr.ac.pusan.pickle.llm.dto.ModelPricing.ModelPriceAxis;
import kr.ac.pusan.pickle.llm.dto.ModelPricing.ModelPriceTier;
import kr.ac.pusan.pickle.llm.dto.ModelPricing.ModelPriceUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads pricing objects copied from the vendor's public model listing on
 * 2026-10-10, five entries chosen because each carries a different shape: a
 * long-context band, eleven axes, weekday and clock bands, a clock band that
 * wraps to midnight, and the router sentinel.
 */
class ModelPricingsTest {

    private static final Map<String, String> SAMPLE = new HashMap<>();

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = ModelPricingsTest.class
                .getResourceAsStream("/llm/openrouter/models-pricing-sample.json")) {
            JsonNode root = JsonMapper.builder().build().readTree(in);
            for (JsonNode model : root.path("data")) {
                SAMPLE.put(model.path("id").asString(), model.path("pricing").toString());
            }
        }
    }

    private static ModelPricing read(String id) {
        ModelPricing pricing = ModelPricings.read(SAMPLE.get(id));
        assertThat(pricing).as(id).isNotNull();
        return pricing;
    }

    private static ModelPriceAxis axis(java.util.List<ModelPriceAxis> axes, String name) {
        return axes.stream().filter(a -> a.axis().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void everyPublishedAxisIsReadNotOnlyInputAndOutput() {
        ModelPricing pricing = read("google/gemini-2.5-flash-image");

        assertThat(pricing.axes()).extracting(ModelPriceAxis::axis).containsExactly(
                "prompt", "completion", "input_cache_read", "input_cache_write",
                "internal_reasoning", "image", "image_output", "audio", "input_audio_cache",
                "web_search");
        assertThat(axis(pricing.axes(), "image_output").unit())
                .isEqualTo(ModelPriceUnit.PER_MILLION_TOKENS);
        assertThat(axis(pricing.axes(), "image_output").price())
                .isEqualByComparingTo("30");
        assertThat(pricing.tiers()).isEmpty();
    }

    @Test
    void webSearchIsPricedPerCallAndKeptAsTheVendorStatesIt() {
        ModelPriceAxis search = axis(read("google/gemini-2.5-flash-image").axes(), "web_search");

        assertThat(search.unit()).isEqualTo(ModelPriceUnit.PER_CALL);
        assertThat(search.price()).isEqualByComparingTo("0.014");
    }

    @Test
    void aLongContextBandCarriesItsThresholdAndItsPrices() {
        ModelPricing pricing = read("anthropic/claude-sonnet-4.5");

        assertThat(axis(pricing.axes(), "prompt").price()).isEqualByComparingTo("3");
        assertThat(pricing.tiers()).hasSize(1);
        ModelPriceTier band = pricing.tiers().get(0);
        assertThat(band.minPromptTokens()).isEqualTo(200000);
        assertThat(band.utcDays()).isEmpty();
        assertThat(band.utcStartMinute()).isNull();
        assertThat(band.otherConditions()).isEmpty();
        assertThat(axis(band.axes(), "prompt").price()).isEqualByComparingTo("6");
        assertThat(axis(band.axes(), "input_cache_write_1h").price())
                .isEqualByComparingTo("12");
        assertThat(band.axes()).extracting(ModelPriceAxis::axis)
                .doesNotContain("min_prompt_tokens");
    }

    @Test
    void clockBandsBecomeMinutesAndAnEndOfZeroIsMidnight() {
        ModelPricing pricing = read("tencent/hy4-preview");

        assertThat(pricing.tiers()).extracting(ModelPriceTier::utcStartMinute)
                .containsExactly(0, 960);
        assertThat(pricing.tiers()).extracting(ModelPriceTier::utcEndMinute)
                .containsExactly(960, 1440);
    }

    @Test
    void weekdayBandsKeepTheirDays() {
        ModelPricing pricing = read("deepseek/deepseek-v4-pro-0813");

        assertThat(pricing.tiers()).hasSize(6);
        assertThat(pricing.tiers().get(0).utcDays()).containsExactly("saturday", "sunday");
        assertThat(pricing.tiers().get(0).utcStartMinute()).isNull();
        assertThat(pricing.tiers().get(1).utcDays()).hasSize(5);
        assertThat(pricing.tiers().get(1).utcEndMinute()).isEqualTo(60);
        assertThat(pricing.tiers()).allSatisfy(band ->
                assertThat(band.otherConditions()).isEmpty());
    }

    @Test
    void theRouterSentinelIsNotAPrice() {
        assertThat(read("openrouter/auto").axes()).isEmpty();
    }

    @Test
    void anAxisWithNoKnownUnitIsSentRatherThanDropped() {
        ModelPricing pricing = ModelPricings.read(
                "{\"prompt\":\"0.000001\",\"video_second\":\"0.05\"}");

        assertThat(pricing).isNotNull();
        ModelPriceAxis unknown = axis(pricing.axes(), "video_second");
        assertThat(unknown.unit()).isEqualTo(ModelPriceUnit.UNKNOWN);
        assertThat(unknown.price()).isEqualByComparingTo(new BigDecimal("0.05"));
        // Known axes first, so an unknown one cannot push input off the line.
        assertThat(pricing.axes().get(0).axis()).isEqualTo("prompt");
    }

    @Test
    void aConditionThisCodeCannotStateIsNamedNotIgnored() {
        ModelPricing pricing = ModelPricings.read("""
                {"prompt":"0.000001","overrides":[
                  {"region":"eu","prompt":"0.000002"},
                  {"utc_start":2575,"prompt":"0.000003"},
                  {"max_prompt_tokens":500000,"prompt":"0.000004"},
                  {"utc_days":["monday",1],"prompt":"0.000005"}]}
                """);

        assertThat(pricing).isNotNull();
        assertThat(pricing.tiers().get(0).otherConditions()).containsExactly("region");
        assertThat(pricing.tiers().get(1).otherConditions()).containsExactly("utc_start");
        assertThat(pricing.tiers().get(1).utcStartMinute()).isNull();
        // A numeric bound is a condition, not a price axis.
        assertThat(pricing.tiers().get(2).otherConditions()).containsExactly("max_prompt_tokens");
        assertThat(pricing.tiers().get(2).axes()).extracting(ModelPriceAxis::axis)
                .containsExactly("prompt");
        assertThat(pricing.tiers().get(3).otherConditions()).containsExactly("utc_days");
    }

    @Test
    void zeroIsAPriceAndNothingStoredIsNotAnEmptyPricing() {
        ModelPricing free = ModelPricings.read("{\"prompt\":\"0\",\"completion\":\"0\"}");

        assertThat(free).isNotNull();
        assertThat(free.axes()).extracting(ModelPriceAxis::price)
                .allSatisfy(price -> assertThat(price).isEqualByComparingTo("0"));
        assertThat(ModelPricings.read(null)).isNull();
        assertThat(ModelPricings.read("not json")).isNull();
    }
}
