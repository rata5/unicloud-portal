-- A user created by someone else (an inspector, an admin, or the first-admin bootstrap) must
-- choose a new password at the first sign-in (accounts-and-sign-in spec). The flag is set when
-- such a user is created, and cleared when the user changes the password. The PostgreSQL copy is
-- db/migration-postgresql/V6__users_must_change_password.sql.
-- Existing users keep signing in as before: the default is FALSE.
ALTER TABLE users
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE AFTER enabled;
