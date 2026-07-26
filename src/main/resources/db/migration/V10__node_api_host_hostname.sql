-- The pveproxy certificate's SANs cover the hostname (pve1 / pve1.pnuops.com)
-- and the campus IP, NOT the vmbr1 bridge IP 172.30.0.1 — so with CA pinning
-- the client must address the API by a SAN name. LXC 101 maps pve1 to
-- 172.30.0.1 in /etc/hosts (set up when the application container is built).
-- Found at the cutover smoke (2026-07-09): step 3 failed hostname
-- verification against https://172.30.0.1:8006 (V3 seed value).
update nodes set api_host = 'https://pve1:8006' where name = 'pve1';
