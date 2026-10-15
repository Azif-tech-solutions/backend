-- Prints every index in the public schema as Markdown, grouped by table: what each index covers,
-- whether it is the primary key or unique, and any partial-index predicate. Regenerates
-- docs/database-indexes.md:
--   psql -U <user> -d <db> -At -f scripts/db/index-report.sql > docs/database-indexes.md
\pset footer off
select string_agg(section, E'\n' order by tbl)
from (
    select t.relname as tbl,
           '### `' || t.relname || '`' || E'\n\n' ||
           '| Index | Kind | Columns / expression | Only rows where |' || E'\n' ||
           '|---|---|---|---|' || E'\n' ||
           string_agg(
               '| `' || i.relname || '` | ' ||
               case when x.indisprimary then 'primary key' when x.indisunique then 'unique' else am.amname end || ' | ' ||
               -- The column list: the definition without its WHERE, between "USING <method> (" and the last ")".
               replace(substring(regexp_replace(pg_get_indexdef(x.indexrelid), ' WHERE .*$', '') from ' USING [a-z]+ [(](.*)[)]$'), '|', '\|') || ' | ' ||
               coalesce(replace(pg_get_expr(x.indpred, x.indrelid), '|', '\|'), '') || ' |',
               E'\n' order by x.indisprimary desc, i.relname) || E'\n' as section
    from pg_index x
    join pg_class i on i.oid = x.indexrelid
    join pg_class t on t.oid = x.indrelid
    join pg_namespace n on n.oid = t.relnamespace
    join pg_am am on am.oid = i.relam
    where n.nspname = 'public' and t.relname <> 'flyway_schema_history'
    group by t.relname
) per_table;
