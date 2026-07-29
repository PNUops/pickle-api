-- The IP quarantine window is coupled to the forwarding relay's snapshot
-- replay: a relay agent re-applies its persisted snapshot on boot for as long
-- as that snapshot is considered usable (24 hours), so a released address must
-- stay unassignable at least that long. Below it, the address could be
-- reassigned while stale forwarding rules still point at it and public traffic
-- would land on a different tenant's VM. The editor now refuses anything under
-- 24; the description says why. The value itself is unchanged.
update settings
   set description = '회수된 IP를 재할당하지 않고 격리하는 시간(시간). '
                     || '릴레이 에이전트가 보관된 스냅샷을 재적용할 수 있는 기간(24시간)보다 '
                     || '짧게 설정할 수 없습니다.',
       updated_at = now()
 where key = 'ip_quarantine_hours';
