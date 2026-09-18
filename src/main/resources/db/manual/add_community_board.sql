CREATE TABLE IF NOT EXISTS community_posts (
    id BIGSERIAL PRIMARY KEY,
    community_id BIGINT NOT NULL REFERENCES communities(id) ON DELETE CASCADE,
    author_community_user_id BIGINT NOT NULL REFERENCES community_users(id),
    category VARCHAR(20) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    is_notice BOOLEAN NOT NULL DEFAULT FALSE,
    is_pinned BOOLEAN NOT NULL DEFAULT FALSE,
    view_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ NULL,
    CONSTRAINT ck_community_posts_category CHECK (category IN ('FREE', 'QUESTION', 'PARTY', 'REVIEW'))
);

CREATE INDEX IF NOT EXISTS idx_community_posts_feed
    ON community_posts (community_id, is_pinned DESC, is_notice DESC, created_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS community_post_comments (
    id BIGSERIAL PRIMARY KEY,
    post_id BIGINT NOT NULL REFERENCES community_posts(id) ON DELETE CASCADE,
    author_community_user_id BIGINT NOT NULL REFERENCES community_users(id),
    content VARCHAR(2000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ NULL
);

CREATE INDEX IF NOT EXISTS idx_community_post_comments_post
    ON community_post_comments (post_id, created_at, id);
