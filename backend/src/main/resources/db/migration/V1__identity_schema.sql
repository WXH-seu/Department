CREATE TABLE department_group (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE app_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE app_role (
    code VARCHAR(32) PRIMARY KEY,
    name VARCHAR(100) NOT NULL
);

INSERT INTO app_role (code, name) VALUES
    ('ADMIN', '系统管理员'), ('MINISTER', '部长'),
    ('LEADER', '组长'), ('MEMBER', '部员'), ('GUEST', '访客');

CREATE TABLE user_role (
    user_id BIGINT NOT NULL,
    role_code VARCHAR(32) NOT NULL,
    PRIMARY KEY (user_id, role_code),
    FOREIGN KEY (user_id) REFERENCES app_user(id),
    FOREIGN KEY (role_code) REFERENCES app_role(code)
);

CREATE TABLE group_member (
    group_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    position_name VARCHAR(100),
    is_leader BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (group_id, user_id),
    FOREIGN KEY (group_id) REFERENCES department_group(id),
    FOREIGN KEY (user_id) REFERENCES app_user(id)
);
CREATE INDEX idx_group_member_user ON group_member(user_id);
