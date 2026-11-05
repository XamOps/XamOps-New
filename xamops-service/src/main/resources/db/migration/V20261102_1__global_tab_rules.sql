-- Platform-wide XamOps page visibility, managed from the BillOps admin User Manager.
--
-- global_tab_rule holds at most one rule per XamOps catalog key (a module key such as
-- 'observability' or a tab key such as 'obs-logs', see frontend constants/xamopsTabs.ts):
--   HIDE  - the page is hidden from every ROLE_XAMOPS user, sidebar and URL, whatever their own
--           user_tab_access rows say.
--   GRANT - the page is shown to every ROLE_XAMOPS user, including users whose user_tab_access
--           list would otherwise leave it out (the "new key is missing from every saved list" trap
--           described in xamopsTabs.ts).
-- A tab rule wins over its module's rule. No row means today's behaviour (per-user access only).
--
-- global_tab_rule_exception lists users a rule does not apply to. For them the page falls back to
-- their own user_tab_access settings.
--
-- BillOps writes these tables through JPA (GlobalTabRule / GlobalTabRuleException) and Hibernate
-- ddl-auto=update may create them before this runs, hence IF NOT EXISTS and the guarded FKs.
-- Idempotent.
--
-- To reverse: DROP TABLE global_tab_rule_exception; DROP TABLE global_tab_rule;

CREATE TABLE IF NOT EXISTS global_tab_rule (
    id          BIGSERIAL PRIMARY KEY,
    tab_key     VARCHAR(100) NOT NULL,
    mode        VARCHAR(10)  NOT NULL,
    updated_by  VARCHAR(255),
    updated_at  TIMESTAMP,
    CONSTRAINT uk_global_tab_rule_tab_key UNIQUE (tab_key),
    CONSTRAINT ck_global_tab_rule_mode CHECK (mode IN ('HIDE', 'GRANT'))
);

CREATE TABLE IF NOT EXISTS global_tab_rule_exception (
    id          BIGSERIAL PRIMARY KEY,
    rule_id     BIGINT NOT NULL,
    user_id     BIGINT NOT NULL,
    created_at  TIMESTAMP,
    CONSTRAINT uk_global_tab_rule_exception UNIQUE (rule_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_global_tab_rule_exception_user ON global_tab_rule_exception (user_id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'global_tab_rule_exception'::regclass
          AND confrelid = 'global_tab_rule'::regclass
          AND contype = 'f'
    ) THEN
        ALTER TABLE global_tab_rule_exception
            ADD CONSTRAINT fk_gtre_rule FOREIGN KEY (rule_id)
            REFERENCES global_tab_rule (id) ON DELETE CASCADE;
    END IF;

    -- app_user is created by Hibernate, so a fresh database may not have it yet.
    IF to_regclass('app_user') IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'global_tab_rule_exception'::regclass
          AND confrelid = 'app_user'::regclass
          AND contype = 'f'
    ) THEN
        ALTER TABLE global_tab_rule_exception
            ADD CONSTRAINT fk_gtre_user FOREIGN KEY (user_id)
            REFERENCES app_user (id) ON DELETE CASCADE;
    END IF;
END $$;
