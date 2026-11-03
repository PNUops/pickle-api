package kr.ac.pusan.pickle.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validator;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.consent.TermsDocType;
import kr.ac.pusan.pickle.consent.dto.ConsentInput;
import kr.ac.pusan.pickle.oauth.dto.OauthCompleteRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.i18n.LocaleContextHolder;

class ValidationMessagesTest {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(
                    MessageSourceAutoConfiguration.class, ValidationAutoConfiguration.class));

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void koreanDefaultsInterpolateConstraintValues() {
        LocaleContextHolder.setLocale(Locale.KOREAN);
        contexts.run(context -> {
            Validator validator = context.getBean(Validator.class);
            assertThat(messages(validator, new Sample(" ", null, List.of(), "x", 1, -1L, false)))
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            "text", "공백이 아닌 값을 입력해 주세요.",
                            "required", "값을 입력해 주세요.",
                            "choices", "하나 이상 입력해 주세요.",
                            "bounded", "길이나 항목 수는 2~4 범위여야 해요.",
                            "count", "2 이상으로 입력해 주세요.",
                            "bytes", "0 이상으로 입력해 주세요.",
                            "confirmed", "true로 입력해 주세요."));
            assertThat(messages(validator, new Sample("x", 1, List.of("x"), "ab", 5, 0L, true)))
                    .containsExactlyEntriesOf(Map.of("count", "4 이하로 입력해 주세요."));
        });
    }

    @Test
    void inclusiveBoundsAndOptionalNullsKeepTheirExistingMeaning() {
        LocaleContextHolder.setLocale(Locale.KOREAN);
        contexts.run(context -> {
            Validator validator = context.getBean(Validator.class);
            assertThat(validator.validate(new Sample("x", 1, List.of("x"), "ab", 2, 0L, true)))
                    .isEmpty();
            assertThat(validator.validate(new Sample("x", 1, List.of("x"), "abcd", 4, 0L, true)))
                    .isEmpty();
            assertThat(validator.validate(new Sample("x", 1, List.of("x"), null, null, null, null)))
                    .isEmpty();
        });
    }

    @Test
    void explicitRequestMessagesTakePrecedenceOverDefaults() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("ko-KR"));
        contexts.run(context -> {
            OauthCompleteRequest request = new OauthCompleteRequest("x".repeat(129), "", null,
                    null, null, null, List.of(new ConsentInput(TermsDocType.TERMS_OF_SERVICE, 1)));
            assertThat(messages(context.getBean(Validator.class), request))
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            "registrationToken", "길이나 항목 수는 0~128 범위여야 해요.",
                            "name", "이름을 입력해 주세요."));
        });
    }

    @Test
    @ResourceLock(Resources.LOCALE)
    void otherLocalesKeepProviderTranslationsUnderAKoreanJvmDefault() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.KOREAN);
            Map.of(Locale.ENGLISH, "must not be blank", Locale.FRENCH, "ne doit pas être vide")
                    .forEach((locale, expected) -> {
                        LocaleContextHolder.setLocale(locale);
                        contexts.run(context -> {
                            var violations = context.getBean(Validator.class)
                                    .validateProperty(new Sample("", 1, List.of("x"), "ab", 2, 0L, true), "text");
                            assertThat(violations).singleElement()
                                    .satisfies(violation -> assertThat(violation.getMessage()).isEqualTo(expected));
                        });
                    });
        } finally {
            Locale.setDefault(previous);
        }
    }

    private static Map<String, String> messages(Validator validator, Object input) {
        return validator.validate(input).stream().collect(Collectors.toMap(
                violation -> violation.getPropertyPath().toString(), violation -> violation.getMessage()));
    }

    private record Sample(@NotBlank String text, @NotNull Integer required,
            @NotEmpty List<String> choices, @Size(min = 2, max = 4) String bounded,
            @Min(2) @Max(4) Integer count, @PositiveOrZero Long bytes, @AssertTrue Boolean confirmed) { }
}
