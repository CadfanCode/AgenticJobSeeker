-- The content fingerprint is a lookup hint, not an identity.
--
-- Employers legitimately publish the same role in several cities with byte-identical
-- text. Teamtailor feeds carry no employer org number, so those postings share a
-- fingerprint while being genuinely distinct vacancies — distinguished only by their
-- employer-side job id. Uniqueness therefore belongs to (source, source_ad_id) and to
-- employer_job_key, not here.
DROP INDEX IF EXISTS ux_job_posting_fingerprint;

CREATE INDEX ix_job_posting_fingerprint ON job_posting (fingerprint);
