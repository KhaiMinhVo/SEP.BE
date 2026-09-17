UPDATE "user"
SET "status" = 'DISABLED', "updatedAt" = CURRENT_TIMESTAMP
WHERE "status" = 'INACTIVE';
