-- Records how much guest disk a node actually has behind it.
--
-- nodes already carries cpu_threads and memory_mb, so the headroom surfaces can
-- put allocated vCPU and memory over a denominator. Disk had no denominator at
-- all: the allocated sum was shown on its own, which reads as a number without
-- a scale. The missing figure is the thin pool guest disks are carved out of,
-- and it is not derivable from anything already stored -- the pool is sized on
-- the host, independently of the node's CPU and memory.
--
-- Advisory on purpose. The pool is over-provisioned by design (thin volumes
-- allocate on write), so allocated-over-capacity may legitimately exceed 1 and
-- this column must never become a placement gate; it exists so an operator can
-- see how far past the physical size the promises have gone.
--
-- Nullable and null until measured: the value is fed by the infra inventory
-- script, which reads the pool from the host. A node nobody has measured yet
-- makes the platform-wide denominator null rather than a wrong smaller number,
-- which is why the reading surfaces treat one missing node as no capacity.

alter table nodes add column disk_capacity_gb bigint;

comment on column nodes.disk_capacity_gb is
    '게스트 디스크가 놓이는 thin pool의 물리 용량(GB). 인프라 인벤토리 스크립트가 호스트에서 측정해 채우며, 측정 전에는 null이다. 오버프로비저닝을 전제하므로 배치 제한이 아니라 조언용 분모로만 쓴다.';
