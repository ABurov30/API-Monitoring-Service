-- Pass the chosen event UUID: psql -v event_id='uuid' -f scripts/replay.sql
UPDATE api_outbox
SET status='PENDING', attempts=0, next_attempt_at=now(),
    locked_until=NULL, lock_token=NULL, last_error=NULL, sent_at=NULL
WHERE id=:'event_id'::uuid AND status='FAILED'
RETURNING id, status;
