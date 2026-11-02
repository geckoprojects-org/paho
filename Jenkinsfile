pipeline  {
    agent any

    environment {
        JAVA_OPTS = "-Xms4048m -Xmx4048m -XX:MaxMetaspaceSize=2048m -Dgosh.args=--nointeractive ${sh(script:'echo $JAVA_OPTS', returnStdout: true).trim()}"
    }
    tools {
        // Built with JDK 25; the bundles stay Java 8 bytecode (javac --release 8, cnf/ext/fennec.bnd).
        jdk 'OpenJDK25'
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '5'))
    }

    stages {
        stage('Build & Tests') {
            steps {
                script {
                    try {
                        sh './gradlew clean build --info --stacktrace -Dmaven.repo.local=${WORKSPACE}/.m2 --no-daemon'
                    } finally {
                        junit testResults: '**/generated/test-reports/**/TEST-*.xml', skipPublishingChecks: true
                    }
                }
            }
        }
        stage('Main branch release') {
            when {
                branch 'main'
            }
            steps {
                echo "I am building on ${env.BRANCH_NAME}"
                sh "./gradlew clean release -x test -Drelease.dir=$JENKINS_HOME/repo.gecko/release/paho --info --stacktrace -Dmaven.repo.local=${WORKSPACE}/.m2"
            }
        }
        stage('Snapshot branch release') {
            when {
                branch 'snapshot'
            }
            steps  {
                echo "I am building on ${env.JOB_NAME}"
                sh "./gradlew clean release -x test --info --stacktrace -Dmaven.repo.local=${WORKSPACE}/.m2"
                sh "mkdir -p $JENKINS_HOME/repo.gecko/snapshot/paho"
                sh "rm -rf $JENKINS_HOME/repo.gecko/snapshot/paho/*"
                sh "cp -r cnf/release/* $JENKINS_HOME/repo.gecko/snapshot/paho"
            }
        }
    }
}
