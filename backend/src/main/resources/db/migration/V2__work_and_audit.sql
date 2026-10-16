CREATE TABLE work_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    description TEXT NOT NULL,
    group_id BIGINT NOT NULL,
    assignee_id BIGINT NOT NULL,
    creator_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'TODO',
    progress INT NOT NULL DEFAULT 0,
    due_date DATE,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (group_id) REFERENCES department_group(id),
    FOREIGN KEY (assignee_id) REFERENCES app_user(id),
    FOREIGN KEY (creator_id) REFERENCES app_user(id)
);
CREATE INDEX idx_work_assignee ON work_task(assignee_id, status);
CREATE INDEX idx_work_group ON work_task(group_id, status);
CREATE TABLE task_comment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    body VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (task_id) REFERENCES work_task(id),
    FOREIGN KEY (author_id) REFERENCES app_user(id)
);
CREATE TABLE audit_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    target VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
