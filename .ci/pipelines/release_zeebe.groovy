// vim: set filetype=groovy:


def buildName = "${env.JOB_BASE_NAME.replaceAll("%2F", "-").replaceAll("\\.", "-").take(20)}-${env.BUILD_ID}"

pipeline {
    agent {
        kubernetes {
            cloud 'zeebe-ci'
            label "zeebe-ci-release_${buildName}"
            defaultContainer 'jnlp'
            yaml '''\
metadata:
  labels:
    agent: zeebe-ci-build
spec:
  nodeSelector:
    cloud.google.com/gke-nodepool: agents-n1-standard-32-netssd-stable
  tolerations:
    - key: "agents-n1-standard-32-netssd-stable"
      operator: "Exists"
      effect: "NoSchedule"
  containers:
    - name: maven
      image: maven:3.8.4-eclipse-temurin-17
      command: ["cat"]
      tty: true
      env:
        - name: LIMITS_CPU
          valueFrom:
            resourceFieldRef:
              resource: limits.cpu
        - name: JAVA_TOOL_OPTIONS
          value: |
            -XX:+UseContainerSupport
      resources:
        limits:
          cpu: 8
          memory: 32Gi
        requests:
          cpu: 8
          memory: 32Gi
    - name: golang
      image: golang:1.17.11
      command: ["cat"]
      tty: true
      resources:
        limits:
          cpu: 8
          memory: 8Gi
        requests:
          cpu: 8
          memory: 8Gi
'''
        }
    }

    environment {
        NEXUS = credentials('camunda-nexus')
        MAVEN_CENTRAL = credentials('maven_central_deployment_credentials')
        GPG_PASS = credentials('password_maven_central_gpg_signing_key')
        GPG_PUB_KEY = credentials('maven_central_gpg_signing_key_pub')
        GPG_SEC_KEY = credentials('maven_central_gpg_signing_key_sec')
        GITHUB_TOKEN = credentials('github-camunda-zeebe-app')
        RELEASE_VERSION = "${params.RELEASE_VERSION}"
        RELEASE_BRANCH = "release-${params.RELEASE_VERSION}"
        DEVELOPMENT_VERSION = "${params.DEVELOPMENT_VERSION}"
        PUSH_CHANGES = "${params.PUSH_CHANGES}"
        PUSH_DOCKER = "${params.PUSH_DOCKER}"
        SKIP_DEPLOY = "${!params.PUSH_CHANGES}"
        BINDIR = "/usr/local/bin"
    }

    options {
        buildDiscarder(logRotator(daysToKeepStr: '-1', numToKeepStr: '10'))
        skipDefaultCheckout()
        timestamps()
        timeout(time: 60, unit: 'MINUTES')
    }

    stages {
        stage('Prepare') {
            steps {
                git url: 'https://github.com/camunda/zeebe.git',
                        branch: "${env.RELEASE_BRANCH}",
                        credentialsId: 'github-camunda-zeebe-app',
                        poll: false

                container('maven') {
                    sh '.ci/scripts/release/prepare.sh'
                }
                container('golang') {
                    sh '.ci/scripts/release/prepare-go.sh'
                }
            }
        }

        stage('Build') {
            steps {
                container('golang') {
                    sh '.ci/scripts/release/build-go.sh'
                }
                container('maven') {
                    configFileProvider([configFile(fileId: 'maven-nexus-settings-zeebe', variable: 'MAVEN_SETTINGS_XML')]) {
                        sh '.ci/scripts/release/build-java.sh'
                    }
                }
            }
        }

        stage('Maven Release') {
            steps {
                container('maven') {
                    configFileProvider([configFile(fileId: 'maven-nexus-settings-zeebe', variable: 'MAVEN_SETTINGS_XML')]) {
                        sh '.ci/scripts/release/maven-release.sh'
                    }
                }
            }
        }

        stage('Update Compat Version') {
            steps {
                container('golang') {
                    sh '.ci/scripts/release/compat-update-go.sh'
                }
                container('maven') {
                    configFileProvider([configFile(fileId: 'maven-nexus-settings-zeebe', variable: 'MAVEN_SETTINGS_XML')]) {
                        sh '.ci/scripts/release/compat-update-java.sh'
                    }
                }
            }
        }


        stage('GitHub Release') {
            when { expression { return params.PUSH_CHANGES } }
            steps {
                container('maven') {
                    sh '.ci/scripts/release/github-release.sh'
                }

                container('golang') {
                    sh '.ci/scripts/release/post-release-go.sh'
                }
            }
        }

        stage('Docker Image') {
            environment {
                // Retrieve the git commit hash from the checked out tag of the release
                // as when maven performs the perform-release step it will checkout the created tag
                // to the workingDirectory that defaults to target/checkout, see
                // https://maven.apache.org/maven-release/maven-release-plugin/examples/perform-release.html
                // The runners main workdir will already contain next release commits that
                // maven creates once the release is finished, thus the rev would not match the release.
                REVISION = sh(returnStdout: true, script: "git --git-dir target/checkout/.git log -n 1 --pretty=format:'%h'").trim()
            }
            steps {
                build job: 'zeebe-docker', parameters: [
                        string(name: 'BRANCH', value: env.RELEASE_BRANCH),
                        string(name: 'VERSION', value: params.RELEASE_VERSION),
                        string(name: 'REVISION', value: env.REVISION),
                        string(name: 'DATE', value: java.time.Instant.now().toString()),
                        booleanParam(name: 'IS_LATEST', value: params.IS_LATEST),
                        booleanParam(name: 'PUSH', value: params.PUSH_DOCKER),
                        booleanParam(name: 'VERIFY', value: true)
                ]
            }
        }
    }

    post {
        failure {
            slackSend(
                    channel: "#zeebe-ci${jenkins.model.JenkinsLocationConfiguration.get()?.getUrl()?.contains('stage') ? '-stage' : ''}",
                    message: "Release job build ${currentBuild.absoluteUrl} failed!")
        }
    }
}
