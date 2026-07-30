CREATE TABLE courses (
    id VARCHAR(36) PRIMARY KEY,
    slug VARCHAR(120) NOT NULL UNIQUE,
    title VARCHAR(180) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    tech_stack VARCHAR(500) NOT NULL,
    publication_status VARCHAR(20) NOT NULL,
    version INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE course_modules (
    id VARCHAR(36) PRIMARY KEY,
    course_id VARCHAR(36) NOT NULL,
    module_order INT NOT NULL,
    title VARCHAR(180) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    CONSTRAINT fk_course_modules_course FOREIGN KEY (course_id) REFERENCES courses (id),
    CONSTRAINT uk_course_module_order UNIQUE (course_id, module_order)
);

CREATE TABLE lessons (
    id VARCHAR(80) PRIMARY KEY,
    module_id VARCHAR(36) NOT NULL,
    lesson_order INT NOT NULL,
    slug VARCHAR(140) NOT NULL UNIQUE,
    title VARCHAR(180) NOT NULL,
    summary VARCHAR(2000) NOT NULL,
    estimated_minutes INT NOT NULL,
    content_json LONGTEXT NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_lessons_module FOREIGN KEY (module_id) REFERENCES course_modules (id),
    CONSTRAINT uk_lesson_order UNIQUE (module_id, lesson_order)
);

CREATE TABLE lesson_sources (
    id VARCHAR(36) PRIMARY KEY,
    lesson_id VARCHAR(80) NOT NULL,
    source_order INT NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    title VARCHAR(300) NOT NULL,
    url VARCHAR(2048) NOT NULL,
    locator VARCHAR(300),
    bvid VARCHAR(20),
    video_page INT,
    CONSTRAINT fk_lesson_sources_lesson FOREIGN KEY (lesson_id) REFERENCES lessons (id),
    CONSTRAINT uk_lesson_source_order UNIQUE (lesson_id, source_order)
);

CREATE TABLE lesson_progress (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    lesson_id VARCHAR(80) NOT NULL,
    status VARCHAR(20) NOT NULL,
    video_completed BOOLEAN NOT NULL DEFAULT FALSE,
    reading_completed BOOLEAN NOT NULL DEFAULT FALSE,
    practice_completed BOOLEAN NOT NULL DEFAULT FALSE,
    last_section_key VARCHAR(120),
    started_at TIMESTAMP(6),
    completed_at TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_lesson_progress_owner FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT fk_lesson_progress_lesson FOREIGN KEY (lesson_id) REFERENCES lessons (id),
    CONSTRAINT uk_lesson_progress_owner UNIQUE (owner_id, lesson_id)
);

CREATE INDEX idx_course_modules_course ON course_modules (course_id);
CREATE INDEX idx_lessons_module ON lessons (module_id, published);
CREATE INDEX idx_lesson_sources_lesson ON lesson_sources (lesson_id);
CREATE INDEX idx_lesson_progress_owner ON lesson_progress (owner_id, status);
