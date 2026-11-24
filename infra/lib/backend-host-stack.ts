import {
    CfnOutput,
    CfnParameter,
    RemovalPolicy,
    Stack,
    StackProps,
    Tags,
    aws_cloudfront as cloudfront,
    custom_resources as cr,
    aws_ec2 as ec2,
    aws_ecr as ecr,
    aws_iam as iam,
    aws_budgets as budgets
} from 'aws-cdk-lib';
import {Construct} from 'constructs';
import {createBackendUserData} from './backend-user-data';

export class BackendHostStack extends Stack {
    constructor(scope: Construct, id: string, props?: StackProps) {
        super(scope, id, props);
        Tags.of(this).add('Project', 'wardrobe-please');

        const qdrantHostParameter = new CfnParameter(this, 'QdrantHost', {
            description: 'Qdrant Cloud DNS hostname only (no scheme/path/key).',
            allowedPattern: '[a-zA-Z0-9][a-zA-Z0-9.-]+',
        });
        const instanceTypeParameter = new CfnParameter(this, 'InstanceType', {
            default: 't4g.micro',
            allowedValues: ['t4g.micro', 't4g.small'],
        });
        const imageTag = new CfnParameter(this, 'ImageTag', {
            default: 'initial',
            allowedPattern: '[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}',
        });

        const vpc = ec2.Vpc.fromLookup(this, 'Vpc', {isDefault: true});
        // No public ingress, including the shared CloudFront prefix list.
        const originSecurityGroup = new ec2.SecurityGroup(this, 'OriginSecurityGroup', {
            vpc,
            allowAllOutbound: true,
            description: 'CloudFront origins on 8080 only; administration uses SSM',
        });
        const repository = new ecr.Repository(this, 'BackendRepository', {
            imageTagMutability: ecr.TagMutability.IMMUTABLE,
            encryption: ecr.RepositoryEncryption.AES_256,
            lifecycleRules: [{maxImageCount: 3}],
            removalPolicy: RemovalPolicy.RETAIN,
        });
        const role = this.createHostRole(repository);
        const userData = createBackendUserData({
            region: this.region,
            image: repository.repositoryUri,
            tag: imageTag.valueAsString,
            qdrantHost: qdrantHostParameter.valueAsString,
        });
        const host = new ec2.Instance(this, 'BackendInstance', {
            vpc,
            vpcSubnets: {subnetType: ec2.SubnetType.PUBLIC},
            securityGroup: originSecurityGroup,
            role,
            instanceType: new ec2.InstanceType(instanceTypeParameter.valueAsString),
            machineImage: ec2.MachineImage.latestAmazonLinux2023({cpuType: ec2.AmazonLinuxCpuType.ARM_64}),
            requireImdsv2: true,
            associatePublicIpAddress: false,
            userData,
            blockDevices: [{
                deviceName: '/dev/xvda',
                volume: ec2.BlockDeviceVolume.ebs(8, {
                    encrypted: true,
                    volumeType: ec2.EbsDeviceVolumeType.GP3,
                    deleteOnTermination: true,
                }),
            }],
        });
        // Host-only credential retrieval; application containers do not need IMDS access.
        const cfnHost = host.node.defaultChild as ec2.CfnInstance;
        cfnHost.addPropertyOverride('MetadataOptions.HttpPutResponseHopLimit', 1);
        cfnHost.addPropertyOverride('MetadataOptions.HttpTokens', 'required');
        cfnHost.addPropertyOverride('CreditSpecification.CPUCredits', 'standard');

        const address = new ec2.CfnEIP(this, 'OriginAddress', {domain: 'vpc'});
        new ec2.CfnEIPAssociation(this, 'OriginAddressAssociation', {
            allocationId: address.attrAllocationId,
            instanceId: host.instanceId,
        });
        const origin = this.createVpcOrigin(host, originSecurityGroup, vpc);

        new CfnOutput(this, 'VpcOriginId', {
            value: origin.vpcOriginId,
            exportName: 'WardrobeBackendVpcOriginId',
        });
        new CfnOutput(this, 'OriginPrivateDnsName', {
            value: host.instancePrivateDnsName,
            exportName: 'WardrobeBackendOriginPrivateDns',
        });
        new CfnOutput(this, 'OriginIp', {
            value: address.ref,
            description: 'Outbound internet address only; never use as a CloudFront custom origin',
        });
        new CfnOutput(this, 'InstanceId', {
            value: host.instanceId,
            exportName: 'WardrobeBackendInstanceId',
        });
        new CfnOutput(this, 'RepositoryUri', {value: repository.repositoryUri});

        this.createBudgetAlert();
    }

