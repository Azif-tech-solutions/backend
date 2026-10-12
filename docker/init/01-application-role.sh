#!/bin/sh
# Runs once, when the Postgres volume is first initialised. Creates the non-superuser role the
# application connects as (so row-level security applies to it) from APP_DB_USER / APP_DB_PASSWORD
# in .env. Changing those later needs ALTER ROLE; see README "Changing secrets".
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v app_user="$APP_DB_USER" -v app_password="$APP_DB_PASSWORD" -v db="$POSTGRES_DB" <<'SQL'
create role :"app_user" login password :'app_password' nosuperuser nobypassrls;
grant all privileges on database :"db" to :"app_user";
alter database :"db" owner to :"app_user";
grant all privileges on schema public to :"app_user";
SQL
