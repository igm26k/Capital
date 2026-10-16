-- Run ONLY in the restored destination, with API/worker and ingress stopped.
-- New generations prevent clients from replaying uncertain commands after a rollback.
BEGIN;
LOCK TABLE sessions,workspaces,sync_heads,sync_groups,sync_changes,sync_snapshots,sync_snapshot_items,csv_imports,csv_rows,auth_challenges,auth_mail,source_events,transaction_sources IN ACCESS EXCLUSIVE MODE;
CREATE TEMP TABLE recovery_previous_generations ON COMMIT DROP AS
 SELECT id,sync_generation_id FROM workspaces;
DELETE FROM auth_challenges; -- also drops queued email proofs restored from an older backup.
UPDATE sessions SET revoked_at=coalesce(revoked_at,clock_timestamp());
DELETE FROM sync_groups; -- cascades only retained journal events; receipts remain historical.
DELETE FROM sync_snapshot_items;
UPDATE sync_snapshots SET payload_cleared_at=coalesce(payload_cleared_at,clock_timestamp());
UPDATE workspaces SET sync_generation_id=gen_random_uuid(),updated_at=clock_timestamp();
INSERT INTO sync_heads(workspace_id,generation_id,last_sequence,min_available_sequence)
 SELECT id,sync_generation_id,0,1 FROM workspaces
 ON CONFLICT(workspace_id) DO UPDATE SET generation_id=excluded.generation_id,last_sequence=0,min_available_sequence=1;
UPDATE source_events SET state='failed',error_code='recovery_required',version=version+1 WHERE state='queued';
UPDATE csv_imports SET state='failed',version=version+1,error_code='sync_generation_conflict' WHERE state='queued';
UPDATE csv_imports SET state='expired',version=version+1,error_code='sync_generation_conflict' WHERE state='ready';
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM workspaces w JOIN recovery_previous_generations p USING(id) WHERE w.sync_generation_id=p.sync_generation_id)
 OR EXISTS(SELECT 1 FROM sessions WHERE revoked_at IS NULL)
 OR EXISTS(SELECT 1 FROM sync_heads WHERE last_sequence<>0 OR min_available_sequence<>1)
 OR EXISTS(SELECT 1 FROM sync_groups) OR EXISTS(SELECT 1 FROM sync_changes)
 OR EXISTS(SELECT 1 FROM sync_snapshot_items) OR EXISTS(SELECT 1 FROM sync_snapshots WHERE payload_cleared_at IS NULL)
 OR EXISTS(SELECT 1 FROM source_events WHERE state='queued')
 OR EXISTS(SELECT 1 FROM auth_challenges) OR EXISTS(SELECT 1 FROM auth_mail)
 OR EXISTS(SELECT 1 FROM csv_imports WHERE state IN ('ready','queued')) THEN
  RAISE EXCEPTION 'Restored database recovery guards failed';
 END IF;
END $$;
COMMIT;
