-- Additive PostgreSQL migration. Existing bingo data is intentionally untouched.
create table if not exists pubg_matches (
    id bigserial primary key,
    match_id varchar(100) not null,
    shard varchar(30) not null,
    started_at timestamptz not null,
    duration integer null,
    game_mode varchar(50) null,
    match_type varchar(50) null,
    map_name varchar(100) null,
    custom_match boolean null,
    telemetry_url varchar(1000) null,
    telemetry_loaded boolean not null default false,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint uk_pubg_matches_match_id unique (match_id)
);

create index if not exists idx_pubg_matches_started_at on pubg_matches (started_at);
create index if not exists idx_pubg_matches_shard_started on pubg_matches (shard, started_at);

create table if not exists pubg_match_players (
    id bigserial primary key,
    pubg_match_id bigint not null references pubg_matches(id),
    pubg_account_id varchar(100) not null,
    player_name varchar(100) null,
    team_number integer not null,
    kills integer not null,
    damage numeric(18,3) null,
    assists integer not null,
    dbnos integer not null,
    headshot_kills integer not null,
    revives integer not null,
    heals integer not null,
    boosts integer not null,
    walk_distance numeric(18,3) null,
    ride_distance numeric(18,3) null,
    swim_distance numeric(18,3) null,
    placement integer not null,
    win boolean not null,
    metrics_json text null,
    throwable_uses_json text null,
    picked_items_json text null,
    used_items_json text null,
    care_package_items_json text null,
    destroyed_armor_json text null,
    latest_evidence_at timestamptz null,
    telemetry_clan_members integer null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint uk_pubg_match_players_match_account unique (pubg_match_id, pubg_account_id)
);

create index if not exists idx_pubg_match_players_account on pubg_match_players (pubg_account_id);

create table if not exists pubg_match_kills (
    id bigserial primary key,
    pubg_match_id bigint not null references pubg_matches(id),
    killer_account_id varchar(100) not null,
    victim_account_id varchar(100) null,
    weapon varchar(150) null,
    weapon_category varchar(80) null,
    throwable varchar(150) null,
    distance_meters numeric(12,3) not null,
    wall_penetration boolean not null,
    occurred_at timestamptz null,
    created_at timestamptz not null
);

create index if not exists idx_pubg_match_kills_match_killer on pubg_match_kills (pubg_match_id, killer_account_id);
create index if not exists idx_pubg_match_kills_occurred on pubg_match_kills (occurred_at);
