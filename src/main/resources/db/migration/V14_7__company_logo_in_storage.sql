-- The company logo moves from a bytea column into file storage (S3, docs/03 §8.4), like every other
-- upload: company_profiles points at the stored_files row, the profile returns a short-lived link,
-- and documents read the bytes from storage. Logos stored in the old column are not carried over
-- (SQL can't upload to S3): a tenant that had one uploads it again.
alter table company_profiles add column logo_file_id uuid references stored_files(id);
alter table company_profiles drop column logo, drop column logo_content_type;
update company_profiles set logo_updated_at = null;
