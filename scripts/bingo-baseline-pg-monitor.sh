#!/bin/zsh
set -u

output_file="${1:-target/bingo-baseline-pg.log}"
: > "$output_file"

while true; do
  now=$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)
  {
    print -- "SNAPSHOT|$now"
    psql -d guildup -X -At -F '|' -c "
      select 'ACTIVITY', pid, coalesce(state,''), coalesce(wait_event_type,''), coalesce(wait_event,''),
             coalesce(xact_start::text,''), coalesce(query_start::text,''),
             coalesce(extract(epoch from clock_timestamp()-xact_start)::numeric(12,3)::text,''),
             coalesce(extract(epoch from clock_timestamp()-query_start)::numeric(12,3)::text,''),
             regexp_replace(left(query,500), E'[\\n\\r]+', ' ', 'g')
      from pg_stat_activity
      where datname='guildup' and pid <> pg_backend_pid()
        and (xact_start is not null or wait_event_type is not null)
      order by pid;"
    psql -d guildup -X -At -F '|' -c "
      select 'LOCK', a.pid, l.granted, l.mode, coalesce(c.relname,''),
             coalesce(extract(epoch from clock_timestamp()-a.xact_start)::numeric(12,3)::text,''),
             array_to_string(pg_blocking_pids(a.pid), ','),
             regexp_replace(left(a.query,500), E'[\\n\\r]+', ' ', 'g')
      from pg_locks l
      join pg_stat_activity a on a.pid=l.pid
      left join pg_class c on c.oid=l.relation
      where a.datname='guildup' and (not l.granted or cardinality(pg_blocking_pids(a.pid)) > 0
            or c.relname in ('communities','bingo_events'))
      order by a.pid,l.granted,l.mode;"
    psql -d guildup -X -At -F '|' -c "
      select 'BLOCKING', waiter.pid, blocker.pid,
             waiter.wait_event_type, waiter.wait_event,
             coalesce(extract(epoch from clock_timestamp()-waiter.xact_start)::numeric(12,3)::text,''),
             regexp_replace(left(waiter.query,300), E'[\\n\\r]+', ' ', 'g'),
             regexp_replace(left(blocker.query,300), E'[\\n\\r]+', ' ', 'g')
      from pg_stat_activity waiter
      cross join lateral unnest(pg_blocking_pids(waiter.pid)) blocking_pid
      join pg_stat_activity blocker on blocker.pid=blocking_pid
      where waiter.datname='guildup';"
  } >> "$output_file" 2>&1
  sleep 0.5
done
