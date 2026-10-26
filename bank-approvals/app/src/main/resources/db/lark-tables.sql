-- A database the bank used while the library was named Lark has its journal, snapshots and offsets in lark_* tables,
-- and Liquibase's record says lark:journal made them. Surefold's changelog names them surefold_* and its changesets
-- surefold:journal, so on such a database it would make empty tables beside the old ones. This renames the tables,
-- their indexes and the ordering's sequence, and has Liquibase's record name surefold's changesets in place of
-- lark's, with no checksum so that Liquibase stores the new one. Run before Liquibase, in one transaction; on any
-- other database (a new one, or one already renamed) it does nothing.
do $$
declare
    relation record;
begin
    if to_regclass('lark_journal') is null or to_regclass('surefold_journal') is not null then
        return;
    end if;
    for relation in
        select c.relname, c.relkind
        from pg_class c
        join pg_namespace n on n.oid = c.relnamespace
        where n.nspname = current_schema()
          and c.relkind in ('r', 'i', 'S')
          and (c.relname like 'lark\_journal%' or c.relname like 'lark\_snapshot%' or c.relname like 'lark\_offset%')
        order by c.relkind = 'r' desc, c.relname
    loop
        execute format(
            'alter %s %I rename to %I',
            case relation.relkind when 'r' then 'table' when 'i' then 'index' else 'sequence' end,
            relation.relname,
            'surefold_' || substr(relation.relname, length('lark_') + 1)
        );
    end loop;
    update databasechangelog
    set author = 'surefold',
        filename = replace(filename, 'lark/journal/jdbc/', 'surefold/journal/jdbc/'),
        md5sum = null
    where author = 'lark' and id in ('journal', 'journal-metadata');
end
$$;
