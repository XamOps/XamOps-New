pipeline {
    agent any
    environment {
        GHCR_USER = 'sahil-remoterepo'
        REGISTRY = 'ghcr.io'
    }
    stages {
        stage('Checkout') {
            steps { checkout scm }
        }

        stage('Login to GHCR') {
            steps {
                withCredentials([string(credentialsId: 'github-pat', variable: 'PAT')]) {
                    sh 'echo $PAT | docker login ghcr.io -u sahil-remoterepo --password-stdin'
                }
            }
        }

        stage('Build & Push Images') {
            parallel {
                stage('Service Registry') { steps { buildAndPush('service-registry', './service-registry/Dockerfile') } }
                stage('Billops')         { steps { buildAndPush('billops', './billops/Dockerfile') } }
                stage('Xamops Service')  { steps { buildAndPush('xamops-service', './xamops-service/Dockerfile') } }
                stage('Frontend')        { steps { buildAndPush('frontend-app', './frontend-app/Dockerfile', './frontend-app') } }
            }
        }

        stage('Deploy to SIT') {
            steps {
                withCredentials([string(credentialsId: 'github-pat', variable: 'PAT')]) {
                    sh '''
                        ssh -o StrictHostKeyChecking=no ec2-user@13.204.166.9 "
                            echo '$PAT' | docker login ghcr.io -u sahil-remoterepo --password-stdin

                            docker pull ghcr.io/sahil-remoterepo/service-registry:latest
                            docker pull ghcr.io/sahil-remoterepo/billops:latest
                            docker pull ghcr.io/sahil-remoterepo/xamops-service:latest
                            docker pull ghcr.io/sahil-remoterepo/frontend-app:latest

                            docker network create xamops-network || true

                            docker stop service-registry billops xamops-service frontend-app || true
                            docker rm   service-registry billops xamops-service frontend-app || true

                            docker run -d -p 8761:8761 --name service-registry --network xamops-network ghcr.io/sahil-remoterepo/service-registry:latest
                            docker run -d -p 8082:8082 --name billops --network xamops-network ghcr.io/sahil-remoterepo/billops:latest
                            docker run -d -p 8080:8080 --name xamops-service --network xamops-network --restart always \
                              -e SPRING_PROFILES_ACTIVE=sit \
                              -e DB_HOST=172.31.9.24 \
                              -e DB_USER=xamops_local_user \
                              -e DB_PASS=XamOps@123 \
                              -e DB_NAME=xamops_local_db \
                              -e EUREKA_CLIENT_SERVICEURL_DEFAULTZONE=http://service-registry:8761/eureka/ \
                              -e REDIS_HOST=redis-sit \
                              -e REDIS_PORT=6379 \
                              -e \"GROQ_API_KEY=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\" \
                              ghcr.io/sahil-remoterepo/xamops-service:latest
                            docker run -d --name frontend-app --network xamops-network ghcr.io/sahil-remoterepo/frontend-app:latest
                        "
                    "
                    '''
                }
            }
        }
    }
    post { always { cleanWs() } }
}

def buildAndPush(imageName, dockerfile, context = '.') {
    script {
        def img = docker.build("${env.REGISTRY}/${env.GHCR_USER}/${imageName}:latest", "-f ${dockerfile} ${context}")
        img.push()
    }
}
