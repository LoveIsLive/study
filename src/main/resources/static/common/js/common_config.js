
const common_config = {
    tokenName: 'authToken',
    LARGE_FILE_THRESHOLD: 10 * 1024 * 1024, // 10MB
    CHUNK_UPLOAD_CONCURRENCY: 4,

    // 后端常量配置
    back_base_url: 'http://127.0.0.1:8080/api/v1',
    back_AUTH_PREFIX: '/auth',
    back_INDEX_PREFIX: '/index',

    // 前端常量配置
    front_AUTH_PREFIX: '/auth',
    front_HOME_PAGE_URL: "/"
}

