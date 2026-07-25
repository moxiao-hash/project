CREATE TABLE user_settings (
    user_id VARCHAR(36) PRIMARY KEY,
    time_zone VARCHAR(50) NOT NULL,
    daily_study_limit_minutes INT NOT NULL,
    weekend_preference VARCHAR(10) NOT NULL,
    default_privacy_level VARCHAR(20) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_user_settings_user
        FOREIGN KEY (user_id) REFERENCES app_users (id)
);

CREATE TABLE weekly_availability (
    user_id VARCHAR(36) NOT NULL,
    day_of_week VARCHAR(10) NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    CONSTRAINT fk_weekly_availability_user
        FOREIGN KEY (user_id) REFERENCES app_users (id)
);

CREATE INDEX idx_weekly_availability_user
    ON weekly_availability (user_id);
