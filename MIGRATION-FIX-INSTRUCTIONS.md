# Database Migration Fix Instructions

## Issue
The application failed to start due to a Flyway migration using MySQL syntax on a PostgreSQL database.

**Error**: `ERROR: syntax error at or near "AUTO_INCREMENT"`

**Migration**: `V20260604_2__add_architecture_blueprints.sql`

---

## Root Cause
The migration file used MySQL-specific syntax:
- `AUTO_INCREMENT` → PostgreSQL uses `SERIAL` or `BIGSERIAL`
- `LONGTEXT` → PostgreSQL uses `TEXT`
- `DATETIME` → PostgreSQL uses `TIMESTAMP`

---

## Fix Applied
Updated the migration file to use PostgreSQL syntax:

```sql
-- BEFORE (MySQL):
CREATE TABLE IF NOT EXISTS architecture_blueprints (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    graph_data  LONGTEXT,
    created_at  DATETIME,
    updated_at  DATETIME
);

-- AFTER (PostgreSQL):
CREATE TABLE IF NOT EXISTS architecture_blueprints (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    graph_data  TEXT,
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP
);
```

---

## Database Cleanup Required

Since the migration failed, Flyway has marked it as failed in the `flyway_schema_history` table. You need to repair this before the application can start successfully.

### Option 1: Flyway Repair (Recommended)

If you have Flyway CLI installed:

```bash
# Connect to your database
flyway -url=jdbc:postgresql://your-host:5432/your-database \
       -user=your-user \
       -password=your-password \
       repair
```

### Option 2: Manual Database Fix

Connect to your PostgreSQL database and run:

```sql
-- 1. Check the current status
SELECT * FROM flyway_schema_history 
WHERE version = '20260604.2' 
ORDER BY installed_rank DESC;

-- 2. If the migration shows as failed (success = false), delete the failed entry
DELETE FROM flyway_schema_history 
WHERE version = '20260604.2' 
AND success = false;

-- 3. Verify the table doesn't exist (it was rolled back)
SELECT EXISTS (
    SELECT FROM information_schema.tables 
    WHERE table_name = 'architecture_blueprints'
);
-- Should return 'f' (false)

-- 4. If the table somehow exists, drop it
DROP TABLE IF EXISTS architecture_blueprints;
```

### Option 3: Using Maven Plugin

If you're using Maven:

```bash
cd /Users/apple/Desktop/XamOps-New
mvn flyway:repair -pl xamops-service
```

---

## Verification Steps

After cleaning up the database:

### 1. Rebuild the application
```bash
cd /Users/apple/Desktop/XamOps-New
mvn clean install -DskipTests
```

### 2. Start the application
```bash
java -jar xamops-service/target/xamops-service-1.0.0.jar --spring.profiles.active=uat
```

### 3. Check the logs
Look for:
```
INFO  o.f.core.internal.command.DbMigrate - Migrating schema "public" to version "20260604.2 - add architecture blueprints"
INFO  o.f.core.internal.command.DbMigrate - Successfully applied 1 migration
```

### 4. Verify the table was created
```sql
\d architecture_blueprints

-- Should show:
-- Column      | Type      | Collation | Nullable | Default
-- id          | bigint    |           | not null | nextval('...')
-- name        | varchar   |           | not null |
-- description | text      |           |          |
-- graph_data  | text      |           |          |
-- created_at  | timestamp |           |          |
-- updated_at  | timestamp |           |          |
```

---

## Prevention

### For Future Migrations

Always use PostgreSQL-specific syntax for PostgreSQL databases:

| MySQL | PostgreSQL | Usage |
|-------|------------|-------|
| `INT AUTO_INCREMENT` | `SERIAL` | Auto-incrementing integer |
| `BIGINT AUTO_INCREMENT` | `BIGSERIAL` | Auto-incrementing big integer |
| `TINYTEXT` | `TEXT` | Small text |
| `TEXT` | `TEXT` | Regular text |
| `MEDIUMTEXT` | `TEXT` | Medium text |
| `LONGTEXT` | `TEXT` | Large text |
| `DATETIME` | `TIMESTAMP` | Date and time |
| `DATETIME(6)` | `TIMESTAMP(6)` | Date and time with microseconds |
| `NOW()` | `CURRENT_TIMESTAMP` or `NOW()` | Current timestamp |

### Testing Migrations

Before deploying, test migrations locally:

```bash
# 1. Start PostgreSQL container for testing
docker run --name test-postgres -e POSTGRES_PASSWORD=test -p 5432:5432 -d postgres:15

# 2. Run the application against test database
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/postgres
export SPRING_DATASOURCE_USERNAME=postgres
export SPRING_DATASOURCE_PASSWORD=test
java -jar xamops-service/target/xamops-service-1.0.0.jar

# 3. Verify migrations succeeded
docker exec test-postgres psql -U postgres -c "SELECT * FROM flyway_schema_history ORDER BY installed_rank;"

# 4. Clean up
docker stop test-postgres && docker rm test-postgres
```

---

## Rollback Plan

If you need to rollback this migration:

```sql
-- Drop the table
DROP TABLE IF EXISTS architecture_blueprints;

-- Remove from Flyway history
DELETE FROM flyway_schema_history WHERE version = '20260604.2';
```

---

## Related Issues

### Flyway Version Warning

The logs show:
```
WARN  o.f.c.i.database.base.Database - Flyway upgrade recommended: 
PostgreSQL 15.13 is newer than this version of Flyway and support has not been tested. 
The latest supported version of PostgreSQL is 14.
```

**Recommendation**: Upgrade Flyway to a version that officially supports PostgreSQL 15.

Current Flyway version in Spring Boot 2.7.5: ~9.x  
Recommended: Upgrade to Flyway 10.x (supports PostgreSQL 15+)

Update in `pom.xml`:
```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
    <version>10.21.0</version>
</dependency>
```

---

## Quick Reference

### Database Connection Info (from logs)
- **Database**: PostgreSQL 15.13
- **Schema**: public
- **Profile**: uat
- **Pool**: UATPool (HikariCP)

### Flyway Configuration (from logs)
- **Out-of-order mode**: Active (migrations may not be reproducible)
- **Total migrations**: 44 validated
- **Current version**: 20260604.1
- **Failed migration**: 20260604.2

---

## Commit Information

**Commit**: `69e574032`  
**Message**: fix: convert MySQL syntax to PostgreSQL in architecture_blueprints migration

---

## Support

If you encounter issues:

1. Check Flyway schema history: `SELECT * FROM flyway_schema_history ORDER BY installed_rank;`
2. Check PostgreSQL version: `SELECT version();`
3. Check existing tables: `\dt`
4. Enable debug logging: `--logging.level.org.flywaydb=DEBUG`

---

**Status**: ✅ Migration file fixed, awaiting database cleanup
**Next Step**: Run Flyway repair or manually clean the database
**Date**: June 8, 2026
