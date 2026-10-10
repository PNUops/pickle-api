-- The vendor publishes a dozen price axes per model and this table kept two.
-- The approval screen read those two and showed them as the model's price,
-- while cache writes, web search, audio and long-context surcharges were
-- billed against the same money limit without appearing anywhere.
--
-- The whole pricing object is kept as the vendor sent it rather than one
-- column per axis. A column per axis is the shape that let the gap open: a new
-- axis would need a migration before it could be stored, and until then it
-- would be dropped without any signal. Kept whole, an axis this code has never
-- heard of still reaches the screen under its vendor name.
--
-- prompt_price and completion_price stay. They are what the listing sorts on,
-- and they are the same values the object carries.
--
-- Null means the row has not been refreshed since this column appeared; the
-- next catalogue refresh fills every listed row.
alter table openrouter_catalogue_model
    add column pricing jsonb,
    add constraint openrouter_catalogue_model_pricing_object
        check (pricing is null or jsonb_typeof(pricing) = 'object');

comment on column openrouter_catalogue_model.pricing is
    'OpenRouter 모델 목록의 pricing 객체 원본. 비어 있으면 이 컬럼이 생긴 뒤 아직 갱신되지 않은 행이다.';
