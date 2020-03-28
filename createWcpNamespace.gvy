pipeline
{
    agent {
        node {
            label 'GCM_jumphost'
            customWorkspace "workspace/$JOB_NAME/$BUILD_NUMBER"
        }
    }

    environment {
        GOSRCDIR = '/go/src/gitlab.eng.vmware.com'

        // Ref - https://issues.jenkins-ci.org/browse/JENKINS-52850
        // The credentials binding plugin does not support SSH Key in the environment section yet so we can't use:
        // my_ssh_key = credentials('ssh cred id')
        // and the "withCredentials" directive does not work around or inside the "dockerfile" directive below
        // so we will create a file in the workspace that Jenkins can use when running Docker
        SSH_KEY_FILE = "${WORKSPACE}/.private_ssh_key.txt"
    }

    parameters {
        string(description: '[Required] Branch to build from guest-clusters/gce2e.git repository', name: 'BRANCH', defaultValue: 'topic/jparappurath/wcp-e2e-sonobuoy')
        string(description: '[Required] A reachable IP address of the VCSA', name: 'VCSA_IP', defaultValue: '10.78.93.67' )
        string(description: '[Required] Name of the WCP cluster', name: 'CLUSTER_NAME', defaultValue: 'compute-cluster')
        string(description: '[Required] VC Password', name: 'VCSA_PASSWORD', defaultValue: 'vmware')

    }

    options {
        skipDefaultCheckout true
        gitLabConnection 'Gitlab-eng'
        timestamps() // Requires timestamp plugin
        buildDiscarder(logRotator(numToKeepStr: '30'))
        timeout(time: 150, unit: 'MINUTES')
    }


    stages
       {
         stage('init')
           {
            steps
             {
		git url: 'https://gitlab.com/[username]/[my-repo].git', branch: 'master', credentialsId: 'my-gitlab-repo-creds'
               }
             }
           }
        stage('Create WCP Namespaces')
          {
            environment
            {
                JUNIT_REPORTS_DIR = "junit-reports-${BUILD_NUMBER}"
                TEST_FOCUS = "${params.TEST_FOCUS}"
                BASE_LOG_DIR = "${BASE_LOG_DIR}"
            }

            steps
              {
                script
                 {

                    cmd = "sshpass -p ${env.VCSA_PASSWORD} ssh -o StrictHostKeyChecking=no root@${env.VCSA_IP} dcli  +show-unreleased +username 'Administrator@vsphere.local' +password 'Admin!23' com vmware vcenter cluster list | grep ${env.CLUSTER_NAME} | grep True | cut -d'|' -f3"
                    CLUSTER_UUID = sh(returnStdout: true, script: cmd)
                    CLUSTER_UUID = CLUSTER_UUID.trim()
                    println "CLUSTER UUID: ${CLUSTER_UUID}"
                    
                    cmd = "sshpass -p ${env.VCSA_PASSWORD} ssh -o StrictHostKeyChecking=no root@${env.VCSA_IP} /usr/lib/vmware-wcp/decryptK8Pwd.py | grep ${CLUSTER_UUID} -A 1 | grep IP | cut -d ':' -f 2"
                    SV_IP = sh(returnStdout: true, script: cmd)
                    SV_IP = SV_IP.trim()
                    println "SV IP: ${SV_IP}"

                    cmd = "sshpass -p ${env.VCSA_PASSWORD} ssh -o StrictHostKeyChecking=no root@${env.VCSA_IP} /usr/lib/vmware-wcp/decryptK8Pwd.py | grep $SV_IP -A 1 | grep PWD | cut -d ':' -f 2"
                    SV_PASSWORD = sh (returnStdout: true, script: cmd)
                    SV_PASSWORD = SV_PASSWORD.trim()
                    println "SV_PASSWORD: ${SV_PASSWORD}"

                    cmd = "sshpass -p ${SV_PASSWORD} ssh -o StrictHostKeyChecking=no root@${SV_IP} kubectl get sc -o jsonpath='{..metadata.name}'"
                    STORAGE_CLASS = sh(returnStdout: true, script: cmd)
                    STORAGE_CLASS = STORAGE_CLASS.trim()
                    println "STORAGE_CLASS: ${STORAGE_CLASS}"

                    

                    sh "sshpass -p ${env.VCSA_PASSWORD} scp -o StrictHostKeyChecking=no Create_WCP_Namespaces.sh root@${env.VCSA_IP}:/root"
                    

                    //cmd = "sshpass -p ${env.VCSA_PASSWORD} ssh -o StrictHostKeyChecking=no root@$VC_IP ./Create_WCP_Namespaces.sh 1 2 $STORAGE_PROFILE $WCP_NAMESPACE_PREFIX $CLUSTER_NAME"
                   }

                }
             }
          }

 }


def loginToSv(String scIp, String scUser, String scPwd){

    expectCmd="\"set timeout 180\";expect -c \"spawn ./kubectl vsphere login --server=${scIp} -u ${scUser} --insecure-skip-tls-verify\";expect \"Password: \";send \"${scPwd}\r\";expect eof"
    //sh "expect -c ${expectCmd}"

    managedCluster = sh(script: "expect -c ${expectCmd}",
                        returnStdout: true)
}

def installk8sKubectlPlugin (String svIp){
    sh "curl -LOk https://${svIp}/wcp/plugin/linux-amd64/vsphere-plugin.zip"
    sh "unzip vsphere-plugin.zip"
    sh "chmod 777 -R bin"
    sh "cp -v bin/* ."
}

def getSupervisorClusterIp(String vcIp, String vcUser, String vcPwd) {
    def scIp
    vcUser='root'
    vcPwd='vmware'
    def scInfo = sh(returnStdout: true, script: "sshpass -p ${vcPwd} ssh -o 'StrictHostKeyChecking=no' -o 'UserKnownHostsFile=/dev/null' ${vcUser}@${vcIp} '/usr/lib/vmware-wcp/decryptK8Pwd.py'")
    def scInfoArray = scInfo.split('\n')
    for (int i = 0; i < scInfoArray.length; i++) {
        if (scInfoArray[i].startsWith('IP')) {
            scIp = scInfoArray[i].split(':').last().trim()
            break
        }
    }
    return scIp
}

// https://wcp.svc.eng.vmware.com only allow user "worker" to use docker
// so need to change user from "jenkins" to "worker" for every docker command
def getDockerRunCmd(String image, String runArgs, String command) {
    String runCmd = "echo ${'$PASSWORD'} | su ${'$USERNAME'} -c 'docker run ${runArgs} " +
            "${image} /bin/bash -c \"${command}\"'"
    return runCmd
}

def getDockerCmd(String cmd) {
    String command = "echo ${'$PASSWORD'} | su ${'$USERNAME'} -c 'docker ${cmd}'"
    return command
}

def runCmd(String cmd) {
    withCredentials([[$class          : 'UsernamePasswordMultiBinding',
                      credentialsId   : 'butler-worker-worker',
                      usernameVariable: 'USERNAME',
                      passwordVariable: 'PASSWORD']]) {
        sh cmd
    }
}

def getGcBuild(String recoOvaUrl) {
    def jsonSlurper = new groovy.json.JsonSlurper()
    def content = jsonSlurper.parse(recoOvaUrl.toURL())
    return content.GC_OVA_BUILD

    }


