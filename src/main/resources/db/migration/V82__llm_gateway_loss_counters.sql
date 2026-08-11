-- Loss counters the gateway reports about itself: text and accounting it gave
-- up on. These are the losses that are otherwise invisible to everyone --
-- nothing downstream can observe an event that was never shipped.
--
-- spool_write_failures is the earliest of the three: those events never
-- reached the gateway's outbox, so shipping never sees them and the stored
-- usage simply comes out low. bodies_dropped counts captured text the bounded
-- bodies queue discarded (text only -- the usage event still went to the
-- durable spool); usage_ship_failures counts batches the gateway skipped its
-- checkpoint past.
--
-- Nullable like the other self-report columns: the gateway omits a counter
-- that is zero, and null records "not reported" honestly.
alter table llm_gateway_state
    add column bodies_dropped       bigint,
    add column usage_ship_failures  bigint,
    add column spool_write_failures bigint;
