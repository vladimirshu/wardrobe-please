import {aws_ec2 as ec2} from 'aws-cdk-lib';
import {readFileSync} from 'node:fs';
import {join} from 'node:path';

interface BackendBootstrapConfig {
    region: string;
    image: string;
    tag: string;
    qdrantHost: string;
}

export function createBackendUserData(config: BackendBootstrapConfig): ec2.UserData {
    const userData = ec2.UserData.forLinux();
    const serializedConfig = JSON.stringify(config);
    userData.addCommands(
        'set -eu',
        'umask 077',
        'for attempt in $(seq 1 60); do curl -sS --connect-timeout 3 https://ssm.' + config.region + '.amazonaws.com/ -o /dev/null && break || sleep 5; done',
        'dnf install -y docker python3',
        'install -d -m 700 /etc/wardrobe /run/wardrobe',
        `cat > /etc/wardrobe/config.json <<'WARDROBE_CONFIG'\n${serializedConfig}\nWARDROBE_CONFIG`,
    );

    for (const [source, target, mode] of [
        ['prepare.py', '/usr/local/sbin/wardrobe-prepare', '700'],
        ['backend.service', '/etc/systemd/system/wardrobe-backend.service', '644'],
    ]) {
        userData.addCommands(
            `cat > ${target} <<'WARDROBE_FILE'\n${readFileSync(join(__dirname, '../scripts', source), 'utf8')}\nWARDROBE_FILE`,
            `chmod ${mode} ${target}`,
        );
    }

    userData.addCommands(
        "cat > /etc/docker/daemon.json <<'DOCKER_CONFIG'\n{\"log-driver\":\"local\",\"log-opts\":{\"max-size\":\"10m\",\"max-file\":\"3\"}}\nDOCKER_CONFIG",
        'if [ ! -f /swapfile ]; then fallocate -l 1G /swapfile; chmod 600 /swapfile; mkswap /swapfile; fi',
        "grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab",
        'swapon -a',
        "mkdir -p /etc/systemd/journald.conf.d; printf '[Journal]\\nSystemMaxUse=50M\\nRuntimeMaxUse=20M\\n' > /etc/systemd/journald.conf.d/wardrobe.conf",
        'systemctl restart systemd-journald',
        'systemctl enable --now docker',
        'systemctl daemon-reload',
        'systemctl enable --now --no-block wardrobe-backend',
    );
    return userData;
}
