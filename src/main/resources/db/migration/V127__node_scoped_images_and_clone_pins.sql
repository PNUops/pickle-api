-- Preserve every catalog row and public UUID while allowing node-local replicas.
alter table os_images
    drop constraint os_images_name_version_key,
    add constraint os_images_node_name_version_key unique (node_id, name, version),
    add constraint os_images_clone_coordinates_key unique (id, node_id, proxmox_vmid);

-- Existing VMs remain unpinned. Never infer their creation provenance in a migration.
alter table vms
    add column clone_image_id bigint,
    add column clone_node_id bigint,
    add column clone_template_vmid integer,
    add column clone_revision_sha256 varchar(64),
    add constraint vms_clone_pin_complete_check check (
        num_nonnulls(clone_image_id, clone_node_id, clone_template_vmid,
                     clone_revision_sha256) in (0, 4)
    ),
    add constraint vms_clone_template_vmid_positive_check check (clone_template_vmid > 0),
    add constraint vms_clone_revision_sha256_check check (
        clone_revision_sha256 ~ '^[0-9a-f]{64}$'
    ),
    add constraint vms_clone_image_coordinates_fkey
        foreign key (clone_image_id, clone_node_id, clone_template_vmid)
        references os_images (id, node_id, proxmox_vmid) match full;

create index vms_clone_image_id_idx on vms (clone_image_id)
    where clone_image_id is not null;

comment on column vms.clone_image_id is
    '최초 생성에 고정한 실제 노드별 이미지 행. NULL은 이력 고정 도입 전 VM이며 추정값으로 채우지 않는다.';
comment on column vms.clone_node_id is
    '최초 복제 노드. 수동 복구 후 현재 node_id와 달라도 생성 이력은 유지한다.';
comment on column vms.clone_template_vmid is
    '최초 복제에 고정한 PVE 템플릿 VMID. 재시도에서 다른 템플릿으로 교체하지 않는다.';
comment on column vms.clone_revision_sha256 is
    '승인 이미지와 실제 복제 이미지의 논리 revision 메타데이터 SHA-256. 디스크 내용 전체의 해시가 아니다.';
