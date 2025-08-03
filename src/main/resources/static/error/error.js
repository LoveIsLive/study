document.addEventListener('DOMContentLoaded', () => {

    const errorData = {
        '404': {
            title: '404 - 找不到页面',
            description: "糟糕！您要找的页面似乎去星际旅行了，让我们帮您返回轨道吧。",
            illustrationId: 'illustration-404'
        },
        '500': {
            title: '500 - 服务器开小差了',
            description: "休斯顿，我们遇到了点麻烦... 我们的服务器正在打盹，工程师正在全力叫醒它！",
            illustrationId: 'illustration-500'
        },
        'unknown': {
            title: '发生未知错误',
            description: "发生了一些意料之外的事情。不是您的错，是我们的问题，我们正在调查。",
            illustrationId: 'illustration-500' // 未知错误也显示服务器错误插画
        }
    };

    const params = new URLSearchParams(window.location.search);
    const code = params.get('code') || 'unknown';

    const data = errorData[code] || errorData['unknown'];

    // 更新文本内容
    document.getElementById('error-title').textContent = data.title;
    document.getElementById('error-description').textContent = data.description;

    // 显示对应的插画
    document.getElementById(data.illustrationId).style.display = 'block';
});