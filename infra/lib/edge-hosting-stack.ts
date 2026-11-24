import { CfnOutput, Duration, Fn, RemovalPolicy, Stack, StackProps, Tags,
  aws_cloudfront as cloudfront, aws_cloudfront_origins as origins, aws_s3 as s3 } from 'aws-cdk-lib';
import { Construct } from 'constructs';

/** Pay-as-you-go edge: no plan subscription, web ACL, access logs or paid metrics. */
export class EdgeHostingStack extends Stack {
  constructor(scope: Construct, id: string, props?: StackProps) {
    super(scope, id, props);
    Tags.of(this).add('Project', 'wardrobe-please');
    const bucket = new s3.Bucket(this, 'SpaBucket', {
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      objectOwnership: s3.ObjectOwnership.BUCKET_OWNER_ENFORCED,
      encryption: s3.BucketEncryption.S3_MANAGED,
      enforceSSL: true,
      // Retain rebuildable releases on accidental stack deletion; no cleanup Lambda.
      removalPolicy: RemovalPolicy.RETAIN,
    });
    const backend = cloudfront.VpcOrigin.fromVpcOriginAttributes(this, 'BackendOrigin', {
      vpcOriginId: Fn.importValue('WardrobeBackendVpcOriginId'),
      domainName: Fn.importValue('WardrobeBackendOriginPrivateDns'),
    });
    const controlOac = new cloudfront.FunctionUrlOriginAccessControl(this, 'ControlOac', {
      signing: cloudfront.Signing.SIGV4_ALWAYS,
    });
    // Permissions deliberately stay in the control stack's second, parameterized
    // deployment. FunctionUrlOrigin.withOriginAccessControl would add duplicates here.
    const control = new origins.HttpOrigin(Fn.select(2, Fn.split('/', Fn.importValue('WardrobeControlFunctionUrl'))), {
      originId: 'Control', originAccessControlId: controlOac.originAccessControlId,
      protocolPolicy: cloudfront.OriginProtocolPolicy.HTTPS_ONLY,
      connectionAttempts: 1, connectionTimeout: Duration.seconds(3), readTimeout: Duration.seconds(15),
    });
    const apiBehavior = {
      viewerProtocolPolicy: cloudfront.ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
      cachePolicy: cloudfront.CachePolicy.CACHING_DISABLED,
      // Includes Content-Type, cookies and query strings; Host is replaced for Lambda.
      originRequestPolicy: cloudfront.OriginRequestPolicy.ALL_VIEWER_EXCEPT_HOST_HEADER,
      compress: true,
    };
    const distribution = new cloudfront.Distribution(this, 'Distribution', {
      comment: 'Wardrobe Please: pay-as-you-go, private origins, no WAF',
      defaultRootObject: 'index.html',
      enableLogging: false,
      publishAdditionalMetrics: false,
      defaultBehavior: {
        origin: origins.S3BucketOrigin.withOriginAccessControl(bucket),
        viewerProtocolPolicy: cloudfront.ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
        // Do not forward the viewer Host to S3: it uses Host to select the bucket.
        // CachingOptimized honors our 60s/one-year object headers (minimum TTL 1s).
        cachePolicy: cloudfront.CachePolicy.CACHING_OPTIMIZED,
        responseHeadersPolicy: cloudfront.ResponseHeadersPolicy.SECURITY_HEADERS,
        compress: true,
      },
      additionalBehaviors: {
        '/api/v1/control/*': { ...apiBehavior, origin: control,
          allowedMethods: cloudfront.AllowedMethods.ALLOW_GET_HEAD },
        '/api/v1/*': { ...apiBehavior,
          origin: origins.VpcOrigin.withVpcOrigin(backend, {
            originId: 'Backend', connectionAttempts: 1, connectionTimeout: Duration.seconds(3),
            readTimeout: Duration.seconds(30),
          }),
          allowedMethods: cloudfront.AllowedMethods.ALLOW_ALL,
        },
      },
      // CloudFront error TTLs are distribution-wide, not path-scoped. Preserve status/body;
      // never turn API errors into index.html. S3 alone has an AWS minimum floor of 1s.
      errorResponses: [500, 501, 502, 503, 504].map(httpStatus => ({ httpStatus, ttl: Duration.seconds(0) })),
    });
    new CfnOutput(this, 'SpaBucketName', { value: bucket.bucketName });
    new CfnOutput(this, 'DistributionId', { value: distribution.distributionId });
    new CfnOutput(this, 'DistributionDomain', { value: distribution.distributionDomainName });
    new CfnOutput(this, 'StorefrontUrl', { value: `https://${distribution.distributionDomainName}` });
  }
}
