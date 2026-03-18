INSERT INTO gateway_users (login, active, roles, user_grants)
VALUES ('ivanov_ii', TRUE, 'ROLE_USER,ROLE_ANALYST', 'grant.read,grant.export');

INSERT INTO gateway_users (login, active, roles, user_grants)
VALUES ('petrov_pp', TRUE, 'ROLE_USER', 'grant.read');

INSERT INTO gateway_users (login, active, roles, user_grants)
VALUES ('blocked_user', FALSE, 'ROLE_USER', 'grant.read');
