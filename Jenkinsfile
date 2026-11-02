// The publishing pipeline. GitHub Actions (.github/workflows/build.yml) only builds and tests;
// this is the one that publishes:
//
// Taken from org.gecko.weather (branch sunorcloud), with this repository's branches:
//
//   any other branch  build, test
//   snapshot          build, test, release ${tstamp}-SNAPSHOT bundles to the DIM Nexus (dim-snapshot)
//                     and the Portal's snapshot repository
//   main              build, test, release bare-version bundles to the DIM Nexus (dim-release)
//                     and, signed, into dist/bundles, which bnd's sonatype-upload.sh hands to the
//                     Sonatype Central Portal as one deployment
//
// The mechanics live in cnf/ext/release.bnd and the fennec library (releng/*.bnd). DO_RELEASE
// decides the version the bundles are built with, so build and release share one invocation.
// One publish for both branches, set up the way the old weather pipeline ran on this Jenkins:
//   GNUPG_PASSPHRASE   environment of the Jenkins (the old DIM library read exactly this name);
//                      the fennec library's name is GPG_PASSPHRASE, so it is mapped
//   GNUPGHOME          the node's keyring with the signing key, /var/jenkins_home/.gnupg as before
//   dim-nexus          Jenkins credential, username/password: the DIM Nexus login
//   central-portal-token  Jenkins credential, username/password: the Sonatype Central Portal user
//                      token (CS_USERNAME/CS_PASSWORD) — new, the old library spoke to OSSRH
// Optional, for a Jenkins that keeps them as credentials instead: gnupg-passphrase (secret text)
// and gnupg-private-key (secret file, imported into a keyring of this run).
// GPG_PASSPHRASE is what makes the fennec library add the Sonatype repository and sign: a snapshot
// goes to the Portal's snapshot repository, a release is uploaded by centralUpload(). Both targets
// are the point of this stage, so anything missing fails the run with its name.
def publish() {
    def has = { binding ->
        try {
            withCredentials([binding]) { }
            return true
        } catch (e) {
            return false
        }
    }
    def nexus = usernamePassword(credentialsId: 'dim-nexus', usernameVariable: 'NEXUS_USERNAME', passwordVariable: 'NEXUS_PASSWORD')
    def portal = usernamePassword(credentialsId: 'central-portal-token', usernameVariable: 'CS_USERNAME', passwordVariable: 'CS_PASSWORD')
    def passphrase = string(credentialsId: 'gnupg-passphrase', variable: 'GPG_PASSPHRASE')
    def key = file(credentialsId: 'gnupg-private-key', variable: 'GNUPG_KEY_FILE')
    def problems = []
    def creds = []
    if (has(nexus)) creds << nexus else problems << "Jenkins credential 'dim-nexus' (username/password, the Nexus login)"
    if (has(portal)) creds << portal else problems << "Jenkins credential 'central-portal-token' (username/password, the Portal user token)"
    def passphraseFrom
    if (env.GNUPG_PASSPHRASE) {
        passphraseFrom = 'GNUPG_PASSPHRASE of the Jenkins environment'
    } else if (has(passphrase)) {
        creds << passphrase
        passphraseFrom = "Jenkins credential 'gnupg-passphrase'"
    } else {
        problems << "the signing passphrase: neither GNUPG_PASSPHRASE in the environment of node '${env.NODE_NAME}' nor a Jenkins credential 'gnupg-passphrase' (secret text)"
    }
    def importKey = has(key)
    if (importKey) creds << key
    if (problems) {
        error("Cannot publish to the Nexus and Maven Central - missing: ${problems.join('; ')}")
    }
    echo "publish on node '${env.NODE_NAME}': passphrase from ${passphraseFrom}, keyring ${importKey ? 'imported for this run' : env.GNUPGHOME}, credentials ${creds.size()}"
    def body = {
        // The key must be in the keyring, whichever one it is.
        def keys = sh(returnStdout: true, script: 'gpg --batch --list-secret-keys --with-colons 2>/dev/null | grep -c "^sec" || true').trim()
        if (keys == '0' || keys == '') {
            error("No secret key in the keyring ${env.GNUPGHOME} on node '${env.NODE_NAME}' - the signing key has to be there, or come as the Jenkins credential 'gnupg-private-key' (secret file, ASCII export).")
        }
        // What bnd will release to: both names, or the run is pointless. A snapshot goes to the
        // Portal's snapshot repository and the Nexus, a release to the Nexus and dist/bundles,
        // which the upload below hands to the Portal.
        def release = env.DO_RELEASE == 'true'
        def expected = release ? (env.NEXUS_RELEASE == 'false' ? ['Dist'] : ['Release', 'Dist']) : ['Sonatype', 'Release']
        def repos = sh(returnStdout: true, script: './gradlew -q :org.eclipse.paho.client.mqttv3:bndproperties $COMMON_ARGS 2>/dev/null | grep "^-releaserepo:" || true').trim()
        echo "bnd ${repos}"
        if (!expected.every { repos.contains(it) }) {
            error("bnd would release to '${repos}' - expected ${expected.join(' and ')}. DO_RELEASE or GPG_PASSPHRASE did not reach bnd, or maven-central is off (cnf/ext/release.bnd, the fennec library's releng/central.bnd).")
        }
        sh 'rm -rf dist'
        sh './gradlew clean build release $COMMON_ARGS'
        if (release) {
            centralUpload()
        }
    }
    withCredentials(creds) {
        if (importKey) {
            withEnv(["GNUPGHOME=${env.WORKSPACE}/.gnupg-run"]) {
                try {
                    sh 'rm -rf "$GNUPGHOME" && mkdir -p "$GNUPGHOME" && chmod 700 "$GNUPGHOME" && gpg --batch --quiet --import "$GNUPG_KEY_FILE"'
                    body()
                } finally {
                    sh 'rm -rf "$GNUPGHOME"'
                }
            }
        } else {
            body()
        }
    }
}

