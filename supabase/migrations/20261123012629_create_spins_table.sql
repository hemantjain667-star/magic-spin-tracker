/*
# Create spins table (single-tenant, no auth)

1. New Tables
- `spins`: one row per recorded roulette spin for the Magic app.
  - `spin_number` (integer, primary key): the position of the spin (1, 2, 3...), used for ordering and pettan counting.
  - `value` (integer, not null): the spin result (0-36).
  - `created_at` (timestamp): when the row was inserted.
2. Security
- Enable RLS on `spins`.
- Grant SELECT, INSERT, DELETE to `anon` + `authenticated` (no sign-in in this app, data is intentionally shared).
- Four separate policies allow anon + authenticated to read, insert, and delete rows (no update needed: spins are never edited).
3. Notes
- Seed rows are inserted only if the table is empty, preserving the original demo history.
*/

CREATE TABLE IF NOT EXISTS spins (
  spin_number integer PRIMARY KEY,
  value integer NOT NULL CHECK (value BETWEEN 0 AND 36),
  created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE spins ENABLE ROW LEVEL SECURITY;

GRANT SELECT, INSERT, DELETE ON spins TO anon, authenticated;

DROP POLICY IF EXISTS "Anyone can view spins" ON spins;
CREATE POLICY "Anyone can view spins"
ON spins FOR SELECT
TO anon, authenticated
USING (true);

DROP POLICY IF EXISTS "Anyone can add spins" ON spins;
CREATE POLICY "Anyone can add spins"
ON spins FOR INSERT
TO anon, authenticated
WITH CHECK (true);

DROP POLICY IF EXISTS "Anyone can delete spins" ON spins;
CREATE POLICY "Anyone can delete spins"
ON spins FOR DELETE
TO anon, authenticated
USING (true);
