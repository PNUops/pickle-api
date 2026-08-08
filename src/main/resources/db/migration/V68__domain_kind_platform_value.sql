-- REQUESTED dates from when the subdomain was one more field on the VM request
-- form, so the value named the step that produced the row. The request form no
-- longer carries a domain axis: the user picks the subdomain at publish time,
-- and what distinguishes this kind from CUSTOM is whose name space it sits in,
-- ours rather than the user's. PLATFORM says that, and it lines up with the
-- Domain.platform() factory that already creates exactly these rows.
--
-- RENAME VALUE is fully transactional (PG10+) and touches the catalog only;
-- rows store enum OIDs, so no domains row is rewritten.

alter type domain_kind rename value 'REQUESTED' to 'PLATFORM';
