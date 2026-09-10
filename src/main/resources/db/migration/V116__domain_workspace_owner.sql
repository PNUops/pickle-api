-- A domain row has always named a VM, and everything about it was reached
-- through that VM: who may see it, which organisation scopes it in the admin
-- listing, who hears when its name is about to be reclaimed. A name issued on
-- its own has no VM to reach any of that through, so ownership moves to the
-- workspace, which is what the VM was being asked about in the first place.
--
-- vm_id stays for the kinds that have one and becomes the answer to "which VM
-- does this name serve", a narrower question than "who owns this name".
--
-- The organisation comes with it, denormalised onto the row rather than read
-- through a join, because workspaces do not carry one: vms and llm_api_keys
-- both hold their own org_id for exactly this reason, and the admin listing
-- scopes on it. Read off the VM here; a name with no VM is told its
-- organisation when it is created.

alter table domains
    add column workspace_id bigint references workspaces (id),
    add column org_id bigint references orgs (id);

update domains d
   set workspace_id = v.workspace_id,
       org_id = v.org_id
  from vms v
 where v.id = d.vm_id;

-- The two new columns stay nullable here, and that is the whole of the
-- expand/contract discipline in this file. A deploy that fails its health
-- window restores the previous release's jar and does not undo the migrations
-- the new one already applied; a NOT NULL column that jar's entity does not
-- know is a column its inserts omit, so every domain creation on the restored
-- jar would fail. Tightening them belongs to the release AFTER the jar that
-- writes them is running. Until then the entity's mapping is what holds the
-- invariant, which is enough because nothing else writes this table.
alter table domains alter column vm_id drop not null;

create index domains_workspace_id_idx on domains (workspace_id);
create index domains_org_id_idx on domains (org_id);

comment on column domains.workspace_id is
    '이 이름을 소유한 워크스페이스. 조회 범위와 인가, 알림 수신자가 이 열을 탄다.';
comment on column domains.org_id is
    '이 이름을 관리하는 기관. 워크스페이스는 기관을 갖지 않으므로 행이 직접 든다. vms와 llm_api_keys가 같은 모양이다.';
comment on column domains.vm_id is
    '이 이름이 서빙하는 VM. EXTERNAL 종류는 VM 없이 존재하므로 비어 있다.';

-- The two are equivalent, not merely compatible: a kind that serves no VM must
-- not carry one, and a kind that serves one must not lose it. Written as an
-- equality so neither direction can drift.
--
-- Compared as text for the reason V89 gives, not the one about adding a value:
-- V115 is a separate file and therefore a separate transaction, so an enum
-- literal would be legal here. A CHECK that a later migration has to rewrite
-- is easier to rewrite when it is already in text form, and this one names a
-- label that a rename would otherwise break silently -- pg_depend does not see
-- inside a CHECK body.
alter table domains
    add constraint domains_vm_id_kind_check
        check ((kind::text = 'EXTERNAL') = (vm_id is null));

-- The kinds listed here are the ones DomainKind.servedByPlatformProxy() answers
-- true for. Two homes for one list, because a CHECK cannot call into the
-- application; adding a kind means editing both, and the Java side is where
-- the reasoning lives.
--
-- V114 asked which kind is excluded from having a platform-written record.
-- That reads as "the platform writes a record for every name in its own zone",
-- which stopped being true the moment a kind in this zone pointed elsewhere.
-- Asked the other way round, a kind is outside this until it says otherwise.
alter table domains drop constraint domains_dns_status_custom_check;
alter table domains
    add constraint domains_dns_status_proxy_check
        check (kind::text in ('AUTO', 'PLATFORM') or dns_status::text = 'NONE');

-- A name issued on its own has no VM whose expiry ends it, and it is not
-- approved, so no granted period ends it either. What ends it is its owner
-- failing to say they still want it. The column is the deadline for saying so;
-- the platform kinds have no such deadline and must not carry one.
alter table domains add column renew_due_at timestamptz;

alter table domains
    add constraint domains_renew_due_at_kind_check
        check ((kind::text = 'EXTERNAL') = (renew_due_at is not null));

-- REMOVED rows are out, the same shape domains_released_at_idx uses. The
-- equivalence above means a reclaimed EXTERNAL row keeps its past deadline
-- rather than clearing it, so an index that only asked "is there a deadline"
-- would hand the renewal scan every dead row it ever reclaimed.
create index domains_renew_due_at_idx on domains (renew_due_at)
    where renew_due_at is not null and status <> 'REMOVED';

comment on column domains.renew_due_at is
    '연장 기한. 지나면 이름을 해제하고 예약 유예로 넘긴다. EXTERNAL 전용이다.';
