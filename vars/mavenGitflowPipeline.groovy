def call(body) {

    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    if (config.dockerContext == null) {
        config.dockerContext = '.'
    }
    if (config.githubCredentials == null) {
        config.githubCredentials = 'github'
    }

    pipeline {
        agent {
            node {
                label 'maven'
            }
        }
        parameters {
            string(name: 'releaseVersion', defaultValue: '', description: 'Provide the release version (leave blank for no release):')
            string(name: 'developmentVersion', defaultValue: '', description: 'Provide the next development version (leave blank for no release):')
        }
        stages {
            stage('Checkout') {
                steps {
                    script {
                        slackThread = slack.message("Starting ${env.JOB_NAME} [${env.BUILD_NUMBER}]")
                    }
                }
            }
            stage('Create Release') {
                when {
                    expression {
                        return params.releaseVersion != ''
                    }
                }
                steps {
                    script {
                        slack.message("Creating Release: ${params.releaseVersion}", null, slackThread)
                        if (config.mvnSettingsFile == null) {
                            config.mvnSettingsFile = maven.initSettings()
                        }
                        maven.release(params.releaseVersion, params.developmentVersion, config.mvnSettingsFile, false, config.githubCredentials)
                    }
                }
            }
            stage('Building Application') {
                steps {
                    script {
                        slack.message("Building Library", null, slackThread)
                        if (config.mvnSettingsFile == null) {
                            config.mvnSettingsFile = maven.initSettings()
                        }
                        config.version = maven.version()
                        echo "Building version ${config.version}... from branch: ${env.BRANCH_NAME}"
                        maven.build(config.mvnSettingsFile)
                    }
                }
                post {
                    always {
                        junit '**/target/surefire-reports/*.xml'
                    }
                }
            }
            stage('Source Code Analysis') {
                parallel {
                    stage('Sonar Analysis') {
                        steps {
                            script {
                                slack.message("Sonar Analysis", null, slackThread)
                                sonar(config.sonarGithubCredential)
                            }
                        }
                    }
                    stage('Security Scanning') {
                        steps {
                            script {
                                if (!config.skipFortify) {
                                    fortifyStage {
                                        failOnGates = false
                                        useMavenPlugin = config.useFortifyMavenPlugin
                                    }
                                }
                            }
                        }
                    }
                }
            }
            stage('Deploy to Repository') {
                when {
                    not {
                        changeRequest()
                    }
                }
                steps {
                    script {
                        slack.message("Deploy to Repository", null, slackThread)
                        maven.deploy(config.mvnSettingsFile)
                    }
                }
            }
            stage("Build Containers") {
                steps {
                    script {
                        slack.message("Building Containers", null, slackThread)
                        dockerBuild {
                            dockerBuilds = config.dockerBuilds
                            version = config.version
                        }
                    }
                }
            }
            stage('Testing') {
                stages{
                  stage('Functional Testing') {
                      steps {
                        script{
                          slack.message("Executing Functional Tests", null, slackThread)
                          try {
                              def serviceUrl = deployment.deploy(config.deploymentTemplates, config.deploymentParameters, 'functionalTesting')
                              maven.functionalTest(
                                config.mvnSettingsFile,
                                serviceUrl,
                                config.cucumberMavenOpts,
                                config.cucumberReportDirectory,
                                config.cucumberOpts
                              )
                          } finally {
                            if (!(currentBuild.result != null && config.skipUndeploy)) {
                                deployment.undeploy('functionalTesting')
                            }
                          }
                        }
                      }
                  }
                  stage('Performance Testing') {
                      steps {
                        script {
                          slack.message("Executing Functional Tests", null, slackThread)
                          try {
                            def serviceUrl = deployment.deploy(config.deploymentTemplates, config.deploymentParameters, 'performanceTesting')
                             maven.performanceTest(
                               config.mvnSettingsFile,
                               config.perfOpts,
                               serviceUrl
                            )
                          }
                          finally {
                            if (!(currentBuild.result != null && config.skipUndeploy)) {
                                deployment.undeploy('performanceTesting')
                            }
                          }
                        }
                      }
                  }
                }

            }
            stage('Deploy Review Instance') {
                when {
                    expression {
                        return config.deploymentTemplates != null
                    }
                }
                steps {
                  script{
                    slack.message("Deploying Instance to Dev Environment", null, slackThread)
                    deployment.deploy(config.deploymentTemplates, config.deploymentParameters)
                  }

                }
            }
        }
        post {
            always {
              script {
                slack.sendBuildStatus(currentBuild.currentResult, slackThread)
              }
            }
            regression {
              script {
                  slack.sendBuildStatus(currentBuild.currentResult)
              }
            }
            fixed {
              script {
                slack.sendBuildStatus(currentBuild.currentResult)
              }
            }
        }
    }
}
