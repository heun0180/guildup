-- Existing bingos retain their current aggregation behavior.
alter table pubg_bingo_events
    add column if not exists exclude_bot_combat_stats boolean not null default false;

alter table pubg_bingo_events
    add column if not exists clan_play_required boolean not null default false;

-- Version 2 stores non-bot damage/DBNO/headshot metrics and headshot kill facts.
-- Existing telemetry rows stay at version 0 and are upgraded once when an affected
-- account is next synchronized; upgraded facts are then reused by every bingo.
alter table pubg_matches
    add column if not exists telemetry_fact_version integer not null default 0;

alter table pubg_match_kills
    add column if not exists headshot boolean not null default false;
