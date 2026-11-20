-- Runs once, when the PostgreSQL volume is first created (docker-compose).
-- The risk service gets its own database; it never reads the transfer tables.
CREATE DATABASE kirim_risk OWNER kirim;