    private createHostRole(repository: ecr.IRepository): iam.Role {
        const role = new iam.Role(this, 'HostRole', {
            assumedBy: new iam.ServicePrincipal('ec2.amazonaws.com'),
            managedPolicies: [iam.ManagedPolicy.fromAwsManagedPolicyName('AmazonSSMManagedInstanceCore')],
        });
        repository.grantPull(role);
        const secretArns = ['openai-api-key', 'qdrant-api-key'].map(name =>
            this.formatArn({
                service: 'ssm',
                resource: 'parameter',
                resourceName: `ecom/prod/${name}`,
            }));
        role.addToPolicy(new iam.PolicyStatement({
            actions: ['ssm:GetParameters'],
            resources: secretArns,
        }));
        // SSM's managed baseline also grants wildcard GetParameter(s). An explicit deny
        // confines those reads to the two application secrets even through that policy.
        role.addToPolicy(new iam.PolicyStatement({
            effect: iam.Effect.DENY,
            actions: ['ssm:GetParameter', 'ssm:GetParameters'],
            notResources: secretArns,
        }));
        return role;
    }

    private createVpcOrigin(
        host: ec2.Instance,
        originSecurityGroup: ec2.ISecurityGroup,
        vpc: ec2.IVpc,
    ): cloudfront.VpcOrigin {
        // CloudFront reaches the private interface; the EIP is retained only for outbound internet.
        const origin = new cloudfront.VpcOrigin(this, 'BackendVpcOrigin', {
            endpoint: cloudfront.VpcOriginEndpoint.ec2Instance(host),
            httpPort: 8080,
            protocolPolicy: cloudfront.OriginProtocolPolicy.HTTP_ONLY,
        });
        // AWS creates this group while provisioning the VPC origin. Lookup after creation;
        // never create/edit the service-managed group or temporarily open prefix-list ingress.
        const lookupCall = {
            service: 'EC2',
            action: 'describeSecurityGroups',
            parameters: {
                Filters: [
                    {Name: 'vpc-id', Values: [vpc.vpcId]},
                    {Name: 'group-name', Values: ['CloudFront-VPCOrigins-Service-SG']},
                ],
            },
            physicalResourceId: cr.PhysicalResourceId.of('CloudFrontVpcOriginServiceGroup'),
            outputPaths: ['SecurityGroups.0.GroupId'],
        };
        const serviceGroup = new cr.AwsCustomResource(this, 'VpcOriginServiceGroup', {
            onCreate: lookupCall,
            onUpdate: lookupCall,
            installLatestAwsSdk: false,
            policy: cr.AwsCustomResourcePolicy.fromStatements([new iam.PolicyStatement({
                actions: ['ec2:DescribeSecurityGroups'],
                resources: ['*'],
            })]),
        });
        serviceGroup.node.addDependency(origin);
        // Separate resource avoids SG -> lookup -> origin -> EC2 -> SG dependency cycles.
        new ec2.CfnSecurityGroupIngress(this, 'VpcOriginIngress', {
            groupId: originSecurityGroup.securityGroupId,
            sourceSecurityGroupId: serviceGroup.getResponseField('SecurityGroups.0.GroupId'),
            ipProtocol: 'tcp',
            fromPort: 8080,
            toPort: 8080,
            description: 'CloudFront VPC origin private connections only',
        });
        return origin;
    }

    private createBudgetAlert(): void {
        const email = this.node.tryGetContext('budgetEmail') as string | undefined;
        if (email) {
            new budgets.CfnBudget(this, 'MonthlyBudget', {
                budget: {
                    budgetName: 'wardrobe-monthly',
                    budgetType: 'COST',
                    timeUnit: 'MONTHLY',
                    budgetLimit: {amount: Number(this.node.tryGetContext('budgetUsd') ?? 8), unit: 'USD'},
                    costFilters: {LinkedAccount: [this.account]}
                },
                notificationsWithSubscribers: [80, 100].map(threshold => ({
                    notification: {
                        notificationType: 'ACTUAL',
                        comparisonOperator: 'GREATER_THAN',
                        threshold,
                        thresholdType: 'PERCENTAGE'
                    },
                    subscribers: [{subscriptionType: 'EMAIL', address: email}],
                })),
            });
        }
    }
}