// The Maven Central half of a release, the way bnd releases itself (bnd docs, 'Sonatype Central
// Portal Publishing'): bnd has written the signed release into dist/bundles; the scripts bnd
// ships upload that directory as one deployment and follow it until the Portal has validated it.
// SONATYPE_PUBLISHING_TYPE USER_MANAGED (the default) leaves the validated deployment waiting
// for 'Publish' at central.sonatype.com/publishing; AUTOMATIC publishes it right away.
def centralUpload() {
    def jars = sh(returnStdout: true, script: 'find dist/bundles -name "*.jar" | wc -l').trim()
    def signed = sh(returnStdout: true, script: 'find dist/bundles -name "*.jar.asc" | wc -l').trim()
    echo "dist/bundles: ${jars} jars, ${signed} signatures"
    if (jars == '0' || jars != signed) {
        error("dist/bundles holds ${jars} jars and ${signed} signatures - the Portal would reject the deployment. Nothing was uploaded to Maven Central.")
    }
    // The Portal's bearer token is base64(username:password) of the user token.
    sh '''#!/bin/bash
        set -euo pipefail
        export SONATYPE_BEARER=$(printf '%s:%s' "$CS_USERNAME" "$CS_PASSWORD" | base64 -w0)
        .github/scripts/sonatype-upload.sh --publishing-type "${SONATYPE_PUBLISHING_TYPE:-USER_MANAGED}" \
            --name "${JOB_BASE_NAME} #${BUILD_NUMBER} $(git rev-parse --short HEAD)" dist/bundles
        # 0: validated or published, 2: still in progress, anything else: failed.
        for i in $(seq 1 90); do
            sleep 10
            rc=0
            .github/scripts/sonatype-status.sh dist/bundles || rc=$?
            [ "$rc" -eq 2 ] || exit "$rc"
            sleep 10
        done
        echo "The Portal did not finish validating within 30 minutes; see central.sonatype.com/publishing" >&2
        exit 1
    '''
}

pipeline {
    agent any

    tools {
        // Gradle runs on JDK 25; the bundles stay Java 8 bytecode (javac --release 8, cnf/ext/fennec.bnd).
        jdk 'OpenJDK25'
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '10'))
        // Two concurrent runs of a branch would publish the same coordinates twice.
        disableConcurrentBuilds()
    }

    environment {
        // A workspace-local artifact cache, so concurrent jobs cannot corrupt each other's.
        COMMON_ARGS = "--no-daemon --stacktrace -Dmaven.repo.local=${WORKSPACE}/.m2"
    }

    stages {
        stage('Clean workspace') {
            steps {
                // Jenkins reuses the checkout. Projects removed from git leave their ignored
                // bin/ and generated/ folders behind, and the bnd workspace plugin takes every
                // root folder for a project — so stale ones get built as empty projects. Drop
                // everything git does not track, except the workspace-local artifact cache.
                sh 'git clean -ffdx -e .m2'
            }
        }

        stage('Build and test') {
            when {
                not { anyOf { branch 'snapshot'; branch 'main' } }
            }
            steps {
                echo "Building ${env.BRANCH_NAME}"
                sh './gradlew clean build $COMMON_ARGS'
            }
        }

        stage('Publish snapshot') {
            when {
                branch 'snapshot'
            }
            environment {
                // ${tstamp}-SNAPSHOT on every bundle, which sends the Release repository to
                // dim-snapshot and the Sonatype repository to the Portal's snapshot repository.
                DO_RELEASE = 'false'
                GPG_PASSPHRASE = "${env.GNUPG_PASSPHRASE ?: ''}"
                GNUPGHOME = "${env.GNUPGHOME ?: '/var/jenkins_home/.gnupg'}"
            }
            steps {
                echo "Publishing a snapshot of ${env.BRANCH_NAME} to the DIM Nexus and the Maven Central snapshots"
                // The public agent (/opt/ci-public/agent) has no connection settings of its own;
                // the Nexus login comes from the dim-nexus credential.
                script { publish() }
            }
        }

        stage('Publish release') {
            when {
                branch 'main'
            }
            environment {
                // Bare versions, which send the Release repository to dim-release; cnf/ext/release.bnd
                // adds Dist (dist/bundles) for the Central upload.
                DO_RELEASE = 'true'
                // GPG_PASSPHRASE switches signing on (the fennec library's releng/central.bnd,
                // '-maven-release: pom, sign'). This Jenkins has carried the
                // passphrase as GNUPG_PASSPHRASE since the old DIM library (which read exactly
                // that); the fennec library's name is GPG_PASSPHRASE, so it is mapped here.
                GPG_PASSPHRASE = "${env.GNUPG_PASSPHRASE ?: ''}"
                // The keyring the old setup signed with; gpg reads GNUPGHOME natively. Set the
                // variable on the agent to use another one.
                GNUPGHOME = "${env.GNUPGHOME ?: '/var/jenkins_home/.gnupg'}"
            }
            steps {
                echo "Releasing ${env.BRANCH_NAME} to the DIM Nexus and Maven Central"
                script { publish() }
            }
        }
    }

    post {
        always {
            junit allowEmptyResults: true,
                  testResults: '*/generated/test-reports/**/TEST-*.xml'
        }
    }
}
