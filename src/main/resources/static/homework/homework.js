document.addEventListener('DOMContentLoaded', () => {

    // --- 状态管理 (State) ---
    const state = {
        currentUser: {
            name: getUserName(), // 假设你有一个全局函数可以获取用户ID
            roles: getRoles() // 假设你有一个全局函数可以获取用户角色
        },
        currentView: null,
        // 用于文件上传的状态管理
        filesToUpload: [],
    };

    // --- 配置 (Config) ---
    const API_PREFIX = '/api/v1';
    const homeworkAPI = axiosCreate(`${API_PREFIX}/homework`);
    const submissionAPI = axiosCreate(`${API_PREFIX}/submission`);
    const uploadAPI = axiosCreate(`${API_PREFIX}/attach/upload`);
    const downloadAPI = axiosCreate(`${API_PREFIX}/attach/download`);
    const LARGE_FILE_THRESHOLD = 10 * 1024 * 1024; // 10MB
    const CHUNK_UPLOAD_CONCURRENCY = 4; // 分块上传并发数

    // --- DOM 元素缓存 ---
    const dom = {
        app: document.getElementById('homework-app'),
        mainTitle: document.getElementById('main-title'),
        headerActions: document.getElementById('header-actions'),
        loadingSpinner: document.getElementById('loading-spinner'),
        mainContent: document.getElementById('app-main-content'),
        views: {
            homeworkList: document.getElementById('view-homework-list'),
            submissionList: document.getElementById('view-submission-list'),
            studentSubmissionDetail: document.getElementById('view-student-submission-detail'),
        },
        // 作业列表视图
        studentTabs: document.getElementById('student-tabs'),
        homeworkListContainer: document.getElementById('homework-list-container'),
        mySubmissionListContainer: document.getElementById('my-submission-list-container'),
        // 提交列表视图
        backToHomeworkListBtn: document.getElementById('back-to-homework-list-btn'),
        submissionListTitle: document.getElementById('submission-list-title'),
        submissionListContainer: document.getElementById('submission-list-container'),
        // 学生提交详情视图
        backToStudentHomeworkListBtn: document.getElementById('back-to-student-homework-list-btn'),
        studentSubmissionHomeworkTitle: document.getElementById('student-submission-homework-title'),
        submissionDetailContainer: document.getElementById('submission-detail-container'),
        // 发布作业模态框
        publishModal: document.getElementById('publish-homework-modal'),
        publishForm: document.getElementById('publish-homework-form'),
        publishDragDropArea: document.getElementById('publish-drag-drop-area'),
        publishFileInput: document.getElementById('publish-file-input'),
        publishFileList: document.getElementById('publish-file-list'),
        publishProgressContainer: document.getElementById('publish-upload-progress-container'),
        closeModalBtn: document.querySelector('#publish-homework-modal .close-btn')
    };

    // --- 工具函数 (Utils) ---
    const showLoading = (show) => {
        dom.loadingSpinner.style.display = show ? 'flex' : 'none';
        dom.mainContent.style.display = show ? 'none' : 'block';
    };

    const toast = (icon, title) => Swal.fire({
        toast: true, position: 'top-end', icon, title,
        showConfirmButton: false, timer: 3000, timerProgressBar: true
    });

    const formatDate = (dateString) => new Date(dateString).toLocaleString('zh-CN');

    const formatFileSize = (bytes) => {
        if (bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    };

    const getFileIconClass = (fileName) => {
        const extension = fileName.split('.').pop().toLowerCase();
        const iconMap = {
            'pdf': 'fa-file-pdf', 'doc': 'fa-file-word', 'docx': 'fa-file-word',
            'xls': 'fa-file-excel', 'xlsx': 'fa-file-excel', 'ppt': 'fa-file-powerpoint',
            'pptx': 'fa-file-powerpoint', 'zip': 'fa-file-archive', 'rar': 'fa-file-archive',
            'txt': 'fa-file-alt', 'jpg': 'fa-file-image', 'jpeg': 'fa-file-image',
            'png': 'fa-file-image', 'gif': 'fa-file-image',
        };
        return `fas ${iconMap[extension] || 'fa-file'}`;
    };

    // --- 视图管理 (View Manager) ---
    const showView = (viewName, stateData = {}, replace = false) => {
        Object.values(dom.views).forEach(view => view.style.display = 'none');
        dom.views[viewName].style.display = 'block';
        state.currentView = viewName;

        // 更新URL，支持浏览器前进后退
        const url = new URL(window.location);
        url.hash = `${viewName}${stateData.id ? `/${stateData.id}` : ''}`;
        if (replace) {
            history.replaceState({ view: viewName, data: stateData }, '', url);
        } else {
            history.pushState({ view: viewName, data: stateData }, '', url);
        }
    };

    window.onpopstate = (event) => {
        if (event.state) {
            const { view, data } = event.state;
            switch (view) {
                case 'homeworkList':
                    state.currentUser.roles.includes('ROLE_TEACHER') ? initTeacherDashboard() : initStudentDashboard();
                    break;
                case 'submissionList':
                    renderSubmissionListView(data.id);
                    break;
                case 'studentSubmissionDetail':
                    renderStudentSubmissionDetailView(data.id);
                    break;
                default:
                    // 默认返回主面板
                    state.currentUser.roles.includes('ROLE_TEACHER') ? initTeacherDashboard() : initStudentDashboard();
            }
        }
    };

    // --- 渲染函数 (Render Functions) ---

    // 渲染附件列表的通用函数
    const renderAttachmentList = (attachments) => {
        if (!attachments || attachments.length === 0) {
            return '<p style="color: #888; font-size: 0.9em;">无附件</p>';
        }
        return `
            <h4><i class="fas fa-paperclip"></i> 附件</h4>
            <ul class="attachment-list">
                ${attachments.map(att => `
                    <li class="attachment-item" data-path="${att.filePath}" title="点击下载">
                        <i class="${getFileIconClass(att.fileName)}"></i>
                        <span>${att.fileName} (${formatFileSize(att.fileSize)})</span>
                    </li>
                `).join('')}
            </ul>
        `;
    };

    // 渲染单个作业卡片
    const renderHomeworkCard = (homework) => {
        const isTeacher = state.currentUser.roles.includes('ROLE_TEACHER');
        return `
            <div class="item-card" data-homework-id="${homework.id}">
                <div class="card-header">
                    <div>
                        ${isTeacher ? `<h3 class="card-noclick-title">${homework.title}</h3>` :
                         `<h3 class="card-title">${homework.title}</h3>`}
                    </div>
                    <div class="card-meta">
                        发布于: ${formatDate(homework.createTime)} <br>
                        发布者: ${homework.teacherName}
                    </div>
                </div>
                <div class="card-content">${homework.content || `<i>无提交内容</i>`}</div>
                <div class="card-attachments">
                    ${renderAttachmentList(homework.attachments)}
                </div>
                ${isTeacher ? `
                <div class="card-footer">
                    <button class="btn btn-secondary view-submissions-btn"><i class="fas fa-users"></i> 查看提交</button>
                    <button class="btn btn-danger delete-homework-btn"><i class="fas fa-trash-alt"></i> 删除作业</button>
                </div>
                ` : ''}
            </div>
        `;
    };

    // 教师渲染单个提交记录卡片
    const teacherRenderSubmissionCard = (submission) => {
        const submissionName = `<strong>作业: ${submission.studentId}</strong><br>`;
        return `
            <div class="item-card" data-submission-id="${submission.id}">
                 <div class="card-header">
                     <h3 class="card-noclick-title">${submissionName}</h3>
                     <div class="card-meta">
                         提交于: ${formatDate(submission.createTime)} <br>
                         提交者: ${submission.studentName}
                     </div>
                 </div>
                 <div class="card-content">${submission.content || `<i>无提交内容</i>`}</div>
                 <div class="card-attachments">
                     ${renderAttachmentList(submission.attachments)}
                 </div>
            </div>
        `;
    };

    // 学生渲染单个提交记录卡片
    const studentRenderSubmissionCard = (submission) => {
        const homeworkTitle = submission.homework ? `<strong>作业: ${submission.homework.title}</strong><br>` : '';
        return `
            <div class="item-card" data-submission-id="${submission.id}">
                 <div class="card-header">
                     <h3 class="card-noclick-title">${homeworkTitle}</h3>
                     <div class="card-meta">
                         提交于: ${formatDate(submission.createTime)} <br>
                         提交者: ${submission.studentName}
                     </div>
                 </div>
                 <div class="card-content">${submission.content || `<i>无提交内容</i>`}</div>
                 <div class="card-attachments">
                     ${renderAttachmentList(submission.attachments)}
                 </div>
            </div>
        `;
    };

    // 渲染教师的作业提交列表视图
    const renderSubmissionListView = async (homeworkId) => {
        showView('submissionList', { id: homeworkId });
        showLoading(true);
        try {
            // 首先获取作业详情来显示标题
            const homework = await homeworkAPI.get(`${homeworkId}`).then(res => res.data.data);
            dom.submissionListTitle.textContent = `"${homework.title}" 的提交列表`;

            const response = await submissionAPI.get(`/${homeworkId}/submissions`);
            const submissions = response.data.data;
            if (submissions && submissions.length > 0) {
                dom.submissionListContainer.innerHTML = submissions.map(teacherRenderSubmissionCard).join('');
            } else {
                dom.submissionListContainer.innerHTML = '<p class="placeholder-text">暂无学生提交</p>';
            }
        } catch (error) {
            toast('error', '加载提交列表失败');
            console.error(error);
        } finally {
            showLoading(false);
        }
    };

    // 渲染学生的作业详情/提交视图
    const renderStudentSubmissionDetailView = async (homeworkId, replace = false) => {
        showView('studentSubmissionDetail', { id: homeworkId }, replace);
        showLoading(true);
        try {
            const homework = await homeworkAPI.get(`${homeworkId}`).then(res => res.data.data);
            dom.studentSubmissionHomeworkTitle.textContent = homework.title;

            const submissionRes = await submissionAPI.get(`/student/${homeworkId}/submission`);
            const submission = submissionRes.data.data;

            if (submission) {
                // 已提交，显示提交详情
                dom.submissionDetailContainer.innerHTML = `
                    <div class="submission-detail-card">
                         <h3>我的提交</h3>
                         <p><strong>提交内容:</strong></p>
                         <p>${submission.content || `<i>无提交内容</i>`}</p>
                         <br>
                         ${renderAttachmentList(submission.attachments)}
                    </div>`;
            } else {
                // 未提交，显示提交表单
                dom.submissionDetailContainer.innerHTML = `
                    <div class="submission-form-card">
                        <h3>提交作业</h3>
                        <form id="submit-homework-form" data-homework-id="${homeworkId}">
                            <div class="form-group">
                                <label for="submission-content">提交内容 (选填)</label>
                                <textarea id="submission-content" rows="5" placeholder="可以在此输入文本内容..."></textarea>
                            </div>
                            <div class="form-group">
                                <label>附件</label>
                                <div id="submit-drag-drop-area" class="drag-drop-area">
                                    <i class="fas fa-cloud-upload-alt"></i>
                                    <p>将文件拖拽到此处，或点击选择文件</p>
                                    <input type="file" id="submit-file-input" multiple hidden>
                                </div>
                                <div id="submit-file-list" class="file-list-preview"></div>
                            </div>
                            <div id="submit-upload-progress-container" class="upload-progress-container" style="display: none;"></div>
                             <div class="form-actions">
                                <button type="submit" class="btn btn-primary"><i class="fas fa-check"></i> 确认提交</button>
                            </div>
                        </form>
                    </div>
                `;
                // 必须在innerHTML更新后，重新绑定文件上传的事件
                setupFileUpload('submit');
            }
        } catch (error) {
            if (error.response && error.response.status === 404) {
                // 这属于正常情况，接口返回404说明学生未提交
            } else {
                toast('error', '加载作业详情失败');
                console.error(error);
            }
        } finally {
            showLoading(false);
        }
    }


    // --- 核心业务逻辑 (Core Logic) ---

    // 初始化教师仪表盘
    const initTeacherDashboard = async () => {
        showView('homeworkList');
        dom.studentTabs.style.display = 'none';
        dom.headerActions.innerHTML = `<button id="publish-homework-btn" class="btn btn-primary"><i class="fas fa-plus"></i> 发布作业</button>`;
        document.getElementById('publish-homework-btn').addEventListener('click', () => {
            dom.publishForm.reset();
            state.filesToUpload = [];
            renderFilePreview('publish');
            dom.publishProgressContainer.style.display = 'none';
            dom.publishProgressContainer.innerHTML = '';
            dom.publishModal.style.display = 'block';
        });

        showLoading(true);
        try {
            const response = await homeworkAPI.get('/teacher/all');
            const homeworks = response.data.data;
            if (homeworks && homeworks.length > 0) {
                dom.homeworkListContainer.innerHTML = homeworks.map(renderHomeworkCard).join('');
            } else {
                dom.homeworkListContainer.innerHTML = '<p class="placeholder-text">您还没有发布任何作业</p>';
            }
        } catch (error) {
            toast('error', '加载作业列表失败');
            console.error(error);
        } finally {
            showLoading(false);
        }
    };

    // 初始化学生仪表盘
    const initStudentDashboard = async () => {
        showView('homeworkList');
        dom.headerActions.innerHTML = '';
        dom.studentTabs.style.display = 'flex';
        // 默认显示所有作业
        switchStudentTab('all-homework');
    };

    // 学生Tab切换逻辑
    const switchStudentTab = async (tabName) => {
        document.querySelectorAll('.tab-btn').forEach(btn => btn.classList.remove('active'));
        document.querySelector(`.tab-btn[data-tab="${tabName}"]`).classList.add('active');

        showLoading(true);
        dom.mySubmissionListContainer.style.display = 'none';
        dom.homeworkListContainer.style.display = 'none';

        try {
            if (tabName === 'all-homework') {
                const response = await homeworkAPI.get('/all');
                const homeworks = response.data.data;
                if (homeworks && homeworks.length > 0) {
                    dom.homeworkListContainer.innerHTML = homeworks.map(renderHomeworkCard).join('');
                } else {
                    dom.homeworkListContainer.innerHTML = '<p class="placeholder-text">当前没有作业</p>';
                }
                dom.homeworkListContainer.style.display = 'flex';
            } else if (tabName === 'my-submissions') {
                const response = await submissionAPI.get('/student/all');
                const submissions = response.data.data;
                if (submissions && submissions.length > 0) {
                    dom.mySubmissionListContainer.innerHTML = submissions.map(studentRenderSubmissionCard).join('');
                } else {
                    dom.mySubmissionListContainer.innerHTML = '<p class="placeholder-text">你还没有提交过任何作业</p>';
                }
                dom.mySubmissionListContainer.style.display = 'flex';
            }
        } catch (error) {
            toast('error', `加载${tabName === 'all-homework' ? '作业' : '提交'}列表失败`);
        } finally {
            showLoading(false);
        }
    };

    // --- 文件上传逻辑 (File Upload Logic) ---

    // 设置文件上传区域的事件监听 (type: 'publish' or 'submit')
    const setupFileUpload = (type) => {
        const dragDropArea = document.getElementById(`${type}-drag-drop-area`);
        const fileInput = document.getElementById(`${type}-file-input`);

        dragDropArea.addEventListener('click', () => fileInput.click());
        fileInput.addEventListener('change', (e) => handleFiles(e.target.files));

        ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(eventName => {
            dragDropArea.addEventListener(eventName, e => {
                e.preventDefault();
                e.stopPropagation();
            }, false);
        });
        ['dragenter', 'dragover'].forEach(eventName => {
            dragDropArea.addEventListener(eventName, () => dragDropArea.classList.add('dragover'), false);
        });
        ['dragleave', 'drop'].forEach(eventName => {
            dragDropArea.addEventListener(eventName, () => dragDropArea.classList.remove('dragover'), false);
        });
        dragDropArea.addEventListener('drop', e => handleFiles(e.dataTransfer.files));

        const handleFiles = (files) => {
            for (const file of files) {
                state.filesToUpload.push(file);
            }
            renderFilePreview(type);
        };
    };

    // 渲染文件预览列表
    const renderFilePreview = (type) => {
        const fileListContainer = document.getElementById(`${type}-file-list`);
        fileListContainer.innerHTML = state.filesToUpload.map((file, index) => `
            <div class="file-preview-item" data-index="${index}">
                <span class="file-name">${file.name} (${formatFileSize(file.size)})</span>
                <button type="button" class="remove-file-btn">&times;</button>
            </div>
        `).join('');
    };

    // 文件上传总控制器
    const handleFileUploads = async (type) => {
        const smallFiles = state.filesToUpload.filter(f => f.size <= LARGE_FILE_THRESHOLD);
        const largeFiles = state.filesToUpload.filter(f => f.size > LARGE_FILE_THRESHOLD);
        let largeFileAttachmentIds = [];

        const progressContainer = document.getElementById(`${type}-upload-progress-container`);
        progressContainer.style.display = 'block';
        progressContainer.innerHTML = ''; // 清空旧进度条

        if (largeFiles.length > 0) {
            // 1. 批量初始化大文件上传
            const initDTO = { files: largeFiles.map(f => ({ fileName: f.name, fileSize: f.size, mimeTypeName: f.type })) };
            const initResponse = await uploadAPI.post('/batch-init', initDTO);
            const uploadResults = initResponse.data.data;

            // 2. 并发分块上传
            const uploadPromises = largeFiles.map(file => {
                const result = uploadResults.find(r => r.originalFileName === file.name);
                if (result) {
                    largeFileAttachmentIds.push(result.uploadId);
                    return uploadFileInChunks(file, result.uploadId, type);
                }
                return Promise.resolve();
            });
            // TODO: 这会很慢，待改为异步通知
            await Promise.all(uploadPromises);
        }

        return { smallFiles, largeFileAttachmentIds };
    };

    // 分块上传实现
    const uploadFileInChunks = async (file, uploadId, type) => {
        const totalChunks = Math.ceil(file.size / LARGE_FILE_THRESHOLD);
        const progressContainer = document.getElementById(`${type}-upload-progress-container`);

        // 创建此文件的进度条
        const progressItem = document.createElement('div');
        progressItem.className = 'progress-item';
        progressItem.innerHTML = `
            <p>${file.name}</p>
            <div class="progress-bar">
                <div id="progress-${uploadId}" class="progress-bar-inner" style="width: 0%;">0%</div>
            </div>
        `;
        progressContainer.appendChild(progressItem);
        const progressBarInner = document.getElementById(`progress-${uploadId}`);

        const chunkPromises = [];
        for (let i = 0; i < totalChunks; i++) {
            const start = i * LARGE_FILE_THRESHOLD;
            const end = Math.min(start + LARGE_FILE_THRESHOLD, file.size);
            const chunk = file.slice(start, end);
            const formData = new FormData();
            formData.append('uploadId', uploadId);
            formData.append('chunkIndex', i);
            formData.append('totalChunks', totalChunks);
            formData.append('chunk', chunk);

            // 使用函数包装以实现并发控制
            const task = () => uploadAPI.post('/chunk', formData);
            chunkPromises.push(task);
        }

        // 简易并发控制器
        const executeConcurrent = async (tasks, limit) => {
            const results = [];
            const executing = [];
            let completed = 0;
            for (const task of tasks) {
                const p = Promise.resolve().then(() => task());
                results.push(p);
                executing.push(p);

                const updateProgress = () => {
                    completed++;
                    const percent = Math.round((completed * 100) / totalChunks);
                    progressBarInner.style.width = `${percent}%`;
                    progressBarInner.textContent = `${percent}%`;
                };

                p.then(() => {
                    updateProgress();
                    executing.splice(executing.indexOf(p), 1);
                });

                if (executing.length >= limit) {
                    await Promise.race(executing);
                }
            }
            return Promise.all(results);
        };

        await executeConcurrent(chunkPromises, CHUNK_UPLOAD_CONCURRENCY);
    };


    // --- 事件监听 (Event Listeners) ---

    // 教师: 发布作业表单提交
    dom.publishForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const title = document.getElementById('homework-title').value.trim();
        const content = document.getElementById('homework-content').value.trim();

        if (!title) {
            toast('warning', '作业标题不能为空');
            return;
        }

        const submitBtn = dom.publishForm.querySelector('button[type="submit"]');
        submitBtn.disabled = true;
        submitBtn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> 发布中...';

        try {
            const { smallFiles, largeFileAttachmentIds } = await handleFileUploads('publish');

            const dto = {
                title,
                content,
                attachmentUploadIds: largeFileAttachmentIds
            };

            const formData = new FormData();
            formData.append('dto', new Blob([JSON.stringify(dto)], { type: 'application/json' }));
            smallFiles.forEach(file => {
                formData.append('files', file);
            });

            await homeworkAPI.post('/publish', formData);
            toast('success', '作业发布成功!');
            dom.publishModal.style.display = 'none';
            initTeacherDashboard();

        } catch (error) {
            console.error(error);
            toast('error', '发布失败，请稍后重试');
        } finally {
            submitBtn.disabled = false;
            submitBtn.innerHTML = '<i class="fas fa-paper-plane"></i> 立即发布';
        }
    });

    // 主内容区的事件委托
    dom.mainContent.addEventListener('click', async (e) => {
        const target = e.target;

        // --- 通用事件 ---
        // 下载附件
        const attachmentItem = target.closest('.attachment-item');
        if (attachmentItem) {
            const path = attachmentItem.dataset.path;
            try {
                const res = await downloadAPI.get('/get/downloadId', { params: { path } });
                const token = res.data.data;
                const downloadUrl = `${downloadAPI.defaults.baseURL}/download?path=${path}&token=${token}`;
                window.open(downloadUrl, '_blank');
            } catch (error) {
                toast('error', '获取下载链接失败');
            }
        }

        // --- 教师事件 ---
        // 查看提交
        if (target.classList.contains('view-submissions-btn')) {
            const homeworkId = target.closest('.item-card').dataset.homeworkId;
            renderSubmissionListView(homeworkId);
        }
        // 删除作业
        if (target.classList.contains('delete-homework-btn')) {
            const card = target.closest('.item-card');
            const homeworkId = card.dataset.homeworkId;
            const homeworkTitle = card.querySelector('.card-title').textContent;

            const result = await Swal.fire({
                title: `确认删除作业 "${homeworkTitle}"?`,
                text: "此操作将一并删除所有学生的提交记录，且无法恢复！",
                icon: 'warning',
                showCancelButton: true,
                confirmButtonColor: '#d33',
                cancelButtonText: '取消',
                confirmButtonText: '确认删除'
            });

            if (result.isConfirmed) {
                try {
                    await homeworkAPI.delete(`/${homeworkId}`);
                    toast('success', '作业已删除');
                    initTeacherDashboard();
                } catch (error) {
                    toast('error', '删除失败');
                }
            }
        }

        // --- 学生事件 ---
        // 学生点击作业卡片标题，进入提交/查看页面
        const isStudent = !state.currentUser.roles.includes('ROLE_TEACHER');
        const cardTitle = target.closest('.card-title');
        if (isStudent && cardTitle) {
            const homeworkId = target.closest('.item-card').dataset.homeworkId;
            if (homeworkId) {
                renderStudentSubmissionDetailView(homeworkId);
            }
        }

        // 学生切换Tab
        const tabBtn = target.closest('.tab-btn');
        if (isStudent && tabBtn && !tabBtn.classList.contains('active')) {
            switchStudentTab(tabBtn.dataset.tab);
        }
    });

    // 返回按钮
    dom.backToHomeworkListBtn.addEventListener('click', () => history.back());
    dom.backToStudentHomeworkListBtn.addEventListener('click', () => history.back());

    // 动态添加的表单提交事件 (学生提交作业)
    dom.submissionDetailContainer.addEventListener('submit', async (e) => {
        if (e.target.id === 'submit-homework-form') {
            e.preventDefault();
            const form = e.target;
            const homeworkId = form.dataset.homeworkId;
            const content = form.querySelector('#submission-content').value;

            const submitBtn = form.querySelector('button[type="submit"]');
            submitBtn.disabled = true;
            submitBtn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> 正在提交...';

            try {
                const { smallFiles, largeFileAttachmentIds } = await handleFileUploads('submit');
                const dto = { homeworkId, content, attachmentUploadIds: largeFileAttachmentIds };

                const formData = new FormData();
                formData.append('dto', new Blob([JSON.stringify(dto)], { type: 'application/json' }));
                smallFiles.forEach(file => formData.append('files', file));

                await submissionAPI.post('/submit', formData);
                toast('success', '作业提交成功!');
                // 提交成功后，刷新当前页面显示已提交内容
                renderStudentSubmissionDetailView(homeworkId, true);

            } catch (error) {
                toast('error', '提交失败');
            } finally {
                submitBtn.disabled = false;
                submitBtn.innerHTML = '<i class="fas fa-check"></i> 确认提交';
            }
        }
    });

    // 动态添加的文件移除事件
    document.addEventListener('click', e => {
        if (e.target.classList.contains('remove-file-btn')) {
            const index = parseInt(e.target.closest('.file-preview-item').dataset.index, 10);
            state.filesToUpload.splice(index, 1);
            // 推断是哪个表单的预览
            const type = e.target.closest('#publish-file-list') ? 'publish' : 'submit';
            renderFilePreview(type);
        }
    });


    // 模态框关闭
    dom.closeModalBtn.onclick = () => dom.publishModal.style.display = 'none';
    window.onclick = (event) => {
        if (event.target === dom.publishModal) {
            dom.publishModal.style.display = 'none';
        }
    };


    // --- 初始化 (Initialization) ---
    const initialize = () => {
        if (state.currentUser.roles.includes('ROLE_TEACHER')) {
            initTeacherDashboard();
        } else {
            initStudentDashboard();
        }
        setupFileUpload('publish');
    };

    initialize();
});