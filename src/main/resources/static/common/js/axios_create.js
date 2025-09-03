const axiosCreate = (base_url) => {
    // 创建一个 Axios 实例
    const apiClient = axios.create({
        baseURL: common_config.back_base_url + base_url
    });

    // 添加请求拦截器
    apiClient.interceptors.request.use(config => {
        // 从 localStorage 获取 token
        const token = localStorage.getItem(common_config.tokenName);

        // 如果 token 存在，则添加到请求头
        if (token) {
            config.headers.Authorization = `Bearer ${token}`;
        }

        return config;
    }, error => {
        return Promise.reject(error);
    });

    // 添加响应拦截器
    apiClient.interceptors.response.use(response => {
        return response;
    }, error => {
        if (error.response && (error.response.status === 401 || error.response.status === 403)) {
            // Token 无效或过期，清除 token 并跳转到登录页
            localStorage.removeItem(common_config.tokenName);
            Swal.fire({
                icon: 'warning',
                title: '认证失效',
                text: '您的登录已过期，请重新登录。',
                showConfirmButton: false,
                timer: 2000
            }).then(() => {
                window.location.href = common_config.front_AUTH_PREFIX;
            });
        }
        return Promise.reject(error);
    });

    return apiClient;
}

