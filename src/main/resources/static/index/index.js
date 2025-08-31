document.addEventListener('DOMContentLoaded', () => {

    // 1. Mock数据
    const timelineData = [{
        title: '课时一：初识宇宙 - HTML基础',
        time: '2025-09-01',
        description: '探索Web的起源，学习HTML基本结构和常用标签，构建你自己的第一个静态页面，就像发射第一艘探索飞船。'
    },
        {
            title: '课时二：星系之光 - CSS样式',
            time: '2025-09-08',
            description: '为你的宇宙飞船添加色彩和样式。掌握CSS选择器、盒模型、浮动和定位，让页面焕发光彩。'
        },
        {
            title: '课时三：行星轨道 - Flexbox与Grid布局',
            time: '2025-09-15',
            description: '学习现代CSS布局技术，像规划行星轨道一样精确地控制页面元素的位置和对齐。'
        },
        {
            title: '课时四：时空跳跃 - JavaScript入门',
            time: '2025-09-22',
            description: '进入动态世界！学习JavaScript基础语法、变量、数据类型和函数，为你的页面注入生命力。'
        },
        {
            title: '课时五：操控飞船 - DOM操作',
            time: '2025-09-29',
            description: '学习如何使用JavaScript与HTML文档进行交互。动态地创建、修改和删除页面元素，实现用户交互功能。'
        },
        {
            title: '课时六：星际通讯 - API与异步编程',
            time: '2025-10-13',
            description: '掌握与服务器进行数据交换的能力。学习Ajax、Fetch API以及Promise和Async/Await，实现真正的动态应用。'
        },
        {
            title: '课时七：建造空间站 - 前端框架初探',
            time: '2025-10-20',
            description: '了解现代前端框架（如Vue或React）的基本概念和优势，为构建大型复杂应用打下基础。'
        },
        {
            title: '课时八：驶向未来 - 项目实战与部署',
            time: '2025-10-27',
            description: '综合运用所学知识，完成一个完整的项目，并学习如何将其部署到线上，让全世界看到你的作品。'
        }
    ];

    // 2. 动态生成时间线内容
    const timelineContent = document.querySelector('.timeline-content');
    timelineData.forEach((item, index) => {
        const timelineItem = document.createElement('div');
        timelineItem.classList.add('timeline-item');

        const innerHTML = `
            <div class="timeline-item-inner">
                <h3>${item.title}</h3>
                <div class="time">${item.time}</div>
                <p>${item.description}</p>
            </div>
        `;
        timelineItem.innerHTML = innerHTML;
        timelineContent.appendChild(timelineItem);
    });

    // 3. 初始化星空背景
    particlesJS('particles-js', {
        "particles": {
            "number": {
                "value": 120, // 粒子数量
                "density": {
                    "enable": true,
                    "value_area": 800
                }
            },
            "color": {
                "value": "#ffffff" // 粒子颜色
            },
            "shape": {
                "type": "circle",
                "stroke": {
                    "width": 0,
                    "color": "#000000"
                },
            },
            "opacity": {
                "value": 0.8,
                "random": true,
                "anim": {
                    "enable": true,
                    "speed": 1,
                    "opacity_min": 0.1,
                    "sync": false
                }
            },
            "size": {
                "value": 2, // 粒子大小
                "random": true,
                "anim": {
                    "enable": false
                }
            },
            "line_linked": {
                "enable": true,
                "distance": 150,
                "color": "#ffffff",
                "opacity": 0.4,
                "width": 1
            },
            "move": {
                "enable": true,
                "speed": 2, // 移动速度
                "direction": "none",
                "random": false,
                "straight": false,
                "out_mode": "out",
                "bounce": false,
            }
        },
        "interactivity": {
            "detect_on": "canvas",
            "events": {
                "onhover": {
                    "enable": true,
                    "mode": "grab" // 鼠标悬停效果
                },
                "onclick": {
                    "enable": true,
                    "mode": "push" // 鼠标点击效果
                },
                "resize": true
            },
            "modes": {
                "grab": {
                    "distance": 140,
                    "line_linked": {
                        "opacity": 1
                    }
                },
                "bubble": {
                    "distance": 400,
                    "size": 40,
                    "duration": 2,
                    "opacity": 8,
                    "speed": 3
                },
                "repulse": {
                    "distance": 200,
                    "duration": 0.4
                },
                "push": {
                    "particles_nb": 4
                },
                "remove": {
                    "particles_nb": 2
                }
            }
        },
        "retina_detect": true
    });


    // 4. GSAP滚动触发动画
    gsap.registerPlugin(ScrollTrigger);

    const items = document.querySelectorAll('.timeline-item');
    items.forEach(item => {
        gsap.to(item, {
            scrollTrigger: {
                trigger: item,
                start: "top 80%", // 当元素顶部到达视口80%时
                end: "bottom 20%",
                toggleClass: 'is-visible',
                // markers: true, // 调试时可以开启
            }
        });
    });

    // 5. SVG路径渐变定义 (通过JS添加以避免HTML中过长的代码)
    const svgNS = "http://www.w3.org/2000/svg";
    const defs = document.createElementNS(svgNS, 'defs');
    const gradient = document.createElementNS(svgNS, 'linearGradient');
    gradient.setAttribute('id', 'line-gradient');
    gradient.setAttribute('x1', '0%');
    gradient.setAttribute('y1', '0%');
    gradient.setAttribute('x2', '0%');
    gradient.setAttribute('y2', '100%');

    const stop1 = document.createElementNS(svgNS, 'stop');
    stop1.setAttribute('offset', '0%');
    stop1.setAttribute('style', 'stop-color:rgb(0,255,255);stop-opacity:1');
    const stop2 = document.createElementNS(svgNS, 'stop');
    stop2.setAttribute('offset', '100%');
    stop2.setAttribute('style', 'stop-color:rgb(138,43,226);stop-opacity:1');

    gradient.appendChild(stop1);
    gradient.appendChild(stop2);
    defs.appendChild(gradient);

    document.querySelector('.timeline-path svg').prepend(defs);

});