document.addEventListener('DOMContentLoaded', function () {

    // --- 模块1: 动态设置当前激活的导航Tab ---
    function setActiveTab() {
        const currentPath = window.location.pathname;
        const navLinks = document.querySelectorAll('.header-nav a');

        navLinks.forEach(link => {
            // 如果链接的href与当前路径匹配，则添加active类
            if (link.getAttribute('href') === currentPath) {
                link.classList.add('active');
            }
        });
    }

    // --- 模块2: 用户信息下拉菜单交互 ---
    const userProfileTrigger = document.getElementById('user-profile-trigger');
    const userDropdownMenu = document.getElementById('user-dropdown-menu');

    if (userProfileTrigger && userDropdownMenu) {
        userProfileTrigger.addEventListener('click', function (event) {
            // 阻止事件冒泡，防止立即被window的click事件关闭
            event.stopPropagation();
            userDropdownMenu.classList.toggle('show');
        });

        // 点击页面其他地方，关闭下拉菜单
        window.addEventListener('click', function () {
            if (userDropdownMenu.classList.contains('show')) {
                userDropdownMenu.classList.remove('show');
            }
        });
    }

    // --- 模块3: 加载并显示用户信息 ---
    function fetchAndDisplayUserData() {
        const roles = getRoles();
        const userInfo = {
            name: getUserName(), // 或者 '李同学'
            role: roles.includes("ROLE_TEACHER") ? '教师' : '学生',   // 或者 '学生'
            avatarUrl: 'https://placehold.co/100x100/4A90E2/FFFFFF?text=W' // 生成一个带首字母的头像
        };

        // 更新导航栏的用户信息
        document.getElementById('user-avatar').src = userInfo.avatarUrl;
        document.getElementById('user-name').textContent = userInfo.name;

        // 更新下拉菜单的用户信息
        document.getElementById('dropdown-user-avatar').src = userInfo.avatarUrl;
        document.getElementById('dropdown-user-name').textContent = userInfo.name;
        document.getElementById('dropdown-user-role').textContent = userInfo.role;
    }


    // --- 模块4: 退出登录 ---
    const logoutBtn = document.getElementById('logout-btn');
    if (logoutBtn) {
        logoutBtn.addEventListener('click', function () {
            // 弹出确认框，增强用户体验
            Swal.fire({
                title: '您确定要退出吗?',
                icon: 'warning',
                showCancelButton: true,
                confirmButtonColor: '#3085d6',
                cancelButtonColor: '#d33',
                confirmButtonText: '确定退出',
                cancelButtonText: '取消'
            }).then((result) => {
                if (result.isConfirmed) {
                    // 1. 调用后端的登出接口
                    // 2. 清除本地存储的token或session
                    localStorage.removeItem(common_config.tokenName);

                    // 3. 跳转到登录页面
                    window.location.href = common_config.front_AUTH_PREFIX;
                }
            });
        });
    }


    // --- 初始化函数调用 ---
    setActiveTab();
    fetchAndDisplayUserData();

});