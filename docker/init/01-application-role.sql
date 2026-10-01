create role zifiq_app login password 'zifiq-local' nosuperuser nobypassrls;
grant all privileges on database zifiq to zifiq_app;
alter database zifiq owner to zifiq_app;
grant all privileges on schema public to zifiq_app;
