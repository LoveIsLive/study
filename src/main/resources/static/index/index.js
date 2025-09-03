document.addEventListener('DOMContentLoaded', async () => {
    const indexAPI = axiosCreate(common_config.back_INDEX_PREFIX);

    // 1. 数据
    const timelineData = await indexAPI.get('/timeline').then(res => res.data.data);

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