document.addEventListener('DOMContentLoaded', () => {
    const API_BASE_URL = 'http://localhost:8080/api/v1';
    const AUTH_PREFIX = "/auth";
    const AUTH_BASE_URL = API_BASE_URL + AUTH_PREFIX;
    const HOME_PAGE_URL = "/";

    const apiClient = axios.create({
        baseURL: AUTH_BASE_URL
    });

    const passwordInput = document.getElementById('password');
    const togglePasswordIcon = document.getElementById('togglePassword');

    togglePasswordIcon.addEventListener('click', function () {
        // 切换密码框的类型 (password/text)
        const type = passwordInput.getAttribute('type') === 'password' ? 'text' : 'password';
        passwordInput.setAttribute('type', type);

        // 切换眼睛图标的样式 (fa-eye / fa-eye-slash)
        this.classList.toggle('fa-eye');
        this.classList.toggle('fa-eye-slash');
    });

    document.getElementById('login-form').addEventListener('submit', async function(event) {
        event.preventDefault(); // 阻止表单默认提交行为

        const username = document.getElementById('username').value;
        const password = document.getElementById('password').value;

        try {
            // 后端登录接口地址
            const response = await apiClient.post('/login', {
                username: username,
                password: password
            });
            console.log(response)

            if (response.data && response.data.code === 200 && response.data.data) {
                // 登录成功
                const token = response.data.data;

                // 将JWT存储在localStorage
                localStorage.setItem('authToken', token);

                // 提示成功并跳转到主页
                Swal.fire({
                    icon: 'success',
                    title: '登录成功!',
                    showConfirmButton: false,
                    timer: 1500
                }).then(() => {
                    window.location.href = HOME_PAGE_URL;
                });

            } else {
                // 后端返回的业务错误
                throw new Error(response.data.message || '登录失败');
            }

        } catch (error) {
            // 网络错误或认证失败 (401, 403等)
            console.log(error)

            Swal.fire({
                icon: 'error',
                title: '登录失败',
                text: error.response.data.message,
            });
        }
    });
})

