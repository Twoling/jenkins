// 多个仓库，推送单个仓库
def encryption(file) {
    stage('encry file') {
        base64_value = sh (
            script: "cat $file | base64 -w 0",
            returnStdout: true
        ).trim()
    }
    return base64_value
}

node('master') {
    def context_dir='configs'
    def secret_dir='yaml-teamplates'
    def app=env.APP
    def cfg_name=context_dir + '/' + app + '/' + 'alertmanager.yaml'
    def cfg_seret_name=secret_dir + '/' + 'apps' + '/' + app + '/' + 'alertmanager' + '/' + 'alertmanager-secret.yaml'

    stage('init') {
        sh "test -d $context_dir || mkdir $context_dir"
        sh "test -d $secret_dir || mkdir $secret_dir"
    }

    stage('GitSCM') {
        dir(context_dir) {
            git url: 'http://git.om.iwgame.com/liaoyulong/configs.git', credentialsId: '0629e9e8-f1e4-4e33-9b78-13e7b3cfcd21'
        }
        dir(secret_dir) {
            git url: 'http://git.om.iwgame.com/liaoyulong/yaml-templates.git', credentialsId: '0629e9e8-f1e4-4e33-9b78-13e7b3cfcd21'
        }
    }

    stage('check new file') {
        dir(context_dir + '/' + app) {
            files = sh (
                script: "ls",
                returnStdout: true
            )
        }
    }

    stage('modify') {
        files = files.split()
        for(file in files) {
            encry_text = encryption(context_dir + '/' + app + '/' + file)
            sh """
                test -f $cfg_seret_name && \
                { grep $file $cfg_seret_name && \
                sed -r -i "s@(${file}: ).*@\\1${encry_text}@g" $cfg_seret_name || \
                echo "  ${file}: $encry_text" >> $cfg_seret_name \\ ;} || \
                exit 127
            """
        }
    }

    stage('Git Push') {
        dir('yaml-teamplates') {
            sh """
                git config --global user.email "liaoyulong@iwgame.com"
                git config --global user.name "liaoyulong"
                git add .
                git commit -m "${BUILD_ID}"
            """
            withCredentials([usernamePassword(credentialsId: 'gitlab-ci', passwordVariable: 'GITLAB_PASS', usernameVariable: 'GITLAB_USER')]) {
                sh('git push http://${GITLAB_USER}:${GITLAB_PASS}@git.om.iwgame.com/liaoyulong/yaml-templates.git')
            }
        }
    }
}
