ALTER TABLE campaign_requirements
    ALTER COLUMN currency TYPE VARCHAR(3) USING TRIM(currency);
