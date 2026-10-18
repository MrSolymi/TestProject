create table users (
    id            uuid primary key,
    username      varchar(32)  not null unique,
    password_hash varchar(100) not null,
    created_at    timestamptz  not null,
    invited_by    uuid         references users (id)
);

create table invites (
    id         uuid primary key,
    token_hash varchar(64) not null unique,
    created_by uuid        not null references users (id),
    created_at timestamptz not null,
    expires_at timestamptz not null,
    used_by    uuid        references users (id),
    used_at    timestamptz
);

create index invites_created_by_idx on invites (created_by);
