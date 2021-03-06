// 测试pipeline

def get_revision() {
    stage('get_revision') {
        echo "===> Get Revision <==="
        revision = sh (
            script: '''
                svn info | egrep 'Revision: [0-9]+' | awk '{print $NF}'
            ''',
            returnStdout: true
        )
    }
    int revision = revision
    return revision
}

def get_change_files(revision) {
    stage('Get Change: ' + revision) {
        withCredentials([usernamePassword(credentialsId: '0f69f126-373a-4734-b13b-7685823c7a21', passwordVariable: 'ci_pass', usernameVariable: 'ci_user')]) {
            change_files = sh (
                script: """
                    svn log -v -q --incremental -r ${revision} --username ${ci_user} --password ${ci_pass}
                """,
                returnStdout: true
            )
        }
        files = sh (
            script: """
                cat > tmp.txt <<EOF
                $change_files
EOF
                grep '/' tmp.txt | awk '{print \$NF}'
            """,
            returnStdout: true
        )
    }
    return files
}

def generate_push_files(files_list) {
    stage('generate_push_files') {
        pf = ""
        for(file in files_list) {
            pf = pf + '**' + file + ','
        }
        return pf
    }
}

def push_files(Host, files) {
    stage('push file') {
        sshPublisher(
            publishers: [
                sshPublisherDesc(
                    // 远程主机，根据参数化构建参数提供
                    configName: Host,
                    transfers: [
                        sshTransfer(
                            cleanRemote: false,
                            excludes: '**/tmp.txt',
                            execCommand: '',
                            execTimeout: 120000,
                            flatten: false,
                            makeEmptyDirs: false,
                            noDefaultExcludes: false,
                            patternSeparator: '[, ]+',
                            // 远程目录位置，可以做成参数化构建通过变量提供
                            remoteDirectory: '/tmp/jenkins-ci',
                            remoteDirectorySDF: false,
                            // 删除路径中的'/'
                            removePrefix: '/',
                            // 拼接文件路径
                            sourceFiles: files
                        )
                    ],
                    usePromotionTimestamp: false,
                    useWorkspaceInPromotion: false,
                    verbose: false
                )
            ]
        )
    }
}

node('master') {
    def Host = env.Host
    stage('init') {
        if (env.INIT == "false") {
            // 获取当前Revision号
            echo "===> Init Revision Number <==="
            if (diy_version) {
                int diy_version = diy_version
                current_revision = diy_version
            } else {
                current_revision = get_revision()
            }
            echo "current_revision: ${current_revision}"
        }
    }

    stage('svn checkout') {
        checkout(
            [
                $class: 'SubversionSCM',
                additionalCredentials: [],
                excludedCommitMessages: '',
                excludedRegions: '',
                excludedRevprop: '',
                excludedUsers: '',
                changelog: true,
                filterChangelog: true,
                ignoreDirPropChanges: false,
                includedRegions: '',
                locations: [
                    [
                        cancelProcessOnExternalsFail: true,
                        credentialsId: '0f69f126-373a-4734-b13b-7685823c7a21',
                        depthOption: 'infinity',
                        ignoreExternalsOption: true,
                        local: '.',
                        remote: 'http://192.168.21.201/jenkins-ci'
                    ]
                ],
                quietOperation: true,
                workspaceUpdater: [$class: 'UpdateUpdater']
            ]
        )
        sh "svn upgrade"
    }

    stage('check max version') {
        if (env.INIT == "true") {
            echo '===> 全量推送 <==='
            exit = false
        } else {
            // 获取最新Revision号
            max_revision = get_revision()
            sh "echo Max_revision: ${max_revision}"
            // 获取差异值
            difference = max_revision - current_revision
            sh "echo Difference: ${difference}"
            // 判断有无更新
            if (difference == 0) {
                exit = true
            }
            // 大于5,全量推送
            if (difference >= 5) {
                INIT = "true"
            }
            // 大于1，初始化变量
            if (difference > 1) {
                sh "echo 'Revision 差异大于1, 开始循环递增更新'"
                current_revision = current_revision + 1
                exit = false
                loop = true
            }
            // 等于1, 修改变量
            if (difference == 1) {
                loop = false
                exit = false
            }
        }
    }

    stage('Deploy') {
        // 无更新推出流水线
        if (exit) {
            echo "未发现更新"
            sh 'exit 0'
        } else {
            // INIT参数为真，全量推送
            if (env.INIT == "true") {
                push_files(Host,'**')
            } else {
                // 判断是否需要循环
                if (loop) {
                    // 生成循环列表
                    versions = sh(
                        script: """
                            seq $current_revision $max_revision
                        """,
                        returnStdout: true
                        )
                    versions = versions.split()
                    // 开始循环
                    for(v in versions) {
                        // 获取 Change log
                        stage('Get Change: ' + v) {
                            files = get_change_files(v)
                        }
                        // 拼接推送文件
                        files = files.split()
                        files = generate_push_files(files)
                        // 推送文件
                        push_files(Host, files)
                    }
                } else {
                    stage('Get Change: ' + max_revision) {
                        files = get_change_files(max_revision)
                    }
                    stage('Push Filels: ' + max_revision) {
                        files = files.split()
                        files = generate_push_files(files)
                        // 推送文件
                        push_files(Host, files)
                    }
                }
            }
        }
    }
    stage('claen') {
        sh 'rm -f tmp.txt'
    }
}
