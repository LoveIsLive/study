const token = localStorage.getItem('authToken');

// 检查token是否存在
if (!token) {
    // 如果不存在，立即重定向到登录页
    // 使用 replace 可以防止用户通过“后退”按钮回到这个未授权的页面
    window.location.replace('/auth');
}