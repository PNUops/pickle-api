package kr.ac.pusan.pickle.gpu;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** An integer-valued JSON number is required; fractional hours are never truncated. */
public class StrictGpuHoursDeserializer extends ValueDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT && parser.currentToken() != JsonToken.VALUE_NUMBER_FLOAT) {
            return context.reportInputMismatch(Integer.class, "임대 시간은 정수로 입력해 주세요.");
        }
        try {
            return parser.getDecimalValue().intValueExact();
        } catch (ArithmeticException e) {
            return context.reportInputMismatch(Integer.class, "임대 시간은 표현 가능한 정수로 입력해 주세요.");
        }
    }
}
