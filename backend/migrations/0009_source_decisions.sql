ALTER TABLE source_events DROP CONSTRAINT source_events_state_check;
ALTER TABLE source_events ADD CONSTRAINT source_events_state_check CHECK(state IN ('review','queued','failed','dismissed','applied'));
ALTER TABLE source_events ADD COLUMN job jsonb;
ALTER TABLE source_events ADD COLUMN error_code text;
CREATE TABLE transaction_sources (
 workspace_id uuid NOT NULL, event_id uuid NOT NULL, transaction_id uuid NOT NULL,
 PRIMARY KEY(workspace_id,event_id),
 FOREIGN KEY(workspace_id,event_id) REFERENCES source_events(workspace_id,id),
 FOREIGN KEY(workspace_id,transaction_id) REFERENCES transactions(workspace_id,id)
);
CREATE INDEX source_events_queue ON source_events(workspace_id,id) WHERE state='queued';
ALTER TABLE source_events ADD COLUMN job_history jsonb NOT NULL DEFAULT '[]'::jsonb CHECK(jsonb_typeof(job_history)='array' AND jsonb_array_length(job_history)<=100);
ALTER TABLE source_events ADD COLUMN queued_at timestamptz;
