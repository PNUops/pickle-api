-- released_at means "this row is holding its name in reserve". A REMOVED row
-- reserves nothing — its name is already free — but the paths that flipped rows
-- to REMOVED (the user's immediate return, the reservation sweeper's reclaim,
-- the admin takedown) left the stamp behind, so those rows kept reading as
-- reserved to anything that discriminates on released_at alone. The code now
-- clears the stamp whenever it retires a row; this corrects the rows the old
-- behaviour already left behind.
update domains
   set released_at = null
 where status = 'REMOVED'
   and released_at is not null;

comment on column domains.released_at is
    '서빙을 멈춘 시각. 플랫폼 서브도메인은 이 시각부터 유예 기간 동안 이름이 예약되고, 그 뒤 스위퍼가 REMOVED로 회수한다. 서빙 중이거나 REMOVED면 null.';
