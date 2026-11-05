-- Announcement banners written by BillOps admins and shown at the top of XamOps client dashboards.
--
-- admin_banner holds the heading and body. target_all = true means every client sees it;
-- otherwise admin_banner_target lists the clients that do.
--
-- Written through JPA (AdminBanner) and Hibernate ddl-auto=update may create these tables before
-- this runs, hence IF NOT EXISTS and the guarded FKs. Idempotent.
--
-- To reverse: DROP TABLE admin_banner_target; DROP TABLE admin_banner;

CREATE TABLE IF NOT EXISTS admin_banner (
    id                  BIGSERIAL PRIMARY KEY,
    heading             VARCHAR(200) NOT NULL,
    body                TEXT         NOT NULL,
    target_all          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by          VARCHAR(255),
    created_by_user_id  BIGINT,
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS admin_banner_target (
    banner_id  BIGINT NOT NULL,
    client_id  BIGINT NOT NULL,
    CONSTRAINT pk_admin_banner_target PRIMARY KEY (banner_id, client_id)
);

CREATE INDEX IF NOT EXISTS idx_admin_banner_target_client ON admin_banner_target (client_id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'admin_banner_target'::regclass
          AND confrelid = 'admin_banner'::regclass
          AND contype = 'f'
    ) THEN
        ALTER TABLE admin_banner_target
            ADD CONSTRAINT fk_abt_banner FOREIGN KEY (banner_id)
            REFERENCES admin_banner (id) ON DELETE CASCADE;
    END IF;

    -- client is created by Hibernate, so a fresh database may not have it yet.
    IF to_regclass('client') IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'admin_banner_target'::regclass
          AND confrelid = 'client'::regclass
          AND contype = 'f'
    ) THEN
        ALTER TABLE admin_banner_target
            ADD CONSTRAINT fk_abt_client FOREIGN KEY (client_id)
            REFERENCES client (id) ON DELETE CASCADE;
    END IF;
END $$;
