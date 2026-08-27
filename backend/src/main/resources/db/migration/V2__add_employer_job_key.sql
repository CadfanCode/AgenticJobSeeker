-- Cross-source identity link.
--
-- The content fingerprint alone cannot match the same job across sources: JobTech and
-- Teamtailor describe a posting differently and Teamtailor feeds carry no employer org
-- number. Both, however, point at the same employer-side job, whose id is embedded in
-- the URL (Teamtailor /jobs/<id>-<slug>, Varbi jobID:<id>). That pair of
-- (employer host, job id) is the durable link.
ALTER TABLE job_posting ADD COLUMN employer_job_key VARCHAR(320);

CREATE INDEX ix_job_posting_employer_job_key ON job_posting (employer_job_key);
