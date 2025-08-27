document.addEventListener('DOMContentLoaded', () => {
    // --- 状态管理 ---
    let currentUser = null;
    let userRoles = getRoles();
    let isTeacher = userRoles.includes("ROLE_TEACHER");
    let isStudent = userRoles.includes("ROLE_STUDENT");

    // 当前视图状态
    let currentView = isTeacher ? 'teacher' : 'student';
    let currentTab = isTeacher ? 'my-homeworks' : 'all-homeworks';

    // 数据缓存
    let homeworksCache = [];
    let submissionsCache = [];
    let currentHomeworkDetail = null;

    // --- 配置 ---
    const API_BASE_URL = 'http://localhost:8080/api/v1';
    const HOMEWORK_API_URL = API_BASE_URL + '/homework';
    const SUBMISSION_API_URL = API_BASE_URL + '/submission';
    const UPLOAD_API_URL = API_BASE_URL + '/attach/upload';
    const DOWNLOAD_API_URL = API_BASE_URL + '/attach/download';

    const CHUNK_SIZE = 10 * 1024 * 1024; // 10MB 分块上传阈值

    // --- DOM 元素 ---
    const teacherView = document.getElementById('teacher-view');
    const studentView = document.getElementById('student-view');
    const loadingSpinner = document.getElementById('loading-spinner');
    const emptyState = document.getElementById('empty-state');
    const emptyMessage = document.getElementById('empty-message');

    // 用户信息
    const userRoleBadge = document.getElementById('user-role-badge');
    const userName = document.getElementById('user-name');

    // 教师视图元素
    const publishHomeworkBtn = document.getElementById('publish-homework-btn');
    const myHomeworksTab = document.getElementById('my-homeworks-tab');
    const allSubmissionsTab = document.getElementById('all-submissions-tab');
    const myHomeworksSection = document.getElementById('my-homeworks-section');
    const allSubmissionsSection = document.getElementById('all-submissions-section');
    const teacherHomeworkList = document.getElementById('teacher-homework-list');
    const allSubmissionsList = document.getElementById('all-submissions-list');

    // 学生视图元素
    const allHomeworksTab = document.getElementById('all-homeworks-tab');
    const mySubmissionsTab = document.getElementById('my-submissions-tab');
    const allHomeworksSection = document.getElementById('all-homeworks-section');
    const mySubmissionsSection = document.getElementById('my-submissions-section');
    const studentHomeworkList = document.getElementById('student-homework-list');
    const studentSubmissionsList = document.getElementById('student-submissions-list');
    const statusFilter = document.getElementById('status-filter');

    // 模态框
    const publishHomeworkModal = document.getElementById('publish-homework-modal');
    const homeworkDetailModal = document.getElementById('homework-detail-modal');
    const submitHomeworkModal = document.getElementById('submit-homework-modal');
    const submissionDetailModal = document.getElementById('submission-detail-modal');

    // --- API 客户端 ---
    const homeworkAPI = axiosCreate(HOMEWORK_API_URL);
    const submissionAPI = axiosCreate(SUBMISSION_API_URL);
    const uploadAPI = axiosCreate(UPLOAD_API_URL);
    const downloadAPI = axiosCreate(DOWNLOAD_API_URL);

    // --- 工具函数 ---
    const showLoading = (show) => {
        loadingSpinner.style.display = show ? 'flex' : 'none';
    };

    const showEmptyState = (show, message = '暂无内容') => {
        emptyState.style.display = show ? 'block' : 'none';
        emptyMessage.textContent = message;
    };

    const toast = (icon, title) => {
        Swal.fire({
            toast: true,
            position: 'top-end',
            icon,
            title,
            showConfirmButton: false,
            timer: 3000,
            timerProgressBar: true
        });
    };

    const formatDate = (dateString) => {
        return new Date(dateString).toLocaleString('zh-CN');
    };

    const formatFileSize = (bytes) => {
        if (bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    };

    // --- 初始化 ---
    const initializeApp = async () => {
        try {
            // 设置用户信息
            const userInfo = getUserInfo();
            currentUser = userInfo;
            userName.textContent = userInfo.username || '用户';

            if (isTeacher) {
                userRoleBadge.textContent = '教师';
                userRoleBadge.className = 'role-badge teacher';
                teacherView.style.display = 'block';
                await loadTeacherData();
            } else if (isStudent) {
                userRoleBadge.textContent = '学生';
                userRoleBadge.className = 'role-badge student';
                studentView.style.display = 'block';
                await loadStudentData();
            }

            setupEventListeners();
        } catch (error) {
            console.error('初始化失败:', error);
            toast('error', '初始化失败');
        }
    };

    // --- 数据加载 ---
    const loadTeacherData = async () => {
        showLoading(true);
        try {
            await loadTeacherHomeworks();
            if (currentTab === 'all-submissions') {
                await loadAllSubmissions();
            }
        } catch (error) {
            console.error('加载教师数据失败:', error);
            toast('error', '加载数据失败');
        } finally {
            showLoading(false);
        }
    };

    const loadStudentData = async () => {
        showLoading(true);
        try {
            await loadAllHomeworks();
            if (currentTab === 'my-submissions') {
                await loadStudentSubmissions();
            }
        } catch (error) {
            console.error('加载学生数据失败:', error);
            toast('error', '加载数据失败');
        } finally {
            showLoading(false);
        }
    };

    const loadTeacherHomeworks = async () => {
        try {
            const response = await homeworkAPI.get('/teacher/all');
            homeworksCache = response.data || [];
            renderTeacherHomeworks();
        } catch (error) {
            console.error('加载教师作业失败:', error);
            throw error;
        }
    };

    const loadAllHomeworks = async () => {
        try {
            const response = await homeworkAPI.get('/all');
            homeworksCache = response.data || [];
            renderStudentHomeworks();
        } catch (error) {
            console.error('加载所有作业失败:', error);
            throw error;
        }
    };

    const loadAllSubmissions = async () => {
        try {
            // 注意：这里需要后端提供一个获取所有提交的接口
            // 当前后端接口中没有这个，需要添加
            console.warn('需要后端提供获取所有提交的接口');
            submissionsCache = [];
            renderAllSubmissions();
        } catch (error) {
            console.error('加载所有提交失败:', error);
            throw error;
        }
    };

    const loadStudentSubmissions = async () => {
        try {
            const response = await submissionAPI.get('/student/all');
            submissionsCache = response.data.data || [];
            renderStudentSubmissions();
        } catch (error) {
            console.error('加载学生提交失败:', error);
            throw error;
        }
    };

    // --- 渲染函数 ---
    const renderTeacherHomeworks = () => {
        if (homeworksCache.length === 0) {
            teacherHomeworkList.innerHTML = '';
            showEmptyState(true, '您还没有发布任何作业');
            return;
        }

        showEmptyState(false);
        teacherHomeworkList.innerHTML = homeworksCache.map(homework => `
            <div class="homework-card fade-in">
                <div class="homework-header-info">
                    <div>
                        <h3 class="homework-title">${homework.title}</h3>
                        <div class="homework-meta">
                            <span><i class="fas fa-calendar"></i> ${formatDate(homework.createTime)}</span>
                            ${homework.attachments ? `<span><i class="fas fa-paperclip"></i> ${homework.attachments.length} 个附件</span>` : ''}
                        </div>
                    </div>
                </div>
                <div class="homework-content">
                    ${homework.content || '暂无详细说明'}
                </div>
                ${homework.attachments && homework.attachments.length > 0 ? `
                    <div class="homework-attachments">
                        <span class="attachment-count">
                            <i class="fas fa-paperclip"></i>
                            ${homework.attachments.length} 个附件
                        </span>
                    </div>
                ` : ''}
                <div class="homework-actions">
                    <button class="btn btn-primary" onclick="viewHomeworkDetail(${homework.id})">
                        <i class="fas fa-eye"></i> 查看详情
                    </button>
                    <button class="btn btn-outline" onclick="viewHomeworkSubmissions(${homework.id})">
                        <i class="fas fa-users"></i> 查看提交
                    </button>
                    <button class="btn btn-danger" onclick="deleteHomework(${homework.id})">
                        <i class="fas fa-trash"></i> 删除
                    </button>
                </div>
            </div>
        `).join('');
    };

    const renderStudentHomeworks = () => {
        if (homeworksCache.length === 0) {
            studentHomeworkList.innerHTML = '';
            showEmptyState(true, '暂无作业');
            return;
        }

        showEmptyState(false);

        // 应用筛选
        let filteredHomeworks = homeworksCache;
        const filterValue = statusFilter.value;

        if (filterValue === 'submitted') {
            filteredHomeworks = homeworksCache.filter(hw => hw.submitted);
        } else if (filterValue === 'not-submitted') {
            filteredHomeworks = homeworksCache.filter(hw => !hw.submitted);
        }

        studentHomeworkList.innerHTML = filteredHomeworks.map(homework => {
            const isSubmitted = homework.submitted; // 假设后端会返回这个字段
            return `
                <div class="homework-card ${isSubmitted ? 'submitted' : 'not-submitted'} fade-in">
                    <div class="homework-header-info">
                        <div>
                            <h3 class="homework-title">${homework.title}</h3>
                            <div class="homework-meta">
                                <span><i class="fas fa-user"></i> ${homework.teacherName || '教师'}</span>
                                <span><i class="fas fa-calendar"></i> ${formatDate(homework.createTime)}</span>
                                ${homework.attachments ? `<span><i class="fas fa-paperclip"></i> ${homework.attachments.length} 个附件</span>` : ''}
                            </div>
                        </div>
                        <span class="homework-status ${isSubmitted ? 'status-submitted' : 'status-not-submitted'}">
                            ${isSubmitted ? '已提交' : '未提交'}
                        </span>
                    </div>
                    <div class="homework-content">
                        ${homework.content || '暂无详细说明'}
                    </div>
                    ${homework.attachments && homework.attachments.length > 0 ? `
                        <div class="homework-attachments">
                            <span class="attachment-count">
                                <i class="fas fa-paperclip"></i>
                                ${homework.attachments.length} 个附件
                            </span>
                        </div>
                    ` : ''}
                    <div class="homework-actions">
                        <button class="btn btn-primary" onclick="viewHomeworkDetail(${homework.id})">
                            <i class="fas fa-eye"></i> 查看详情
                        </button>
                        ${isSubmitted ? `
                            <button class="btn btn-success" onclick="viewMySubmission(${homework.id})">
                                <i class="fas fa-check"></i> 查看提交
                            </button>
                        ` : `
                            <button class="btn btn-outline" onclick="submitHomework(${homework.id})">
                                <i class="fas fa-upload"></i> 提交作业
                            </button>
                        `}
                    </div>
                </div>
            `;
        }).join('');
    };

    const renderAllSubmissions = () => {
        if (submissionsCache.length === 0) {
            allSubmissionsList.innerHTML = '<div class="empty-state"><p>暂无提交记录</p></div>';
            return;
        }

        allSubmissionsList.innerHTML = submissionsCache.map(submission => `
            <div class="submission-item">
                <div class="submission-header">
                    <div class="submission-info">
                        <h4>${submission.homework?.title || '作业'}</h4>
                        <div class="submission-meta">
                            <span><i class="fas fa-user"></i> ${submission.studentName || '学生'}</span>
                            <span><i class="fas fa-calendar"></i> ${formatDate(submission.createTime)}</span>
                            ${submission.attachments ? `<span><i class="fas fa-paperclip"></i> ${submission.attachments.length} 个附件</span>` : ''}
                        </div>
                    </div>
                </div>
                <div class="submission-content">
                    ${submission.content || '无提交内容'}
                </div>
                <div class="submission-actions">
                    <button class="btn btn-primary" onclick="viewSubmissionDetail(${submission.id})">
                        <i class="fas fa-eye"></i> 查看详情
                    </button>
                </div>
            </div>
        `).join('');
    };

    const renderStudentSubmissions = () => {
        if (submissionsCache.length === 0) {
            studentSubmissionsList.innerHTML = '<div class="empty-state"><p>您还没有提交任何作业</p></div>';
            return;
        }

        studentSubmissionsList.innerHTML = submissionsCache.map(submission => `
            <div class="submission-item">
                <div class="submission-header">
                    <div class="submission-info">
                        <h4>${submission.homework?.title || '作业'}</h4>
                        <div class="submission-meta">
                            <span><i class="fas fa-calendar"></i> ${formatDate(submission.createTime)}</span>
                            ${submission.attachments ? `<span><i class="fas fa-paperclip"></i> ${submission.attachments.length} 个附件</span>` : ''}
                        </div>
                    </div>
                </div>
                <div class="submission-content">
                    ${submission.content || '无提交内容'}
                </div>
                <div class="submission-actions">
                    <button class="btn btn-primary" onclick="viewSubmissionDetail(${submission.id})">
                        <i class="fas fa-eye"></i> 查看详情
                    </button>
                </div>
            </div>
        `).join('');
    };

    // --- 事件处理 ---
    const setupEventListeners = () => {
        // 标签页切换
        if (isTeacher) {
            myHomeworksTab?.addEventListener('click', () => switchTab('my-homeworks'));
            allSubmissionsTab?.addEventListener('click', () => switchTab('all-submissions'));
            publishHomeworkBtn?.addEventListener('click', () => openPublishModal());
        }

        if (isStudent) {
            allHomeworksTab?.addEventListener('click', () => switchTab('all-homeworks'));
            mySubmissionsTab?.addEventListener('click', () => switchTab('my-submissions'));
            statusFilter?.addEventListener('change', renderStudentHomeworks);
        }

        // 模态框关闭
        document.querySelectorAll('.close-btn').forEach(btn => {
            btn.addEventListener('click', (e) => {
                e.target.closest('.modal').style.display = 'none';
            });
        });

        // 点击模态框外部关闭
        window.addEventListener('click', (e) => {
            if (e.target.classList.contains('modal')) {
                e.target.style.display = 'none';
            }
        });

        // 发布作业表单
        setupPublishHomeworkForm();

        // 提交作业表单
        setupSubmitHomeworkForm();
    };

    const switchTab = async (tabName) => {
        currentTab = tabName;

        if (isTeacher) {
            // 教师标签页切换
            myHomeworksTab.classList.toggle('active', tabName === 'my-homeworks');
            allSubmissionsTab.classList.toggle('active', tabName === 'all-submissions');

            myHomeworksSection.style.display = tabName === 'my-homeworks' ? 'block' : 'none';
            allSubmissionsSection.style.display = tabName === 'all-submissions' ? 'block' : 'none';

            if (tabName === 'all-submissions' && submissionsCache.length === 0) {
                await loadAllSubmissions();
            }
        } else if (isStudent) {
            // 学生标签页切换
            allHomeworksTab.classList.toggle('active', tabName === 'all-homeworks');
            mySubmissionsTab.classList.toggle('active', tabName === 'my-submissions');

            allHomeworksSection.style.display = tabName === 'all-homeworks' ? 'block' : 'none';
            mySubmissionsSection.style.display = tabName === 'my-submissions' ? 'block' : 'none';

            if (tabName === 'my-submissions' && submissionsCache.length === 0) {
                await loadStudentSubmissions();
            }
        }
    };

    // --- 发布作业相关 ---
    const openPublishModal = () => {
        publishHomeworkModal.style.display = 'block';
        resetPublishForm();
    };

    const resetPublishForm = () => {
        document.getElementById('publish-homework-form').reset();
        document.getElementById('small-files-list').innerHTML = '';
        document.getElementById('large-files-list').innerHTML = '';
        smallFiles = [];
        largeFiles = [];
        largeFileUploadIds = [];
    };

    let smallFiles = [];
    let largeFiles = [];
    let largeFileUploadIds = [];

    const setupPublishHomeworkForm = () => {
        const form = document.getElementById('publish-homework-form');
        const smallFileInput = document.getElementById('small-file-input');
        const largeFileInput = document.getElementById('large-file-input');
        const smallFileDropZone = document.getElementById('small-file-drop-zone');
        const largeFileDropZone = document.getElementById('large-file-drop-zone');

        // 小文件处理
        smallFileDropZone.addEventListener('click', () => smallFileInput.click());
        smallFileInput.addEventListener('change', (e) => handleSmallFiles(e.target.files));
        setupDropZone(smallFileDropZone, handleSmallFiles);

        // 大文件处理
        largeFileDropZone.addEventListener('click', () => largeFileInput.click());
        largeFileInput.addEventListener('change', (e) => handleLargeFiles(e.target.files));
        setupDropZone(largeFileDropZone, handleLargeFiles);

        // 表单提交
        form.addEventListener('submit', handlePublishHomework);

        // 取消按钮
        document.getElementById('cancel-publish-btn').addEventListener('click', () => {
            publishHomeworkModal.style.display = 'none';
        });
    };

    const setupDropZone = (dropZone, handler) => {
        ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(eventName => {
            dropZone.addEventListener(eventName, (e) => {
                e.preventDefault();
                e.stopPropagation();
            });
        });

        ['dragenter', 'dragover'].forEach(eventName => {
            dropZone.addEventListener(eventName, () => {
                dropZone.classList.add('dragover');
            });
        });

        ['dragleave', 'drop'].forEach(eventName => {
            dropZone.addEventListener(eventName, () => {
                dropZone.classList.remove('dragover');
            });
        });

        dropZone.addEventListener('drop', (e) => {
            handler(e.dataTransfer.files);
        });
    };

    const handleSmallFiles = (files) => {
        Array.from(files).forEach(file => {
            if (file.size <= CHUNK_SIZE) {
                smallFiles.push(file);
                addFileToList('small-files-list', file, () => removeSmallFile(file));
            } else {
                toast('warning', `文件 ${file.name} 过大，请使用大文件上传`);
            }
        });
    };

    const handleLargeFiles = async (files) => {
        const fileArray = Array.from(files);
        const initData = fileArray.map(file => ({
            originalFileName: file.name,
            fileSize: file.size
        }));

        try {
            const response = await uploadAPI.post('/batch-init', { files: initData });
            const results = response.data.data;

            fileArray.forEach((file, index) => {
                const result = results[index];
                largeFiles.push(file);
                largeFileUploadIds.push(result.uploadId);
                addFileToList('large-files-list', file, () => removeLargeFile(file, index));
            });
        } catch (error) {
            console.error('初始化大文件上传失败:', error);
            toast('error', '初始化大文件上传失败');
        }
    };

    const addFileToList = (listId, file, removeHandler) => {
        const list = document.getElementById(listId);
        const fileItem = document.createElement('div');
        fileItem.className = 'file-item';
        fileItem.innerHTML = `
            <div class="file-info">
                <i class="fas fa-file"></i>
                <span class="file-name">${file.name}</span>
                <span class="file-size">(${formatFileSize(file.size)})</span>
            </div>
            <div class="file-actions">
                <button type="button" class="btn btn-danger" onclick="this.parentElement.parentElement.remove()">
                    <i class="fas fa-times"></i>
                </button>
            </div>
        `;

        fileItem.querySelector('.btn-danger').addEventListener('click', removeHandler);
        list.appendChild(fileItem);
    };

    const removeSmallFile = (file) => {
        const index = smallFiles.indexOf(file);
        if (index > -1) {
            smallFiles.splice(index, 1);
        }
    };

    const removeLargeFile = (file, index) => {
        largeFiles.splice(index, 1);
        largeFileUploadIds.splice(index, 1);
    };

    const handlePublishHomework = async (e) => {
        e.preventDefault();

        const formData = new FormData();
        const dto = {
            teacherId: currentUser.id,
            title: document.getElementById('homework-title').value,
            content: document.getElementById('homework-content').value,
            attachmentUploadIds: largeFileUploadIds
        };

        formData.append('dto', new Blob([JSON.stringify(dto)], { type: 'application/json' }));

        // 添加小文件
        smallFiles.forEach(file => {
            formData.append('files', file);
        });

        try {
            showLoading(true);

            // 如果有大文件，先上传大文件
            if (largeFiles.length > 0) {
                await uploadLargeFiles();
            }

            const response = await homeworkAPI.post('/publish', formData, {
                headers: { 'Content-Type': 'multipart/form-data' }
            });

            toast('success', '作业发布成功');
            publishHomeworkModal.style.display = 'none';
            await loadTeacherHomeworks();
        } catch (error) {
            console.error('发布作业失败:', error);
            toast('error', error.response?.data?.message || '发布作业失败');
        } finally {
            showLoading(false);
        }
    };

    const uploadLargeFiles = async () => {
        for (let i = 0; i < largeFiles.length; i++) {
            const file = largeFiles[i];
            const uploadId = largeFileUploadIds[i];
            await uploadFileInChunks(file, uploadId);
        }
    };

    const uploadFileInChunks = async (file, uploadId) => {
        const totalChunks = Math.ceil(file.size / CHUNK_SIZE);

        for (let chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
            const start = chunkIndex * CHUNK_SIZE;
            const end = Math.min(start + CHUNK_SIZE, file.size);
            const chunk = file.slice(start, end);

            const formData = new FormData();
            formData.append('uploadId', uploadId);
            formData.append('chunkIndex', chunkIndex);
            formData.append('totalChunks', totalChunks);
            formData.append('chunk', chunk);

            await uploadAPI.post('/chunk', formData, {
                headers: { 'Content-Type': 'multipart/form-data' }
            });
        }
    };

    // --- 提交作业相关 ---
    const setupSubmitHomeworkForm = () => {
        const form = document.getElementById('submit-homework-form');
        const smallFileInput = document.getElementById('submission-small-file-input');
        const largeFileInput = document.getElementById('submission-large-file-input');
        const smallFileDropZone = document.getElementById('submission-small-file-drop-zone');
        const largeFileDropZone = document.getElementById('submission-large-file-drop-zone');

        // 文件处理（类似发布作业）
        smallFileDropZone.addEventListener('click', () => smallFileInput.click());
        smallFileInput.addEventListener('change', (e) => handleSubmissionSmallFiles(e.target.files));
        setupDropZone(smallFileDropZone, handleSubmissionSmallFiles);

        largeFileDropZone.addEventListener('click', () => largeFileInput.click());
        largeFileInput.addEventListener('change', (e) => handleSubmissionLargeFiles(e.target.files));
        setupDropZone(largeFileDropZone, handleSubmissionLargeFiles);

        // 表单提交
        form.addEventListener('submit', handleSubmitHomework);

        // 取消按钮
        document.getElementById('cancel-submit-btn').addEventListener('click', () => {
            submitHomeworkModal.style.display = 'none';
        });
    };

    let submissionSmallFiles = [];
    let submissionLargeFiles = [];
    let submissionLargeFileUploadIds = [];

    const handleSubmissionSmallFiles = (files) => {
        Array.from(files).forEach(file => {
            if (file.size <= CHUNK_SIZE) {
                submissionSmallFiles.push(file);
                addFileToList('submission-small-files-list', file, () => removeSubmissionSmallFile(file));
            } else {
                toast('warning', `文件 ${file.name} 过大，请使用大文件上传`);
            }
        });
    };

    const handleSubmissionLargeFiles = async (files) => {
        const fileArray = Array.from(files);
        const initData = fileArray.map(file => ({
            originalFileName: file.name,
            fileSize: file.size
        }));

        try {
            const response = await uploadAPI.post('/batch-init', { files: initData });
            const results = response.data.data;

            fileArray.forEach((file, index) => {
                const result = results[index];
                submissionLargeFiles.push(file);
                submissionLargeFileUploadIds.push(result.uploadId);
                addFileToList('submission-large-files-list', file, () => removeSubmissionLargeFile(file, index));
            });
        } catch (error) {
            console.error('初始化大文件上传失败:', error);
            toast('error', '初始化大文件上传失败');
        }
    };

    const removeSubmissionSmallFile = (file) => {
        const index = submissionSmallFiles.indexOf(file);
        if (index > -1) {
            submissionSmallFiles.splice(index, 1);
        }
    };

    const removeSubmissionLargeFile = (file, index) => {
        submissionLargeFiles.splice(index, 1);
        submissionLargeFileUploadIds.splice(index, 1);
    };

    const handleSubmitHomework = async (e) => {
        e.preventDefault();

        const formData = new FormData();
        const dto = {
            homeworkId: parseInt(document.getElementById('submit-homework-id').value),
            content: document.getElementById('submission-content').value,
            attachmentUploadIds: submissionLargeFileUploadIds
        };

        formData.append('dto', new Blob([JSON.stringify(dto)], { type: 'application/json' }));

        // 添加小文件
        submissionSmallFiles.forEach(file => {
            formData.append('files', file);
        });

        try {
            showLoading(true);

            // 如果有大文件，先上传大文件
            if (submissionLargeFiles.length > 0) {
                await uploadSubmissionLargeFiles();
            }

            const response = await submissionAPI.post('/submit', formData, {
                headers: { 'Content-Type': 'multipart/form-data' }
            });

            toast('success', '作业提交成功');
            submitHomeworkModal.style.display = 'none';
            await loadStudentData(); // 重新加载数据
        } catch (error) {
            console.error('提交作业失败:', error);
            toast('error', error.response?.data?.message || '提交作业失败');
        } finally {
            showLoading(false);
        }
    };

    const uploadSubmissionLargeFiles = async () => {
        for (let i = 0; i < submissionLargeFiles.length; i++) {
            const file = submissionLargeFiles[i];
            const uploadId = submissionLargeFileUploadIds[i];
            await uploadFileInChunks(file, uploadId);
        }
    };

    // --- 全局函数（供HTML调用） ---
    window.viewHomeworkDetail = async (homeworkId) => {
        try {
            const homework = homeworksCache.find(h => h.id === homeworkId);
            if (!homework) return;

            currentHomeworkDetail = homework;

            document.getElementById('homework-detail-title').innerHTML = `
                <i class="fas fa-clipboard-list"></i> ${homework.title}
            `;

            document.getElementById('homework-detail-content').innerHTML = `
                <div class="homework-info">
                    <div class="homework-meta">
                        <p><i class="fas fa-user"></i> <strong>发布教师:</strong> ${homework.teacherName || '教师'}</p>
                        <p><i class="fas fa-calendar"></i> <strong>发布时间:</strong> ${formatDate(homework.createTime)}</p>
                        ${homework.updateTime !== homework.createTime ? `<p><i class="fas fa-edit"></i> <strong>更新时间:</strong> ${formatDate(homework.updateTime)}</p>` : ''}
                    </div>
                </div>
                <div class="homework-content">
                    <h4>作业内容:</h4>
                    <p>${homework.content || '暂无详细说明'}</p>
                </div>
                ${homework.attachments && homework.attachments.length > 0 ? `
                    <div class="attachments-list">
                        <h4>附件:</h4>
                        ${homework.attachments.map(attachment => `
                            <div class="attachment-item">
                                <div class="attachment-info">
                                    <i class="fas fa-file"></i>
                                    <span class="attachment-name">${attachment.fileName}</span>
                                    <span class="attachment-size">(${formatFileSize(attachment.fileSize)})</span>
                                </div>
                                <div class="attachment-actions">
                                    <button class="btn btn-primary" onclick="downloadAttachment('${attachment.filePath}', '${attachment.fileName}')">
                                        <i class="fas fa-download"></i> 下载
                                    </button>
                                </div>
                            </div>
                        `).join('')}
                    </div>
                ` : ''}
            `;

            homeworkDetailModal.style.display = 'block';
        } catch (error) {
            console.error('查看作业详情失败:', error);
            toast('error', '查看作业详情失败');
        }
    };

    window.submitHomework = (homeworkId) => {
        const homework = homeworksCache.find(h => h.id === homeworkId);
        if (!homework) return;

        document.getElementById('submit-homework-id').value = homeworkId;
        document.getElementById('submit-homework-title').textContent = homework.title;
        document.getElementById('submit-homework-content').innerHTML = homework.content || '暂无详细说明';

        // 重置表单
        document.getElementById('submit-homework-form').reset();
        document.getElementById('submission-small-files-list').innerHTML = '';
        document.getElementById('submission-large-files-list').innerHTML = '';
        submissionSmallFiles = [];
        submissionLargeFiles = [];
        submissionLargeFileUploadIds = [];

        submitHomeworkModal.style.display = 'block';
    };

    window.viewMySubmission = async (homeworkId) => {
        try {
            const submission = submissionsCache.find(s => s.homeworkId === homeworkId);
            if (submission) {
                viewSubmissionDetail(submission.id);
            } else {
                toast('warning', '未找到提交记录');
            }
        } catch (error) {
            console.error('查看提交失败:', error);
            toast('error', '查看提交失败');
        }
    };

    window.viewSubmissionDetail = async (submissionId) => {
        try {
            const submission = submissionsCache.find(s => s.id === submissionId);
            if (!submission) return;

            document.getElementById('submission-detail-content').innerHTML = `
                <div class="homework-info">
                    <h3>${submission.homework?.title || '作业'}</h3>
                    <div class="submission-meta">
                        <p><i class="fas fa-user"></i> <strong>提交学生:</strong> ${submission.studentName || '学生'}</p>
                        <p><i class="fas fa-calendar"></i> <strong>提交时间:</strong> ${formatDate(submission.createTime)}</p>
                        ${submission.updateTime !== submission.createTime ? `<p><i class="fas fa-edit"></i> <strong>更新时间:</strong> ${formatDate(submission.updateTime)}</p>` : ''}
                    </div>
                </div>
                <div class="submission-content">
                    <h4>提交内容:</h4>
                    <p>${submission.content || '无提交内容'}</p>
                </div>
                ${submission.attachments && submission.attachments.length > 0 ? `
                    <div class="attachments-list">
                        <h4>附件:</h4>
                        ${submission.attachments.map(attachment => `
                            <div class="attachment-item">
                                <div class="attachment-info">
                                    <i class="fas fa-file"></i>
                                    <span class="attachment-name">${attachment.fileName}</span>
                                    <span class="attachment-size">(${formatFileSize(attachment.fileSize)})</span>
                                </div>
                                <div class="attachment-actions">
                                    <button class="btn btn-primary" onclick="downloadAttachment('${attachment.filePath}', '${attachment.fileName}')">
                                        <i class="fas fa-download"></i> 下载
                                    </button>
                                </div>
                            </div>
                        `).join('')}
                    </div>
                ` : ''}
            `;

            submissionDetailModal.style.display = 'block';
        } catch (error) {
            console.error('查看提交详情失败:', error);
            toast('error', '查看提交详情失败');
        }
    };

    window.viewHomeworkSubmissions = async (homeworkId) => {
        // 这里需要后端提供获取特定作业的所有提交的接口
        console.warn('需要后端提供获取特定作业所有提交的接口');
        toast('info', '功能开发中');
    };

    window.deleteHomework = async (homeworkId) => {
        const result = await Swal.fire({
            title: '确认删除',
            text: '确定要删除这个作业吗？此操作不可撤销！',
            icon: 'warning',
            showCancelButton: true,
            confirmButtonColor: '#d33',
            cancelButtonColor: '#3085d6',
            confirmButtonText: '确定删除',
            cancelButtonText: '取消'
        });

        if (result.isConfirmed) {
            try {
                await homeworkAPI.delete(`/${homeworkId}`);
                toast('success', '作业删除成功');
                await loadTeacherHomeworks();
            } catch (error) {
                console.error('删除作业失败:', error);
                toast('error', error.response?.data?.message || '删除作业失败');
            }
        }
    };

    window.downloadAttachment = async (filePath, fileName) => {
        try {
            const response = await downloadAPI.get('/get/downloadId', {
                params: { path: filePath }
            });
            const token = response.data.data;

            const link = document.createElement('a');
            link.href = `${DOWNLOAD_API_URL}/download?path=${encodeURIComponent(filePath)}&token=${token}`;
            link.download = fileName;
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);
        } catch (error) {
            console.error('下载文件失败:', error);
            toast('error', '下载文件失败');
        }
    };

    // --- 启动应用 ---
    initializeApp();
});
