-- Step 7.6: files people attach to their own requests from the staff app (a correction now, leave later).
ALTER TABLE file DROP CONSTRAINT IF EXISTS file_purpose_check;
ALTER TABLE file ADD CONSTRAINT file_purpose_check CHECK (purpose IN ('PHOTO', 'DOCUMENT', 'LOGO', 'ATTACHMENT'));
