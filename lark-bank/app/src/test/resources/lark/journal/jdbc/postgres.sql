--liquibase formatted sql

-- lark-actor-journal-jdbc 0.11.0's changelog, as it was, for LarkTablesSpec: the tables a database the bank used
-- before the library was renamed Surefold holds.

-- lark-actor-journal-jdbc's tables on Postgres, as a Liquibase changelog: include it from the service's own
-- (`<include file="lark/journal/jdbc/postgres.sql"/>`). Nothing creates these tables at start.

--changeset lark:journal
-- Every event, one row each (spec 0072).
create table lark_journal (
    kind     text   not null,
    id       text   not null,
    seq_nr   bigint not null,
    bytes    bytea  not null,
    -- Where the event stands among every event appended (spec 0075): what a feed's offsets are.
    ordering bigint generated always as identity,
    -- The id's slice (spec 0105), set on append.
    slice    integer not null,
    primary key (kind, id, seq_nr)
);
create unique index lark_journal_ordering on lark_journal (ordering);
create index lark_journal_kind_ordering on lark_journal (kind, ordering);
create index lark_journal_slice on lark_journal (slice);
-- A partition of a read model reads its own slices in the feed's order (spec 0106).
create index lark_journal_kind_slice on lark_journal (kind, slice, ordering);

-- The orderings each deletion (spec 0076) spanned, so the feed reads past rows deleted rather than waiting on them.
create table lark_journal_pruned (
    from_ordering bigint not null,
    to_ordering   bigint not null
);
create index lark_journal_pruned_to on lark_journal_pruned (to_ordering);

-- Slices this database refuses appends for (spec 0105): given to another database, or on their way to one.
create table lark_journal_fenced (
    slice integer not null,
    primary key (slice)
);

-- Which database owns each range of slices, by version (spec 0105). Kept in the first database only.
create table lark_journal_slices (
    version    bigint  not null,
    from_slice integer not null,
    to_slice   integer not null,
    owner      text    not null,
    primary key (version, from_slice)
);

-- Each range moved (spec 0105), until its rows are cleaned from the source. Kept in the first database only.
create table lark_journal_moves (
    version     bigint  not null,
    from_slice  integer not null,
    to_slice    integer not null,
    source      text    not null,
    target      text    not null,
    -- The source's highest ordering in the range when it switched: what its projections must pass before cleaning.
    copied_to   bigint  not null,
    switched_at bigint  not null,
    cleaned     boolean not null,
    primary key (version)
);

-- JdbcSnapshots' table (spec 0074): one row per id, the newest snapshot saved.
create table lark_snapshot (
    kind   text   not null,
    id     text   not null,
    seq_nr bigint not null,
    bytes  bytea  not null,
    primary key (kind, id)
);

-- JdbcOffsets' table (spec 0075): the last offset each read model handled, by its name.
create table lark_offset (
    name          text   not null,
    last_ordering bigint not null,
    primary key (name)
);

--changeset lark:journal-metadata
-- What the append that wrote each event carried (spec 0123): a trace's ids and log annotations, an object of strings.
-- Null where it carried nothing, as every event written before this did.
alter table lark_journal add column metadata jsonb;
