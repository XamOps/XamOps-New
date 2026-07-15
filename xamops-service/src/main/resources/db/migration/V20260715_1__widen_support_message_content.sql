-- The chatbot's FAQ auto-answer persists full sentence descriptions plus a
-- follow-up nudge as the bot's reply content, routinely exceeding the
-- default varchar(255) this column was created with. Widen to TEXT so
-- inserts stop failing with "value too long for type character varying(255)".
ALTER TABLE support_message ALTER COLUMN content TYPE TEXT;
