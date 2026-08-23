-- M9.5 T1: per-user read/dismiss state for an otherwise immutable alert. Kept in its own table so
-- `alert` stays insert-only and one alert can carry independent state per viewing user.

CREATE TABLE alert_state (
    alert_id      UUID NOT NULL REFERENCES alert (id) ON DELETE CASCADE,
    user_id       UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    read_at       TIMESTAMPTZ,
    dismissed_at  TIMESTAMPTZ,
    PRIMARY KEY (alert_id, user_id)
);

CREATE INDEX idx_alert_state_user ON alert_state (user_id);
