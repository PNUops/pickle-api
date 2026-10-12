-- Retain exact issuance provenance without guessing historical names.
alter table domains add column request_id bigint;

alter table domains
    add constraint domains_request_fk foreign key (request_id) references requests(id);

create index domains_request_id_idx on domains(request_id) where request_id is not null;

comment on column domains.request_id is
    '발급 신청의 내부 식별자. 식별자가 없는 과거 발급은 null이며 이름만으로 신청을 추정하지 않는다.';
