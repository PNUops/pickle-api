-- The daily token limit an approval granted, and whether the key has spent it.
--
-- Until now the granted daily figure lived only in llm_key_request_details --
-- the record of what was decided -- and nothing carried it to the key or to
-- the gateway. The approval screen said a daily limit had been granted and
-- nothing enforced it.
--
-- The two columns are deliberately separate. daily_tokens is policy, written
-- once at approval; quota_exhausted is a derived fact recomputed from usage.
-- Keeping the derived one in a column rather than computing it inside the
-- sync query is what makes the gateway hear about it at all: the gateway only
-- receives a new document when the generation moves, and only a WRITE bumps
-- the generation. A flag computed at read time would flip in the database and
-- never reach the gateway, because nothing would have written anything.
alter table llm_api_keys
    add column daily_tokens    bigint,
    add column quota_exhausted boolean not null default false;

-- Carry across what approvals already granted. Without this, every key
-- approved before today keeps a limit that exists in the decision record and
-- nowhere else -- which is the very defect this migration closes.
update llm_api_keys k
   set daily_tokens = d.granted_daily_tokens
  from llm_key_request_details d
 where d.request_id = k.request_id
   and d.granted_daily_tokens is not null;

-- Only keys that have a limit are ever recomputed, so the sweep reads the
-- narrow set rather than every key.
create index llm_api_keys_daily_quota_idx on llm_api_keys (id)
    where daily_tokens is not null;
